package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The engine subscribes to a transfer's events before it enqueues the transfer, and never
 * re-downloads or deletes a temp file that is already complete. Together they close the hole in
 * which a terminal event emitted with nobody listening (a download that is already whole, a failure
 * to begin) was lost, the attempt stalled, and a finished file was thrown away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineSubscriptionTest {

    private val group = TestGroup("model_bundle")
    private val model = TestUnit(id = "model", group = group)

    init {
        group.units = listOf(model)
    }

    private fun TestScope.engine(
        downloader: BackgroundResourceDownloader,
        storage: DownloaderStorage,
    ): DefaultDownloaderEngine = DefaultDownloaderEngine(
        storage = storage,
        notifier = RecordingNotifier(),
        backgroundDownloader = downloader,
        stateStore = InMemoryStateStore(),
        bundledResourcesPresent = false,
        groups = listOf(group),
        dispatchers = TestDownloadDispatchers(this),
        logger = NoopLogger,
    )

    /**
     * The skeleton `07-background-downloader.md` shows: a `MutableSharedFlow` with no replay and
     * `tryEmit`, finishing synchronously inside [enqueueDownload] when the file is already whole.
     */
    private class SharedFlowDownloader(private val storage: SetStorage) : BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        var enqueueCount: Int = 0
            private set

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueueCount++
            storage.setTempState(unit, TempFileState.Complete)
            events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = events
    }

    @Test
    fun `group - a FileReady emitted inside enqueueDownload to a replayless flow is received`() = runTest {
        val storage = SetStorage()
        val downloader = SharedFlowDownloader(storage)
        val engine: DefaultDownloaderEngine = engine(downloader, storage)

        engine.ensureAvailable(group)

        assertEquals(1, downloader.enqueueCount)
        assertTrue(testScheduler.currentTime < 5.minutes.inWholeMilliseconds, "must not wait out a stall")
        assertEquals(GroupDownloadState.Completed, engine.downloadState(group).value)
    }

    @Test
    fun `unit - a FileReady emitted inside enqueueDownload to a replayless flow is received`() = runTest {
        val storage = SetStorage()
        val downloader = SharedFlowDownloader(storage)
        val engine: DefaultDownloaderEngine = engine(downloader, storage)

        engine.ensureAvailable(model)

        assertEquals(1, downloader.enqueueCount)
        assertTrue(testScheduler.currentTime < 5.minutes.inWholeMilliseconds, "must not wait out a stall")
        assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(model).first())
    }

    @Test
    fun `an Error emitted inside enqueueDownload is received and not waited out as a stall`() = runTest {
        val storage = SetStorage()

        val thrown: DownloadFailedException = assertFailsWith {
            engine(ErrorInsideEnqueue(), storage).ensureAvailable(model)
        }

        assertEquals(DownloadError.NotFound, thrown.error)
        assertTrue(testScheduler.currentTime < 5.minutes.inWholeMilliseconds, "must not wait out a stall")
    }

    private class ErrorInsideEnqueue : BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        override fun enqueueDownload(unit: DownloadUnit) {
            events.tryEmit(BackgroundDownloadEvent.Error(unit, "HTTP 404"))
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = events
    }

    @Test
    fun `every attempt including a retry subscribes before it enqueues`() = runTest {
        val downloader = ScriptedAttemptsDownloader { _, unit ->
            emit(BackgroundDownloadEvent.Error(unit, "Unable to resolve host"))
        }

        assertFailsWith<DownloadFailedException> { engine(downloader, SetStorage()).ensureAvailable(group) }

        assertEquals(
            listOf("subscribe", "enqueue", "subscribe", "enqueue", "subscribe", "enqueue"),
            downloader.log,
        )
    }

    @Test
    fun `unit - the attempt subscribes before it enqueues`() = runTest {
        val downloader = ScriptedAttemptsDownloader { _, unit ->
            emit(BackgroundDownloadEvent.Error(unit, "Unable to resolve host"))
        }

        assertFailsWith<DownloadFailedException> { engine(downloader, SetStorage()).ensureAvailable(model) }

        assertEquals(listOf("subscribe", "enqueue"), downloader.log)
    }

    @Test
    fun `a transfer already running is attached to without an enqueue`() = runTest {
        val storage = SetStorage()
        val downloader = ScriptedAttemptsDownloader(inProgress = true) { _, unit ->
            storage.setTempState(unit, TempFileState.Complete)
            emit(BackgroundDownloadEvent.FileReady(unit))
        }

        engine(downloader, storage).ensureAvailable(group)

        assertEquals(listOf("subscribe"), downloader.log)
    }

    // -- A complete temp file is never re-downloaded or deleted -----------------------------

    private fun lostTerminalEvent(storage: SetStorage): ScriptedAttemptsDownloader =
        ScriptedAttemptsDownloader { _, unit ->
            // The transfer finished and marked its file, but its FileReady never arrived.
            emit(BackgroundDownloadEvent.Progress(unit, 0.5f))
            storage.setTempState(unit, TempFileState.Complete)
            awaitCancellation()
        }

    @Test
    fun `group - a stall over a complete temp file commits it instead of re-enqueueing or deleting`() = runTest {
        val storage = SetStorage()
        val downloader = lostTerminalEvent(storage)
        val engine: DefaultDownloaderEngine = engine(downloader, storage)

        engine.ensureAvailable(group)

        assertEquals(1, downloader.enqueueCount, "no second transfer over a finished file")
        assertTrue(storage.deletedTempFiles.isEmpty())
        assertTrue(storage.isResourceAvailable(model))
        assertEquals(GroupDownloadState.Completed, engine.downloadState(group).value)
    }

    @Test
    fun `unit - a stall over a complete temp file commits it instead of deleting it`() = runTest {
        val storage = SetStorage()
        val downloader = lostTerminalEvent(storage)
        val engine: DefaultDownloaderEngine = engine(downloader, storage)

        engine.ensureAvailable(model)

        assertEquals(1, downloader.enqueueCount)
        assertTrue(storage.deletedTempFiles.isEmpty())
        assertTrue(storage.isResourceAvailable(model))
    }

    @Test
    fun `an Error over a complete temp file commits it even when it would classify as NotFound`() = runTest {
        for (surface in listOf("group", "unit")) {
            val storage = SetStorage()
            val downloader = ScriptedAttemptsDownloader { _, unit ->
                storage.setTempState(unit, TempFileState.Complete)
                emit(BackgroundDownloadEvent.Error(unit, "HTTP 404"))
            }
            val engine: DefaultDownloaderEngine = engine(downloader, storage)

            if (surface == "group") engine.ensureAvailable(group) else engine.ensureAvailable(model)

            assertTrue(storage.deletedTempFiles.isEmpty(), surface)
            assertTrue(storage.isResourceAvailable(model), surface)
            assertEquals(1, downloader.enqueueCount, surface)
        }
    }
}
