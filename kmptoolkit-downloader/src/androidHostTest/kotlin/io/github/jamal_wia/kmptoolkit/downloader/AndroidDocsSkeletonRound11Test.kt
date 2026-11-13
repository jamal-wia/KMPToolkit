package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadUrlResolver
import io.github.jamal_wia.kmptoolkit.downloader.spi.ResolvedDownload
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.security.MessageDigest
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * B round 11, Q3: the Android skeleton of docs/kmptoolkit-downloader/07-background-downloader.md ("A worked skeleton
 * (Android)") compiled as written (the class body below is the documented one; only the comment block of
 * `enqueueDownload` is replaced by a call that starts the stand-in service, and `finish` is not private) and driven by the
 * real engine over the real Android storage. Contract: the 07 contract list (idempotent enqueue, `isDownloadInProgress`
 * false when `cancelDownload` returns, the cancelled `Cancelled` not replayed, `HTTP 404` read as NotFound with the partial
 * deleted, a whole file marked complete before `FileReady`).
 */
@RunWith(AndroidJUnit4::class)
class AndroidDocsSkeletonRound11Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "docs_skeleton_r11")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        serviceScope.cancel()
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class TestUnit(override val id: String, override val group: ResourceGroup, override val sha256: Sha256?) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val relativePath: String = "files/$id.bin"
        override val tempExtension: String = "tmp"
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

    /** The class of the documentation. [startService] stands for "Start your foreground service / worker here". */
    private class MyBackgroundResourceDownloader(
        private val storage: DownloaderStorage,
        private val urlResolver: DownloadUrlResolver,
        private val startService: (MyBackgroundResourceDownloader, DownloadUnit) -> Unit,
    ) : BackgroundResourceDownloader {

        // A plain hot flow, no replay: the engine subscribes before it enqueues, so nothing is lost,
        // and there is no leftover terminal event to deliver to a later attempt.
        private val events = MutableSharedFlow<BackgroundDownloadEvent>(replay = 0, extraBufferCapacity = 64)

        // The service/worker thread, the engine and cancelDownload all touch this set: keep it in one
        // atomic cell. A plain mutableSetOf() here is a data race.
        private val inProgress = MutableStateFlow<Set<String>>(emptySet())

        override fun enqueueDownload(unit: DownloadUnit) {
            var added = false
            inProgress.update { current ->
                added = unit.id !in current
                current + unit.id
            }
            if (!added) return // already running — join it
            startService(this, unit)
        }

        // Emit first, then drop the id: a subscriber that sees the terminal event can still ask
        // isDownloadInProgress and must not be told "gone" before it has the outcome. Either order
        // is safe for the engine; this one never reports a finished transfer as vanished.
        fun finish(terminal: BackgroundDownloadEvent.Terminal) {
            events.tryEmit(terminal)
            inProgress.update { it - terminal.unit.id }
        }

        fun progress(unit: DownloadUnit, fraction: Float) {
            events.tryEmit(BackgroundDownloadEvent.Progress(unit, fraction))
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            events.filter { it.unit.id == unit.id }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = unit.id in inProgress.value

        override fun cancelDownload(unit: DownloadUnit) {
            // Stop your service/worker for unit.id; do NOT delete the temp file here.
            // The id goes first: once this returns isDownloadInProgress must be false. The Cancelled
            // event is not replayed (replay = 0), so a request made right after starts clean.
            inProgress.update { it - unit.id }
            events.tryEmit(BackgroundDownloadEvent.Cancelled(unit))
        }

        fun stillRunning(unit: DownloadUnit): Boolean = isDownloadInProgress(unit)
    }

    private fun sha(bytes: ByteArray): Sha256 =
        Sha256.parse(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private val resolver: DownloadUrlResolver = DownloadUrlResolver { unit: DownloadUnit -> ResolvedDownload("https://example.invalid/${unit.id}", unit.sha256) }

    private fun engine(storage: DownloaderStorage, port: BackgroundResourceDownloader, group: ResourceGroup): Downloader =
        createDownloader(
            storage = storage,
            backgroundDownloader = port,
            groups = listOf(group),
            stateStore = MapStateStore(),
            dispatchers = DownloadDispatchers.Default,
            logger = NoopLogger,
        )

    /** The steps the documentation lists for the service: resume offset, begin, write, mark, then FileReady. */
    private fun streamWholeFile(storage: DownloaderStorage, port: MyBackgroundResourceDownloader, unit: DownloadUnit, bytes: ByteArray) {
        val resolved: ResolvedDownload = kotlinx.coroutines.runBlocking { resolver.resolve(unit) }
        if (storage.getTempFileSize(unit) == 0L) storage.beginTempFile(unit, resolved.expectedSha256)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.appendBytes(bytes)
        port.progress(unit, 1f)
        storage.markTempFileComplete(unit)
        port.finish(BackgroundDownloadEvent.FileReady(unit))
    }

    @Test
    fun `the documented skeleton downloads a unit through the engine and the commit finds it complete`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val bytes: ByteArray = ByteArray(30_000) { (it % 233).toByte() }
            val unit = TestUnit("model", group, sha(bytes))
            group.units = listOf(unit)
            val starts = AtomicInteger()
            val port = MyBackgroundResourceDownloader(storage, resolver) { p: MyBackgroundResourceDownloader, u: DownloadUnit ->
                starts.incrementAndGet()
                serviceScope.launch { streamWholeFile(storage, p, u, bytes) }
            }
            val downloader: Downloader = engine(storage, port, group)

            downloader.ensureAvailable(group)

            assertTrue(downloader.isAvailable(group))
            assertTrue(bytes.contentEquals(File(downloader.pathOf(unit)).readBytes()))
            assertEquals(1, starts.get())
            assertFalse(port.isDownloadInProgress(unit), "the finished transfer must be gone")
            assertEquals(TempFileState.None, storage.tempFileState(unit))
        }
    }

    @Test
    fun `a second enqueue of a unit in flight joins the running transfer`() {
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        val unit = TestUnit("joined", Group("g"), null)
        val starts = AtomicInteger()
        val port = MyBackgroundResourceDownloader(storage, resolver) { _: MyBackgroundResourceDownloader, _: DownloadUnit -> starts.incrementAndGet() }

        port.enqueueDownload(unit)
        port.enqueueDownload(unit)

        assertEquals(1, starts.get(), "a unit already in flight was started twice")
        assertTrue(port.isDownloadInProgress(unit))
        port.cancelDownload(unit)
        assertFalse(port.isDownloadInProgress(unit), "isDownloadInProgress must be false when cancelDownload returns")
    }

    @Test
    fun `an error stating HTTP 404 ends the unit call as NotFound and deletes the partial`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val unit = TestUnit("gone", group, null)
            group.units = listOf(unit)
            val port = MyBackgroundResourceDownloader(storage, resolver) { p: MyBackgroundResourceDownloader, u: DownloadUnit ->
                serviceScope.launch {
                    storage.beginTempFile(u, null)
                    File(storage.getTempFilePath(u)).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(10))
                    p.finish(BackgroundDownloadEvent.Error(u, "HTTP 404: Not Found"))
                }
            }
            val downloader: Downloader = engine(storage, port, group)

            val failure: DownloadFailedException = assertFailsWith { downloader.ensureAvailable(unit) }

            assertEquals(DownloadError.NotFound, failure.error)
            assertEquals(TempFileState.None, storage.tempFileState(unit), "a NotFound failure deletes the partial")
        }
    }

    @Test
    fun `a cancel during the transfer ends the call as cancelled and the next request is not served the old Cancelled`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val bytes: ByteArray = ByteArray(5_000) { 3 }
            val unit = TestUnit("cancelled", group, sha(bytes))
            group.units = listOf(unit)
            val firstStarted = CompletableDeferred<Unit>()
            val starts = AtomicInteger()
            val port = MyBackgroundResourceDownloader(storage, resolver) { p: MyBackgroundResourceDownloader, u: DownloadUnit ->
                val attempt: Int = starts.incrementAndGet()
                serviceScope.launch {
                    if (attempt == 1) {
                        firstStarted.complete(Unit)
                        // The documented service stops as soon as cancelDownload is called: it never emits again.
                        while (p.stillRunning(u)) kotlinx.coroutines.yield()
                    } else {
                        streamWholeFile(storage, p, u, bytes)
                    }
                }
            }
            val downloader: Downloader = engine(storage, port, group)

            val first = async(Dispatchers.Default) { runCatching { downloader.ensureAvailable(unit) }.exceptionOrNull() }
            firstStarted.await()
            downloader.cancelDownload(unit)
            val failure: Throwable? = first.await()
            assertTrue(failure is DownloadCancelledException, "expected DownloadCancelledException, was $failure")
            assertFalse(port.isDownloadInProgress(unit))

            downloader.ensureAvailable(unit)

            assertTrue(downloader.isAvailable(unit))
            assertEquals(2, starts.get())
        }
    }
}
