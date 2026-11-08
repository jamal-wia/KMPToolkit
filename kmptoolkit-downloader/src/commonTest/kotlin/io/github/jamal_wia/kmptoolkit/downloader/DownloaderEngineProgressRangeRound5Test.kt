package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The documented range of a progress value is 0..1 ("one continuous 0..1" on the group state,
 * `showProgress` "(0..1)"). A port reports a fraction it computed from byte counts, so a wrong
 * Content-Length (an overshoot) or an unknown one (0 / 0, n / 0) reaches the engine as a value
 * outside the range, or as NaN or infinity. What the engine publishes must stay inside 0..1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineProgressRangeRound5Test {

    private val group = TestGroup("range")
    private val unit = TestUnit(id = "range_unit", group = group)

    init {
        group.units = listOf(unit)
    }

    private val odd: List<Float> = listOf(
        Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.7f, -0.3f, 0.5f,
    )

    private fun TestScope.engine(notifier: RecordingNotifier): DefaultDownloaderEngine = DefaultDownloaderEngine(
        storage = SetStorage(),
        notifier = notifier,
        backgroundDownloader = ScriptedAttemptsDownloader(clock = { testScheduler.currentTime }) { _, u ->
            for (fraction: Float in odd) emit(BackgroundDownloadEvent.Progress(u, fraction))
            emit(BackgroundDownloadEvent.Error(u, "http 404"))
        },
        stateStore = InMemoryStateStore(),
        bundledResourcesPresent = false,
        groups = listOf(group),
        dispatchers = TestDownloadDispatchers(this),
        logger = NoopLogger,
    )

    @Test
    fun `group progress and notifications stay finite and inside 0 to 1`() = runTest {
        val notifier = RecordingNotifier()
        val engine: DefaultDownloaderEngine = engine(notifier)
        val seen: MutableList<GroupDownloadState> = mutableListOf()
        backgroundScope.launch { engine.downloadState(group).collect { seen += it } }

        try {
            engine.ensureAvailable(group)
        } catch (_: DownloadFailedException) {
        }

        val published: List<Float> = seen.filterIsInstance<GroupDownloadState.Downloading>().map { it.progress }
        val notified: List<Float> = notifier.calls.filter { it.kind == RecordingNotifier.Kind.PROGRESS }.mapNotNull { it.progress }
        assertTrue(published.all { it.isFinite() && it in 0f..1f }, "group states: $published")
        assertTrue(notified.all { it.isFinite() && it in 0f..1f }, "notifications: $notified")
    }

    @Test
    fun `unit progress stays finite and inside 0 to 1`() = runTest {
        val engine: DefaultDownloaderEngine = engine(RecordingNotifier())
        val seen: MutableList<UnitDownloadState> = mutableListOf()
        backgroundScope.launch { engine.unitDownloadStateFlow(unit).collect { seen += it } }

        try {
            engine.ensureAvailable(unit)
        } catch (_: DownloadFailedException) {
        }

        val published: List<Float> = seen.filterIsInstance<UnitDownloadState.Downloading>().map { it.progress }
        assertTrue(published.all { it.isFinite() && it in 0f..1f }, "unit states: $published")
    }

    @Test
    fun `an infinite fraction does not stop later progress from counting toward the retry limit`() = runTest {
        val storage = SetStorage()
        val downloader = ScriptedAttemptsDownloader(clock = { testScheduler.currentTime }) { attempt, u ->
            emit(BackgroundDownloadEvent.Progress(u, Float.POSITIVE_INFINITY))
            emit(BackgroundDownloadEvent.Progress(u, 0.2f * (attempt + 1)))
            if (attempt < 4) {
                emit(BackgroundDownloadEvent.Error(u, "connection reset"))
            } else {
                storage.setTempState(u, TempFileState.Complete)
                emit(BackgroundDownloadEvent.FileReady(u))
            }
        }
        val engine = DefaultDownloaderEngine(
            storage = storage,
            notifier = RecordingNotifier(),
            backgroundDownloader = downloader,
            stateStore = InMemoryStateStore(),
            bundledResourcesPresent = false,
            groups = listOf(group),
            dispatchers = TestDownloadDispatchers(this),
            logger = NoopLogger,
        )

        engine.ensureAvailable(group)

        // Each attempt advances the real fraction, so none of the four errors may end the call early.
        assertEquals(5, downloader.enqueueCount)
    }

    @Test
    fun `a group never publishes a value above 1 when the last unit reports a full fraction`() = runTest {
        val wide = TestGroup("range-wide")
        val units: List<DownloadUnit> = (1..3).map { TestUnit(id = "wide_$it", group = wide) }
        wide.units = units
        val storage = SetStorage()
        val notifier = RecordingNotifier()
        val engine = DefaultDownloaderEngine(
            storage = storage,
            notifier = notifier,
            backgroundDownloader = ScriptedAttemptsDownloader(clock = { testScheduler.currentTime }) { _, u ->
                emit(BackgroundDownloadEvent.Progress(u, 1f))
                storage.setTempState(u, TempFileState.Complete)
                emit(BackgroundDownloadEvent.FileReady(u))
            },
            stateStore = InMemoryStateStore(),
            bundledResourcesPresent = false,
            groups = listOf(wide),
            dispatchers = TestDownloadDispatchers(this),
            logger = NoopLogger,
        )
        val seen: MutableList<GroupDownloadState> = mutableListOf()
        backgroundScope.launch { engine.downloadState(wide).collect { seen += it } }

        engine.ensureAvailable(wide)

        val published: List<Float> = seen.filterIsInstance<GroupDownloadState.Downloading>().map { it.progress }
        val notified: List<Float> = notifier.calls.filter { it.kind == RecordingNotifier.Kind.PROGRESS }.mapNotNull { it.progress }
        assertTrue(published.all { it in 0f..1f }, "group states: $published")
        assertTrue(notified.all { it in 0f..1f }, "notifications: $notified")
    }
}
