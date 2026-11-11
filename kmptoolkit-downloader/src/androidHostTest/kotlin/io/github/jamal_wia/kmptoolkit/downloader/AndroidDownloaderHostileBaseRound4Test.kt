package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * A base directory the storage cannot use (a plain file in its place, a name no file system takes,
 * a read-only directory, a directory deleted while the engine lives), the descriptors the storage
 * holds over many failing commits, and a resource larger than an Int can count.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderHostileBaseRound4Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names: MutableList<String> = mutableListOf()
    private val restorers: MutableList<AutoCloseable> = mutableListOf()

    @BeforeTest
    fun setUp() {
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        restorers.asReversed().forEach { restorer: AutoCloseable -> runCatching { restorer.close() } }
        restorers.clear()
        for (name in names) File(context.filesDir, name).deleteRecursively()
    }

    private fun baseName(prefix: String): String = (prefix + System.nanoTime()).also { names += it }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class TestUnit(override val id: String, override val relativePath: String, override val group: ResourceGroup) :
        DownloadUnit {
        override val apiPath: String = "test/$id"
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

    private class Service(private val storage: DownloaderStorage) : BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 256)
        val enqueues: AtomicInteger = AtomicInteger()

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            storage.beginTempFile(unit, null)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(16) { 3 })
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

    private fun outcomeOfRequests(base: String, label: String) {
        val storage: DownloaderStorage = createDownloaderStorage(context, DownloaderStorageConfig(base))
        val group = Group("g")
        val unit = TestUnit("u", "files/u.bin", group)
        group.units = listOf(unit)
        val engine: Downloader = engine(storage, Service(storage), group)
        runBlocking {
            withTimeout(60_000) {
                repeat(3) { attempt: Int ->
                    val failure: Throwable? = runCatching { engine.ensureAvailable(unit) }.exceptionOrNull()
                    assertTrue(failure is DownloadFailedException, "$label attempt $attempt: $failure")
                    assertFalse(engine.isAvailable(unit), label)
                    assertFalse(engine.hasActiveUnitDownload(), label)
                }
                val groupFailure: Throwable? = runCatching { engine.ensureAvailable(group) }.exceptionOrNull()
                assertTrue(groupFailure is DownloadFailedException, "$label group: $groupFailure")
                engine.cancelDownload(unit)
                engine.cancelDownload(group)
                engine.cancelAllDownloads()
            }
        }
    }

    @Test
    fun `a plain file where the base directory should be fails requests with a typed failure`() {
        val base: String = baseName("file_base_")
        File(context.filesDir, base).writeText("not a directory")
        outcomeOfRequests(base, "file in place of base")
        assertEquals("not a directory", File(context.filesDir, base).readText())
    }

    @Test
    fun `a base directory name the file system cannot take fails requests with a typed failure`() {
        outcomeOfRequests("n".repeat(300), "300-char base name")
    }

    @Test
    fun `a read-only base directory fails every request with a typed failure and eight callers all return`() {
        // A Windows ACL deny stops creating files but not creating sub-directories (measured), so the
        // storage would still be able to build its tree there: only a POSIX read-only directory is one.
        TestHost.assumePosixFileSemantics("a read-only directory refuses to get sub-directories")
        val base: String = baseName("ro_base_")
        val dir = File(context.filesDir, base).apply { mkdirs() }
        restorers += TestFs.makeUnwritable(dir)
        outcomeOfRequests(base, "read-only base")
        val storage: DownloaderStorage = createDownloaderStorage(context, DownloaderStorageConfig(base))
        val group = Group("g")
        val unit = TestUnit("u8", "files/u8.bin", group)
        group.units = listOf(unit)
        val engine: Downloader = engine(storage, Service(storage), group)
        runBlocking {
            withTimeout(60_000) {
                val failures: List<Throwable?> = (0 until 8).map {
                    async(Dispatchers.Default) { runCatching { engine.ensureAvailable(unit) }.exceptionOrNull() }
                }.awaitAll()
                assertTrue(failures.all { it is DownloadFailedException }, "$failures")
            }
        }
    }

    @Test
    fun `a base directory deleted while the engine lives is recreated by the next request`() {
        val base: String = baseName("deleted_base_")
        val storage: DownloaderStorage = createDownloaderStorage(context, DownloaderStorageConfig(base))
        val group = Group("g")
        val unit = TestUnit("u", "files/u.bin", group)
        val hashed = TestUnit("odd/id", "files/odd.bin", group)
        group.units = listOf(unit, hashed)
        val service = Service(storage)
        val engine: Downloader = engine(storage, service, group)
        runBlocking {
            withTimeout(60_000) {
                repeat(5) { round: Int ->
                    engine.ensureAvailable(unit)
                    engine.ensureAvailable(hashed)
                    assertTrue(engine.isAvailable(unit) && engine.isAvailable(hashed), "round $round")
                    File(context.filesDir, base).deleteRecursively()
                    assertFalse(engine.isAvailable(unit), "round $round: still available after the base was deleted")
                }
                engine.ensureAvailable(group)
                assertTrue(engine.isAvailable(group))
            }
        }
    }

    // -- Descriptors ---------------------------------------------------------------------------------

    /** Open descriptors of this process; skips on a host that cannot count them (Windows), fails on one that should. */
    private fun openDescriptors(): Int = TestFs.requireOpenDescriptorCount().toInt()

    @Test
    fun `hundreds of failing commits of every kind hold no descriptor afterwards`() {
        val base: String = baseName("fd_base_")
        val storage: DownloaderStorage = createDownloaderStorage(context, DownloaderStorageConfig(base))
        val group = Group("g")
        val archive = object : DownloadUnit {
            override val id: String = "arc"
            override val apiPath: String = "t/arc"
            override val relativePath: String = "arc"
            override val format: ResourceFormat = ResourceFormat.ZipArchive("ok.txt")
            override val group: ResourceGroup = group
        }
        val database = object : DownloadUnit {
            override val id: String = "db"
            override val apiPath: String = "t/db"
            override val relativePath: String = "db.sqlite"
            override val format: ResourceFormat = ResourceFormat.SqliteDatabase("t", "k")
            override val group: ResourceGroup = group
        }
        val checked = object : DownloadUnit {
            override val id: String = "chk"
            override val apiPath: String = "t/chk"
            override val relativePath: String = "chk.bin"
            override val sha256: Sha256? = Sha256.parse("c".repeat(64))
            override val group: ResourceGroup = group
        }
        fun cycle() {
            for (unit in listOf(archive, database, checked)) {
                storage.beginTempFile(unit, null)
                File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(4_000) { it.toByte() })
                storage.markTempFileComplete(unit)
                runCatching { runBlocking { storage.commitResource(unit) } }
                storage.deleteTempFile(unit)
                storage.getResourceSize(unit)
                storage.deleteResource(unit)
            }
        }
        repeat(20) { cycle() }
        val before: Int = openDescriptors()
        repeat(300) { cycle() }
        val after: Int = openDescriptors()
        assertTrue(after <= before + 10, "descriptors grew from $before to $after over 900 failing commits")
    }

    // -- A resource larger than an Int ---------------------------------------------------------------

    @Test
    fun `a five gigabyte sparse resource keeps its size as a Long through temp state, commit and delete`() {
        val base: String = baseName("huge_base_")
        val storage: DownloaderStorage = createDownloaderStorage(context, DownloaderStorageConfig(base))
        val unit = TestUnit("huge", "files/huge.bin", Group("g"))
        val size: Long = 5L * 1024 * 1024 * 1024
        runBlocking {
            withTimeout(60_000) {
                storage.beginTempFile(unit, null)
                val partial = File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }
                RandomAccessFile(partial, "rw").use { it.setLength(size) }
                assertEquals(TempFileState.Partial, storage.tempFileState(unit))
                assertEquals(size, storage.getTempFileSize(unit))
                storage.markTempFileComplete(unit)
                storage.commitResource(unit)
                assertTrue(storage.isResourceAvailable(unit))
                assertEquals(size, storage.getResourceSize(unit))
                storage.deleteResource(unit)
                assertEquals(0L, storage.getResourceSize(unit))
            }
        }
    }
}
