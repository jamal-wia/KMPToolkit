package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two ways a long-lived player is reused: [VideoPlayer.unload] between videos, and a
 * [VideoPlayer.prepare] that replaces one still loading — what a feed or a playlist screen does all
 * day. Both leave a hung caller, a stale state or two loads on one decoder when they are wrong.
 *
 * A test that leaves a player playing releases it at the end: the polling loop shares the test
 * scheduler and would otherwise keep `runTest` advancing virtual time forever.
 */
class VideoPlayerReuseTest {

    private val first: VideoSource = VideoSource.Remote("https://example.test/first.mp4")
    private val second: VideoSource = VideoSource.Remote("https://example.test/second.mp4")
    private val third: VideoSource = VideoSource.Remote("https://example.test/third.mp4")

    private fun TestScope.newPlayer(
        engine: RecordingVideoPlaybackEngine,
        config: VideoPlayerConfig = VideoPlayerConfig(),
    ): VideoPlayer = createVideoPlayer(
        engine = engine,
        config = config,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `unload frees the source but not the platform player`() = runTest {
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(first)
        player.play()
        player.unload()

        assertEquals(1, engine.releaseCount)
        assertEquals(0, engine.disposeCount)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        player.release()
    }

    @Test
    fun `an unloaded player prepares and plays again`() = runTest {
        val engine = RecordingVideoPlaybackEngine(duration = 7_000L)
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(first)
        player.unload()
        player.prepare(second)
        player.play()

        assertEquals(listOf(first, second), engine.loadedSources)
        assertEquals(VideoPlayerState.Playing(duration = 7_000L, currentPosition = 0L), player.stateFlow.value)
        player.release()
    }

    @Test
    fun `unload on an idle player is harmless`() = runTest {
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.unload()
        player.unload()
        player.prepare(first)

        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        player.release()
    }

    @Test
    fun `unload keeps the listener attached so the next source still completes`() = runTest {
        val engine = RecordingVideoPlaybackEngine(duration = 7_000L)
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(first)
        player.unload()
        player.prepare(second)
        player.play()
        requireNotNull(engine.listener).onCompleted()

        assertEquals(VideoPlayerState.Completed(7_000L), player.stateFlow.value)
        player.release()
    }

    @Test
    fun `unload stops the polling`() = runTest {
        val engine = RecordingVideoPlaybackEngine(duration = 10_000L)
        val player: VideoPlayer = newPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L))

        player.prepare(first)
        player.play()
        player.unload()
        engine.position = 5_000L
        engine.buffered = 6_000L
        advanceTimeBy(1_000L)

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        assertEquals(0L, player.bufferedPositionFlow.value)
        player.release()
    }

    @Test
    fun `callbacks arriving after unload are dropped`() = runTest {
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(first)
        player.play()
        val listener: VideoPlaybackEngineListener = requireNotNull(engine.listener)
        player.unload()
        listener.onCompleted()
        listener.onFailed(IllegalStateException("too late"))
        listener.onBufferingChanged(true)
        listener.onVideoSizeChanged(VideoSize(1280, 720))

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(false, player.isBufferingFlow.value)
        assertEquals(null, player.videoSizeFlow.value)
        player.release()
    }

    @Test
    fun `unloading during a load abandons it without failing the caller`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply { loadDelayMs = 1_000L }
        val player: VideoPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(first) }
        runCurrent()
        player.unload()
        advanceUntilIdle()

        assertTrue(loading.isCompleted)
        assertFalse(loading.isCancelled)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0, engine.activeLoads)
        player.release()
    }

    @Test
    fun `unload on a released player does nothing`() = runTest {
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(first)
        player.release()
        player.unload()
        player.prepare(second)

        assertEquals(1, engine.releaseCount)
        assertEquals(listOf(first), engine.loadedSources)
    }

    @Test
    fun `a prepare that replaces one still loading wins and the replaced caller returns`() = runTest {
        val engine = RecordingVideoPlaybackEngine(duration = 4_000L).apply { loadDelayMs = 1_000L }
        val player: VideoPlayer = newPlayer(engine)

        val replaced: Job = launch { player.prepare(first) }
        runCurrent()
        val replacing: Job = launch { player.prepare(second) }
        advanceUntilIdle()

        assertTrue(replaced.isCompleted)
        assertFalse(replaced.isCancelled)
        assertTrue(replacing.isCompleted)
        assertEquals(VideoPlayerState.Ready(4_000L), player.stateFlow.value)
        assertEquals(listOf(first, second), engine.loadedSources)
        player.release()
    }

    @Test
    fun `a replaced load finishes unwinding before the next one starts`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply {
            loadDelayMs = 1_000L
            unwindDelayMs = 500L
        }
        val player: VideoPlayer = newPlayer(engine)

        launch { player.prepare(first) }
        runCurrent()
        launch { player.prepare(second) }
        runCurrent()
        // Replaces the second while it is still waiting for the first to unwind.
        launch { player.prepare(third) }
        advanceUntilIdle()

        assertEquals(1, engine.maxConcurrentLoads)
        assertEquals(listOf(first, third), engine.loadedSources)
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        player.release()
    }

    @Test
    fun `a prepare right after unload waits for the abandoned load to unwind`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply {
            loadDelayMs = 1_000L
            unwindDelayMs = 500L
        }
        val player: VideoPlayer = newPlayer(engine)

        launch { player.prepare(first) }
        runCurrent()
        player.unload()
        launch { player.prepare(second) }
        advanceUntilIdle()

        assertEquals(1, engine.maxConcurrentLoads)
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        player.release()
    }

    @Test
    fun `a prepare cancelled while waiting for the load it replaced does not free the engine under it`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply {
            loadDelayMs = 1_000L
            unwindDelayMs = 500L
        }
        val player: VideoPlayer = newPlayer(engine)

        launch { player.prepare(first) }
        runCurrent()
        val waiting: Job = launch { player.prepare(second) }
        runCurrent()
        waiting.cancel()
        runCurrent()
        val releasesWhileUnwinding: Int = engine.releaseCount
        launch { player.prepare(third) }
        advanceUntilIdle()

        assertEquals(1, engine.maxConcurrentLoads)
        assertEquals(0, releasesWhileUnwinding, "the engine was freed while the first load was still unwinding")
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        player.release()
    }

    @Test
    fun `a failure in a replaced load does not overwrite the replacing one`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply {
            loadDelayMs = 1_000L
            failureWhenCancelled = IllegalStateException("released while loading")
        }
        val player: VideoPlayer = newPlayer(engine)

        launch { player.prepare(first) }
        runCurrent()
        launch { player.prepare(second) }
        runCurrent()
        engine.failureWhenCancelled = null
        advanceUntilIdle()

        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        player.release()
    }

    @Test
    fun `a picture size from a replaced load does not survive into the replacing one`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply {
            loadDelayMs = 1_000L
            sizeReportedWhileLoading = VideoSize(1920, 1080)
        }
        val player: VideoPlayer = newPlayer(engine)

        launch { player.prepare(first) }
        runCurrent()
        engine.sizeReportedWhileLoading = null
        launch { player.prepare(second) }
        advanceUntilIdle()

        assertEquals(null, player.videoSizeFlow.value)
        player.release()
    }
}
