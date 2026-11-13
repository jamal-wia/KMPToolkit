package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.files.CancellationCheck
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.runner.RunWith

/**
 * B round 9, Q1 (environment hostility) and Q2 (re-attack of the rename-aside swap). Contracts: DownloaderStorage.commitResource
 * ("nothing that fails a check may reach the final path", "a committed resource is never removed before its replacement is
 * ready"), 01-overview "A crash needs no recovery pass", 03-guide "the marker file is what proves completeness".
 *
 * The invariant monitor looks at the resource path at every call that crosses the rename seam or the file layer, so it sees
 * the state at every instant a concurrent reader could: the whole old tree, nothing, or a whole new tree, never a mixture.
 */
@RunWith(AndroidJUnit4::class)
class AndroidEnvironmentRound9Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "env_r9")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val trashDir: File get() = File(root, UnitFileLayout.TRASH_DIRECTORY)
    private val restorers: MutableList<AutoCloseable> = mutableListOf()

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        restorers.asReversed().forEach { restorer: AutoCloseable -> runCatching { restorer.close() } }
        restorers.clear()
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class TestUnit(
        override val id: String,
        override val relativePath: String,
        override val group: ResourceGroup,
        override val format: ResourceFormat,
        override val sha256: Sha256?,
    ) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val tempExtension: String = "tmp"
    }

    private fun sha(bytes: ByteArray): Sha256 =
        Sha256.parse(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun zip(files: Map<String, ByteArray>, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip: ZipOutputStream ->
            zip.setLevel(level)
            for ((name: String, data: ByteArray) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun newStorage(
        files: FileAccess = createFileAccess(),
        rename: (from: File, to: File) -> Boolean = { from: File, to: File -> from.renameTo(to) },
    ): DownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return AndroidDownloaderStorage(context, config, NoopLogger, files = files, rename = rename)
    }

    /** No unit hash: each staged archive is checked against the hash its own beginTempFile recorded. */
    private fun archiveUnit(group: Group, id: String, path: String): TestUnit =
        TestUnit(id, path, group, ResourceFormat.ZipArchive("marker.txt"), null)

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, sha(bytes))
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
    }

    private fun treeOf(dir: File): Map<String, ByteArray> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }

    private fun sameTree(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>): Boolean =
        expected.keys == actual.keys && expected.all { (name: String, data: ByteArray) -> data.contentEquals(actual.getValue(name)) }

    private fun tree(tag: String): Map<String, ByteArray> =
        (1..5).associate { "f$it.bin" to ByteArray(1_000) { b: Int -> (b % (it + 3) + tag.length).toByte() } } +
            mapOf("marker.txt" to tag.encodeToByteArray(), "sub/g.bin" to tag.repeat(50).encodeToByteArray())

    /** Everything under the files directory except [root] itself, as relative names; what a hostile archive must not change. */
    private fun outsideBase(): Set<String> =
        context.filesDir.walkTopDown()
            .filter { f: File -> f != context.filesDir && !f.absolutePath.startsWith(root.absolutePath) }
            .map { f: File -> f.relativeTo(context.filesDir).invariantSeparatorsPath }
            .toSet()

    /**
     * Watches the resource path of [target] at every rename and every recursive delete the storage performs (before and
     * after it) and reports a state that is not one of [allowed] trees or nothing. [onTrashDelete] runs once, inside the
     * first recursive delete of a trash entry, i.e. while the detached old tree is being removed after the lock was released.
     */
    private inner class Monitor(
        private val target: File,
        private val allowed: () -> List<Map<String, ByteArray>>,
    ) {
        val violations: MutableList<String> = mutableListOf()
        var observations: Int = 0
        var onTrashDelete: (() -> Unit)? = null

        fun check(where: String) {
            observations++
            if (!target.exists()) return
            val now: Map<String, ByteArray> = treeOf(target)
            if (allowed().none { whole: Map<String, ByteArray> -> sameTree(whole, now) }) {
                violations += "$where: ${now.keys.sorted()}"
            }
        }

        fun rename(): (File, File) -> Boolean = { from: File, to: File ->
            check("before rename ${from.name} -> ${to.name}")
            val result: Boolean = from.renameTo(to)
            check("after rename ${from.name} -> ${to.name} = $result")
            result
        }

        fun files(real: FileAccess): FileAccess = object : FileAccess by real {
            override fun deleteRecursively(path: String, cancellation: CancellationCheck): FileResult<Unit> {
                check("before delete $path")
                val inTrash: Boolean = File(path).absoluteFile.parentFile == trashDir.absoluteFile
                if (inTrash) onTrashDelete?.also { hook: () -> Unit -> onTrashDelete = null; hook() }
                val result: FileResult<Unit> = real.deleteRecursively(path, cancellation)
                check("after delete $path")
                return result
            }
        }
    }

    /**
     * Q2: commit of unit A replaces its tree; while the detached old tree is deleted, a commit of ANOTHER unit runs (its own
     * detach, delete and sweep of the whole trash, including the entry being deleted). At every instant the resource path of A
     * is the whole old tree, nothing, or the whole new tree; B ends committed whole; the trash and staging are empty.
     */
    @Test
    fun `a commit of another unit sweeping the trash mid delete never shows a torn tree of the first`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val oldA: Map<String, ByteArray> = tree("old")
            val newA: Map<String, ByteArray> = tree("new!")
            val treeB: Map<String, ByteArray> = tree("bbbbb")
            val zipOldA: ByteArray = zip(oldA)
            val zipNewA: ByteArray = zip(newA)
            val zipB: ByteArray = zip(treeB)
            val unitA: TestUnit = archiveUnit(group, "a", "archives/a")
            val unitB: TestUnit = archiveUnit(group, "b", "archives/b")
            val plain: DownloaderStorage = newStorage()
            stage(plain, unitA, zipOldA)
            plain.commitResource(unitA)
            stage(plain, unitA, zipNewA)
            stage(plain, unitB, zipB)
            val monitor = Monitor(File(plain.getResourcePath(unitA))) { listOf(oldA, newA) }
            val storage: DownloaderStorage = newStorage(monitor.files(createFileAccess()), monitor.rename())
            var innerRan = false
            monitor.onTrashDelete = {
                innerRan = true
                runBlocking { storage.commitResource(unitB) }
            }
            storage.commitResource(unitA)
            assertTrue(innerRan, "the interleaving did not happen")
            assertEquals(emptyList(), monitor.violations, "observations=${monitor.observations}")
            assertTrue(sameTree(newA, treeOf(File(storage.getResourcePath(unitA)))))
            assertTrue(sameTree(treeB, treeOf(File(storage.getResourcePath(unitB)))))
            assertEquals(emptyList(), trashDir.list().orEmpty().toList(), "the trash is not empty")
            assertEquals(emptyList(), File(root, "tmp").list().orEmpty().filter { it.startsWith("staging-") })
        }
    }

    /**
     * Q2: a deleteResource of the same unit runs while the replaced tree is deleted. It sees the NEW tree (already in place)
     * and removes it whole: afterwards the resource is absent, never a torn tree, at every observed instant.
     */
    @Test
    fun `a deleteResource during the delete of the replaced tree removes the new tree whole`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val oldA: Map<String, ByteArray> = tree("old")
            val newA: Map<String, ByteArray> = tree("new!")
            val zipOldA: ByteArray = zip(oldA)
            val zipNewA: ByteArray = zip(newA)
            val unitA: TestUnit = archiveUnit(group, "a", "archives/a")
            val plain: DownloaderStorage = newStorage()
            stage(plain, unitA, zipOldA)
            plain.commitResource(unitA)
            stage(plain, unitA, zipNewA)
            val target = File(plain.getResourcePath(unitA))
            val monitor = Monitor(target) { listOf(oldA, newA) }
            val storage: DownloaderStorage = newStorage(monitor.files(createFileAccess()), monitor.rename())
            monitor.onTrashDelete = { storage.deleteResource(unitA) }
            storage.commitResource(unitA)
            assertEquals(emptyList(), monitor.violations, "observations=${monitor.observations}")
            assertFalse(target.exists(), "the resource survived the deleteResource")
            assertFalse(storage.isResourceAvailable(unitA))
            assertEquals(emptyList(), trashDir.list().orEmpty().toList())
        }
    }

    /**
     * Q2: a SECOND commit of the same unit (a third tree) runs while the replaced tree is deleted: the commit lock was
     * released, so it proceeds; the resource is whole old, nothing or a whole new tree at every instant and ends as the
     * third tree.
     */
    @Test
    fun `a second commit of the same unit during the delete of the replaced tree ends whole`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val trees: List<Map<String, ByteArray>> = listOf(tree("one"), tree("two!"), tree("three"))
            val zips: List<ByteArray> = trees.map { zip(it) }
            val unitA: TestUnit = archiveUnit(group, "a", "archives/a")
            val plain: DownloaderStorage = newStorage()
            stage(plain, unitA, zips[0])
            plain.commitResource(unitA)
            stage(plain, unitA, zips[1])
            val target = File(plain.getResourcePath(unitA))
            val monitor = Monitor(target) { trees }
            val storage: DownloaderStorage = newStorage(monitor.files(createFileAccess()), monitor.rename())
            monitor.onTrashDelete = {
                stage(storage, unitA, zips[2])
                runBlocking { storage.commitResource(unitA) }
            }
            storage.commitResource(unitA)
            assertEquals(emptyList(), monitor.violations, "observations=${monitor.observations}")
            assertTrue(sameTree(trees[2], treeOf(target)), "the second commit did not land")
            assertEquals(emptyList(), trashDir.list().orEmpty().toList())
        }
    }

    /**
     * Q1/Q2: the parent of the resource directory is made read-only under a running storage (a permission change,
     * an unwritable mount). The commit must fail without touching the old tree and keep the temp file; a retry after the
     * permission returns commits with no transfer. deleteResource, which never throws, must not leave an available
     * resource behind.
     */
    @Test
    fun `a read-only parent directory fails the commit cleanly and a delete leaves nothing available`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val oldA: Map<String, ByteArray> = tree("old")
            val newA: Map<String, ByteArray> = tree("new!")
            val zipNewA: ByteArray = zip(newA)
            val unitA: TestUnit = archiveUnit(group, "a", "archives/a")
            val storage: DownloaderStorage = newStorage()
            stage(storage, unitA, zip(oldA))
            storage.commitResource(unitA)
            stage(storage, unitA, zipNewA)
            val target = File(storage.getResourcePath(unitA))
            val parent: File = target.parentFile!!
            val restore: AutoCloseable = TestFs.makeUnwritable(parent)
            restorers += restore
            Assume.assumeFalse("this user can write into a read-only directory", runCatching { File(parent, "probe").createNewFile() }.getOrDefault(false))
            assertFailsWith<Exception> { storage.commitResource(unitA) }
            assertTrue(sameTree(oldA, treeOf(target)), "the old tree is not whole after the failed commit")
            assertTrue(storage.isResourceAvailable(unitA))
            assertEquals(TempFileState.Complete, storage.tempFileState(unitA))
            assertEquals(emptyList(), File(root, "tmp").list().orEmpty().filter { it.startsWith("staging-") }, "staging was left behind")
            restore.close()
            restorers.clear()
            storage.commitResource(unitA)
            assertTrue(sameTree(newA, treeOf(target)), "the retry did not commit the new tree")
            // deleteResource under a read-only parent: never throws, never leaves a resource that reads as available.
            val again: AutoCloseable = TestFs.makeUnwritable(parent)
            restorers += again
            storage.deleteResource(unitA)
            assertFalse(storage.isResourceAvailable(unitA), "deleteResource left an available resource")
        }
    }

    /**
     * Q2: a trash entry that cannot be deleted (a locked subdirectory left by an earlier process) and a trash entry that is a
     * symbolic link to a live directory elsewhere. A commit that replaces a tree and a deleteResource both succeed, the
     * resource path is right, the live directory keeps its files, and once the lock is gone the next sweep empties the trash.
     */
    @Test
    fun `an undeletable trash entry and a link in the trash block neither a commit nor a delete`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val oldA: Map<String, ByteArray> = tree("old")
            val newA: Map<String, ByteArray> = tree("new!")
            val unitA: TestUnit = archiveUnit(group, "a", "archives/a")
            val storage: DownloaderStorage = newStorage()
            stage(storage, unitA, zip(oldA))
            storage.commitResource(unitA)
            val locked = File(trashDir, "1/locked").apply { mkdirs() }
            File(locked, "stuck.bin").writeText("stuck")
            val restore: AutoCloseable = TestFs.makeUnwritable(locked)
            restorers += restore
            Assume.assumeFalse("this user can write into a read-only directory", runCatching { File(locked, "probe").createNewFile() }.getOrDefault(false))
            val live = File(root, "live-elsewhere").apply { mkdirs() }
            File(live, "keep.txt").writeText("keep")
            val link = File(trashDir, "2")
            val linked: Boolean = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), live.toPath()) }.isSuccess
            Assume.assumeTrue("this host cannot create symbolic links", linked)
            stage(storage, unitA, zip(newA))
            storage.commitResource(unitA)
            val target = File(storage.getResourcePath(unitA))
            assertTrue(sameTree(newA, treeOf(target)), "the commit did not land beside an undeletable trash entry")
            assertEquals("keep", File(live, "keep.txt").readText(), "the sweep reached through a link in the trash")
            assertFalse(java.nio.file.Files.isSymbolicLink(link.toPath()), "the link in the trash was not swept as a link")
            storage.deleteResource(unitA)
            assertFalse(storage.isResourceAvailable(unitA))
            assertTrue(File(locked, "stuck.bin").exists(), "a locked file cannot have been deleted")
            restore.close()
            restorers.clear()
            stage(storage, unitA, zip(oldA))
            storage.commitResource(unitA)
            stage(storage, unitA, zip(newA))
            storage.commitResource(unitA)
            assertEquals(emptyList(), trashDir.list().orEmpty().toList(), "the sweep after the lock was gone left litter")
            assertEquals("keep", File(live, "keep.txt").readText())
        }
    }

    /**
     * Q2: the resource path holds a plain FILE when an archive commit arrives (a stray file, or a unit whose format changed
     * from opaque to archive). The file is moved aside as the old resource and the tree lands whole.
     */
    @Test
    fun `an archive commit replaces a plain file at the resource path`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val newA: Map<String, ByteArray> = tree("new!")
            val zipNewA: ByteArray = zip(newA)
            val unitA: TestUnit = archiveUnit(group, "a", "archives/a")
            val storage: DownloaderStorage = newStorage()
            val target = File(storage.getResourcePath(unitA))
            target.parentFile!!.mkdirs()
            target.writeText("a stray file")
            assertFalse(storage.isResourceAvailable(unitA))
            stage(storage, unitA, zipNewA)
            storage.commitResource(unitA)
            assertTrue(target.isDirectory)
            assertTrue(sameTree(newA, treeOf(target)))
            assertEquals(emptyList(), trashDir.list().orEmpty().toList())
        }
    }

    /**
     * Q2 (observation pinned as behaviour): a unit whose format changed from archive to opaque at the same path. The
     * commit cannot move a file onto a directory: it fails with an exception, the directory tree stays whole, the temp
     * file stays complete; deleteResource is the way out and the retry then commits.
     */
    @Test
    fun `an opaque commit onto a committed archive tree fails cleanly and a delete unblocks it`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val oldA: Map<String, ByteArray> = tree("old")
            val unitZip: TestUnit = archiveUnit(group, "a", "res/a")
            val payload: ByteArray = ByteArray(300) { 7 }
            val unitFile = TestUnit("a", "res/a", group, ResourceFormat.Opaque, sha(payload))
            val storage: DownloaderStorage = newStorage()
            stage(storage, unitZip, zip(oldA))
            storage.commitResource(unitZip)
            stage(storage, unitFile, payload)
            assertFailsWith<Exception> { storage.commitResource(unitFile) }
            val target = File(storage.getResourcePath(unitFile))
            assertTrue(target.isDirectory && sameTree(oldA, treeOf(target)), "the old tree was damaged")
            assertEquals(TempFileState.Complete, storage.tempFileState(unitFile))
            storage.deleteResource(unitFile)
            storage.commitResource(unitFile)
            assertTrue(target.isFile)
            assertTrue(payload.contentEquals(target.readBytes()))
        }
    }

    /**
     * Q1: names that climb out of the extraction directory, absolute names and a name that climbs through a subdirectory.
     * Each archive is a typed ResourceIntegrityException, nothing is written anywhere else (the files directory outside the
     * base is unchanged, no stray name inside the base), the committed resource stays whole and available, and the temp file
     * is deleted.
     */
    @Test
    fun `traversing archives fail typed write nothing outside and keep the committed resource`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val oldA: Map<String, ByteArray> = tree("old")
            val unitProbe: TestUnit = archiveUnit(group, "a", "archives/a")
            val storage: DownloaderStorage = newStorage()
            stage(storage, unitProbe, zip(oldA))
            storage.commitResource(unitProbe)
            val outsideBefore: Set<String> = outsideBase()
            val baseBefore: Set<String> = root.walkTopDown().map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
            for (evil: String in listOf("../evil.txt", "../../evil.txt", "sub/../../evil.txt", "a/b/../../../evil.txt")) {
                val bad: ByteArray = zip(tree("zzz") + mapOf(evil to "evil".encodeToByteArray()))
                val unit: TestUnit = archiveUnit(group, "a", "archives/a")
                stage(storage, unit, bad)
                assertFailsWith<ResourceIntegrityException>(evil) { storage.commitResource(unit) }
                assertTrue(sameTree(oldA, treeOf(File(storage.getResourcePath(unit)))), "the committed tree changed for '$evil'")
                assertTrue(storage.isResourceAvailable(unit))
                assertEquals(TempFileState.None, storage.tempFileState(unit), "the temp file of '$evil' was kept")
                assertEquals(outsideBefore, outsideBase(), "something was written outside the base for '$evil'")
                val baseAfter: Set<String> = root.walkTopDown().map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
                assertEquals(emptySet(), baseAfter - baseBefore, "litter inside the base for '$evil'")
                assertFalse(root.walkTopDown().any { it.name == "evil.txt" || it.name == "abs-evil.txt" }, "an evil file exists for '$evil'")
            }
        }
    }

    /**
     * Q1: an absolute entry name stays inside, as the documented leading slash of a relativePath does: it is extracted as a
     * relative name of the tree and nothing is written outside the base.
     */
    @Test
    fun `an absolute entry name is extracted inside the tree and nothing is written outside`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val unit: TestUnit = archiveUnit(group, "a", "archives/a")
            val storage: DownloaderStorage = newStorage()
            val outsideBefore: Set<String> = outsideBase()
            val archive: ByteArray = zip(tree("abs") + mapOf("/abs-inside.txt" to "inside".encodeToByteArray()))
            stage(storage, unit, archive)
            storage.commitResource(unit)
            val target = File(storage.getResourcePath(unit))
            assertEquals("inside", File(target, "abs-inside.txt").readText())
            assertEquals(outsideBefore, outsideBase())
            assertFalse(File("/abs-inside.txt").exists())
        }
    }

    /**
     * Q1: an archive of many entries and one that expands to a large declared size (a highly compressed run). The contract
     * has no size bound: both commit whole, the extracted size is what was declared, and the resource is replaceable
     * afterwards. Pins that the commit neither fails nor leaves litter; the absence of any bound is reported in the round
     * report, not asserted.
     */
    @Test
    fun `an archive of thousands of entries and a highly compressed entry commit whole`() = runBlocking<Unit> {
        withTimeout(120_000) {
            val group = Group("g")
            val many: Map<String, ByteArray> =
                (0 until 3_000).associate { "d${it % 30}/e$it.txt" to "x$it".encodeToByteArray() } + mapOf("marker.txt" to "m".encodeToByteArray())
            val zipMany: ByteArray = zip(many)
            val unit: TestUnit = archiveUnit(group, "a", "archives/a")
            val storage: DownloaderStorage = newStorage()
            stage(storage, unit, zipMany)
            storage.commitResource(unit)
            val target = File(storage.getResourcePath(unit))
            assertEquals(many.size, treeOf(target).size)
            assertTrue(sameTree(many, treeOf(target)))
            val declared = 24 * 1024 * 1024
            val bomb: ByteArray = zip(mapOf("zeros.bin" to ByteArray(declared), "marker.txt" to "m".encodeToByteArray()), Deflater.BEST_COMPRESSION)
            assertTrue(bomb.size < declared / 100, "the fixture is not a bomb: ${bomb.size}")
            val unitBomb: TestUnit = archiveUnit(group, "a", "archives/a")
            stage(storage, unitBomb, bomb)
            storage.commitResource(unitBomb)
            assertEquals(declared.toLong() + 1, storage.getResourceSize(unitBomb))
            assertEquals(emptyList(), trashDir.list().orEmpty().toList())
            assertEquals(emptyList(), File(root, "tmp").list().orEmpty().filter { it.startsWith("staging-") })
        }
    }

    /**
     * Q4/Q3 edge of the temp-file protocol on the real storage: a transfer of a zero-byte resource. The partial file is
     * empty, so the state reads None (nothing to resume), yet marking it complete and committing must produce an empty
     * resource, as the engine does for an empty answer.
     */
    @Test
    fun `an empty transfer is markable committable and reads as an empty resource`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val unit = TestUnit("empty", "files/empty.bin", group, ResourceFormat.Opaque, null)
            val storage: DownloaderStorage = newStorage()
            storage.beginTempFile(unit, null)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(0))
            assertEquals(TempFileState.None, storage.tempFileState(unit))
            assertEquals(0L, storage.getTempFileSize(unit))
            storage.markTempFileComplete(unit)
            assertEquals(TempFileState.Complete, storage.tempFileState(unit))
            storage.commitResource(unit)
            assertTrue(storage.isResourceAvailable(unit))
            assertEquals(0L, storage.getResourceSize(unit))
            assertEquals(TempFileState.None, storage.tempFileState(unit))
        }
    }
}
