package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadNotifier
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the engine does when a port the host implements throws. The contract
 * (`Downloader.ensureAvailable`): the caller sees a [DownloadFailedException] or a
 * [DownloadCancelledException], never a raw exception, and the state is never left at `Downloading`.
 * A notifier that throws is the exception: a failed notification never fails the download it was
 * describing (`DownloadNotifier`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEnginePortFailureTest {

    private val group = TestGroup("model_bundle")
    private val model = TestUnit(id = "model", group = group)

    init {
        group.units = listOf(model)
    }

    private class PortException(message: String) : RuntimeException(message)

    private fun TestScope.engine(
        downloader: BackgroundResourceDownloader,
        storage: DownloaderStorage = SetStorage(),
        notifier: DownloadNotifier = RecordingNotifier(),
        stateStore: DownloadStateStore = InMemoryStateStore(),
    ): DefaultDownloaderEngine = DefaultDownloaderEngine(
        storage = storage,
        notifier = notifier,
        backgroundDownloader = downloader,
        stateStore = stateStore,
        bundledResourcesPresent = false,
        groups = listOf(group),
        dispatchers = TestDownloadDispatchers(this),
        logger = NoopLogger,
    )

    private class FaultyDownloader(
        private val enqueueThrows: Exception? = null,
        private val inProgressThrows: Exception? = null,
        private val observeThrows: Exception? = null,
        private val script: suspend FlowCollector<BackgroundDownloadEvent>.(DownloadUnit) -> Unit =
            { awaitCancellation() },
    ) : BackgroundResourceDownloader {
        var enqueueCount: Int = 0
            private set
        val cancelled: MutableList<String> = mutableListOf()
        private val gate = EnqueueGate()

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueueCount++
            gate.enqueued()
            enqueueThrows?.let { throw it }
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean {
            inProgressThrows?.let { throw it }
            return false
        }

        override fun cancelDownload(unit: DownloadUnit) {
            cancelled += unit.id
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            observeThrows?.let { throw it }
            gate.awaitEnqueue()
            script(unit)
        }
    }

    private class ThrowingNotifier : DownloadNotifier {
        override suspend fun showProgress(group: ResourceGroup, progress: Float) = throw PortException("progress")
        override suspend fun showCompleted(group: ResourceGroup) = throw PortException("completed")
        override suspend fun showError(group: ResourceGroup, error: DownloadError) = throw PortException("error")
        override fun remove(group: ResourceGroup) = throw PortException("remove")
    }

    private class ThrowingStateStore : DownloadStateStore {
        override fun readInt(key: String, default: Int): Int = throw PortException("store read")
        override fun writeInt(key: String, value: Int) = throw PortException("store write")
        override fun remove(key: String) = throw PortException("store remove")
    }

    // -- enqueueDownload throws ---------------------------------------------------------------

    @Test
    fun `group - an enqueue that throws ends as an Unknown failure with its cause and not stuck at Downloading`() = runTest {
        val cause = PortException("ForegroundServiceStartNotAllowedException")
        val notifier = RecordingNotifier()
        val engine: DefaultDownloaderEngine = engine(FaultyDownloader(enqueueThrows = cause), notifier = notifier)

        val thrown: DownloadFailedException = assertFailsWith { engine.ensureAvailable(group) }

        assertSame(cause, thrown.cause)
        assertEquals(DownloadError.Unknown("ForegroundServiceStartNotAllowedException"), thrown.error)
        assertEquals(GroupDownloadState.Error(thrown.error), engine.downloadState(group).value)
        assertTrue(notifier.calls.any { it.kind == RecordingNotifier.Kind.ERROR })
    }

    @Test
    fun `unit - an enqueue that throws ends as an Unknown failure with its cause and not stuck at Downloading`() = runTest {
        val cause = PortException("ForegroundServiceStartNotAllowedException")
        val engine: DefaultDownloaderEngine = engine(FaultyDownloader(enqueueThrows = cause))

        val thrown: DownloadFailedException = assertFailsWith { engine.ensureAvailable(model) }

        assertSame(cause, thrown.cause)
        assertEquals(UnitDownloadState.Error(thrown.error), engine.unitDownloadStateFlow(model).first())
    }

    @Test
    fun `an enqueue that throws on a retry ends the same way`() = runTest {
        val cause = PortException("cannot start the service")
        var enqueues = 0
        val gate = EnqueueGate()
        val downloader = object : BackgroundResourceDownloader {
            override fun enqueueDownload(unit: DownloadUnit) {
                gate.enqueued()
                if (++enqueues > 1) throw cause
            }

            override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
            override fun cancelDownload(unit: DownloadUnit) = Unit
            override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
                gate.awaitEnqueue()
                emit(BackgroundDownloadEvent.Error(unit, "connection reset"))
            }
        }
        val engine: DefaultDownloaderEngine = engine(downloader)

        val thrown: DownloadFailedException = assertFailsWith { engine.ensureAvailable(group) }

        assertSame(cause, thrown.cause)
        assertEquals(GroupDownloadState.Error(thrown.error), engine.downloadState(group).value)
    }

    // -- the other downloader calls -----------------------------------------------------------

    @Test
    fun `a downloader whose events throw ends as a failure with its cause`() = runTest {
        val cause = PortException("event stream broke")
        val downloader = FaultyDownloader(observeThrows = cause)
        val engine: DefaultDownloaderEngine = engine(downloader)

        val thrown: DownloadFailedException = assertFailsWith { engine.ensureAvailable(group) }

        assertSame(cause, thrown.cause)
        assertEquals(GroupDownloadState.Error(thrown.error), engine.downloadState(group).value)
    }

    @Test
    fun `a downloader whose isDownloadInProgress throws ends as a failure on both surfaces`() = runTest {
        val cause = PortException("cannot ask")
        val engine: DefaultDownloaderEngine = engine(FaultyDownloader(inProgressThrows = cause))

        val groupThrown: DownloadFailedException = assertFailsWith { engine.ensureAvailable(group) }
        val unitThrown: DownloadFailedException = assertFailsWith { engine.ensureAvailable(model) }

        assertSame(cause, groupThrown.cause)
        assertSame(cause, unitThrown.cause)
        assertEquals(GroupDownloadState.Error(groupThrown.error), engine.downloadState(group).value)
    }

    // -- the state store throws ---------------------------------------------------------------

    @Test
    fun `a state store that throws ends a failing transfer as a failure with its cause`() = runTest {
        val downloader = ScriptedAttemptsDownloader { _, unit ->
            emit(BackgroundDownloadEvent.Error(unit, "connection reset"))
        }
        val engine: DefaultDownloaderEngine = engine(downloader, stateStore = ThrowingStateStore())

        val thrown: DownloadFailedException = assertFailsWith { engine.ensureAvailable(group) }

        assertTrue(thrown.cause is PortException, "was ${thrown.cause}")
        assertEquals(GroupDownloadState.Error(thrown.error), engine.downloadState(group).value)
        assertEquals(1, downloader.enqueueCount, "without a counter there is nothing to bound a retry")
    }

    @Test
    fun `a state store that throws does not fail a download that succeeds`() = runTest {
        val storage = SetStorage()
        val downloader = ScriptedAttemptsDownloader { _, unit ->
            storage.setTempState(unit, TempFileState.Complete)
            emit(BackgroundDownloadEvent.FileReady(unit))
        }
        val engine: DefaultDownloaderEngine = engine(downloader, storage, stateStore = ThrowingStateStore())

        engine.ensureAvailable(group)

        assertEquals(GroupDownloadState.Completed, engine.downloadState(group).value)
    }

    // -- the notifier throws ------------------------------------------------------------------

    @Test
    fun `a notifier that throws on every call does not fail or stall the download`() = runTest {
        val storage = SetStorage()
        val downloader = ScriptedAttemptsDownloader { _, unit ->
            emit(BackgroundDownloadEvent.Progress(unit, 0.5f))
            storage.setTempState(unit, TempFileState.Complete)
            emit(BackgroundDownloadEvent.FileReady(unit))
        }
        val engine: DefaultDownloaderEngine = engine(downloader, storage, notifier = ThrowingNotifier())

        engine.ensureAvailable(group)

        assertEquals(GroupDownloadState.Completed, engine.downloadState(group).value)
        assertTrue(storage.isResourceAvailable(model))
    }

    @Test
    fun `a notifier that throws on the error report still lets the real failure through`() = runTest {
        val downloader = ScriptedAttemptsDownloader { _, unit ->
            emit(BackgroundDownloadEvent.Error(unit, "HTTP 404"))
        }
        val engine: DefaultDownloaderEngine = engine(downloader, notifier = ThrowingNotifier())

        val thrown: DownloadFailedException = assertFailsWith { engine.ensureAvailable(group) }

        assertEquals(DownloadError.NotFound, thrown.error)
        assertEquals(GroupDownloadState.Error(DownloadError.NotFound), engine.downloadState(group).value)
    }

    @Test
    fun `a notifier that throws on remove does not break a group cancel`() = runTest {
        val engine: DefaultDownloaderEngine = engine(FaultyDownloader(), notifier = ThrowingNotifier())

        engine.cancelDownload(group)

        assertEquals(GroupDownloadState.Idle, engine.downloadState(group).value)
    }

    // -- a caller's cancellation is not a port failure ----------------------------------------

    @Test
    fun `cancelling the caller is neither swallowed nor turned into a failure`() = runTest {
        val engine: DefaultDownloaderEngine = engine(FaultyDownloader(script = {
            emit(BackgroundDownloadEvent.Progress(it, 0.3f))
            awaitCancellation()
        }))
        val job = launch { engine.ensureAvailable(group) }
        engine.downloadState(group).first { it == GroupDownloadState.Downloading(0.3f) }

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(GroupDownloadState.Downloading(0.3f), engine.downloadState(group).value)
    }
}
