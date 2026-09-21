package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [FakeVideoPlaybackEngine] is only worth shipping if a real [VideoPlayer] built on it behaves the way
 * the module documents. So these tests drive it the way a consumer would — through the player —
 * rather than asserting that its setters stored what they were given.
 */
class FakeVideoPlaybackEngineTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/lesson.mp4")

    private fun TestScope.newPlayer(engine: FakeVideoPlaybackEngine): VideoPlayer = createVideoPlayer(
        engine = engine,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `a player on a fresh fake reaches ready with the configured duration`() = runTest {
        val engine = FakeVideoPlaybackEngine(durationMs = 30_000L)
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)

        assertEquals(VideoPlayerState.Ready(30_000L), player.stateFlow.value)
        assertEquals(listOf(source), engine.loadedSources)
        assertTrue(engine.hasListener)
        player.release()
    }

    @Test
    fun `loadFailure turns every prepare into an error until cleared`() = runTest {
        val failure = IllegalArgumentException("no such host")
        val engine = FakeVideoPlaybackEngine().apply { loadFailure = failure }
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        val state: VideoPlayerState = player.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertSame(failure, state.cause)

        engine.loadFailure = null
        player.prepare(source)
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        player.release()
    }

    @Test
    fun `a suspended load holds the player in preparing until finishLoad`() = runTest {
        val engine = FakeVideoPlaybackEngine().apply { suspendLoads = true }
        val player: VideoPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
        assertTrue(engine.isLoadPending)

        engine.finishLoad()
        loading.join()

        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        assertFalse(engine.isLoadPending)
        player.release()
    }

    @Test
    fun `failLoad fails a suspended load with that exact throwable`() = runTest {
        val failure = IllegalStateException("403")
        val engine = FakeVideoPlaybackEngine().apply { suspendLoads = true }
        val player: VideoPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        engine.failLoad(failure)
        loading.join()

        val state: VideoPlayerState = player.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertSame(failure, state.cause)
        player.release()
    }

    @Test
    fun `cancelling a suspended load clears it`() = runTest {
        val engine = FakeVideoPlaybackEngine().apply { suspendLoads = true }
        val player: VideoPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        loading.cancel()
        loading.join()

        assertFalse(engine.isLoadPending)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(1, engine.releaseCount)
        player.release()
    }

    @Test
    fun `loadDelayMs holds the player in preparing for that long`() = runTest {
        val engine = FakeVideoPlaybackEngine().apply { loadDelayMs = 1_000L }
        val player: VideoPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        advanceTimeBy(999L)
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)

        advanceTimeBy(2L)
        loading.join()
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        player.release()
    }

    @Test
    fun `cancelling a prepare inside the load delay frees the engine and settles on idle`() = runTest {
        val engine = FakeVideoPlaybackEngine().apply { loadDelayMs = 1_000L }
        val player: VideoPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        advanceTimeBy(500L)
        loading.cancel()
        loading.join()

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(1, engine.releaseCount)
        assertEquals(listOf(source), engine.loadedSources)

        // And the player is reusable afterwards.
        engine.loadDelayMs = 0L
        player.prepare(source)
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        player.release()
    }

    @Test
    fun `a prepare replacing one inside the load delay wins`() = runTest {
        val engine = FakeVideoPlaybackEngine().apply { loadDelayMs = 1_000L }
        val player: VideoPlayer = newPlayer(engine)
        val second: VideoSource = VideoSource.Remote("https://example.test/second.mp4")

        val first: Job = launch { player.prepare(source) }
        advanceTimeBy(500L)
        engine.durationMs = 20_000L
        val replacing: Job = launch { player.prepare(second) }
        advanceTimeBy(500L)
        // Replacing cancelled the first load's delay; the second is still inside its own.
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)

        advanceTimeBy(501L)
        first.join()
        replacing.join()
        assertEquals(VideoPlayerState.Ready(20_000L), player.stateFlow.value)
        assertEquals(listOf(source, second), engine.loadedSources)
        // The replaced caller returns normally rather than being cancelled.
        assertFalse(first.isCancelled)
        player.release()
    }

    @Test
    fun `finishLoad and failLoad without a pending load do nothing`() {
        val engine = FakeVideoPlaybackEngine()

        engine.finishLoad()
        engine.failLoad(IllegalStateException("nobody is waiting"))

        assertFalse(engine.isLoadPending)
    }

    @Test
    fun `transport and settings are visible on the fake`() = runTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        assertTrue(engine.isPlaying)

        player.setPlaybackSpeed(1.5f)
        player.setVolume(0.4f)
        player.setRepeatMode(RepeatMode.One)
        player.seekTo(2_000L)
        assertEquals(1.5f, engine.appliedSpeed)
        assertEquals(0.4f, engine.appliedVolume)
        assertTrue(engine.isLooping)
        assertEquals(listOf(2_000L), engine.seekTargets)

        player.setMuted(true)
        assertEquals(0f, engine.appliedVolume)

        player.pause()
        assertFalse(engine.isPlaying)
        player.release()
    }

    @Test
    fun `advancing the playhead and the buffer is picked up by the next poll`() = runTest {
        val engine = FakeVideoPlaybackEngine(durationMs = 30_000L)
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        engine.advancePositionBy(4_000L)
        engine.bufferedPositionMs = 12_000L
        advanceTimeBy(251L)

        assertEquals(4_000L, player.playbackPositionFlow.value)
        assertEquals(12_000L, player.bufferedPositionFlow.value)
        player.release()
    }

    @Test
    fun `advancePositionBy never runs past a known duration`() {
        val engine = FakeVideoPlaybackEngine(durationMs = 1_000L)

        engine.advancePositionBy(5_000L)

        assertEquals(1_000L, engine.positionMs)
    }

    @Test
    fun `advancePositionBy runs freely when the duration is unknown`() {
        val engine = FakeVideoPlaybackEngine(durationMs = 0L)

        engine.advancePositionBy(5_000L)

        assertEquals(5_000L, engine.positionMs)
    }

    @Test
    fun `completePlayback drives the player to completed`() = runTest {
        val engine = FakeVideoPlaybackEngine(durationMs = 30_000L)
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        engine.completePlayback()

        assertEquals(VideoPlayerState.Completed(30_000L), player.stateFlow.value)
        assertEquals(30_000L, player.playbackPositionFlow.value)
        assertFalse(engine.isPlaying)
    }

    @Test
    fun `completePlayback on a looping fake starts over instead of completing`() = runTest {
        val engine = FakeVideoPlaybackEngine(durationMs = 30_000L)
        val player: VideoPlayer = newPlayer(engine)

        player.setRepeatMode(RepeatMode.One)
        player.prepare(source)
        player.play()
        engine.advancePositionTo(29_900L)
        engine.completePlayback()
        advanceTimeBy(251L)

        assertEquals(VideoPlayerState.Playing(duration = 30_000L, currentPosition = 0L), player.stateFlow.value)
        assertEquals(0L, engine.positionMs)
        player.release()
    }

    @Test
    fun `failPlayback drives the player to error`() = runTest {
        val failure = IllegalStateException("network lost")
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        engine.failPlayback(failure)

        val state: VideoPlayerState = player.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertSame(failure, state.cause)
    }

    @Test
    fun `reportBuffering and reportVideoSize reach the player`() = runTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        engine.reportBuffering(true)
        engine.reportVideoSize(VideoSize(1280, 720))

        assertTrue(player.isBufferingFlow.value)
        assertEquals(VideoSize(1280, 720), player.videoSizeFlow.value)

        engine.reportBuffering(false)
        assertFalse(player.isBufferingFlow.value)
        player.release()
    }

    @Test
    fun `videoSizeOnLoad is reported while preparing`() = runTest {
        val engine = FakeVideoPlaybackEngine().apply { videoSizeOnLoad = VideoSize(720, 1280) }
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)

        assertEquals(VideoSize(720, 1280), player.videoSizeFlow.value)
        player.release()
    }

    @Test
    fun `release is counted once and detaches the fake`() = runTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.release()
        player.release()

        assertEquals(1, engine.releaseCount)
        assertFalse(engine.hasListener)
    }

    @Test
    fun `dispose is recorded once on release and never on unload`() = runTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.unload()
        player.prepare(source)
        assertEquals(0, engine.disposeCount)

        player.release()
        player.release()

        assertEquals(1, engine.disposeCount)
    }

    @Test
    fun `events after release are ignored`() = runTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        player.release()
        engine.completePlayback()
        engine.failPlayback(IllegalStateException("too late"))
        engine.reportBuffering(true)
        engine.reportVideoSize(VideoSize(10, 10))

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertFalse(player.isBufferingFlow.value)
        assertEquals(null, player.videoSizeFlow.value)
    }

    @Test
    fun `a load resets the playhead and the buffer`() = runTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)

        player.prepare(source)
        engine.advancePositionTo(5_000L)
        engine.bufferedPositionMs = 8_000L
        player.prepare(source)

        assertEquals(0L, engine.positionMs)
        assertEquals(0L, player.bufferedPositionFlow.value)
        player.release()
    }
}
