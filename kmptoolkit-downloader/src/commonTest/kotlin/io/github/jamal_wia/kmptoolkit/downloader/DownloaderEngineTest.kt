package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression coverage for the "shade download notification stuck as infinite loading" bug
 * (fast completion outracing a lagging collector). A fast unit buffers all its Progress events;
 * the lagging collector drains them as a burst of ongoing progress re-posts, after which some
 * notification implementations do not reliably apply the trailing `showCompleted` — the shade
 * stays on a live progress bar. [DefaultDownloaderEngine] must therefore REMOVE the notification
 * before posting the terminal "completed", so termination is a forced cancel rather than an update
 * layered on the stuck bar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineTest {

    private val group = TestGroup("model_bundle")

    // The unit that actually downloads. Its sibling is pre-seeded as available so the group scales
    // over a single downloaded unit (base=0, scale=1 -> scaled progress == fraction).
    private val downloadedUnit = TestUnit(id = "model", group = group)
    private val siblingUnit = TestUnit(id = "sibling", group = group)

    init {
        group.units = listOf(downloadedUnit, siblingUnit)
    }

    @Test
    fun `completion removes the ongoing notification before posting completed`() = runTest {
        val notifier = RecordingNotifier()
        runDownloadToCompletion(notifier, testScope = this)

        val kinds: List<RecordingNotifier.Kind> = notifier.calls.map { it.kind }
        val removeIndex: Int = kinds.indexOf(RecordingNotifier.Kind.REMOVE)
        val completedIndex: Int = kinds.indexOf(RecordingNotifier.Kind.COMPLETED)

        assertTrue(removeIndex >= 0, "a remove must be posted on completion")
        assertTrue(completedIndex >= 0, "a completed must be posted on completion")
        assertTrue(
            removeIndex < completedIndex,
            "remove must precede completed so the shade ends on 'completed', not a stuck bar",
        )
    }

    @Test
    fun `two groups downloading in parallel keep separate states instead of overwriting each other`() = runTest {
        // The old single app-wide state flow let whichever group wrote last win, so one download
        // could paint its progress onto an unrelated screen. Each group now owns its own flow.
        val groupA = TestGroup("bundle_a")
        val unitA = TestUnit(id = "a", group = groupA)
        groupA.units = listOf(unitA)
        val groupB = TestGroup("bundle_b")
        val unitB = TestUnit(id = "b", group = groupB)
        groupB.units = listOf(unitB)

        val storage = FakeStorage(available = mutableSetOf())
        val engine = DefaultDownloaderEngine(
            storage = storage,
            notifier = RecordingNotifier(),
            backgroundDownloader = StallingAtDownloader(unitA to 0.25f, unitB to 0.75f),
            stateStore = InMemoryStateStore(),
            bundledResourcesPresent = false,
            groups = listOf(groupA, groupB),
            dispatchers = TestDownloadDispatchers(this),
            logger = NoopLogger,
        )

        val jobA = backgroundScope.launch { runCatching { engine.ensureAvailable(groupA) } }
        val jobB = backgroundScope.launch { runCatching { engine.ensureAvailable(groupB) } }
        engine.downloadState(groupA).first { it == GroupDownloadState.Downloading(0.25f) }
        engine.downloadState(groupB).first { it == GroupDownloadState.Downloading(0.75f) }

        assertEquals(GroupDownloadState.Downloading(0.25f), engine.downloadState(groupA).value)
        assertEquals(GroupDownloadState.Downloading(0.75f), engine.downloadState(groupB).value)
        jobA.cancel()
        jobB.cancel()
    }

    @Test
    fun `downloadState throws for a group the host never declared`() = runTest {
        val storage = FakeStorage(available = mutableSetOf())
        val engine = DefaultDownloaderEngine(
            storage = storage,
            notifier = RecordingNotifier(),
            backgroundDownloader = FakeDownloader(storage, downloadedUnit),
            stateStore = InMemoryStateStore(),
            bundledResourcesPresent = false,
            groups = listOf(group),
            dispatchers = TestDownloadDispatchers(this),
            logger = NoopLogger,
        )

        assertFailsWith<IllegalArgumentException> {
            engine.downloadState(TestGroup("never_declared"))
        }
    }

    // -- Test wiring -----------------------------------------------------------------------

    /** Emits one Progress per unit at its own fraction, then stalls — for observing two in flight. */
    private class StallingAtDownloader(
        vararg fractions: Pair<DownloadUnit, Float>,
    ) : BackgroundResourceDownloader {
        private val byUnit: Map<String, Float> = fractions.associate { it.first.id to it.second }
        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            emit(BackgroundDownloadEvent.Progress(unit = unit, fraction = byUnit.getValue(unit.id)))
            awaitCancellation()
        }
    }

    private suspend fun runDownloadToCompletion(
        notifier: RecordingNotifier,
        testScope: TestScope,
    ) {
        // The sibling is pre-seeded as available so only one unit downloads (base=0, scale=1).
        val storage = FakeStorage(available = mutableSetOf(siblingUnit))
        val engine = DefaultDownloaderEngine(
            storage = storage,
            notifier = notifier,
            backgroundDownloader = FakeDownloader(storage, downloadedUnit),
            stateStore = InMemoryStateStore(),
            bundledResourcesPresent = false,
            groups = listOf(group),
            dispatchers = TestDownloadDispatchers(testScope),
            logger = NoopLogger,
        )

        engine.ensureAvailable(group)
    }

    /**
     * Emits a burst of Progress events and flips [storage] to "available" mid-stream, modelling a
     * platform downloader committing the unit before the engine drains the remaining buffered
     * progress.
     */
    private class FakeDownloader(
        private val storage: FakeStorage,
        private val unitToFlip: DownloadUnit,
    ) : BackgroundResourceDownloader {
        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            emit(BackgroundDownloadEvent.Progress(unit = unit, fraction = 0.5f))
            // Unit is now committed on disk (as a self-committing downloader does before the engine
            // catches up) — subsequent progress events are stale and must not reach the shade.
            storage.markAvailable(unitToFlip)
            emit(BackgroundDownloadEvent.Progress(unit = unit, fraction = 0.9f))
            emit(BackgroundDownloadEvent.Progress(unit = unit, fraction = 1.0f))
            emit(BackgroundDownloadEvent.FileReady(unit = unit))
        }
    }

    private class FakeStorage(
        private val available: MutableSet<DownloadUnit>,
    ) : DownloaderStorage {
        fun markAvailable(unit: DownloadUnit) {
            available += unit
        }

        override fun isResourceAvailable(unit: DownloadUnit): Boolean = unit in available
        override fun isTempFileAvailable(unit: DownloadUnit): Boolean = false
        override fun getTempFileSize(unit: DownloadUnit): Long = 0L
        override suspend fun commitResource(unit: DownloadUnit) = Unit
        override fun deleteTempFile(unit: DownloadUnit) = Unit
        override fun getResourcePath(unit: DownloadUnit): String = ""
        override fun getTempFilePath(unit: DownloadUnit): String = ""
        override fun getResourceSize(unit: DownloadUnit): Long = 0L
        override fun deleteResource(unit: DownloadUnit) = Unit
    }
}
