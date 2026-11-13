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
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
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
import org.junit.runner.RunWith

/**
 * B round 8, finding F1. Contract (01-overview "A crash needs no recovery pass", 03-guide "the marker file is what
 * proves completeness", DownloaderStorage.isResourceAvailable): a resource that reads as available after the process
 * died while its archive tree was being replaced or deleted is the whole old tree or the whole new tree, never a
 * marker over missing files. The death is modelled by a file layer that removes entries in an adverse order (the
 * marker last, as some file systems list it) and dies after K removals; no cleanup runs after it.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSwapCrashRound8Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "swap_crash_r8")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val trashDir: File get() = File(TestPaths.join(root.path, UnitFileLayout.TRASH_DIRECTORY))
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

    private class ProcessDeath(step: String) : RuntimeException("process death before $step")

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

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip: ZipOutputStream ->
            for ((name: String, data: ByteArray) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun newStorage(files: FileAccess? = null): DownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return if (files == null) {
            AndroidDownloaderStorage(context, config, NoopLogger)
        } else {
            AndroidDownloaderStorage(context, config, NoopLogger, files = files)
        }
    }

    private val oldTree: Map<String, ByteArray> =
        (1..6).associate { "f$it.bin" to ByteArray(2_000) { b: Int -> (b % (it + 3)).toByte() } } +
            mapOf("marker.txt" to "old".encodeToByteArray(), "sub/g.bin" to ByteArray(2_000) { 9 })

    private val newTree: Map<String, ByteArray> =
        oldTree.mapValues { (_: String, v: ByteArray) -> ByteArray(v.size) { 5 } } +
            mapOf("marker.txt" to "new".encodeToByteArray())

    private fun unitFor(group: Group, archive: ByteArray, path: String = "archives/swap"): TestUnit =
        TestUnit("swap", path, group, ResourceFormat.ZipArchive("marker.txt"), sha(archive))

    /** Stages [archive] as the complete temp file of [unit] through [storage]. */
    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, archive: ByteArray) {
        storage.beginTempFile(unit, sha(archive))
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(archive)
        storage.markTempFileComplete(unit)
    }

    private suspend fun commit(storage: DownloaderStorage, unit: DownloadUnit, archive: ByteArray) {
        stage(storage, unit, archive)
        storage.commitResource(unit)
    }

    private fun treeOf(dir: File): Map<String, ByteArray> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }

    private fun sameTree(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>): Boolean =
        expected.keys == actual.keys && expected.all { (name: String, data: ByteArray) -> data.contentEquals(actual.getValue(name)) }

    /**
     * Removes the tree at [target], or at any entry of the trash directory, the way the real engine walks it (last
     * pushed first, children before their directory, the marker listed first and so removed last) and dies after
     * [removals] removals. Every other call and path is the real one.
     */
    private class DyingAfterRemovals(
        private val real: FileAccess,
        private val target: File,
        private val trash: File,
        private val removals: Int,
    ) : FileAccess by real {
        override fun deleteRecursively(path: String, cancellation: CancellationCheck): FileResult<Unit> {
            val file = File(path)
            val inScope: Boolean = file.absoluteFile == target.absoluteFile || file.absoluteFile.parentFile == trash.absoluteFile
            if (!inScope) return real.deleteRecursively(path, cancellation)
            var left: Int = removals
            val stack: ArrayDeque<File> = ArrayDeque()
            stack.addLast(file)
            val visited: MutableSet<File> = mutableSetOf()
            while (stack.isNotEmpty()) {
                val frame: File = stack.last()
                if (frame.isDirectory && visited.add(frame)) {
                    for (name: String in frame.list().orEmpty().sortedBy { if (it == "marker.txt") 0 else 1 }) {
                        stack.addLast(File(frame, name))
                    }
                    continue
                }
                stack.removeLast()
                if (left-- <= 0) throw ProcessDeath("removal ${removals + 1} of an old tree")
                frame.delete()
            }
            return FileResult.Success(Unit)
        }
    }

    /** Counts the paths [deleteRecursively] is asked to remove; everything else is the real file layer. */
    private class RecordingFiles(private val real: FileAccess) : FileAccess by real {
        val deleted: MutableList<File> = mutableListOf()

        override fun deleteRecursively(path: String, cancellation: CancellationCheck): FileResult<Unit> {
            deleted += File(path).absoluteFile
            return real.deleteRecursively(path, cancellation)
        }
    }

    /**
     * Replaces the tree of a committed archive and dies after K removals of the old one. After the death a resource that
     * reads as available is the old tree or the new one, whole and byte-exact; when it is unavailable, the new complete
     * temp file is still there and a new storage's commit ends with exactly the new tree and no transfer.
     */
    @Test
    fun `a death in the middle of replacing a committed archive never leaves a marked partial tree`() = runBlocking<Unit> {
        withTimeout(120_000) {
            val group = Group("g")
            val oldZip: ByteArray = zip(oldTree)
            val newZip: ByteArray = zip(newTree)
            val oldUnit: TestUnit = unitFor(group, oldZip)
            val unit: TestUnit = unitFor(group, newZip)
            group.units = listOf(unit)
            val broken: MutableList<String> = mutableListOf()
            val outcomes = StringBuilder("SWAP TABLE\n")
            for (k: Int in 0..12) {
                root.deleteRecursively()
                val first: DownloaderStorage = newStorage()
                commit(first, oldUnit, oldZip)
                stage(first, unit, newZip)
                val target = File(first.getResourcePath(unit))
                val dying: DownloaderStorage = newStorage(DyingAfterRemovals(createFileAccess(), target, trashDir, k))
                runCatching { dying.commitResource(unit) }
                val after: DownloaderStorage = newStorage()
                val available: Boolean = after.isResourceAvailable(unit)
                val seen: Map<String, ByteArray> = if (target.isDirectory) treeOf(target) else emptyMap()
                val kind: String = when {
                    sameTree(oldTree, seen) -> "old"
                    sameTree(newTree, seen) -> "new"
                    else -> "torn(${seen.keys.sorted()})"
                }
                outcomes.append("  K=$k available=$available tree=$kind tempState=${after.tempFileState(unit)}\n")
                if (available && kind != "old" && kind != "new") broken += "K=$k an available resource is $kind"
                if (!available) {
                    if (after.tempFileState(unit) != TempFileState.Complete) broken += "K=$k unavailable and the complete temp file is gone"
                    after.commitResource(unit)
                    if (!after.isResourceAvailable(unit) || !sameTree(newTree, treeOf(target))) broken += "K=$k the retry did not end with the new tree"
                }
            }
            assertEquals(emptyList(), broken, "a marker over a partial tree after a death while replacing the old tree\n$outcomes")
        }
    }

    /**
     * deleteResource dying after K removals of an archive tree: afterwards the whole old tree stays or no resource is
     * left at all, and the next cleanup of the unit reclaims whatever the walk left, so nothing lingers anywhere.
     */
    @Test
    fun `a death in the middle of deleteResource leaves the whole old tree or no resource`() = runBlocking<Unit> {
        withTimeout(120_000) {
            val group = Group("g")
            val oldZip: ByteArray = zip(oldTree)
            val unit: TestUnit = unitFor(group, oldZip)
            group.units = listOf(unit)
            val broken: MutableList<String> = mutableListOf()
            for (k: Int in 0..12) {
                root.deleteRecursively()
                val first: DownloaderStorage = newStorage()
                commit(first, unit, oldZip)
                val target = File(first.getResourcePath(unit))
                val dying: DownloaderStorage = newStorage(DyingAfterRemovals(createFileAccess(), target, trashDir, k))
                runCatching { dying.deleteResource(unit) }
                val after: DownloaderStorage = newStorage()
                val available: Boolean = after.isResourceAvailable(unit)
                val seen: Map<String, ByteArray> = if (target.isDirectory) treeOf(target) else emptyMap()
                if (available && !sameTree(oldTree, seen)) broken += "K=$k an available resource is torn: ${seen.keys.sorted()}"
                if (!available && target.exists()) broken += "K=$k unavailable but ${target.path} still holds ${treeOf(target).keys.sorted()}"
                after.deleteTempFile(unit)
                if (trashDir.list().orEmpty().isNotEmpty()) broken += "K=$k the trash is not swept: ${trashDir.list().orEmpty().toList()}"
            }
            assertEquals(emptyList(), broken)
        }
    }

    /**
     * The first commit of an archive under a nested relativePath finds no parent directory. The tree is renamed into
     * place; nothing is deleted at the target (there is nothing to detach) and the staging tree is not copied and then
     * deleted (the copy writes top-down, so a death in it could land the marker before the rest).
     */
    @Test
    fun `the first commit under a nested relativePath renames the staging tree without a copy`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val archive: ByteArray = zip(newTree)
            val unit: TestUnit = unitFor(group, archive, path = "archives/pages/deep")
            group.units = listOf(unit)
            val recording = RecordingFiles(createFileAccess())
            val storage: DownloaderStorage = newStorage(recording)
            val target = File(storage.getResourcePath(unit))
            assertFalse(target.parentFile!!.exists(), "the parent of the target must be absent on a first commit")
            commit(storage, unit, archive)
            assertTrue(sameTree(newTree, treeOf(target)), "the committed tree is not the archive")
            val stagingCleanups: List<File> = recording.deleted.filter { it.name.startsWith("staging-") }
            assertEquals(1, stagingCleanups.size, "the staging tree was deleted after the extraction: a copy fallback ran $stagingCleanups")
            assertFalse(recording.deleted.any { it == target.absoluteFile }, "the target was deleted in place: ${recording.deleted}")
            assertEquals(emptyList(), trashDir.list().orEmpty().toList(), "nothing to detach on a first commit")
        }
    }

    /**
     * A rename of the old tree that fails while the tree is still there must not turn into an in-place delete: the commit
     * fails, the old tree is whole and available, the temp file and its record stay, and a retry commits.
     */
    @Test
    fun `a commit that cannot move the old tree aside fails and leaves it whole`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val oldZip: ByteArray = zip(oldTree)
            val newZip: ByteArray = zip(newTree)
            val unit: TestUnit = unitFor(group, newZip)
            val storage: DownloaderStorage = newStorage()
            commit(storage, unitFor(group, oldZip), oldZip)
            stage(storage, unit, newZip)
            val target = File(storage.getResourcePath(unit))
            trashDir.mkdirs()
            restorers += TestFs.makeUnwritable(trashDir)
            val failure: IllegalStateException = assertFailsWith<IllegalStateException> { storage.commitResource(unit) }
            assertTrue(failure.message.orEmpty().contains("aside"), failure.message)
            assertTrue(storage.isResourceAvailable(unit))
            assertTrue(sameTree(oldTree, treeOf(target)), "the old tree is not whole after the failed commit")
            assertEquals(TempFileState.Complete, storage.tempFileState(unit))
            restorers.asReversed().forEach { it.close() }
            restorers.clear()
            storage.commitResource(unit)
            assertTrue(sameTree(newTree, treeOf(target)), "the retry did not commit the new tree")
        }
    }

    /**
     * deleteResource has never thrown: when the tree cannot be moved aside it is deleted in place, as before.
     */
    @Test
    fun `deleteResource falls back to deleting in place when the tree cannot be moved aside`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val oldZip: ByteArray = zip(oldTree)
            val unit: TestUnit = unitFor(group, oldZip)
            val storage: DownloaderStorage = newStorage()
            commit(storage, unit, oldZip)
            trashDir.mkdirs()
            restorers += TestFs.makeUnwritable(trashDir)
            storage.deleteResource(unit)
            assertFalse(storage.isResourceAvailable(unit))
            assertFalse(File(storage.getResourcePath(unit)).exists())
        }
    }
}
