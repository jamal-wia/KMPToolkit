package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Storms against one engine on virtual time: cancel-all with a crowd waiting, a cancel after every
 * request, a cancel during the retry backoff followed by a request at every interleaving point,
 * progress floods before and after a cancel, and the same unit requested, deleted and requested
 * again hundreds of times. After each storm the engine must still do an ordinary download.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineStormAdversarialTest {

    private val single = TestGroup("single")
    private val unit = TestUnit(id = "model", group = single)
    private val other = TestUnit(id = "other", group = single)
    private val solo = TestGroup("solo")
    private val lone = TestUnit(id = "lone", group = solo)

    init {
        single.units = listOf(unit)
        solo.units = listOf(lone)
    }

    private class Port : BackgroundResourceDownloader {
        val events: MutableSharedFlow<BackgroundDownloadEvent> =
            MutableSharedFlow(extraBufferCapacity = 100_000)
        val enqueued: MutableList<String> = mutableListOf()
        val cancelled: MutableList<String> = mutableListOf()
        val running: MutableSet<String> = mutableSetOf()
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
            emitAll(events.filter { it.unit.id == unit.id })
        }
    }

    private fun TestScope.engine(storage: SetStorage, port: Port): DefaultDownloaderEngine = DefaultDownloaderEngine(
        storage = storage,
        notifier = RecordingNotifier(),
        backgroundDownloader = port,
        stateStore = InMemoryStateStore(),
        bundledResourcesPresent = false,
        groups = listOf(single, solo),
        dispatchers = StandardTestDownloadDispatchers(this),
        logger = NoopLogger,
    )

    private fun finish(storage: SetStorage, port: Port, target: DownloadUnit) {
        storage.setTempState(target, TempFileState.Complete)
        port.events.tryEmit(BackgroundDownloadEvent.FileReady(target))
    }

    @Test
    fun `cancelAllDownloads with 32 callers waiting ends them all and the engine downloads again`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port()
            val engine: DefaultDownloaderEngine = engine(storage, port)
            val callers: List<Deferred<Result<Unit>>> = List(32) { async { runCatching { engine.ensureAvailable(unit) } } }
            runCurrent()

            engine.cancelAllDownloads()
            advanceUntilIdle()

            callers.forEachIndexed { index: Int, caller: Deferred<Result<Unit>> ->
                assertTrue(caller.isCompleted, "caller $index is still waiting after cancelAllDownloads")
                assertIs<DownloadCancelledException>(caller.await().exceptionOrNull(), "caller $index")
            }
            assertEquals(false, engine.hasActiveUnitDownload())

            val again: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(unit) } }
            runCurrent()
            assertEquals(2, port.enqueued.size, "a request after the storm starts a transfer")
            finish(storage, port, unit)
            runCurrent()
            assertTrue(again.await().isSuccess)
        }

    @Test
    fun `a cancel after each of 50 requests leaves every call cancelled and the next request working`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port()
            val engine: DefaultDownloaderEngine = engine(storage, port)

            repeat(50) { round: Int ->
                val call: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(unit) } }
                runCurrent()
                engine.cancelDownload(unit)
                runCurrent()
                assertIs<DownloadCancelledException>(call.await().exceptionOrNull(), "round $round")
                assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(unit).first(), "round $round")
                assertEquals(false, engine.hasActiveUnitDownload(), "round $round")
            }
            val last: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(unit) } }
            runCurrent()
            finish(storage, port, unit)
            runCurrent()

            assertTrue(last.await().isSuccess)
            assertEquals(51, port.enqueued.size)
            assertEquals(1, storage.commitCount)
        }

    @Test
    fun `a group cancel in the retry backoff then a request at any interleaving point is not undone by the old call`() =
        runTest(timeout = 30.seconds) {
            for (steps: Int in 0..8) {
                val storage = SetStorage()
                val port = Port()
                port.onEnqueue = { started: DownloadUnit ->
                    if (port.enqueued.size == 1) {
                        port.events.tryEmit(BackgroundDownloadEvent.Error(started, "HTTP 503"))
                    }
                }
                val engine: DefaultDownloaderEngine = engine(storage, port)
                val old: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(single) } }
                runCurrent() // attempt one failed; the call now sits in its backoff

                engine.cancelDownload(single)
                val fresh: Deferred<Result<Unit>> = async {
                    repeat(steps) { yield() }
                    runCatching { engine.ensureAvailable(single) }
                }
                runCurrent()
                // Past the old call's backoff (2 s) but well inside the stall timeout of the new transfer:
                // the old call must not enqueue again behind the cancel.
                advanceTimeBy(10.seconds)
                runCurrent()

                assertEquals(2, port.enqueued.size, "steps=$steps: one attempt before the cancel, one for the new request")
                assertTrue(fresh.isActive, "steps=$steps: the new request is waiting for its transfer")
                finish(storage, port, unit)
                runCurrent()
                advanceUntilIdle()

                assertTrue(fresh.await().isSuccess, "steps=$steps: ${fresh.await().exceptionOrNull()}")
                assertIs<DownloadCancelledException>(old.await().exceptionOrNull(), "steps=$steps")
                assertEquals(1, storage.commitCount, "steps=$steps")
            }
        }

    @Test
    fun `a progress flood after a cancel never moves the state off Idle`() = runTest(timeout = 30.seconds) {
        val storage = SetStorage()
        val port = Port()
        val engine: DefaultDownloaderEngine = engine(storage, port)
        val call: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(unit) } }
        runCurrent()

        engine.cancelDownload(unit)
        repeat(5_000) { index: Int -> port.events.tryEmit(BackgroundDownloadEvent.Progress(unit, (index % 100) / 100f)) }
        runCurrent()
        advanceUntilIdle()

        assertIs<DownloadCancelledException>(call.await().exceptionOrNull())
        assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(unit).first())
        assertEquals(false, engine.hasActiveUnitDownload())
    }

    @Test
    fun `a flood of 5000 progress events never stalls the call and group progress never goes backwards`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port()
            val engine: DefaultDownloaderEngine = engine(storage, port)
            val seen: MutableList<Float> = mutableListOf()
            val watcher = launch {
                engine.downloadState(single).collect { state: GroupDownloadState ->
                    if (state is GroupDownloadState.Downloading) seen += state.progress
                }
            }
            val call: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(single) } }
            runCurrent()

            repeat(5_000) { index: Int ->
                // Deliberately jumpy: a transfer that restarts and reports lower fractions again.
                port.events.tryEmit(BackgroundDownloadEvent.Progress(unit, ((index * 37) % 100) / 100f))
            }
            runCurrent()
            finish(storage, port, unit)
            runCurrent()
            advanceUntilIdle()
            watcher.cancel()

            assertTrue(call.await().isSuccess, "${call.await().exceptionOrNull()}")
            assertEquals(seen.sorted(), seen, "progress must never move backwards")
            assertEquals(GroupDownloadState.Completed, engine.downloadState(single).value)
        }

    @Test
    fun `the same unit requested deleted and requested again 200 times is transferred and committed every time`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port()
            port.onEnqueue = { started: DownloadUnit -> finish(storage, port, started) }
            val engine: DefaultDownloaderEngine = engine(storage, port)

            repeat(200) { round: Int ->
                port.running.clear()
                engine.ensureAvailable(unit)
                assertTrue(storage.isResourceAvailable(unit), "round $round")
                assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(unit).first(), "round $round")
                storage.deleteResource(unit)
                engine.cancelDownload(unit) // the host drops the unit; state returns to Idle
                assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(unit).first(), "round $round")
            }

            assertEquals(200, port.enqueued.size)
            assertEquals(200, storage.commitCount)
            assertEquals(emptyList(), port.cancelled.filter { it != unit.id }, "only the unit's own cancels")
        }

    @Test
    fun `cancelling one unit leaves another unit of the same group downloading and the engine reports it active`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port()
            val engine: DefaultDownloaderEngine = engine(storage, port)
            val a: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(unit) } }
            val b: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(other) } }
            runCurrent()
            assertEquals(true, engine.hasActiveUnitDownload())

            engine.cancelDownload(unit)
            runCurrent()

            assertIs<DownloadCancelledException>(a.await().exceptionOrNull())
            assertTrue(b.isActive, "the other unit's call is untouched by the cancel")
            assertEquals(listOf(unit.id), port.cancelled, "only the cancelled unit reaches the port")
            assertEquals(true, engine.hasActiveUnitDownload())

            finish(storage, port, other)
            runCurrent()
            assertTrue(b.await().isSuccess)
            assertEquals(false, engine.hasActiveUnitDownload())
        }

    @Test
    fun `retryDownload while a call is already running does not start a second transfer`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port()
            val engine: DefaultDownloaderEngine = engine(storage, port)
            val call: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(single) } }
            runCurrent()

            repeat(20) { engine.retryDownload(single) }
            runCurrent()

            assertEquals(listOf(unit.id), port.enqueued, "a retry of a group that is downloading joins it")
            finish(storage, port, unit)
            runCurrent()
            advanceUntilIdle()
            assertTrue(call.await().isSuccess)
            assertEquals(1, storage.commitCount)
        }

    @Test
    fun `a transfer that never speaks ends every one of 32 callers with a timeout and the engine downloads again`() =
        runTest(timeout = 30.seconds) {
            val storage = SetStorage()
            val port = Port()
            val engine: DefaultDownloaderEngine = engine(storage, port)
            val callers: List<Deferred<Result<Unit>>> = List(32) { async { runCatching { engine.ensureAvailable(unit) } } }

            advanceUntilIdle()

            callers.forEachIndexed { index: Int, caller: Deferred<Result<Unit>> ->
                assertTrue(caller.isCompleted, "caller $index never ended")
                val failure: Throwable? = caller.await().exceptionOrNull()
                assertIs<DownloadFailedException>(failure, "caller $index: $failure")
                assertEquals(DownloadError.Timeout, failure.error, "caller $index")
            }
            assertTrue(port.enqueued.size in 1..32, "enqueues: ${port.enqueued.size}")
            assertEquals(UnitDownloadState.Error(DownloadError.Timeout), engine.unitDownloadStateFlow(unit).first())

            val again: Deferred<Result<Unit>> = async { runCatching { engine.ensureAvailable(unit) } }
            runCurrent()
            val before: Int = port.enqueued.size
            finish(storage, port, unit)
            runCurrent()
            assertTrue(again.await().isSuccess, "${again.await().exceptionOrNull()}")
            assertTrue(before > 0)
        }
}
