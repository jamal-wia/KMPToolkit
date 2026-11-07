package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Hostile callers for the downloader engine, all on virtual time: many callers for one unit,
 * callers that leave, a cancel followed at every interleaving point by a new request, a cancel
 * that lands while a commit is parked, a UI that subscribes and unsubscribes without end, and a
 * process that dies mid-transfer while the next one starts over the same storage and state store.
 *
 * Process death is modelled the only way a test can: the dying engine runs on a scheduler of its
 * own that is never advanced again, so nothing after the kill point (no `finally`, no cleanup, no
 * retry) ever runs, and the next engine is built over the very same [SetStorage] and
 * [DownloadStateStore].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineAdversarialTest {

    private val group = TestGroup("catalogue")
    private val unit = TestUnit(id = "model", group = group)
    private val pair = TestGroup("pair")
    private val first = TestUnit(id = "first", group = pair)
    private val second = TestUnit(id = "second", group = pair)

    init {
        group.units = listOf(unit)
        pair.units = listOf(first, second)
    }

    /** A port the test drives by hand; its events are a shared flow without replay unless asked. */
    private class Port(replay: Int = 0) : BackgroundResourceDownloader {
        val events: MutableSharedFlow<BackgroundDownloadEvent> =
            MutableSharedFlow(replay = replay, extraBufferCapacity = 1024)
        val enqueued: MutableList<String> = mutableListOf()
        val cancelled: MutableList<String> = mutableListOf()
        val running: MutableSet<String> = mutableSetOf()
        var subscriptions: Int = 0
            private set
        var onEnqueue: (DownloadUnit) -> Unit = {}

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueued += unit.id
            running += unit.id
            onEnqueue(unit)
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = unit.id in running

        override fun cancelDownload(unit: DownloadUnit) {
            cancelled += unit.id
            running -= unit.id
            events.tryEmit(BackgroundDownloadEvent.Cancelled(unit))
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            subscriptions++
            emitAll(events.filter { it.unit.id == unit.id })
        }
    }

    /** A transfer that is whole the moment it starts: the file is complete and `FileReady` is out. */
    private fun Port.finishesOnEnqueue(storage: SetStorage) {
        onEnqueue = { started: DownloadUnit ->
            storage.setTempState(started, TempFileState.Complete)
            events.tryEmit(BackgroundDownloadEvent.FileReady(started))
        }
    }

    /** Holds every commit until [release], so a test can act while one is in flight. */
    private class ParkedCommitStorage(private val inner: SetStorage) : DownloaderStorage by inner {
        val entered: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()

        override suspend fun commitResource(unit: DownloadUnit) {
            entered.complete(Unit)
            release.await()
            inner.commitResource(unit)
        }
    }

    private fun TestScope.engine(
        storage: DownloaderStorage,
        port: BackgroundResourceDownloader,
        stateStore: DownloadStateStore = InMemoryStateStore(),
        dispatchers: DownloadDispatchers = StandardTestDownloadDispatchers(this),
        config: DownloaderConfig = DownloaderConfig(),
        notifier: RecordingNotifier = RecordingNotifier(),
    ): DefaultDownloaderEngine = DefaultDownloaderEngine(
        storage = storage,
        notifier = notifier,
        backgroundDownloader = port,
        stateStore = stateStore,
        bundledResourcesPresent = false,
        groups = listOf(group, pair),
        dispatchers = dispatchers,
        logger = NoopLogger,
        config = config,
    )

    // -- Many callers for one unit --------------------------------------------------------------

    @Test
    fun `32 callers asking for one unit share a single transfer and a single commit and all return`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port().apply { finishesOnEnqueue(storage) }
            val engine: DefaultDownloaderEngine = engine(storage, port)

            val callers: List<Deferred<Result<Unit>>> = List(32) { async { runCatching { engine.ensureAvailable(unit) } } }
            advanceUntilIdle()

            callers.forEachIndexed { index: Int, caller: Deferred<Result<Unit>> ->
                assertTrue(caller.isCompleted, "caller $index is still waiting")
                assertTrue(caller.await().isSuccess, "caller $index failed: ${caller.await().exceptionOrNull()}")
            }
            assertEquals(listOf(unit.id), port.enqueued, "one transfer for 32 callers")
            assertEquals(1, storage.commitCount, "one commit for 32 callers")
            assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(unit).first())
        }

    @Test
    fun `32 callers asking for one group share one transfer and one commit per unit and all return`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port().apply { finishesOnEnqueue(storage) }
            val engine: DefaultDownloaderEngine = engine(storage, port)

            val callers: List<Deferred<Result<Unit>>> = List(32) { async { runCatching { engine.ensureAvailable(pair) } } }
            advanceUntilIdle()

            callers.forEachIndexed { index: Int, caller: Deferred<Result<Unit>> ->
                assertTrue(caller.isCompleted, "caller $index is still waiting")
                assertTrue(caller.await().isSuccess, "caller $index failed: ${caller.await().exceptionOrNull()}")
            }
            assertEquals(listOf(first.id, second.id), port.enqueued, "each unit is transferred once")
            assertEquals(2, storage.commitCount, "each unit is committed once")
            assertEquals(GroupDownloadState.Completed, engine.downloadState(pair).value)
        }

    @Test
    fun `callers on the group surface and the unit surface for the same unit share one transfer and one commit`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port().apply { finishesOnEnqueue(storage) }
            val engine: DefaultDownloaderEngine = engine(storage, port)

            val callers: List<Deferred<Result<Unit>>> = List(32) { index: Int ->
                async {
                    runCatching { if (index % 2 == 0) engine.ensureAvailable(group) else engine.ensureAvailable(unit) }
                }
            }
            advanceUntilIdle()

            callers.forEachIndexed { index: Int, caller: Deferred<Result<Unit>> ->
                assertTrue(caller.isCompleted, "caller $index is still waiting")
                assertTrue(caller.await().isSuccess, "caller $index failed: ${caller.await().exceptionOrNull()}")
            }
            assertEquals(listOf(unit.id), port.enqueued)
            assertEquals(1, storage.commitCount)
        }

    // -- Callers that leave ---------------------------------------------------------------------

    @Test
    fun `whichever one of 32 callers stays completes the transfer the others left running`() =
        runTest(timeout = 30.seconds) {
            for (survivor: Int in listOf(0, 15, 31)) {
                val storage = SetStorage()
                val port = Port()
                val engine: DefaultDownloaderEngine = engine(storage, port)
                val callers: List<Deferred<Result<Unit>>> =
                    List(32) { async { runCatching { engine.ensureAvailable(unit) } } }
                runCurrent()
                assertEquals(listOf(unit.id), port.enqueued, "survivor=$survivor: one transfer is running")

                callers.forEachIndexed { index: Int, caller: Deferred<Result<Unit>> ->
                    if (index != survivor) caller.cancel()
                }
                runCurrent()

                assertTrue(callers[survivor].isActive, "survivor=$survivor: the caller that stayed must keep waiting")
                assertEquals(emptyList(), port.cancelled, "survivor=$survivor: leaving callers must not stop the transfer")
                assertEquals(listOf(unit.id), port.enqueued, "survivor=$survivor: the survivor joins, it does not enqueue again")
                assertIs<UnitDownloadState.Downloading>(
                    engine.unitDownloadStateFlow(unit).first(),
                    "survivor=$survivor: the state follows the transfer, not the callers that left",
                )

                storage.setTempState(unit, TempFileState.Complete)
                port.events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
                runCurrent()

                assertTrue(callers[survivor].await().isSuccess, "survivor=$survivor: the survivor must complete")
                assertEquals(1, storage.commitCount, "survivor=$survivor")
                assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(unit).first())
            }
        }

    @Test
    fun `when every caller leaves the transfer keeps running and the next caller attaches without a second enqueue`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port()
            val engine: DefaultDownloaderEngine = engine(storage, port)
            val callers: List<Deferred<Result<Unit>>> =
                List(32) { async { runCatching { engine.ensureAvailable(unit) } } }
            runCurrent()

            callers.forEach { it.cancel() }
            runCurrent()

            assertEquals(emptyList(), port.cancelled, "a screen that is left detaches, it does not cancel")
            val next: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(unit) } }
            runCurrent()
            assertTrue(next.isActive, "the next caller waits for the running transfer")
            assertEquals(listOf(unit.id), port.enqueued, "and does not start a second one")

            storage.setTempState(unit, TempFileState.Complete)
            port.events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
            runCurrent()

            assertTrue(next.await().isSuccess)
            assertEquals(1, storage.commitCount)
        }

    // -- A cancel, then a request, at every interleaving point -----------------------------------

    @Test
    fun `a request made after cancelDownload survives the old call unwinding however late it starts`() =
        runTest(timeout = 30.seconds) {
            for (steps: Int in 0..12) {
                val storage = SetStorage()
                val port = Port()
                port.onEnqueue = { started: DownloadUnit -> storage.setTempState(started, TempFileState.Partial) }
                val engine: DefaultDownloaderEngine = engine(storage, port)
                val old: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(unit) } }
                runCurrent()
                assertEquals(1, port.enqueued.size, "steps=$steps: the first transfer is running")

                engine.cancelDownload(unit)
                val fresh: Deferred<Result<Unit>> = async {
                    repeat(steps) { yield() }
                    runCatching { engine.ensureAvailable(unit) }
                }
                runCurrent()

                assertEquals(2, port.enqueued.size, "steps=$steps: the new request must start its own transfer")
                assertTrue(fresh.isActive, "steps=$steps: the new request must not be ended by the earlier cancel")
                val deletesBeforeFinish: Int = storage.deletedTempFiles.size

                storage.setTempState(unit, TempFileState.Complete)
                port.events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
                runCurrent()

                assertTrue(fresh.await().isSuccess, "steps=$steps: ${fresh.await().exceptionOrNull()}")
                assertIs<DownloadCancelledException>(old.await().exceptionOrNull(), "steps=$steps: the old call ends cancelled")
                assertEquals(
                    deletesBeforeFinish,
                    storage.deletedTempFiles.size,
                    "steps=$steps: the new request's temp file must not be deleted",
                )
                assertEquals(1, storage.commitCount, "steps=$steps")
                assertTrue(storage.isResourceAvailable(unit), "steps=$steps")
            }
        }

    // -- A cancel while a commit is parked, on the surfaces the group tests do not reach --------------

    @Test
    fun `a unit cancel while its commit is parked settles the call and the state consistently and starts nothing`() =
        runTest(timeout = 30.seconds) {
            val inner = SetStorage()
            val storage = ParkedCommitStorage(inner)
            val port = Port().apply { finishesOnEnqueue(inner) }
            val engine: DefaultDownloaderEngine = engine(storage, port)
            val call: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(unit) } }
            storage.entered.await()

            engine.cancelDownload(unit)
            assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(unit).first(), "Idle when the cancel returns")
            storage.release.complete(Unit)
            advanceUntilIdle()

            assertTrue(call.isCompleted, "the call must not hang on a cancel that met a parked commit")
            // A commit that finished leaves the bytes on disk, so the call may complete (Completed);
            // otherwise it ends cancelled (Idle). Anything else, an error above all, is a defect.
            val outcome: Throwable? = call.await().exceptionOrNull()
            if (outcome == null) {
                assertTrue(inner.isResourceAvailable(unit))
                assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(unit).first())
            } else {
                assertIs<DownloadCancelledException>(outcome)
                assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(unit).first())
            }
            assertEquals(listOf(unit.id), port.enqueued, "nothing may be enqueued after the cancel")
        }

    @Test
    fun `cancelAllDownloads while a group commit is parked ends the call cancelled with no error and no restart`() =
        runTest(timeout = 30.seconds) {
            val inner = SetStorage()
            val storage = ParkedCommitStorage(inner)
            val port = Port().apply { finishesOnEnqueue(inner) }
            val notifier = RecordingNotifier()
            val engine: DefaultDownloaderEngine = engine(storage, port, notifier = notifier)
            val call: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(pair) } }
            storage.entered.await()

            engine.cancelAllDownloads()
            storage.release.complete(Unit)
            advanceUntilIdle()

            assertIs<DownloadCancelledException>(call.await().exceptionOrNull())
            assertEquals(listOf(first.id), port.enqueued, "the second unit must never start after the cancel")
            assertEquals(GroupDownloadState.Idle, engine.downloadState(pair).value)
            assertTrue(
                notifier.calls.none { it.kind == RecordingNotifier.Kind.ERROR },
                "no error may be shown for a cancel: ${notifier.calls}",
            )
        }

    // -- A UI that subscribes and unsubscribes without end ---------------------------------------------

    @Test
    fun `500 state collectors that come and go never add a transfer attempt or a port subscription`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port()
            val engine: DefaultDownloaderEngine = engine(storage, port)
            val call: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(unit) } }
            runCurrent()
            val subscriptionsOfTheCall: Int = port.subscriptions

            repeat(500) {
                val unitCollector = launch { engine.unitDownloadStateFlow(unit).collect { } }
                val groupCollector = launch { engine.downloadState(group).collect { } }
                runCurrent()
                unitCollector.cancel()
                groupCollector.cancel()
            }
            runCurrent()

            assertEquals(subscriptionsOfTheCall, port.subscriptions, "a UI collector must not subscribe to the port")
            assertEquals(listOf(unit.id), port.enqueued, "a UI collector must not enqueue")
            assertEquals(emptyList(), port.cancelled)
            assertTrue(call.isActive, "the download keeps running under the churn")

            storage.setTempState(unit, TempFileState.Complete)
            port.events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
            runCurrent()

            assertTrue(call.await().isSuccess)
            assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(unit).first())
        }

    // -- Process death mid-transfer ---------------------------------------------------------------------

    @Test
    fun `a unit in flight when the process dies is resumed from its partial file and committed once by the next engine`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val stateStore = InMemoryStateStore()
            val deadScope = TestScope()
            val deadPort = Port()
            deadPort.onEnqueue = { started: DownloadUnit -> storage.setTempState(started, TempFileState.Partial) }
            val dead: DefaultDownloaderEngine = DefaultDownloaderEngine(
                storage = storage,
                notifier = RecordingNotifier(),
                backgroundDownloader = deadPort,
                stateStore = stateStore,
                bundledResourcesPresent = false,
                groups = listOf(group, pair),
                dispatchers = TestDownloadDispatchers(deadScope),
                logger = NoopLogger,
            )
            val deadCall: Deferred<Result<Unit>> = deadScope.async { runCatching { dead.ensureAvailable(unit) } }
            deadScope.runCurrent()
            deadPort.events.tryEmit(BackgroundDownloadEvent.Progress(unit, 0.4f))
            deadScope.runCurrent()
            assertEquals(TempFileState.Partial, storage.tempFileState(unit), "the first process left a partial file")

            // The process is gone: deadScope is never advanced again, nothing of it runs any more.
            val nextPort = Port()
            var tempStateAtEnqueue: TempFileState? = null
            nextPort.onEnqueue = { started: DownloadUnit ->
                tempStateAtEnqueue = storage.tempFileState(started)
                storage.setTempState(started, TempFileState.Complete)
                nextPort.events.tryEmit(BackgroundDownloadEvent.FileReady(started))
            }
            val next: DefaultDownloaderEngine = engine(storage, nextPort, stateStore)
            val nextCall: Deferred<Result<Unit>> = async { runCatching { next.ensureAvailable(unit) } }
            advanceUntilIdle()

            assertTrue(nextCall.await().isSuccess, "next engine: ${nextCall.await().exceptionOrNull()}")
            assertEquals(TempFileState.Partial, tempStateAtEnqueue, "the new transfer resumes from the partial file")
            assertEquals(emptyList(), storage.deletedTempFiles, "the partial file is never deleted")
            assertEquals(1, storage.commitCount, "one commit")
            assertEquals(UnitDownloadState.Completed, next.unitDownloadStateFlow(unit).first())
            assertTrue(deadCall.isActive, "the dead process's call never ran on")
            assertEquals(emptyList(), deadPort.cancelled, "and never cancelled anything")
        }

    @Test
    fun `a transfer the platform kept running through the death is attached to and its replayed outcome is not waited out`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage(complete = setOf(unit.id))
            val port = Port(replay = 1)
            port.running += unit.id
            port.events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
            val next: DefaultDownloaderEngine = engine(storage, port)

            val call: Deferred<Result<Unit>> = async { runCatching { next.ensureAvailable(unit) } }
            advanceUntilIdle()

            assertTrue(call.isCompleted, "the call waits forever for an outcome the platform already replayed")
            assertTrue(call.await().isSuccess, "${call.await().exceptionOrNull()}")
            assertEquals(emptyList(), port.enqueued, "an attach never enqueues")
            assertEquals(1, storage.commitCount)
        }

    @Test
    fun `failures counted by a process that died still count in the next one so it stops after the remaining retries`() =
        runTest(timeout = 30.seconds) {
            val stallTimeout = DownloaderConfig(stallTimeout = 10.seconds)

            // The first process stalls once (one persisted failure), then dies in the retry backoff.
            val sharedStore = InMemoryStateStore()
            val deadScope = TestScope()
            val deadPort = Port()
            val dead: DefaultDownloaderEngine = DefaultDownloaderEngine(
                storage = SetStorage(),
                notifier = RecordingNotifier(),
                backgroundDownloader = deadPort,
                stateStore = sharedStore,
                bundledResourcesPresent = false,
                groups = listOf(group, pair),
                dispatchers = TestDownloadDispatchers(deadScope),
                logger = NoopLogger,
                config = stallTimeout,
            )
            val deadCall: Deferred<Result<Unit>> = deadScope.async { runCatching { dead.ensureAvailable(group) } }
            deadScope.runCurrent()
            deadScope.advanceTimeBy(10.seconds + 1.milliseconds)
            deadScope.runCurrent()
            assertEquals(listOf(unit.id), deadPort.enqueued, "the first process made its first attempt")
            assertTrue(deadCall.isActive, "and is dead while it sits in the retry backoff")

            suspend fun attemptsUntilGiveUp(store: DownloadStateStore): Pair<Int, Throwable?> {
                val port = Port()
                val engine: DefaultDownloaderEngine = engine(SetStorage(), port, store, config = stallTimeout)
                val call: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(group) } }
                advanceUntilIdle()
                assertTrue(call.isCompleted, "a silent transfer must be given up on, not waited for forever")
                return port.enqueued.size to call.await().exceptionOrNull()
            }

            val (attemptsAfterDeath: Int, failure: Throwable?) = attemptsUntilGiveUp(sharedStore)
            val (attemptsFresh: Int, _) = attemptsUntilGiveUp(InMemoryStateStore())

            assertEquals(3, attemptsFresh, "a fresh process allows three failed attempts in a row")
            assertEquals(2, attemptsAfterDeath, "one failure was already counted, so two attempts are left")
            assertIs<DownloadFailedException>(failure)
            assertEquals(DownloadError.Timeout, failure.error)
        }

    @Test
    fun `engines built over one storage never leave a call of the first waiting on the second`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val portA = Port().apply { finishesOnEnqueue(storage) }
            val portB = Port().apply { finishesOnEnqueue(storage) }
            val engineA: DefaultDownloaderEngine = engine(storage, portA)
            val engineB: DefaultDownloaderEngine = engine(storage, portB)

            val callers: List<Deferred<Result<Unit>>> = List(8) { index: Int ->
                async { runCatching { (if (index % 2 == 0) engineA else engineB).ensureAvailable(unit) } }
            }
            advanceUntilIdle()

            callers.forEachIndexed { index: Int, caller: Deferred<Result<Unit>> ->
                assertTrue(caller.isCompleted, "caller $index is still waiting")
                assertTrue(caller.await().isSuccess, "caller $index: ${caller.await().exceptionOrNull()}")
            }
            assertTrue(storage.isResourceAvailable(unit))
            assertFalse(storage.commitCount > 2, "at most one commit per engine, was ${storage.commitCount}")
        }
}
