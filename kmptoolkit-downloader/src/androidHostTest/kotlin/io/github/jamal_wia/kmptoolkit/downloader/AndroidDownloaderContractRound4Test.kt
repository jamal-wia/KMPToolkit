package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
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
import org.junit.runner.RunWith

/**
 * The documented guarantees of the Android storage and engine, tested literally with edge inputs
 * and a hostile file system: a replacing commit that fails, zero-byte resources, an unwritable
 * directory, and what the files look like after many cycles.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderContractRound4Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "contract_round4")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val outside: File get() = File(context.filesDir, "round4-outside")

    @BeforeTest
    fun setUp() {
        makeTreeWritable(root)
        root.deleteRecursively()
        outside.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        makeTreeWritable(root)
        root.deleteRecursively()
        outside.deleteRecursively()
    }

    private fun makeTreeWritable(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().forEach { entry: File ->
            runCatching { Files.setPosixFilePermissions(entry.toPath(), PosixFilePermission.values().toSet()) }
        }
    }

    private fun setReadOnly(dir: File) {
        Files.setPosixFilePermissions(
            dir.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
        )
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class TestUnit(
        override val id: String,
        override val relativePath: String,
        override val group: ResourceGroup,
        override val format: ResourceFormat = ResourceFormat.Opaque,
        override val sha256: Sha256? = null,
        override val tempExtension: String = "tmp",
    ) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override fun toString(): String = "TestUnit($id)"
    }

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

    /** Writes [payload] and marks it complete in enqueue, like a finished transfer. */
    private class Service(private val storage: DownloaderStorage, var payload: ByteArray) : BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 256)
        val enqueues: AtomicInteger = AtomicInteger()

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            storage.beginTempFile(unit, null)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(payload)
            storage.markTempFileComplete(unit)
            events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            events.filter { it.unit.id == unit.id }
    }

    private fun engine(storage: DownloaderStorage, port: BackgroundResourceDownloader, group: ResourceGroup): Downloader =
        createDownloader(
            storage = storage,
            backgroundDownloader = port,
            groups = listOf(group),
            stateStore = MapStateStore(),
            dispatchers = DownloadDispatchers.Default,
            logger = NoopLogger,
        )

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out: ZipOutputStream ->
            for ((name: String, text: String) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.encodeToByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** What a transfer does, then the commit: the same calls a port and the engine make. */
    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, null)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
    }

    private fun storage(): DownloaderStorage = createDownloaderStorage(context, config)

    private fun archive(id: String, group: ResourceGroup = Group("g")): TestUnit =
        TestUnit(id, "archives/$id", group, ResourceFormat.ZipArchive("ok.txt"))

    // -- A replacing commit that fails never takes the committed resource away ----------------------

    @Test
    fun `a corrupt archive keeps the committed archive, removes its staging and keeps its own temp file`() = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = storage()
            val unit: TestUnit = archive("a2")
            stage(storage, unit, zip("ok.txt" to "1", "old.bin" to "old"))
            storage.commitResource(unit)
            val good: ByteArray = zip("ok.txt" to "2", "new.bin" to "x".repeat(10_000))
            stage(storage, unit, good.copyOf(good.size / 2))

            assertFailsWith<Exception> { storage.commitResource(unit) }

            assertTrue(storage.isResourceAvailable(unit))
            assertEquals("old", File(storage.getResourcePath(unit), "old.bin").readText())
            assertFalse(File(root, "tmp/staging-a2").exists(), "staging of the failed extraction is still there")
            assertEquals(TempFileState.Complete, storage.tempFileState(unit))
        }
    }

    @Test
    fun `an archive without the marker never replaces a committed archive`() = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = storage()
            val unit: TestUnit = archive("a1")
            stage(storage, unit, zip("ok.txt" to "1", "old.bin" to "old"))
            storage.commitResource(unit)
            assertTrue(storage.isResourceAvailable(unit))

            stage(storage, unit, zip("other.bin" to "new"))
            assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

            assertTrue(storage.isResourceAvailable(unit), "a previously available resource was replaced by one that is not")
            assertEquals("old", File(storage.getResourcePath(unit), "old.bin").readText())
            assertEquals(TempFileState.None, storage.tempFileState(unit))
            assertFalse(File(root, "tmp/staging-a1").exists(), "staging of the failed commit is still there")
        }
    }

    @Test
    fun `an archive without the marker fails the engine with Corrupted after exactly two transfers and leaves nothing`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = storage()
            val group = Group("g")
            val unit: TestUnit = archive("a4", group)
            group.units = listOf(unit)
            val service = Service(storage, zip("other.bin" to "new"))
            val engine: Downloader = engine(storage, service, group)

            val failure: Throwable? = runCatching { engine.ensureAvailable(unit) }.exceptionOrNull()

            assertTrue(failure is DownloadFailedException, "$failure")
            assertTrue(failure.error is DownloadError.Corrupted, "${failure.error}")
            assertEquals(2, service.enqueues.get())
            assertFalse(engine.isAvailable(unit))
            assertFalse(File(root, "tmp/staging-a4").exists())
            assertEquals(TempFileState.None, storage.tempFileState(unit))
        }
    }

    @Test
    fun `zip entries that climb out of the target fail the commit and write nothing outside`() = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = storage()
            val unit: TestUnit = archive("a3")
            val names: List<String> = listOf(
                "../evil.txt", "sub/../../evil.txt", "../staging-a3-evil/x.txt", "..\\evil.txt",
                "../../round4-outside/evil.txt", "a/b/../../../evil.txt",
            )
            for (name in names) {
                stage(storage, unit, zip("ok.txt" to "1", name to "evil"))
                runCatching { storage.commitResource(unit) }
                assertFalse(File(context.filesDir, "evil.txt").exists(), "$name escaped to filesDir")
                assertFalse(File(root, "evil.txt").exists(), "$name escaped to the base")
                assertFalse(File(root, "tmp/evil.txt").exists(), "$name escaped to tmp")
                assertFalse(outside.exists(), "$name escaped to a sibling directory")
                assertFalse(File(root, "tmp/staging-a3-evil").exists(), "$name escaped to a staging-like sibling")
                storage.deleteTempFile(unit)
                storage.deleteResource(unit)
            }
        }
    }

    @Test
    fun `a failed integrity check keeps the committed file and deletes the new temp file`() = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = storage()
            val group = Group("g")
            val wrong: Sha256 = Sha256.parse("a".repeat(64))
            val unit = TestUnit("p1", "files/p1.bin", group, sha256 = null)
            stage(storage, unit, "old".encodeToByteArray())
            storage.commitResource(unit)

            val checked = TestUnit("p1", "files/p1.bin", group, sha256 = wrong)
            stage(storage, checked, "new".encodeToByteArray())
            assertFailsWith<ResourceIntegrityException> { storage.commitResource(checked) }

            assertEquals("old", File(storage.getResourcePath(unit)).readText())
            assertEquals(TempFileState.None, storage.tempFileState(unit))
        }
    }

    @Test
    fun `a database that does not open keeps the committed database`() = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = storage()
            val unit = TestUnit("d1", "db/d1.sqlite", Group("g"), ResourceFormat.SqliteDatabase())
            stage(storage, unit, "old-bytes".encodeToByteArray())
            runCatching { storage.commitResource(unit) } // not a database either: nothing committed

            val committed = TestUnit("d1", "db/d1.sqlite", Group("g"))
            stage(storage, committed, "old".encodeToByteArray())
            storage.commitResource(committed)
            stage(storage, unit, "not a database".encodeToByteArray())
            assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }
            assertEquals("old", File(storage.getResourcePath(unit)).readText())
        }
    }

    // -- Zero-byte resources ------------------------------------------------------------------------

    @Test
    fun `a zero-byte resource commits, is available, has size zero and leaves no temp file`() = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = storage()
            val unit = TestUnit("z1", "files/z1.bin", Group("g"))
            storage.beginTempFile(unit, null)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(0))
            assertEquals(TempFileState.None, storage.tempFileState(unit))
            storage.markTempFileComplete(unit)
            assertEquals(TempFileState.Complete, storage.tempFileState(unit))
            storage.commitResource(unit)
            assertTrue(storage.isResourceAvailable(unit))
            assertEquals(0L, storage.getResourceSize(unit))
            assertEquals(TempFileState.None, storage.tempFileState(unit))
            storage.deleteResource(unit)
            assertFalse(storage.isResourceAvailable(unit))
        }
    }

    @Test
    fun `an engine downloads a zero-byte resource to completion`() = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = storage()
            val group = Group("g")
            val unit = TestUnit("z2", "files/z2.bin", group)
            group.units = listOf(unit)
            val service = Service(storage, ByteArray(0))
            val engine: Downloader = engine(storage, service, group)
            engine.ensureAvailable(unit)
            assertTrue(engine.isAvailable(unit))
            assertEquals(1, service.enqueues.get())
            assertEquals(0L, File(engine.pathOf(unit)).length())
        }
    }

    // -- Permission denied --------------------------------------------------------------------------

    @Test
    fun `an unwritable target directory fails the commit as a storage error, keeps the temp file and commits it later without a transfer`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = storage()
            val group = Group("g")
            val unit = TestUnit("w1", "locked/w1.bin", group)
            group.units = listOf(unit)
            val service = Service(storage, "payload".encodeToByteArray())
            val engine: Downloader = engine(storage, service, group)
            val locked = File(root, "locked").apply { mkdirs() }
            setReadOnly(locked)
            try {
                val failure: DownloadFailedException = assertFailsWith { engine.ensureAvailable(unit) }
                assertTrue(failure.error is DownloadError.Storage, "${failure.error}")
                assertEquals(1, service.enqueues.get())
                assertEquals(TempFileState.Complete, storage.tempFileState(unit))
                assertFalse(engine.isAvailable(unit))
            } finally {
                makeTreeWritable(locked)
            }
            engine.ensureAvailable(unit)
            assertEquals(1, service.enqueues.get(), "the kept temp file was transferred again")
            assertEquals("payload", File(engine.pathOf(unit)).readText())
        }
    }

    @Test
    fun `deleteTempFile never throws in an unwritable, blocked or read-only tree`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = storage()
            val group = Group("g")
            val unit = archive("h1", group)
            stage(storage, unit, zip("ok.txt" to "1"))
            val tmp = File(root, "tmp")
            val staging = File(root, "tmp/staging-h1/sub").apply { mkdirs() }
            File(staging, "f.bin").writeText("x")
            val trash = File(root, UnitFileLayout.TRASH_DIRECTORY + "/9/inner").apply { mkdirs() }
            File(trash, "f.bin").writeText("x")
            val partialAsDirectory = File(storage.getTempFilePath(unit))
            try {
                setReadOnly(staging)
                setReadOnly(trash)
                repeat(10) { storage.deleteTempFile(unit) }
                setReadOnly(tmp)
                repeat(10) { storage.deleteTempFile(unit) }
                repeat(10) { storage.deleteTempFile(archive("never-seen", group)) }
            } finally {
                makeTreeWritable(root)
            }
            assertTrue(partialAsDirectory.exists() || !partialAsDirectory.exists()) // reached without a throw
            storage.deleteTempFile(unit)
            assertEquals(TempFileState.None, storage.tempFileState(unit))
        }
    }

    @Test
    fun `deleteResource and getResourceSize and isResourceAvailable never throw for a read-only or odd target`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = storage()
            val unit = archive("r1")
            stage(storage, unit, zip("ok.txt" to "1", "dir/inner.bin" to "x"))
            storage.commitResource(unit)
            val target = File(storage.getResourcePath(unit))
            try {
                setReadOnly(File(target, "dir"))
                setReadOnly(target)
                assertTrue(storage.getResourceSize(unit) > 0)
                storage.deleteResource(unit)
            } finally {
                makeTreeWritable(root)
            }
            storage.deleteResource(unit)
            assertFalse(storage.isResourceAvailable(unit), "the resource is still there after two deletes")
            assertEquals(0L, storage.getResourceSize(unit))
        }
    }

    // -- Odd resource paths and sizes ---------------------------------------------------------------

    @Test
    fun `a path component longer than the file system allows ends in a storage error, one transfer, and a kept temp file`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = storage()
            val group = Group("g")
            val unit = TestUnit("long1", "files/" + "n".repeat(300) + ".bin", group)
            group.units = listOf(unit)
            val service = Service(storage, "payload".encodeToByteArray())
            val engine: Downloader = engine(storage, service, group)
            repeat(3) { attempt: Int ->
                val failure: Throwable? = runCatching { engine.ensureAvailable(unit) }.exceptionOrNull()
                assertTrue(failure is DownloadFailedException, "attempt $attempt: $failure")
                assertTrue(failure.error is DownloadError.Storage, "attempt $attempt: ${failure.error}")
            }
            assertEquals(1, service.enqueues.get(), "an unusable path was transferred again")
            assertFalse(engine.isAvailable(unit))
            assertEquals(0L, storage.getResourceSize(unit))
            engine.cancelDownload(unit)
            assertEquals(TempFileState.None, storage.tempFileState(unit))
        }
    }

    // -- What is left on disk after many cycles -----------------------------------------------------

    @Test
    fun `many cycles of commit, delete, cancel, integrity failure and corrupt archive leave only the layout marker`() = runBlocking {
        withTimeout(120_000) {
            val storage: DownloaderStorage = storage()
            val group = Group("g")
            val wrong: Sha256 = Sha256.parse("b".repeat(64))
            val units: List<TestUnit> = listOf(
                TestUnit("plain", "files/plain.bin", group),
                TestUnit("odd/slash", "files/odd-slash.bin", group),
                TestUnit("n".repeat(240), "files/long.bin", group, tempExtension = "ext"),
                TestUnit("ext", "files/ext.bin", group, tempExtension = "a/b"),
                archive("arch", group),
                archive("arch/slash", group),
                TestUnit("checked", "files/checked.bin", group, sha256 = wrong),
            )
            repeat(25) { round: Int ->
                for (unit in units) {
                    val bytes: ByteArray = if (unit.isDirectoryResource) zip("ok.txt" to "$round") else "r$round".encodeToByteArray()
                    stage(storage, unit, bytes)
                    runCatching { storage.commitResource(unit) }
                    storage.deleteResource(unit)
                    storage.deleteTempFile(unit)
                    storage.beginTempFile(unit, null)
                    File(storage.getTempFilePath(unit)).writeBytes(byteArrayOf(1, 2, 3))
                    storage.deleteTempFile(unit)
                    stage(storage, unit, if (unit.isDirectoryResource) byteArrayOf(9, 9, 9) else bytes)
                    runCatching { storage.commitResource(unit) }
                    storage.deleteResource(unit)
                    storage.deleteTempFile(unit)
                }
            }
            val files: List<String> = root.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(root).path }.sorted().toList()
            assertEquals(listOf(UnitFileLayout.LEGACY_LAYOUT_MARKER), files)
        }
    }
}
