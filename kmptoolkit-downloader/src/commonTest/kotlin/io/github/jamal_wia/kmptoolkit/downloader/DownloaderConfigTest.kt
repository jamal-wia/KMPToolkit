package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [DownloaderConfig] and the `createDownloader` overload that takes it: a positive stall timeout
 * (infinite allowed), value equality, and the engine really using the configured value, while a
 * call without a config keeps the five-minute behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderConfigTest {

    private val group = TestGroup("model_bundle")
    private val model = TestUnit(id = "model", group = group)
    private val stateStore: DownloadStateStore = InMemoryStateStore()

    init {
        group.units = listOf(model)
    }

    // -- The class ---------------------------------------------------------------------------

    @Test
    fun `the default stall timeout is five minutes and a config built with no arguments uses it`() {
        assertEquals(5.minutes, DownloaderConfig.DEFAULT_STALL_TIMEOUT)
        assertEquals(DownloaderConfig.DEFAULT_STALL_TIMEOUT, DownloaderConfig().stallTimeout)
    }

    @Test
    fun `a stall timeout that is not positive is rejected`() {
        assertFailsWith<IllegalArgumentException> { DownloaderConfig(stallTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { DownloaderConfig(stallTimeout = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { DownloaderConfig(stallTimeout = -Duration.INFINITE) }
    }

    @Test
    fun `an infinite stall timeout and the smallest positive one are accepted`() {
        assertEquals(Duration.INFINITE, DownloaderConfig(stallTimeout = Duration.INFINITE).stallTimeout)
        assertEquals(1.seconds, DownloaderConfig(stallTimeout = 1.seconds).stallTimeout)
    }

    @Test
    fun `configs are equal by value with matching hash codes and a readable toString`() {
        assertEquals(DownloaderConfig(10.seconds), DownloaderConfig(10.seconds))
        assertEquals(DownloaderConfig(10.seconds).hashCode(), DownloaderConfig(10.seconds).hashCode())
        assertEquals(DownloaderConfig(), DownloaderConfig(DownloaderConfig.DEFAULT_STALL_TIMEOUT))
        assertNotEquals(DownloaderConfig(10.seconds), DownloaderConfig(11.seconds))
        assertNotEquals<Any?>(DownloaderConfig(10.seconds), null)
        assertNotEquals<Any?>(DownloaderConfig(10.seconds), "DownloaderConfig")
        assertEquals("DownloaderConfig(stallTimeout=10s)", DownloaderConfig(10.seconds).toString())
    }

    // -- The engine uses it ------------------------------------------------------------------

    private fun TestScope.build(config: DownloaderConfig?, downloader: BackgroundResourceDownloader): Downloader =
        if (config == null) {
            createDownloader(
                storage = SetStorage(),
                backgroundDownloader = downloader,
                groups = listOf(group),
                stateStore = stateStore,
                dispatchers = TestDownloadDispatchers(this),
            )
        } else {
            createDownloader(
                storage = SetStorage(),
                backgroundDownloader = downloader,
                groups = listOf(group),
                stateStore = stateStore,
                config = config,
                dispatchers = TestDownloadDispatchers(this),
            )
        }

    private fun silentAfterProgress(): ScriptedAttemptsDownloader = ScriptedAttemptsDownloader { _, unit ->
        emit(BackgroundDownloadEvent.Progress(unit, 0.5f))
        delay(Duration.INFINITE)
    }

    @Test
    fun `a call without a config stalls after five minutes of silence`() = runTest {
        val downloader = silentAfterProgress()

        val thrown: DownloadFailedException = assertFailsWith {
            build(null, downloader).ensureAvailable(group)
        }

        assertEquals(DownloadError.Timeout, thrown.error)
        // Three attempts of five silent minutes, and the two waits between them.
        assertTrue(testScheduler.currentTime >= 3 * 5.minutes.inWholeMilliseconds, "was ${testScheduler.currentTime}")
        assertEquals(3, downloader.enqueueCount)
    }

    @Test
    fun `a configured stall timeout is the one the engine waits`() = runTest {
        val downloader = silentAfterProgress()

        val thrown: DownloadFailedException = assertFailsWith {
            build(DownloaderConfig(stallTimeout = 10.seconds), downloader).ensureAvailable(group)
        }

        assertEquals(DownloadError.Timeout, thrown.error)
        val elapsed: Long = testScheduler.currentTime
        assertTrue(elapsed >= 3 * 10.seconds.inWholeMilliseconds, "was $elapsed")
        assertTrue(elapsed < 1.minutes.inWholeMilliseconds, "was $elapsed")
    }

    @Test
    fun `the configured timeout applies to the per-unit surface too`() = runTest {
        val downloader = silentAfterProgress()

        assertFailsWith<DownloadFailedException> {
            build(DownloaderConfig(stallTimeout = 10.seconds), downloader).ensureAvailable(model)
        }

        assertEquals(10.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `an infinite stall timeout never stalls a transfer that is merely slow`() = runTest {
        val storage = SetStorage()
        val downloader = ScriptedAttemptsDownloader { _, unit ->
            emit(BackgroundDownloadEvent.Progress(unit, 0.5f))
            delay(10.hours)
            storage.setTempState(unit, TempFileState.Complete)
            emit(BackgroundDownloadEvent.FileReady(unit))
        }
        val engine: Downloader = createDownloader(
            storage = storage,
            backgroundDownloader = downloader,
            groups = listOf(group),
            stateStore = stateStore,
            config = DownloaderConfig(stallTimeout = Duration.INFINITE),
            dispatchers = TestDownloadDispatchers(this),
        )

        engine.ensureAvailable(group)

        assertEquals(1, downloader.enqueueCount)
        assertEquals(GroupDownloadState.Completed, engine.downloadState(group).value)
    }

    @Test
    fun `the overload without a config still takes a notifier as its fifth positional argument`() = runTest {
        // Source compatibility: this call, as written before 4.0.0, must keep resolving to the
        // existing overload, never to the one whose fifth parameter is the config.
        val downloader: Downloader = createDownloader(
            SetStorage(),
            silentAfterProgress(),
            listOf(group),
            stateStore,
            RecordingNotifier(),
        )

        assertEquals(GroupDownloadState.Idle, downloader.downloadState(group).value)
    }

    // -- The timer is armed at subscription -----------------------------------------------------

    @Test
    fun `a transfer that is silent from the start is stalled after the stall timeout`() = runTest {
        val downloader = ScriptedAttemptsDownloader { _, _ -> delay(Duration.INFINITE) }

        val thrown: DownloadFailedException = assertFailsWith {
            build(DownloaderConfig(stallTimeout = 10.seconds), downloader).ensureAvailable(model)
        }

        assertEquals(DownloadError.Timeout, thrown.error)
        assertEquals(10.seconds.inWholeMilliseconds, testScheduler.currentTime)
        assertEquals(listOf("model"), downloader.cancelledUnits)
    }

    @Test
    fun `a keep-alive Progress of zero prevents a stall but is not progress`() = runTest {
        val storage = SetStorage()
        val downloader = ScriptedAttemptsDownloader { _, unit ->
            repeat(30) {
                delay(60.seconds)
                emit(BackgroundDownloadEvent.Progress(unit, 0f))
            }
            storage.setTempState(unit, TempFileState.Complete)
            emit(BackgroundDownloadEvent.FileReady(unit))
        }
        val engine: Downloader = createDownloader(
            storage = storage,
            backgroundDownloader = downloader,
            groups = listOf(group),
            stateStore = stateStore,
            config = DownloaderConfig(stallTimeout = 2.minutes),
            dispatchers = TestDownloadDispatchers(this),
        )

        engine.ensureAvailable(group)

        assertEquals(1, downloader.enqueueCount)
        assertEquals(GroupDownloadState.Completed, engine.downloadState(group).value)
    }

    @Test
    fun `a keep-alive that then errors is still bounded at three attempts`() = runTest {
        val downloader = ScriptedAttemptsDownloader { _, unit ->
            delay(60.seconds)
            emit(BackgroundDownloadEvent.Progress(unit, 0f))
            emit(BackgroundDownloadEvent.Error(unit, "connection reset"))
        }

        assertFailsWith<DownloadFailedException> {
            build(DownloaderConfig(stallTimeout = 2.minutes), downloader).ensureAvailable(group)
        }

        assertEquals(3, downloader.enqueueCount)
    }
}
