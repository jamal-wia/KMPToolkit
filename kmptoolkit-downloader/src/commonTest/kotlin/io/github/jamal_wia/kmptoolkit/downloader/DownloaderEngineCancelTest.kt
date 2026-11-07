package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a cancel does to a call that is running (group and unit surfaces), and what an attempt may
 * take for its own outcome: the downloader concurrency fixes. A cancel must reach a call that has no
 * transfer to report it (waiting out a backoff, committing, between units), must not be turned into
 * an error by the commit it interrupts, and a request made after a cancel must start clean.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineCancelTest {

    private val group = TestGroup("bundle")
    private val unitA = TestUnit(id = "a", group = group)
    private val unitB = TestUnit(id = "b", group = group)
    private val single = TestGroup("single")
    private val solo = TestUnit(id = "solo", group = single)

    init {
        group.units = listOf(unitA, unitB)
        single.units = listOf(solo)
    }

    private fun TestScope.engine(
        storage: DownloaderStorage,
        downloader: BackgroundResourceDownloader,
        notifier: RecordingNotifier = RecordingNotifier(),
        dispatchers: DownloadDispatchers = TestDownloadDispatchers(this),
        config: DownloaderConfig = DownloaderConfig(),
    ): DefaultDownloaderEngine = DefaultDownloaderEngine(
        storage = storage,
        notifier = notifier,
        backgroundDownloader = downloader,
        stateStore = InMemoryStateStore(),
        bundledResourcesPresent = false,
        groups = listOf(group, single),
        dispatchers = dispatchers,
        logger = NoopLogger,
        config = config,
    )

    /** A port the test drives by hand: events go through a [MutableSharedFlow] with [replay]. */
    private class ControllablePort(replay: Int = 0) : BackgroundResourceDownloader {
        val events: MutableSharedFlow<BackgroundDownloadEvent> =
            MutableSharedFlow(replay = replay, extraBufferCapacity = 64)
        val enqueued: MutableList<String> = mutableListOf()
        val cancelled: MutableList<String> = mutableListOf()
        val running: MutableSet<String> = mutableSetOf()
        var onEnqueue: (DownloadUnit, Int) -> Unit = { _, _ -> }
        var onCancel: (DownloadUnit) -> Unit = {}
        var inProgressAnswer: ((DownloadUnit) -> Boolean)? = null

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueued += unit.id
            running += unit.id
            onEnqueue(unit, enqueued.size)
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean =
            inProgressAnswer?.invoke(unit) ?: (unit.id in running)

        override fun cancelDownload(unit: DownloadUnit) {
            cancelled += unit.id
            running -= unit.id
            onCancel(unit)
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            events.filter { it.unit.id == unit.id }
    }

    /** Holds the commit of [gatedId] until [release]; optionally fails it when its temp file is gone. */
    private class GatedCommitStorage(
        private val inner: SetStorage,
        private val gatedId: String,
        private val failIfTempFileGone: Boolean = false,
    ) : DownloaderStorage by inner {
        val entered: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        var commitEntries: Int = 0
            private set

        override suspend fun commitResource(unit: DownloadUnit) {
            if (unit.id == gatedId) {
                commitEntries++
                entered.complete(Unit)
                release.await()
                if (failIfTempFileGone) {
                    check(inner.tempFileState(unit) == TempFileState.Complete) { "the temp file is gone" }
                }
            }
            inner.commitResource(unit)
        }
    }

    private fun ControllablePort.finishesOnEnqueue(storage: SetStorage) {
        onEnqueue = { unit, _ ->
            storage.setTempState(unit, TempFileState.Complete)
            events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
        }
    }

    // -- A group cancel reaches a call that has no transfer running -------------------------------

    @Test
    fun `a cancel while a unit commits stops the group before the next unit is enqueued`() = runTest {
        val inner = SetStorage()
        val storage = GatedCommitStorage(inner, gatedId = "a")
        val port = ControllablePort().apply { finishesOnEnqueue(inner) }
        val notifier = RecordingNotifier()
        val engine: DefaultDownloaderEngine = engine(storage, port, notifier)
        val call = backgroundScope.async { runCatching { engine.ensureAvailable(group) } }
        storage.entered.await()

        engine.cancelDownload(group)
        storage.release.complete(Unit)
        val outcome: Throwable? = call.await().exceptionOrNull()

        assertTrue(outcome is DownloadCancelledException, "was $outcome")
        assertEquals(listOf("a"), port.enqueued, "unit b must never be started after the cancel")
        assertEquals(GroupDownloadState.Idle, engine.downloadState(group).value)
        assertTrue(inner.isResourceAvailable(unitA), "a commit that succeeded leaves its resource in place")
    }

    @Test
    fun `a cancel during a commit is a cancel and not a storage error`() = runTest {
        val inner = SetStorage()
        val storage = GatedCommitStorage(inner, gatedId = "a", failIfTempFileGone = true)
        val port = ControllablePort().apply { finishesOnEnqueue(inner) }
        val notifier = RecordingNotifier()
        val engine: DefaultDownloaderEngine = engine(storage, port, notifier)
        val call = backgroundScope.async { runCatching { engine.ensureAvailable(group) } }
        storage.entered.await()

        engine.cancelDownload(group)
        storage.release.complete(Unit)
        val outcome: Throwable? = call.await().exceptionOrNull()

        assertTrue(outcome is DownloadCancelledException, "was $outcome")
        assertEquals(GroupDownloadState.Idle, engine.downloadState(group).value)
        assertTrue(notifier.calls.none { it.kind == RecordingNotifier.Kind.ERROR }, "no error notification")
    }

    @Test
    fun `a cancel during the retry backoff ends the call and nothing is enqueued again`() = runTest {
        val storage = SetStorage()
        val downloader = ScriptedAttemptsDownloader(clock = { testScheduler.currentTime }) { _, unit ->
            emit(BackgroundDownloadEvent.Error(unit, "Unable to resolve host"))
        }
        val engine: DefaultDownloaderEngine = engine(storage, downloader)
        val call = backgroundScope.async { runCatching { engine.ensureAvailable(group) } }

        advanceTimeBy(1.seconds)
        engine.cancelDownload(group)
        val outcome: Throwable? = call.await().exceptionOrNull()

        assertTrue(outcome is DownloadCancelledException, "was $outcome")
        assertEquals(1, downloader.enqueueCount)
        assertTrue(testScheduler.currentTime < 2.seconds.inWholeMilliseconds, "the wait ends with the cancel")
        assertEquals(GroupDownloadState.Idle, engine.downloadState(group).value)
        assertTrue(storage.deletedTempFiles.contains("a"), "the call removes what the cancel left to it")
    }

    @Test
    fun `cancelAllDownloads reaches a call launched outside the engine during its backoff`() = runTest {
        val downloader = ScriptedAttemptsDownloader(clock = { testScheduler.currentTime }) { _, unit ->
            emit(BackgroundDownloadEvent.Error(unit, "Unable to resolve host"))
        }
        val engine: DefaultDownloaderEngine = engine(SetStorage(), downloader)
        val call = backgroundScope.async { runCatching { engine.ensureAvailable(group) } }

        advanceTimeBy(1.seconds)
        engine.cancelAllDownloads()
        val outcome: Throwable? = call.await().exceptionOrNull()

        assertTrue(outcome is DownloadCancelledException, "was $outcome")
        assertEquals(1, downloader.enqueueCount)
        assertEquals(GroupDownloadState.Idle, engine.downloadState(group).value)
    }

    @Test
    fun `Progress that arrives after a cancel never brings the group back to Downloading`() = runTest {
        val port = ControllablePort()
        val notifier = RecordingNotifier()
        val engine: DefaultDownloaderEngine = engine(SetStorage(), port, notifier)
        val states: MutableList<GroupDownloadState> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.downloadState(group).collect { states += it }
        }
        val call = backgroundScope.async { runCatching { engine.ensureAvailable(group) } }
        runCurrent()
        port.events.tryEmit(BackgroundDownloadEvent.Progress(unitA, 0.2f))
        runCurrent()

        engine.cancelDownload(group)
        port.events.tryEmit(BackgroundDownloadEvent.Progress(unitA, 0.5f))
        runCurrent()
        call.await()

        val lastIdle: Int = states.indexOfLast { it == GroupDownloadState.Idle }
        assertTrue(lastIdle >= 0)
        assertTrue(states.drop(lastIdle + 1).none { it is GroupDownloadState.Downloading }, "states: $states")
        val lastRemove: Int = notifier.calls.indexOfLast { it.kind == RecordingNotifier.Kind.REMOVE }
        assertTrue(lastRemove >= 0)
        assertTrue(
            notifier.calls.drop(lastRemove + 1).none { it.kind == RecordingNotifier.Kind.PROGRESS },
            "no progress notification after the remove",
        )
    }

    // -- The unit surface: a cancel is synchronous --------------------------------------------------

    @Test
    fun `a unit request made right after cancelDownload starts a new transfer`() = runTest {
        val inner = SetStorage()
        val port = ControllablePort()
        port.running += solo.id
        port.onCancel = { unit -> port.events.tryEmit(BackgroundDownloadEvent.Cancelled(unit)) }
        val engine: DefaultDownloaderEngine =
            engine(inner, port, dispatchers = StandardTestDownloadDispatchers(this))

        engine.cancelDownload(solo)
        val call = async(start = CoroutineStart.UNDISPATCHED) { runCatching { engine.ensureAvailable(solo) } }
        runCurrent()

        assertEquals(listOf("solo"), port.enqueued, "the request after the cancel starts a transfer")
        assertTrue(call.isActive, "and is not ended by the cancel that came before it")
        inner.setTempState(solo, TempFileState.Complete)
        port.events.tryEmit(BackgroundDownloadEvent.FileReady(solo))
        runCurrent()
        assertTrue(call.await().isSuccess)
    }

    @Test
    fun `a unit cancel while its transfer runs ends the call with DownloadCancelledException`() = runTest {
        val inner = SetStorage()
        val port = ControllablePort()
        val engine: DefaultDownloaderEngine = engine(inner, port)
        val call = backgroundScope.async { runCatching { engine.ensureAvailable(solo) } }
        runCurrent()

        engine.cancelDownload(solo)
        runCurrent()

        val outcome: Throwable? = call.await().exceptionOrNull()
        assertTrue(outcome is DownloadCancelledException, "was $outcome")
        assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(solo).first())
    }

    // -- Replayed terminals belong to an earlier attempt ----------------------------------------------

    @Test
    fun `a replayed Cancelled from the engine's own stall cancel is not taken for a user cancel`() = runTest {
        val inner = SetStorage()
        val port = ControllablePort(replay = 1)
        port.onCancel = { unit -> port.events.tryEmit(BackgroundDownloadEvent.Cancelled(unit)) }
        port.onEnqueue = { unit, n ->
            if (n == 2) {
                inner.setTempState(unit, TempFileState.Complete)
                port.events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
            }
        }
        val engine: DefaultDownloaderEngine = engine(inner, port)

        engine.ensureAvailable(single)

        assertEquals(2, port.enqueued.size)
        assertTrue(inner.deletedTempFiles.isEmpty(), "no partial may be deleted for a cancel nobody made")
        assertTrue(inner.isResourceAvailable(solo))
    }

    @Test
    fun `a replayed Error from the previous attempt does not end the next one`() = runTest {
        val inner = SetStorage()
        val port = ControllablePort(replay = 1)
        port.onEnqueue = { unit, n ->
            // Bytes arrive on every transfer; the engine marks the file complete on FileReady.
            inner.setTempState(unit, TempFileState.Partial)
            if (n == 1) {
                port.events.tryEmit(BackgroundDownloadEvent.Error(unit, "connection reset"))
            } else {
                port.events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
            }
        }
        val engine: DefaultDownloaderEngine = engine(inner, port)

        engine.ensureAvailable(single)

        assertEquals(2, port.enqueued.size, "the retry waits for its own outcome")
        assertEquals(GroupDownloadState.Completed, engine.downloadState(single).value)
    }

    // -- Attaching to a transfer that ends before the subscription -------------------------------------

    @Test
    fun `a transfer that finished while the engine attached is committed without waiting out a stall`() = runTest {
        val inner = SetStorage()
        val port = ControllablePort()
        var queries = 0
        port.inProgressAnswer = { unit ->
            queries++
            if (queries == 1) {
                // The engine sees the transfer running; it ends before the engine subscribes, and
                // a port without replay drops its FileReady.
                inner.setTempState(unit, TempFileState.Complete)
                port.events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
                true
            } else {
                false
            }
        }
        val engine: DefaultDownloaderEngine = engine(inner, port)

        engine.ensureAvailable(single)

        assertTrue(testScheduler.currentTime < 5.minutes.inWholeMilliseconds, "must not wait out a stall")
        assertTrue(port.enqueued.isEmpty(), "a finished file is committed, not downloaded again")
        assertEquals(GroupDownloadState.Completed, engine.downloadState(single).value)
    }

    // -- A flow that ends without a terminal -----------------------------------------------------------

    @Test
    fun `an event flow that completes without a terminal fails the call at once even with INFINITE`() = runTest {
        val downloader = ScriptedAttemptsDownloader { _, unit ->
            emit(BackgroundDownloadEvent.Progress(unit, 0.3f))
        }
        val engine: DefaultDownloaderEngine =
            engine(SetStorage(), downloader, config = DownloaderConfig(stallTimeout = Duration.INFINITE))

        val thrown: DownloadFailedException = assertFailsWith { engine.ensureAvailable(single) }

        assertTrue(thrown.error is DownloadError.Unknown, "was ${thrown.error}")
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, downloader.enqueueCount, "nothing retries a port that broke its contract")
    }

    // -- The group loop and the unit surface do not interleave on one id --------------------------------

    @Test
    fun `the unit surface waits for a group commit of the same unit instead of committing it again`() = runTest {
        val inner = SetStorage()
        val storage = GatedCommitStorage(inner, gatedId = "solo")
        val port = ControllablePort().apply { finishesOnEnqueue(inner) }
        val engine: DefaultDownloaderEngine = engine(storage, port)
        val groupCall = backgroundScope.async { runCatching { engine.ensureAvailable(single) } }
        storage.entered.await()

        val unitCall = backgroundScope.async { runCatching { engine.ensureAvailable(solo) } }
        runCurrent()
        assertEquals(1, storage.commitEntries, "the unit call must not commit while the group commits")

        storage.release.complete(Unit)
        assertTrue(groupCall.await().isSuccess)
        assertTrue(unitCall.await().isSuccess)
        assertEquals(1, storage.commitEntries)
        assertEquals(1, port.enqueued.size)
    }
}
