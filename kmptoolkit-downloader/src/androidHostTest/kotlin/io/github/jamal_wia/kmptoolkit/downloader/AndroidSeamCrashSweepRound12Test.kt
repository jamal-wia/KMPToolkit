package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.files.CancellationCheck
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * B round 12, Q1: a process death at EVERY rename and EVERY recursive delete the Android storage makes while it commits,
 * replaces or drops a unit, replayed deterministically. The Nth call of either seam (the `rename` the swap uses and
 * `FileAccess.deleteRecursively`) and every call after it throws, so no cleanup of the dying process can run; a new storage
 * over the same directory then judges the state. Contract (01-overview "A crash needs no recovery pass", 03-guide, 05-platform-notes,
 * DownloaderStorage.isResourceAvailable and deleteTempFile): a resource that reads as available is the whole old tree or the whole
 * new one; a unit never stays unavailable for good (the complete temp file is still there, or the resource is in place), a retry
 * ends with exactly the new content, and the next cleanup of the unit leaves no staging and no trash.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSeamCrashSweepRound12Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "seam_crash_r12")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class ProcessDeath(step: String) : RuntimeException("process death at $step")

    /** Counts renames and recursive deletes; from the [dieAt]th on, every one throws. [dieAt] 0 never dies. */
    private class Clock(private val dieAt: Int) {
        val calls: MutableList<String> = mutableListOf()
        private var dead: Boolean = false

        fun tick(what: String) {
            calls += what
            if (dead || (dieAt > 0 && calls.size >= dieAt)) {
                dead = true
                throw ProcessDeath("#${calls.size} $what")
            }
        }
    }

    private class DyingFiles(private val real: FileAccess, private val clock: Clock) : FileAccess by real {
        override fun deleteRecursively(path: String, cancellation: CancellationCheck): FileResult<Unit> {
            clock.tick("deleteRecursively ${File(path).name}")
            return real.deleteRecursively(path, cancellation)
        }
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

    private fun storage(clock: Clock?): AndroidDownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return if (clock == null) {
            AndroidDownloaderStorage(context, config, NoopLogger)
        } else {
            AndroidDownloaderStorage(
                context, config, NoopLogger,
                files = DyingFiles(createFileAccess(), clock),
                rename = { from: File, to: File ->
                    clock.tick("rename ${from.name}")
                    from.renameTo(to)
                },
            )
        }
    }

    private val oldTree: Map<String, ByteArray> =
        (1..4).associate { "f$it.bin" to ByteArray(500) { b: Int -> (b % (it + 3)).toByte() } } +
            mapOf("marker.txt" to "old".encodeToByteArray(), "sub/g.bin" to ByteArray(500) { 9 })

    private val newTree: Map<String, ByteArray> =
        oldTree.mapValues { (_: String, v: ByteArray) -> ByteArray(v.size) { 5 } } +
            mapOf("marker.txt" to "new".encodeToByteArray(), "extra/h.bin" to ByteArray(300) { 7 })

    private fun unitFor(archive: ByteArray, id: String, path: String, format: ResourceFormat): TestUnit =
        TestUnit(id, path, Group("g"), format, sha(archive))

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, sha(bytes))
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
    }

    private fun treeOf(dir: File): Map<String, ByteArray> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }

    private fun sameTree(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>): Boolean =
        expected.keys == actual.keys && expected.all { (name: String, data: ByteArray) -> data.contentEquals(actual.getValue(name)) }

    private fun litter(): List<String> = root.walkTopDown().filter { it.isFile || (it.isDirectory && it.list().orEmpty().isEmpty()) }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .filter { (it.startsWith("tmp/staging") || it.startsWith("tmp/kmptoolkit-internal/trash")) && !it.endsWith("trash") }
        .sorted().toList()

    /** The sweep: [prepare] builds the starting disk, [act] is the operation that dies at call N, [judge] checks the aftermath. */
    private fun sweep(
        label: String,
        prepare: suspend (DownloaderStorage) -> Unit,
        act: suspend (DownloaderStorage) -> Unit,
        judge: suspend (after: AndroidDownloaderStorage, n: Int, what: String) -> Unit,
    ) = runBlocking<Unit> {
        withTimeout(120_000) {
            root.deleteRecursively()
            val reference = Clock(0)
            prepare(storage(null))
            act(storage(reference))
            val total: Int = reference.calls.size
            assertTrue(total in 2..60, "$label: calls of the seams in one operation: $total ${reference.calls}")
            for (n: Int in 1..total) {
                root.deleteRecursively()
                prepare(storage(null))
                val clock = Clock(n)
                runCatching { act(storage(clock)) }
                judge(storage(null), n, reference.calls.getOrElse(n - 1) { "?" })
            }
        }
    }

    @Test
    fun `a death at every seam call of an archive replace leaves the whole old or new tree and a retry ends with the new one`() {
        val oldZip: ByteArray = zip(oldTree)
        val newZip: ByteArray = zip(newTree)
        val unit: TestUnit = unitFor(newZip, "arch", "archives/arch", ResourceFormat.ZipArchive("marker.txt"))
        val oldUnit: TestUnit = unitFor(oldZip, "arch", "archives/arch", ResourceFormat.ZipArchive("marker.txt"))
        sweep(
            "replace",
            prepare = { s: DownloaderStorage ->
                stage(s, oldUnit, oldZip)
                s.commitResource(oldUnit)
                stage(s, unit, newZip)
            },
            act = { s: DownloaderStorage -> s.commitResource(unit) },
            judge = { after: AndroidDownloaderStorage, n: Int, what: String ->
                val where = "death at call #$n ($what)"
                val target = File(after.getResourcePath(unit))
                val seen: Map<String, ByteArray> = if (target.isDirectory) treeOf(target) else emptyMap()
                if (after.isResourceAvailable(unit)) {
                    assertTrue(sameTree(oldTree, seen) || sameTree(newTree, seen), "$where: an available resource is neither whole tree: ${seen.keys.sorted()}")
                } else {
                    assertEquals(TempFileState.Complete, after.tempFileState(unit), "$where: unavailable and no complete temp file to commit")
                }
                if (after.tempFileState(unit) == TempFileState.Complete) {
                    after.commitResource(unit)
                } else {
                    // The commit had finished: the new tree is in place.
                    assertTrue(sameTree(newTree, seen), "$where: temp file consumed but the tree is not the new one: ${seen.keys.sorted()}")
                }
                assertTrue(after.isResourceAvailable(unit), "$where: unavailable after the retry")
                assertTrue(sameTree(newTree, treeOf(target)), "$where: the retry did not end with the new tree")
                after.deleteTempFile(unit)
                assertEquals(emptyList(), litter(), "$where: staging or trash left after the cleanup of the unit")
            },
        )
    }

    @Test
    fun `a death at every seam call of a first archive commit leaves no resource or the whole new tree`() {
        val newZip: ByteArray = zip(newTree)
        val unit: TestUnit = unitFor(newZip, "first", "archives/nested/first", ResourceFormat.ZipArchive("marker.txt"))
        sweep(
            "first",
            prepare = { s: DownloaderStorage -> stage(s, unit, newZip) },
            act = { s: DownloaderStorage -> s.commitResource(unit) },
            judge = { after: AndroidDownloaderStorage, n: Int, what: String ->
                val where = "death at call #$n ($what)"
                val target = File(after.getResourcePath(unit))
                if (after.isResourceAvailable(unit)) assertTrue(sameTree(newTree, treeOf(target)), "$where: an available resource is not the whole new tree")
                if (after.tempFileState(unit) == TempFileState.Complete) after.commitResource(unit)
                assertTrue(after.isResourceAvailable(unit), "$where: unavailable after the retry (temp ${after.tempFileState(unit)})")
                assertTrue(sameTree(newTree, treeOf(target)), "$where: the end state is not the new tree")
                after.deleteTempFile(unit)
                assertEquals(emptyList(), litter(), "$where: litter after the cleanup")
            },
        )
    }

    @Test
    fun `a death at every seam call of an opaque commit over a stray directory ends with the file or the directory never a torn mix`() {
        val bytes: ByteArray = ByteArray(1_000) { (it % 11).toByte() }
        val unit: TestUnit = unitFor(bytes, "plain", "files/plain.bin", ResourceFormat.Opaque)
        sweep(
            "stray",
            prepare = { s: DownloaderStorage ->
                val path = File(s.getResourcePath(unit))
                File(path, "stray/a.bin").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(100) { 1 })
                File(path, "b.bin").writeBytes(ByteArray(100) { 2 })
                stage(s, unit, bytes)
            },
            act = { s: DownloaderStorage -> s.commitResource(unit) },
            judge = { after: AndroidDownloaderStorage, n: Int, what: String ->
                val where = "death at call #$n ($what)"
                val path = File(after.getResourcePath(unit))
                if (after.isResourceAvailable(unit)) assertTrue(bytes.contentEquals(path.readBytes()), "$where: an available file holds other bytes")
                if (after.tempFileState(unit) == TempFileState.Complete) after.commitResource(unit)
                assertTrue(after.isResourceAvailable(unit), "$where: unavailable after the retry")
                assertTrue(bytes.contentEquals(path.readBytes()), "$where: the end state is not the committed bytes")
                after.deleteTempFile(unit)
                assertEquals(emptyList(), litter(), "$where: trash left after the cleanup")
            },
        )
    }

    @Test
    fun `a death at every seam call of dropping an interrupted archive leaves no torn resource and one more drop reclaims everything`() {
        val newZip: ByteArray = zip(newTree)
        val unit: TestUnit = unitFor(newZip, "drop", "archives/drop", ResourceFormat.ZipArchive("marker.txt"))
        val base = { s: DownloaderStorage -> Triple(File(s.getResourcePath(unit)), File(root, "tmp/staging-${unit.id}"), s) }
        sweep(
            "drop",
            prepare = { s: DownloaderStorage ->
                val (target: File, staging: File, _) = base(s)
                for ((name: String, data: ByteArray) in oldTree) File(target, name).apply { parentFile!!.mkdirs() }.writeBytes(data)
                File(staging, "marker.txt").apply { parentFile!!.mkdirs() }.writeText("half an extraction")
                File(staging, "x/y.bin").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(50))
                stage(s, unit, newZip)
            },
            act = { s: DownloaderStorage ->
                s.deleteTempFile(unit)
                s.deleteResource(unit)
            },
            judge = { after: AndroidDownloaderStorage, n: Int, what: String ->
                val where = "death at call #$n ($what)"
                val target = File(after.getResourcePath(unit))
                if (after.isResourceAvailable(unit)) assertTrue(sameTree(oldTree, treeOf(target)), "$where: an available resource is torn: ${treeOf(target).keys.sorted()}")
                after.deleteTempFile(unit)
                after.deleteResource(unit)
                assertTrue(!target.exists(), "$where: the resource survived the second drop")
                assertEquals(TempFileState.None, after.tempFileState(unit), "$where: temp file survived")
                assertEquals(emptyList(), litter(), "$where: staging or trash survived the second drop")
            },
        )
    }

    /** A rename of the extracted tree into place that fails must fail the commit and keep the temp file; it is never swallowed. */
    @Test
    fun `a failed move of the extracted tree into place fails the commit keeps the temp file and a retry commits`() = runBlocking<Unit> {
        withTimeout(120_000) {
            val newZip: ByteArray = zip(newTree)
            val unit: TestUnit = unitFor(newZip, "inplace", "archives/inplace", ResourceFormat.ZipArchive("marker.txt"))
            stage(storage(null), unit, newZip)
            var failIntoPlace = true
            LegacyTempMigrations.resetForTests()
            val failing = AndroidDownloaderStorage(
                context, config, NoopLogger,
                rename = { from: File, to: File -> if (failIntoPlace && from.name.startsWith("staging-")) false else from.renameTo(to) },
            )

            val failure: Throwable? = runCatching { failing.commitResource(unit) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException, "a failed move into place must fail the commit, was $failure")
            assertEquals(TempFileState.Complete, failing.tempFileState(unit), "the complete temp file must stay for the retry")
            assertTrue(!failing.isResourceAvailable(unit), "nothing may read as available")
            failIntoPlace = false
            failing.commitResource(unit)
            assertTrue(failing.isResourceAvailable(unit))
            assertTrue(sameTree(newTree, treeOf(File(failing.getResourcePath(unit)))))
        }
    }
}
