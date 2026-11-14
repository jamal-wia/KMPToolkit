package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.files.CancellationCheck
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.runner.RunWith

/**
 * B round 9, finding F-1 (D-131, ruling reviews/b-r9-f1-ruling-opus.md). Contract: DownloaderStorage.isResourceAvailable
 * ("a plain file must exist (a symbolic link to one counts; a directory at that path does not)") and 05-platform-notes
 * ("A directory found at a plain-file resource's path reads as unavailable and is moved into the trash by the commit").
 * Promotes the r9 suspect r9-Q2: a directory at the path of an Opaque unit, whether a stray one or the committed tree of an
 * archive unit of the same id, must not read as available, and the commit moves it aside and puts the file in place.
 */
@RunWith(AndroidJUnit4::class)
class AndroidOpaqueDirectoryRound9Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "opaque_dir_r9")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val trashDir: File get() = File(root, UnitFileLayout.TRASH_DIRECTORY)

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

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class TestUnit(
        override val id: String,
        override val relativePath: String,
        override val group: ResourceGroup,
        override var format: ResourceFormat,
        override val sha256: Sha256?,
    ) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val tempExtension: String = "tmp"
        override fun toString(): String = "TestUnit($id)"
    }

    private class ProcessDeath(step: String) : RuntimeException("process death before $step")

    private val payload: ByteArray = ByteArray(300) { b: Int -> (b % 11 + 1).toByte() }

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

    private fun archiveTree(): Map<String, ByteArray> =
        mapOf("marker.txt" to "m".encodeToByteArray(), "a.bin" to ByteArray(500) { 3 }, "sub/b.bin" to ByteArray(500) { 4 })

    private fun newStorage(
        files: FileAccess = createFileAccess(),
        rename: (from: File, to: File) -> Boolean = { from: File, to: File -> from.renameTo(to) },
    ): DownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return AndroidDownloaderStorage(context, config, NoopLogger, files = files, rename = rename)
    }

    private fun opaqueUnit(group: Group, path: String = "files/u.bin"): TestUnit =
        TestUnit("u", path, group, ResourceFormat.Opaque, sha(payload))

    private fun archiveUnit(group: Group, path: String): TestUnit =
        TestUnit("u", path, group, ResourceFormat.ZipArchive("marker.txt"), null)

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, sha(bytes))
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
    }

    private fun trashNames(): List<String> = trashDir.list().orEmpty().toList()

    /** Test 1 (the promoted suspect): a directory at an Opaque path is not an available resource. */
    @Test
    fun `a directory at the path of a plain file unit does not read as an available resource`() {
        val storage: DownloaderStorage = newStorage()
        val unit: TestUnit = opaqueUnit(Group("g"))
        File(storage.getResourcePath(unit)).mkdirs()
        assertFalse(storage.isResourceAvailable(unit), "a directory reads as an available plain file")
        File(storage.getResourcePath(unit), "inside.bin").writeBytes(payload)
        assertFalse(storage.isResourceAvailable(unit), "a non-empty directory reads as an available plain file")
    }

    /** Test 2: the states of 4.0.0 read as before: a plain file and a link to one are available, a dangling link is not. */
    @Test
    fun `a plain file and a link to a file read available and a dangling link and a link to a directory do not`() {
        val storage: DownloaderStorage = newStorage()
        val group = Group("g")
        val unit: TestUnit = opaqueUnit(group)
        val path = File(storage.getResourcePath(unit))
        path.parentFile!!.mkdirs()

        path.writeBytes(payload)
        assertTrue(storage.isResourceAvailable(unit), "a plain file")
        path.delete()

        val realFile = File(root, "elsewhere.bin").apply { writeBytes(payload) }
        val realDir = File(root, "elsewhere-dir").apply { mkdirs() }
        try {
            Files.createSymbolicLink(path.toPath(), realFile.toPath())
        } catch (e: Exception) {
            Assume.assumeNoException("symbolic links are not available here", e)
        }
        assertTrue(storage.isResourceAvailable(unit), "a link to a file")
        path.delete()

        Files.createSymbolicLink(path.toPath(), File(root, "does-not-exist.bin").toPath())
        assertFalse(storage.isResourceAvailable(unit), "a dangling link")
        path.delete()

        Files.createSymbolicLink(path.toPath(), realDir.toPath())
        assertFalse(storage.isResourceAvailable(unit), "a link to a directory")
    }

    /** Test 3: Zip then Opaque at the same relativePath: the archive commits, then the Opaque commit replaces the tree by the file. */
    @Test
    fun `an opaque commit onto the committed tree of an archive unit of the same path lands the file and empties the trash`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val zipUnit: TestUnit = archiveUnit(group, "res/u")
            val opaque: TestUnit = opaqueUnit(group, "res/u")
            val storage: DownloaderStorage = newStorage()
            stage(storage, zipUnit, zip(archiveTree()))
            storage.commitResource(zipUnit)
            assertTrue(storage.isResourceAvailable(zipUnit))
            assertFalse(storage.isResourceAvailable(opaque), "the archive tree reads as an available plain file")

            stage(storage, opaque, payload)
            storage.commitResource(opaque)

            val target = File(storage.getResourcePath(opaque))
            assertTrue(target.isFile, "the path does not hold a file")
            assertTrue(payload.contentEquals(target.readBytes()))
            assertEquals(emptyList(), trashNames(), "the detached tree was left in the trash")
            assertFalse(storage.isResourceAvailable(zipUnit))
            assertTrue(storage.isResourceAvailable(opaque))
            assertEquals(TempFileState.None, storage.tempFileState(opaque))
        }
    }

    /** Test 4: a stray non-empty directory gets the same result. */
    @Test
    fun `a stray non-empty directory at the path is replaced by the committed file`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val opaque: TestUnit = opaqueUnit(Group("g"), "res/stray.bin")
            val storage: DownloaderStorage = newStorage()
            val target = File(storage.getResourcePath(opaque))
            File(target, "deep/er").mkdirs()
            File(target, "deep/er/x.bin").writeBytes(ByteArray(40) { 9 })
            File(target, "top.bin").writeBytes(ByteArray(40) { 8 })
            assertFalse(storage.isResourceAvailable(opaque))

            stage(storage, opaque, payload)
            storage.commitResource(opaque)

            assertTrue(target.isFile, "the path does not hold a file")
            assertTrue(payload.contentEquals(target.readBytes()))
            assertEquals(emptyList(), trashNames(), "the stray directory was left in the trash")
            assertTrue(storage.isResourceAvailable(opaque))
            assertEquals(TempFileState.None, storage.tempFileState(opaque))
        }
    }

    /**
     * Test 5: the detach (the rename of the directory into the trash) fails. The commit throws, the directory is whole, the
     * temp file stays Complete, and a retry on a storage without the fault lands the file.
     */
    @Test
    fun `a failed detach of the directory throws keeps the directory and the temp file and a retry commits`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val zipUnit: TestUnit = archiveUnit(group, "res/u")
            val opaque: TestUnit = opaqueUnit(group, "res/u")
            val first: DownloaderStorage = newStorage()
            val tree: Map<String, ByteArray> = archiveTree()
            stage(first, zipUnit, zip(tree))
            first.commitResource(zipUnit)
            stage(first, opaque, payload)
            val target = File(first.getResourcePath(opaque))
            val refusing: DownloaderStorage = newStorage(rename = { from: File, to: File ->
                if (from.absoluteFile == target.absoluteFile) false else from.renameTo(to)
            })

            val failure: IllegalStateException = assertFailsWith<IllegalStateException> { refusing.commitResource(opaque) }
            assertTrue("aside" in failure.message.orEmpty(), "${failure.message}")
            assertTrue(target.isDirectory, "the directory is gone")
            val whole: Map<String, ByteArray> =
                target.walkTopDown().filter { it.isFile }.associate { it.relativeTo(target).invariantSeparatorsPath to it.readBytes() }
            assertEquals(tree.keys, whole.keys, "the directory is not whole")
            assertTrue(tree.all { (name: String, data: ByteArray) -> data.contentEquals(whole.getValue(name)) })
            assertEquals(TempFileState.Complete, refusing.tempFileState(opaque))

            val retry: DownloaderStorage = newStorage()
            retry.commitResource(opaque)
            assertTrue(target.isFile)
            assertTrue(payload.contentEquals(target.readBytes()))
            assertEquals(emptyList(), trashNames())
            assertEquals(TempFileState.None, retry.tempFileState(opaque))
        }
    }

    /**
     * Test 6: the process dies in the delete of the detached directory after the lock. The file is already at the path, the
     * remainder is only under the trash, and the next sweep (any deleteTempFile) empties it.
     */
    @Test
    fun `a death in the delete of the detached directory leaves the file in place and only the trash holds the remainder`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val zipUnit: TestUnit = archiveUnit(group, "res/u")
            val opaque: TestUnit = opaqueUnit(group, "res/u")
            val first: DownloaderStorage = newStorage()
            stage(first, zipUnit, zip(archiveTree()))
            first.commitResource(zipUnit)
            stage(first, opaque, payload)
            val target = File(first.getResourcePath(opaque))
            val real: FileAccess = createFileAccess()
            val dying: DownloaderStorage = newStorage(files = object : FileAccess by real {
                override fun deleteRecursively(path: String, cancellation: CancellationCheck): FileResult<Unit> {
                    if (File(path).absoluteFile.parentFile == trashDir.absoluteFile) throw ProcessDeath("deleting the detached tree")
                    return real.deleteRecursively(path, cancellation)
                }
            })

            assertFailsWith<ProcessDeath> { dying.commitResource(opaque) }

            assertTrue(target.isFile, "the file is not at the path")
            assertTrue(payload.contentEquals(target.readBytes()))
            assertEquals(1, trashNames().size, "the remainder is not exactly one trash entry: ${trashNames()}")
            assertTrue(File(trashDir, trashNames().single()).isDirectory)

            val after: DownloaderStorage = newStorage()
            assertTrue(after.isResourceAvailable(opaque))
            after.deleteTempFile(opaque)
            assertEquals(emptyList(), trashNames(), "the sweep did not empty the trash")
            assertTrue(target.isFile)
        }
    }

    // -- Test 7: the engine over the real storage ---------------------------------------------------------------------

    private class MapStateStore : DownloadStateStore {
        private val ints: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
        override fun readInt(key: String, default: Int): Int = ints[key] ?: default
        override fun writeInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun remove(key: String) {
            ints.remove(key)
        }
    }

    /** The host's downloader: it writes the staged bytes of the unit's current format and reports the file ready. */
    private class Port(
        private val storage: DownloaderStorage,
        private val bytesFor: (DownloadUnit) -> ByteArray,
    ) : BackgroundResourceDownloader {
        val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 256)
        val enqueues: AtomicInteger = AtomicInteger()

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            storage.beginTempFile(unit, null)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytesFor(unit))
            storage.markTempFileComplete(unit)
            events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) {
            events.tryEmit(BackgroundDownloadEvent.Cancelled(unit))
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = events.filter { it.unit.id == unit.id }
    }

    /**
     * Test 7: engine level, over the real shipped storage. A unit whose catalogue changes from ZipArchive to Opaque at an
     * unchanged path is unavailable after the change (it is not served as a directory), transfers exactly once more, and ends
     * Completed with a file at the path.
     */
    @Test
    fun `a unit whose catalogue changes from archive to opaque at one path transfers once and ends with a file`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val unit = TestUnit("u", "res/u", group, ResourceFormat.ZipArchive("marker.txt"), null)
            group.units = listOf(unit)
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val port = Port(storage) { u: DownloadUnit ->
                if (u.format is ResourceFormat.Opaque) payload else zip(archiveTree())
            }
            val engine: Downloader = createDownloader(
                storage = storage,
                backgroundDownloader = port,
                groups = listOf(group),
                stateStore = MapStateStore(),
                dispatchers = DownloadDispatchers.Default,
                logger = NoopLogger,
            )

            engine.ensureAvailable(unit)
            assertTrue(engine.isAvailable(unit))
            assertTrue(File(engine.pathOf(unit)).isDirectory, "the archive did not commit as a tree")
            assertEquals(1, port.enqueues.get())

            unit.format = ResourceFormat.Opaque
            assertFalse(engine.isAvailable(unit), "the old tree reads as the available opaque resource")
            engine.ensureAvailable(unit)

            assertEquals(2, port.enqueues.get(), "the opaque resource did not transfer exactly once")
            assertTrue(engine.isAvailable(unit))
            val target = File(engine.pathOf(unit))
            assertTrue(target.isFile, "the path does not hold a file")
            assertTrue(payload.contentEquals(target.readBytes()))
            assertEquals(emptyList(), trashNames())
            engine.ensureAvailable(unit)
            assertEquals(2, port.enqueues.get(), "an available unit started another transfer")
        }
    }
}
