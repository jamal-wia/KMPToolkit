package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [DefaultDownloaderEngine]'s per-[DownloadUnit] API (`ensureAvailable(unit)`,
 * `cancelDownload(unit)`, `unitDownloadStateFlow(unit)`) — the surface a host uses for a catalogue
 * whose members are only known at runtime, where several can download at once and each needs its
 * own progress and cancel, rather than the group-level API covered by [DownloaderEngineTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineUnitDownloadTest {

    private val group = TestGroup("catalogue")
    private val asset = TestUnit(id = "item_v1", group = group)

    private fun engine(
        storage: FakeStorage = FakeStorage(),
        downloader: BackgroundResourceDownloader,
        testScope: TestScope,
    ): DefaultDownloaderEngine = DefaultDownloaderEngine(
        storage = storage,
        notifier = RecordingNotifier(),
        backgroundDownloader = downloader,
        stateStore = InMemoryStateStore(),
        bundledResourcesPresent = false,
        groups = listOf(group),
        dispatchers = TestDownloadDispatchers(testScope),
        logger = NoopLogger,
    )

    @Test
    fun `ensureAvailable returns immediately and reports Available when already on disk`() = runTest {
        val storage = FakeStorage(available = mutableSetOf(asset))
        val downloader = NeverAskedDownloader()
        val engine = engine(storage, downloader, this)

        engine.ensureAvailable(asset)

        // Available, not Completed: nothing was transferred this process, and the distinction is
        // what lets an observer tell "just finished" from "was already here".
        assertEquals(UnitDownloadState.Available, engine.unitDownloadStateFlow(asset).first())
        assertFalse(downloader.enqueueCalled, "already-available must not trigger a download")
    }

    @Test
    fun `unitDownloadStateFlow is seeded from disk so a first collector sees Available without asking`() = runTest {
        // The whole point of the seed: a UI row must not need a separate storage read to render
        // "already downloaded" for a unit nobody has called ensureAvailable on this process.
        val storage = FakeStorage(available = mutableSetOf(asset))
        val engine = engine(storage, NeverAskedDownloader(), this)

        assertEquals(UnitDownloadState.Available, engine.unitDownloadStateFlow(asset).first())
    }

    @Test
    fun `ensureAvailable downloads then commits and reports Completed`() = runTest {
        val storage = FakeStorage()
        val downloader = SucceedingDownloader(asset)
        val engine = engine(storage, downloader, this)

        engine.ensureAvailable(asset)

        assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(asset).first())
        assertTrue(storage.isResourceAvailable(asset))
    }

    @Test
    fun `ensureAvailable recovers from an existing temp file without re-downloading`() = runTest {
        val storage = FakeStorage(tempAvailable = mutableSetOf(asset))
        val downloader = NeverAskedDownloader()
        val engine = engine(storage, downloader, this)

        engine.ensureAvailable(asset)

        assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(asset).first())
        assertFalse(downloader.enqueueCalled, "a recovered temp file must commit directly, not re-enqueue")
    }

    @Test
    fun `a failed download throws DownloadFailedException and reports Error and deletes the temp file`() = runTest {
        val storage = FakeStorage()
        val downloader = FailingDownloader(asset, "boom")
        val engine = engine(storage, downloader, this)

        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
            engine.ensureAvailable(asset)
        }

        // The SAME classified error must reach both the thrower and the flow — a caller in a
        // try/catch and an observer of the state must never disagree about what happened.
        val state: UnitDownloadState = engine.unitDownloadStateFlow(asset).first()
        assertTrue(state is UnitDownloadState.Error, "expected Error but was $state")
        assertEquals(state.error, thrown.error)
        assertEquals(asset, thrown.unit)
        assertTrue(storage.tempFileDeletedFor.contains(asset))
    }

    @Test
    fun `a stalled download with no terminal event fails instead of hanging forever`() = runTest {
        val storage = FakeStorage()
        val downloader = StallingDownloader()
        val engine = engine(storage, downloader, this)

        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
            engine.ensureAvailable(asset)
        }

        assertTrue(thrown.error is DownloadError.Timeout, "a stall is a timeout, was ${thrown.error}")
        val state: UnitDownloadState = engine.unitDownloadStateFlow(asset).first()
        assertTrue(state is UnitDownloadState.Error, "expected Error but was $state")
    }

    @Test
    fun `cancelDownload sets Idle and deletes the temp file`() = runTest {
        val storage = FakeStorage()
        val downloader = StallingDownloader()
        val engine = engine(storage, downloader, this)

        // Drive the unit into Downloading first — otherwise a broken cancelDownload that never
        // touched the state at all would still pass an Idle assertion, since Idle is also the
        // untouched default. Waits specifically for the fake's real 0.1f progress, not the
        // Downloading(0f) placeholder ensureAvailable sets before enqueuing.
        val downloadJob = backgroundScope.launch { engine.ensureAvailable(asset) }
        assertEquals(
            UnitDownloadState.Downloading(0.1f),
            engine.unitDownloadStateFlow(asset).first { it == UnitDownloadState.Downloading(0.1f) },
        )

        engine.cancelDownload(asset)
        // cancelDownload dispatches the state write onto the engine's own scope (it isn't suspend) —
        // give that a turn to run before observing.
        launch { }.join()

        assertTrue(downloader.cancelCalled)
        assertTrue(storage.tempFileDeletedFor.contains(asset))
        assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(asset).first())
        downloadJob.cancel()
    }

    @Test
    fun `unitDownloadStateFlow starts at Idle for a unit nothing has ever asked to download`() = runTest {
        val engine = engine(downloader = NeverAskedDownloader(), testScope = this)

        assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(asset).first())
    }

    @Test
    fun `concurrent ensureAvailable calls for the same unit share one download rather than two`() = runTest {
        val storage = FakeStorage()
        val downloader = CountingSucceedingDownloader(asset)
        val engine = engine(storage, downloader, this)

        // Launched together, not awaited one after another — the second call must find the first
        // already holding unitMutex(asset) and share its result instead of enqueuing again.
        val first = backgroundScope.launch { engine.ensureAvailable(asset) }
        val second = backgroundScope.launch { engine.ensureAvailable(asset) }
        first.join()
        second.join()

        assertEquals(1, downloader.enqueueCount, "a duplicate concurrent call must not start a second download")
        assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(asset).first())
    }

    @Test
    fun `commitUnit reports Error when the resource still isn't available right after commit`() = runTest {
        // Models a storage bug distinct from a thrown exception: commitResource() returns normally
        // but isResourceAvailable() still says no — DefaultDownloaderEngine must not report
        // Completed on the strength of "commit didn't throw" alone.
        val storage = FakeStorage(commitIsANoOp = true)
        val downloader = SucceedingDownloader(asset)
        val engine = engine(storage, downloader, this)

        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
            engine.ensureAvailable(asset)
        }

        assertTrue(thrown.error is DownloadError.Storage, "a bad commit is a storage error, was ${thrown.error}")
        val state: UnitDownloadState = engine.unitDownloadStateFlow(asset).first()
        assertTrue(state is UnitDownloadState.Error, "expected Error but was $state")
    }

    @Test
    fun `a redundant ensureAvailable after a completion this process keeps Completed rather than demoting to Available`() = runTest {
        // Completed means "downloaded THIS process" and an observer keys off it (a celebration, a
        // reader refresh). A second call that finds the file already there must not erase that.
        val storage = FakeStorage()
        val engine = engine(storage, SucceedingDownloader(asset), this)
        engine.ensureAvailable(asset)
        assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(asset).first())

        engine.ensureAvailable(asset)

        assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(asset).first())
    }

    @Test
    fun `two different units downloaded one after another keep independent progress histories`() = runTest {
        val second = TestUnit(id = "item_v2", group = group)
        val storage = FakeStorage()
        val downloader = TwoUnitDownloader(unitA = asset, unitB = second)
        val engine = engine(storage, downloader, this)

        val progressA = mutableListOf<Float>()
        val progressB = mutableListOf<Float>()
        // backgroundScope, not the test's own scope: the collect{} never completes on its own (the
        // flow keeps replaying its last state to any future subscriber), so runTest would otherwise
        // wait forever for these child jobs — backgroundScope cancels them when the test body ends.
        backgroundScope.launch {
            engine.unitDownloadStateFlow(asset).collect { state ->
                if (state is UnitDownloadState.Downloading) progressA += state.progress
            }
        }
        backgroundScope.launch {
            engine.unitDownloadStateFlow(second).collect { state ->
                if (state is UnitDownloadState.Downloading) progressB += state.progress
            }
        }

        engine.ensureAvailable(asset)
        engine.ensureAvailable(second)

        assertEquals(listOf(0f, 0.3f), progressA)
        assertEquals(listOf(0f, 0.7f), progressB)
    }

    @Test
    fun `two distinct unit instances with the same id are one download and one state`() = runTest {
        // The engine identifies a unit by its id, never by object identity — a host may construct
        // a fresh DownloadUnit per call (a UI row does exactly that) and must still hit the same
        // mutex and the same state flow. Before this was true, two equal-id instances of a plain
        // (non-data) class got separate mutexes and started the same download twice.
        val storage = FakeStorage()
        val downloader = CountingSucceedingDownloader(asset)
        val engine = engine(storage, downloader, this)
        val sameIdOtherInstance = TestUnit(id = asset.id, group = group)
        assertTrue(sameIdOtherInstance !== asset, "the test needs two distinct instances")

        val first = backgroundScope.launch { engine.ensureAvailable(asset) }
        val second = backgroundScope.launch { engine.ensureAvailable(sameIdOtherInstance) }
        first.join()
        second.join()

        assertEquals(1, downloader.enqueueCount, "same id must share one download, not start two")
        assertEquals(
            UnitDownloadState.Completed,
            engine.unitDownloadStateFlow(sameIdOtherInstance).first(),
            "the other instance must observe the very same state flow",
        )
    }

    @Test
    fun `a 5xx phrased the way a platform downloader phrases it classifies as Server with its code`() = runTest {
        // "HTTP 503 for <unit>" and "HTTP error: 503" both start with a letter, not a digit — an
        // earlier classifier that looked for a leading digit could never reach the Server branch,
        // so the host's "server error" copy could never show.
        for (message in listOf("HTTP 503 for TestUnit(x)", "HTTP error: 503")) {
            val storage = FakeStorage()
            val engine = engine(storage, FailingDownloader(asset, message), this)

            val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
                engine.ensureAvailable(asset)
            }

            val error: DownloadError = thrown.error
            assertTrue(error is DownloadError.Server, "'$message' must classify as Server, was $error")
            assertEquals(503, error.statusCode, "the status code must be carried through for '$message'")
        }
    }

    @Test
    fun `hasActiveUnitDownload reflects the Idle-Downloading-Idle lifecycle of a unit`() = runTest {
        val storage = FakeStorage()
        val downloader = StallingDownloader()
        val engine = engine(storage, downloader, this)

        assertFalse(engine.hasActiveUnitDownload(), "nothing has ever asked to download yet")

        val downloadJob = backgroundScope.launch { engine.ensureAvailable(asset) }
        engine.unitDownloadStateFlow(asset).first { it == UnitDownloadState.Downloading(0.1f) }
        assertTrue(engine.hasActiveUnitDownload(), "a unit is actively Downloading")

        engine.cancelDownload(asset)
        // cancelDownload dispatches the state write onto the engine's own scope — give it a turn.
        launch { }.join()
        assertFalse(engine.hasActiveUnitDownload(), "cancelling returns the unit to Idle")
        downloadJob.cancel()
    }

    @Test
    fun `cancelAllDownloads cancels every in-progress per-unit download`() = runTest {
        val second = TestUnit(id = "item_v2", group = group)
        val storage = FakeStorage()
        val downloader = MultiUnitStallingDownloader()
        val engine = engine(storage, downloader, this)

        val jobA = backgroundScope.launch { engine.ensureAvailable(asset) }
        val jobB = backgroundScope.launch { engine.ensureAvailable(second) }
        engine.unitDownloadStateFlow(asset).first { it == UnitDownloadState.Downloading(0.1f) }
        engine.unitDownloadStateFlow(second).first { it == UnitDownloadState.Downloading(0.1f) }
        assertTrue(engine.hasActiveUnitDownload())

        engine.cancelAllDownloads()
        // No extra turn needed to observe the state reset: unlike the public cancelDownload(unit),
        // cancelAllDownloads awaits each unit's state write directly before returning (see its own
        // doc — this is exactly what closes the race that let a download's Downloading state
        // survive logout).

        // Exactly these two, not merely "at least": the test group declares no units, so the
        // group-level sweep has nothing of its own to cancel — anything extra here would be the
        // engine reaching for units nobody asked it to touch.
        assertEquals(setOf<DownloadUnit>(asset, second), downloader.cancelledUnits.toSet())
        assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(asset).first())
        assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(second).first())
        assertFalse(
            engine.hasActiveUnitDownload(),
            "the exact motivation for cancelAllDownloads: a download must not keep running past " +
                "logout with a now-stale auth token",
        )
        jobA.cancel()
        jobB.cancel()
    }

    // -- Test wiring -----------------------------------------------------------------------

    // Keyed by unit.id throughout — the identity the real storages honour through relativePath.
    // Keying by the object silently broke the same-id-different-instance case the engine promises.
    private class FakeStorage(
        available: MutableSet<DownloadUnit> = mutableSetOf(),
        tempAvailable: MutableSet<DownloadUnit> = mutableSetOf(),
        /** When true, commitResource() returns normally without ever marking the unit available —
         * models a storage bug distinct from commitResource() throwing. */
        private val commitIsANoOp: Boolean = false,
    ) : DownloaderStorage {
        private val availableIds: MutableSet<String> = available.mapTo(mutableSetOf()) { it.id }
        private val tempAvailableIds: MutableSet<String> = tempAvailable.mapTo(mutableSetOf()) { it.id }
        val tempFileDeletedFor: MutableSet<DownloadUnit> = mutableSetOf()

        override fun isResourceAvailable(unit: DownloadUnit): Boolean = unit.id in availableIds
        override fun isTempFileAvailable(unit: DownloadUnit): Boolean = unit.id in tempAvailableIds
        override fun getTempFileSize(unit: DownloadUnit): Long = 0L
        override suspend fun commitResource(unit: DownloadUnit) {
            if (commitIsANoOp) return
            tempAvailableIds.remove(unit.id)
            availableIds += unit.id
        }

        override fun deleteTempFile(unit: DownloadUnit) {
            tempAvailableIds.remove(unit.id)
            tempFileDeletedFor += unit
        }

        override fun getResourcePath(unit: DownloadUnit): String = ""
        override fun getTempFilePath(unit: DownloadUnit): String = ""
        override fun getResourceSize(unit: DownloadUnit): Long = 0L
        override fun deleteResource(unit: DownloadUnit) {
            availableIds.remove(unit.id)
        }
    }

    /** Fails the test (via [enqueueCalled]/[cancelCalled] assertions) if a download is ever started. */
    private class NeverAskedDownloader : BackgroundResourceDownloader {
        var enqueueCalled: Boolean = false
            private set
        var cancelCalled: Boolean = false
            private set

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueueCalled = true
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) {
            cancelCalled = true
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = emptyFlow()
    }

    /** The [FakeStorage] passed to the engine isn't touched directly here —
     * [DefaultDownloaderEngine] calls its `commitResource` itself once
     * [BackgroundDownloadEvent.FileReady] arrives. */
    private class SucceedingDownloader(
        private val expectedUnit: DownloadUnit,
    ) : BackgroundResourceDownloader {
        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            check(unit.id == expectedUnit.id) { "unexpected unit $unit" }
            emit(BackgroundDownloadEvent.Progress(unit = unit, fraction = 0.5f))
            emit(BackgroundDownloadEvent.FileReady(unit = unit))
        }
    }

    /** Like [SucceedingDownloader], but counts how many times a download was actually started —
     * for proving the per-unit mutex dedupes concurrent calls rather than starting two downloads. */
    private class CountingSucceedingDownloader(
        private val expectedUnit: DownloadUnit,
    ) : BackgroundResourceDownloader {
        var enqueueCount: Int = 0
            private set

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueueCount++
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            check(unit.id == expectedUnit.id) { "unexpected unit $unit" }
            emit(BackgroundDownloadEvent.Progress(unit = unit, fraction = 0.5f))
            emit(BackgroundDownloadEvent.FileReady(unit = unit))
        }
    }

    private class FailingDownloader(
        private val expectedUnit: DownloadUnit,
        private val message: String,
    ) : BackgroundResourceDownloader {
        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            check(unit.id == expectedUnit.id) { "unexpected unit $unit" }
            emit(BackgroundDownloadEvent.Error(unit = unit, message = message))
        }
    }

    private class StallingDownloader : BackgroundResourceDownloader {
        var cancelCalled: Boolean = false
            private set

        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) {
            cancelCalled = true
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            emit(BackgroundDownloadEvent.Progress(unit = unit, fraction = 0.1f))
            awaitCancellation()
        }
    }

    /** Like [StallingDownloader], but tracks cancellation per unit — for proving
     * `cancelAllDownloads()` reaches every stalled unit, not just the last one asked about. */
    private class MultiUnitStallingDownloader : BackgroundResourceDownloader {
        val cancelledUnits: MutableSet<DownloadUnit> = mutableSetOf()

        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) {
            cancelledUnits += unit
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            emit(BackgroundDownloadEvent.Progress(unit = unit, fraction = 0.1f))
            awaitCancellation()
        }
    }

    private class TwoUnitDownloader(
        private val unitA: DownloadUnit,
        private val unitB: DownloadUnit,
    ) : BackgroundResourceDownloader {
        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            val fraction: Float = when (unit) {
                unitA -> 0.3f
                unitB -> 0.7f
                else -> error("unexpected unit $unit")
            }
            emit(BackgroundDownloadEvent.Progress(unit = unit, fraction = fraction))
            emit(BackgroundDownloadEvent.FileReady(unit = unit))
        }
    }
}
