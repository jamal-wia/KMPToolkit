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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The release contract from [VideoPlayer]'s KDoc: release once, release twice, use afterwards, and
 * cancel a load half-way — the paths that leak a decoder or crash a screen during teardown when they
 * are wrong, and that no happy-path test observes.
 */
class VideoPlayerLifecycleTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/clip.mp4")

    private fun TestScope.newPlayer(
        engine: RecordingVideoPlaybackEngine,
        config: VideoPlayerConfig = VideoPlayerConfig(),
    ): VideoPlayer = createVideoPlayer(
        engine = engine,
        config = config,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `release frees the engine and resets every source flow`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply { buffered = 4_000L }
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        requireNotNull(engine.listener).onVideoSizeChanged(VideoSize(1920, 1080))
        requireNotNull(engine.listener).onBufferingChanged(true)
        player.release()

        assertEquals(1, engine.releaseCount)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        assertEquals(0L, player.bufferedPositionFlow.value)
        assertEquals(false, player.isBufferingFlow.value)
        assertNull(player.videoSizeFlow.value)
    }

    @Test
    fun `release disposes the platform player once and after releasing the engine`() = runTest {
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.release()
        player.release()

        assertEquals(1, engine.disposeCount)
        assertEquals(listOf("release", "dispose"), engine.calls.takeLast(2))
    }

    @Test
    fun `a player never prepared still releases and disposes its engine`() = runTest {
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.release()

        assertEquals(1, engine.releaseCount)
        assertEquals(1, engine.disposeCount)
    }

    @Test
    fun `release detaches the engine listener`() = runTest {
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.release()

        assertNull(engine.listener)
    }

    @Test
    fun `releasing twice frees the engine exactly once`() = runTest {
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.release()
        player.release()
        player.release()

        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun `close is release`() = runTest {
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.close()
        player.close()

        assertEquals(1, engine.releaseCount)
        assertEquals(1, engine.disposeCount)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
    }

    @Test
    fun `use closes the player`() = runTest {
        val engine = RecordingVideoPlaybackEngine()

        newPlayer(engine).use { player: VideoPlayer -> player.prepare(source) }

        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun `release stops the polling coroutine`() = runTest {
        val engine = RecordingVideoPlaybackEngine(duration = 10_000L)
        val player: VideoPlayer = newPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L))

        player.prepare(source)
        player.play()
        player.release()
        engine.position = 5_000L
        engine.buffered = 6_000L
        advanceTimeBy(10_000L)

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        assertEquals(0L, player.bufferedPositionFlow.value)
    }

    @Test
    fun `transport calls after release are ignored`() = runTest {
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.release()
        val calls: Int = engine.calls.size

        player.play()
        player.pause()
        player.stop()
        player.seekTo(1_000L)
        player.seekForward()
        player.seekBackward()
        player.replay()
        player.unload()

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(calls, engine.calls.size)
    }

    @Test
    fun `prepare after release reports a released error and loads nothing`() = runTest {
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.release()
        player.prepare(source)

        val state: VideoPlayerState = player.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertIs<VideoPlayerReleasedException>(state.cause)
        assertTrue(engine.loadedSources.isEmpty())
    }

    @Test
    fun `late engine callbacks after release do not resurrect the player`() = runTest {
        val engine = RecordingVideoPlaybackEngine(duration = 10_000L)
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        val listener: VideoPlaybackEngineListener = requireNotNull(engine.listener)
        player.release()
        listener.onCompleted()
        listener.onFailed(IllegalStateException("too late"))
        listener.onBufferingChanged(true)
        listener.onVideoSizeChanged(VideoSize(640, 480))

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        assertEquals(false, player.isBufferingFlow.value)
        assertNull(player.videoSizeFlow.value)
    }

    @Test
    fun `cancelling prepare frees the engine and returns to idle`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply { loadDelayMs = 1_000L }
        val player: VideoPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)

        loading.cancel()
        loading.join()

        assertTrue(loading.isCancelled)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(1, engine.releaseCount)
        assertEquals(0, engine.disposeCount, "a cancelled load is not the end of the player")
        assertEquals(0, engine.activeLoads)
    }

    @Test
    fun `cancelling prepare drops a picture size the load reported`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply {
            loadDelayMs = 1_000L
            sizeReportedWhileLoading = VideoSize(1920, 1080)
        }
        val player: VideoPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        assertEquals(VideoSize(1920, 1080), player.videoSizeFlow.value)
        loading.cancel()
        loading.join()

        assertNull(player.videoSizeFlow.value)
    }

    @Test
    fun `a player cancelled mid-load can prepare again`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply { loadDelayMs = 1_000L }
        val player: VideoPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        loading.cancel()
        loading.join()

        engine.loadDelayMs = 0L
        player.prepare(source)

        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        assertEquals(2, engine.loadedSources.size)
    }

    @Test
    fun `releasing during a load leaves the player idle and released`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply { loadDelayMs = 1_000L }
        val player: VideoPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        player.release()
        advanceUntilIdle()
        loading.join()

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertFalse(player.stateFlow.value.isPlayable)
        assertEquals(1, engine.releaseCount)
        assertEquals(1, engine.disposeCount)
        assertEquals(0, engine.activeLoads)
    }

    @Test
    fun `release during an unwinding load disposes only once the load has unwound`() = runTest {
        val engine = RecordingVideoPlaybackEngine().apply {
            loadDelayMs = 1_000L
            unwindDelayMs = 500L
        }
        val player: VideoPlayer = newPlayer(engine)

        launch { player.prepare(source) }
        runCurrent()
        player.release()
        runCurrent()
        assertEquals(0, engine.releaseCount)
        assertEquals(0, engine.disposeCount)

        advanceUntilIdle()

        assertEquals(listOf("load", "release", "dispose"), engine.calls)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
    }
}
