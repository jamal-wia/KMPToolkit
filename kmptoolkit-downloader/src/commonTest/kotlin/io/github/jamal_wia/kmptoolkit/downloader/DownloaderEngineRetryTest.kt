package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The group path's retry policy: what counts as progress, which failures are never retried, how
 * long the engine waits between attempts, and what a repeated [BackgroundDownloadEvent.Progress]
 * does (restarts the stall timer, is not progress).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineRetryTest {

    private val group = TestGroup("model_bundle")
    private val model = TestUnit(id = "model", group = group)
    private val stateStore = InMemoryStateStore()

    init {
        group.units = listOf(model)
    }

    private fun TestScope.engine(
        downloader: BackgroundResourceDownloader,
        storage: DownloaderStorage = SetStorage(),
    ): DefaultDownloaderEngine = DefaultDownloaderEngine(
        storage = storage,
        notifier = RecordingNotifier(),
        backgroundDownloader = downloader,
        stateStore = stateStore,
        bundledResourcesPresent = false,
        groups = listOf(group),
        dispatchers = TestDownloadDispatchers(this),
        logger = NoopLogger,
    )

    private fun TestScope.scripted(
        script: suspend kotlinx.coroutines.flow.FlowCollector<BackgroundDownloadEvent>.(Int, DownloadUnit) -> Unit,
    ): ScriptedAttemptsDownloader = ScriptedAttemptsDownloader(
        clock = { testScheduler.currentTime },
        script = script,
    )

    @Test
    fun `a downloader that re-emits the same fraction and then errors is stopped after three attempts`() = runTest {
        val downloader = scripted { _, unit ->
            emit(BackgroundDownloadEvent.Progress(unit, 0.5f))
            emit(BackgroundDownloadEvent.Progress(unit, 0.5f))
            emit(BackgroundDownloadEvent.Error(unit, "connection reset"))
        }

        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
            engine(downloader).ensureAvailable(group)
        }

        assertEquals(3, downloader.enqueueCount, "the first attempt's progress counts once, nothing after it")
        assertEquals(3, downloader.subscriptions)
        assertTrue(thrown.error is DownloadError.Unknown, "was ${thrown.error}")
    }

    @Test
    fun `an error with no progress at all is stopped after three attempts`() = runTest {
        val downloader = scripted { _, unit ->
            emit(BackgroundDownloadEvent.Error(unit, "Unable to resolve host"))
        }

        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
            engine(downloader).ensureAvailable(group)
        }

        assertEquals(3, downloader.enqueueCount)
        assertEquals(DownloadError.NoConnection, thrown.error)
    }

    @Test
    fun `progress that stops short of an earlier attempt's high-water mark is not progress`() = runTest {
        val fractions: List<Float> = listOf(0.6f, 0.3f, 0.5f, 0.9f)
        val downloader = scripted { attempt, unit ->
            emit(BackgroundDownloadEvent.Progress(unit, fractions[attempt]))
            emit(BackgroundDownloadEvent.Error(unit, "connection reset"))
        }

        assertFailsWith<DownloadFailedException> { engine(downloader).ensureAvailable(group) }

        // 0.6 advances; 0.3 and 0.5 stop short of it, so the run of three is used up before the
        // 0.9 of a fourth attempt could advance.
        assertEquals(3, downloader.enqueueCount)
    }

    @Test
    fun `a transfer that advances on every attempt keeps going and completes`() = runTest {
        val storage = SetStorage()
        val downloader = scripted { attempt, unit ->
            emit(BackgroundDownloadEvent.Progress(unit, 0.2f * (attempt + 1)))
            if (attempt < 4) {
                emit(BackgroundDownloadEvent.Error(unit, "connection reset"))
            } else {
                storage.setTempState(unit, TempFileState.Complete)
                emit(BackgroundDownloadEvent.FileReady(unit))
            }
        }
        val engine: DefaultDownloaderEngine = engine(downloader, storage)

        engine.ensureAvailable(group)

        assertEquals(5, downloader.enqueueCount)
        assertEquals(GroupDownloadState.Completed, engine.downloadState(group).value)
    }

    @Test
    fun `NotFound after progress is reported at once and never retried`() = runTest {
        val downloader = scripted { _, unit ->
            emit(BackgroundDownloadEvent.Progress(unit, 0.5f))
            emit(BackgroundDownloadEvent.Error(unit, "HTTP 404"))
        }

        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
            engine(downloader).ensureAvailable(group)
        }

        assertEquals(DownloadError.NotFound, thrown.error)
        assertEquals(1, downloader.enqueueCount)
    }

    @Test
    fun `Unauthorized after progress is reported at once and never retried`() = runTest {
        for (message in listOf("HTTP 401", "HTTP error: 403")) {
            val downloader = scripted { _, unit ->
                emit(BackgroundDownloadEvent.Progress(unit, 0.5f))
                emit(BackgroundDownloadEvent.Error(unit, message))
            }

            val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
                engine(downloader).ensureAvailable(group)
            }

            assertEquals(DownloadError.Unauthorized, thrown.error, message)
            assertEquals(1, downloader.enqueueCount, message)
        }
    }

    @Test
    fun `a retry waits an exponentially growing time capped at thirty seconds`() = runTest {
        val storage = SetStorage()
        val downloader = scripted { attempt, unit ->
            emit(BackgroundDownloadEvent.Progress(unit, 0.1f * (attempt + 1)))
            if (attempt < 8) {
                emit(BackgroundDownloadEvent.Error(unit, "connection reset"))
            } else {
                storage.setTempState(unit, TempFileState.Complete)
                emit(BackgroundDownloadEvent.FileReady(unit))
            }
        }

        engine(downloader, storage).ensureAvailable(group)

        val gaps: List<Long> = downloader.enqueueTimes.zipWithNext { a: Long, b: Long -> b - a }
        assertEquals(
            listOf(2.seconds, 4.seconds, 8.seconds, 16.seconds, 30.seconds, 30.seconds, 30.seconds, 30.seconds)
                .map { it.inWholeMilliseconds },
            gaps,
        )
    }

    @Test
    fun `a repeated Progress restarts the stall timer`() = runTest {
        val storage = SetStorage()
        val downloader = scripted { _, unit ->
            // Four silent minutes between events, three times over: a stall timer that a repeated
            // value did not restart would fire at the five-minute mark.
            repeat(3) {
                emit(BackgroundDownloadEvent.Progress(unit, 0.5f))
                delay(4.minutes)
            }
            storage.setTempState(unit, TempFileState.Complete)
            emit(BackgroundDownloadEvent.FileReady(unit))
        }
        val engine: DefaultDownloaderEngine = engine(downloader, storage)

        engine.ensureAvailable(group)

        assertEquals(1, downloader.enqueueCount, "no stall, so no retry")
        assertEquals(GroupDownloadState.Completed, engine.downloadState(group).value)
    }

    @Test
    fun `a repeated Progress keeps the transfer alive but is still not progress when it then fails`() = runTest {
        val downloader = scripted { _, unit ->
            repeat(3) {
                emit(BackgroundDownloadEvent.Progress(unit, 0.5f))
                delay(4.minutes)
            }
            emit(BackgroundDownloadEvent.Error(unit, "connection reset"))
        }

        assertFailsWith<DownloadFailedException> { engine(downloader).ensureAvailable(group) }

        assertEquals(3, downloader.enqueueCount)
        assertEquals(0, downloader.cancelledUnits.size, "an alive transfer is never cancelled as stalled")
    }
}
