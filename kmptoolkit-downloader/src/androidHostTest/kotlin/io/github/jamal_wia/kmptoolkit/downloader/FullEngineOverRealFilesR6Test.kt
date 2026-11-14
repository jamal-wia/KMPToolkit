package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 6 of the breaker for unit G, Q1 (downloader): the real default engine (`createDownloader` over
 * `createDownloaderStorage`, both public) on real files in the Robolectric app directory, driven through one happy path,
 * one cancelled path, one disk-full commit and two destination-vanished paths. The seam that does not exist: the storage
 * writes the temp file through the port (the host's own java.io writes) and uses kmptoolkit-files only for tree walks and
 * the no-replace rename (`renameNoReplace` holds a private lazily created FileAccess), so a fault-injecting FileAccess
 * cannot be threaded into `createDownloaderStorage`. The disk-full commit is therefore injected at the public
 * DownloaderStorage interface by a delegating wrapper around the real storage; everything underneath is real.
 */
@RunWith(AndroidJUnit4::class)
class FullEngineOverRealFilesR6Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "full_engine_r6")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
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

    /** The host's downloader: what [onEnqueue] does stands for the transfer; the events are what a real one emits. */
    private class Port(
        private val storage: DownloaderStorage,
        private val onEnqueue: (DownloadUnit, Port) -> Unit,
    ) : BackgroundResourceDownloader {
        val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 256)
        val enqueues: AtomicInteger = AtomicInteger()
        val cancels: AtomicInteger = AtomicInteger()
        val started: CompletableDeferred<Unit> = CompletableDeferred()

        fun writeAndComplete(unit: DownloadUnit, bytes: ByteArray) {
            storage.beginTempFile(unit, null)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
            storage.markTempFileComplete(unit)
        }

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            onEnqueue(unit, this)
            started.complete(Unit)
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) {
            cancels.incrementAndGet()
            events.tryEmit(BackgroundDownloadEvent.Cancelled(unit))
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = events.filter { it.unit.id == unit.id }
    }

    /** A real storage whose first commit is refused like a full disk (what a write to a full volume throws). */
    private class FullDiskOnce(private val real: DownloaderStorage) : DownloaderStorage by real {
        val refusals: AtomicInteger = AtomicInteger()
        override suspend fun commitResource(unit: DownloadUnit) {
            if (refusals.getAndIncrement() == 0) throw IOException("No space left on device")
            real.commitResource(unit)
        }
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

    private fun storage(): DownloaderStorage = createDownloaderStorage(context, config)

    private fun litter(): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.filter { (it.contains("tmp") || it.contains("staging")) && !it.contains("kmptoolkit-internal") }.sorted().toList()

    private fun payload(): ByteArray = ByteArray(70_000) { (it % 251).toByte() }

    private fun setup(onEnqueue: (DownloadUnit, Port) -> Unit, wrap: (DownloaderStorage) -> DownloaderStorage = { it }): Triple<TestUnit, Port, Downloader> {
        val real: DownloaderStorage = storage()
        val storage: DownloaderStorage = wrap(real)
        val group = Group("g")
        val unit = TestUnit("u", "files/u.bin", group)
        group.units = listOf(unit)
        val port = Port(real, onEnqueue)
        return Triple(unit, port, engine(storage, port, group))
    }

    @Test
    fun `happy path commits the whole payload once and leaves no temp litter`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val bytes: ByteArray = payload()
            val (unit: TestUnit, port: Port, engine: Downloader) = setup({ u, p ->
                p.writeAndComplete(u, bytes)
                p.events.tryEmit(BackgroundDownloadEvent.FileReady(u))
            })
            engine.ensureAvailable(unit)
            assertTrue(engine.isAvailable(unit))
            assertTrue(bytes.contentEquals(File(engine.pathOf(unit)).readBytes()), "the committed bytes differ from the payload")
            assertEquals(emptyList(), litter(), "temp litter after the commit")
            engine.ensureAvailable(unit)
            assertEquals(1, port.enqueues.get(), "an available unit started another transfer")
        }
    }

    @Test
    fun `a cancelled transfer ends with the cancel exception removes the partial file and commits nothing`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val (unit: TestUnit, port: Port, engine: Downloader) = setup({ u, p ->
                val storage: DownloaderStorage = createDownloaderStorage(context, config)
                storage.beginTempFile(u, null)
                File(storage.getTempFilePath(u)).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(10_000) { 7 })
                // no FileReady: the transfer is running until the user cancels
            })
            val call = async(Dispatchers.Default) { runCatching { engine.ensureAvailable(unit) }.exceptionOrNull() }
            port.started.await()
            engine.cancelDownload(unit)
            val failure: Throwable? = call.await()
            assertTrue(failure is DownloadCancelledException, "$failure")
            assertFalse(engine.isAvailable(unit))
            assertEquals(1, port.cancels.get())
            assertEquals(emptyList(), litter(), "the partial file of a cancelled transfer is still there")
        }
    }

    @Test
    fun `a full disk at the commit is a Storage error that keeps the complete temp file and the next call commits it without a transfer`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val bytes: ByteArray = payload()
            var wrapped: FullDiskOnce? = null
            val (unit: TestUnit, port: Port, engine: Downloader) = setup({ u, p ->
                p.writeAndComplete(u, bytes)
                p.events.tryEmit(BackgroundDownloadEvent.FileReady(u))
            }, { real: DownloaderStorage -> FullDiskOnce(real).also { wrapped = it } })
            val failure: Throwable? = runCatching { engine.ensureAvailable(unit) }.exceptionOrNull()
            assertTrue(failure is DownloadFailedException, "$failure")
            assertTrue(failure.error is DownloadError.Storage, "${failure.error}")
            assertFalse(engine.isAvailable(unit))
            val real: DownloaderStorage = storage()
            assertEquals(TempFileState.Complete, real.tempFileState(unit), "the complete temp file was not kept")
            assertEquals(1, port.enqueues.get())

            engine.ensureAvailable(unit)
            assertTrue(engine.isAvailable(unit))
            assertEquals(1, port.enqueues.get(), "the retry started a transfer instead of committing the kept file")
            assertTrue(bytes.contentEquals(File(engine.pathOf(unit)).readBytes()))
            assertEquals(emptyList(), litter(), "temp litter after the recovery")
            assertEquals(2, wrapped!!.refusals.get())
        }
    }

    @Test
    fun `a destination directory that vanishes before the commit ends in a typed outcome and never a half written resource`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val bytes: ByteArray = payload()
            val (unit: TestUnit, _, engine: Downloader) = setup({ u, p ->
                p.writeAndComplete(u, bytes)
                // The directory the resource goes into disappears after the transfer finished.
                File(createDownloaderStorage(context, config).getResourcePath(u)).parentFile?.deleteRecursively()
                p.events.tryEmit(BackgroundDownloadEvent.FileReady(u))
            })
            val failure: Throwable? = runCatching { engine.ensureAvailable(unit) }.exceptionOrNull()
            if (failure == null) {
                assertTrue(bytes.contentEquals(File(engine.pathOf(unit)).readBytes()), "a commit into a recreated directory is torn")
            } else {
                assertTrue(failure is DownloadFailedException, "an untyped failure: $failure")
                assertFalse(engine.isAvailable(unit))
            }
            assertEquals(emptyList(), litter().filterNot { failure != null && it.endsWith(".complete") || failure != null && it.endsWith(".tmp") }, "litter")
        }
    }

    @Test
    fun `the whole base directory vanishing under a finished transfer ends in a typed outcome within the bound`() = runBlocking<Unit> {
        withTimeout(90_000) {
            val bytes: ByteArray = payload()
            val enqueues = AtomicInteger()
            val (unit: TestUnit, _, engine: Downloader) = setup({ u, p ->
                if (enqueues.getAndIncrement() == 0) {
                    p.writeAndComplete(u, bytes)
                    root.deleteRecursively()
                    p.events.tryEmit(BackgroundDownloadEvent.FileReady(u))
                } else {
                    p.writeAndComplete(u, bytes)
                    p.events.tryEmit(BackgroundDownloadEvent.FileReady(u))
                }
            })
            val outcome: Throwable? = withContext(Dispatchers.Default) { runCatching { engine.ensureAvailable(unit) }.exceptionOrNull() }
            if (outcome == null) {
                assertTrue(bytes.contentEquals(File(engine.pathOf(unit)).readBytes()), "success with torn content")
            } else {
                assertTrue(outcome is DownloadFailedException || outcome is DownloadCancelledException, "an untyped failure: $outcome")
                assertFailsWith<DownloadFailedException> { throw outcome }
            }
        }
    }
}
