package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.progress
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Round 7 (breaker hf), Q4(c): the sentences of docs/kmptoolkit-video-player 04-api-reference.md (Flows,
 * prepare, Transport, Settings, Lifecycle, VideoPlayerState, VideoSource, RepeatMode) that no earlier
 * test named, each as one short test over the real player and the fake engine. Positions are driven by a
 * TestCoroutineScheduler (virtual time); `runBlocking` only hosts the suspend calls, which return at once
 * over the fake because it loads without delay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerDocsMatrixR7AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val scheduler = TestCoroutineScheduler()

    private fun player(
        engine: VideoPlaybackEngine,
        config: VideoPlayerConfig = VideoPlayerConfig(positionUpdateIntervalMs = 100L),
    ): VideoPlayer = createVideoPlayer(engine, config, StandardTestDispatcher(scheduler))

    private fun ready(engine: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(5_000L)): Pair<FakeVideoPlaybackEngine, VideoPlayer> {
        val p: VideoPlayer = player(engine)
        runBlocking { p.prepare(source) }
        return engine to p
    }

    @Test
    fun `a new prepare resets the playhead the buffered position the buffering and the picture size`() {
        val (engine: FakeVideoPlaybackEngine, p: VideoPlayer) = ready()
        engine.videoSizeOnLoad = VideoSize(1280, 720)
        engine.bufferedPositionMs = 2_000L
        p.play()
        engine.advancePositionTo(1_500L)
        engine.reportBuffering(true)
        engine.reportVideoSize(VideoSize(640, 360))
        scheduler.advanceTimeBy(300L)
        scheduler.runCurrent()
        assertEquals(1_500L, p.playbackPositionFlow.value)
        assertEquals(true, p.isBufferingFlow.value)

        engine.suspendLoads = true
        val scope = TestScope(StandardTestDispatcher(scheduler))
        val prepare: Job = scope.launch { p.prepare(source) }
        scheduler.runCurrent()
        assertTrue(engine.isLoadPending, "the second load did not start")
        assertEquals(VideoPlayerState.Preparing, p.stateFlow.value)
        assertEquals(0L, p.playbackPositionFlow.value)
        assertEquals(0L, p.bufferedPositionFlow.value)
        assertEquals(false, p.isBufferingFlow.value)
        // The engine reported the new source's size during its load: that one is shown, the old one is gone.
        assertEquals(VideoSize(1280, 720), p.videoSizeFlow.value)
        engine.finishLoad()
        scheduler.runCurrent()
        assertTrue(prepare.isCompleted)
        p.release()
    }

    @Test
    fun `settings change their flows at once with nothing loaded and after release and survive unload`() {
        val engine = FakeVideoPlaybackEngine()
        val p: VideoPlayer = player(engine)
        p.setVolume(0.4f)
        p.setMuted(true)
        p.setRepeatMode(RepeatMode.One)
        p.setPlaybackSpeed(2f)
        assertEquals(0.4f, p.volumeFlow.value)
        assertEquals(true, p.isMutedFlow.value)
        assertEquals(RepeatMode.One, p.repeatModeFlow.value)
        assertEquals(2f, p.playbackSpeedFlow.value)

        runBlocking { p.prepare(source) }
        // Pushed to every newly loaded source: muted volume is 0f, looping on, speed 2.
        assertEquals(0f, engine.appliedVolume)
        assertEquals(true, engine.isLooping)
        assertEquals(2f, engine.appliedSpeed)
        p.unload()
        assertEquals(0.4f, p.volumeFlow.value)
        assertEquals(2f, p.playbackSpeedFlow.value)
        runBlocking { p.prepare(source) }
        assertEquals(2f, engine.appliedSpeed)

        p.release()
        p.setVolume(0.9f)
        p.setPlaybackSpeed(0.5f)
        p.setMuted(false)
        assertEquals(0.9f, p.volumeFlow.value)
        assertEquals(0.5f, p.playbackSpeedFlow.value)
        assertEquals(false, p.isMutedFlow.value)
        assertEquals(0f, engine.appliedVolume, "nothing may reach the engine after the release")
    }

    @Test
    fun `NaN and out of range settings are ignored or clamped like the table says`() {
        val engine = FakeVideoPlaybackEngine()
        val p: VideoPlayer = player(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L, minPlaybackSpeed = 0.5f, maxPlaybackSpeed = 2f))
        p.setPlaybackSpeed(Float.NaN)
        assertEquals(1f, p.playbackSpeedFlow.value)
        p.setPlaybackSpeed(10f)
        assertEquals(2f, p.playbackSpeedFlow.value)
        p.setPlaybackSpeed(Float.NEGATIVE_INFINITY)
        assertEquals(0.5f, p.playbackSpeedFlow.value)
        p.setVolume(Float.NaN)
        assertEquals(1f, p.volumeFlow.value)
        p.setVolume(-3f)
        assertEquals(0f, p.volumeFlow.value)
        p.setVolume(7f)
        assertEquals(1f, p.volumeFlow.value)
        p.release()
    }

    @Test
    fun `a stall reported after the end is ignored and buffering is false on completion and on failure`() {
        val (engine: FakeVideoPlaybackEngine, p: VideoPlayer) = ready()
        p.play()
        engine.reportBuffering(true)
        assertEquals(true, p.isBufferingFlow.value)
        engine.completePlayback()
        assertIs<VideoPlayerState.Completed>(p.stateFlow.value)
        assertEquals(false, p.isBufferingFlow.value)
        engine.reportBuffering(true)
        assertEquals(false, p.isBufferingFlow.value, "a stall after the end must be ignored")

        p.replay()
        engine.reportBuffering(true)
        engine.failPlayback(IOException("boom"))
        assertIs<VideoPlayerState.Error>(p.stateFlow.value)
        assertEquals(false, p.isBufferingFlow.value)
        assertEquals(0L, p.bufferedPositionFlow.value, "zero after a playback failure")
        assertEquals(null, p.videoSizeFlow.value, "the picture size is null after a failure")
        p.release()
    }

    @Test
    fun `the picture size is kept on completed and cleared by unload and release`() {
        val (engine: FakeVideoPlaybackEngine, p: VideoPlayer) = ready()
        engine.reportVideoSize(VideoSize(320, 240))
        p.play()
        engine.completePlayback()
        assertEquals(VideoSize(320, 240), p.videoSizeFlow.value)
        p.unload()
        assertEquals(null, p.videoSizeFlow.value)
        runBlocking { p.prepare(source) }
        engine.reportVideoSize(VideoSize(1, 1))
        p.release()
        assertEquals(null, p.videoSizeFlow.value)
    }

    @Test
    fun `the buffered position is snapshotted at load and on pause and polled while playing`() {
        val engine = FakeVideoPlaybackEngine(5_000L)
        engine.suspendLoads = true
        val p: VideoPlayer = player(engine)
        val scope = TestScope(StandardTestDispatcher(scheduler))
        val prepare: Job = scope.launch { p.prepare(source) }
        scheduler.runCurrent()
        // The fake resets the buffered position when a load starts; the platform fills it while loading.
        engine.bufferedPositionMs = 1_000L
        engine.finishLoad()
        scheduler.runCurrent()
        assertTrue(prepare.isCompleted)
        assertEquals(1_000L, p.bufferedPositionFlow.value)
        engine.suspendLoads = false
        p.play()
        engine.bufferedPositionMs = 3_000L
        scheduler.advanceTimeBy(150L)
        scheduler.runCurrent()
        assertEquals(3_000L, p.bufferedPositionFlow.value)
        engine.bufferedPositionMs = 4_000L
        p.pause()
        assertEquals(4_000L, p.bufferedPositionFlow.value)
        engine.bufferedPositionMs = 4_500L
        scheduler.advanceTimeBy(1_000L)
        scheduler.runCurrent()
        assertEquals(4_000L, p.bufferedPositionFlow.value, "polling must stop while paused")
        p.release()
    }

    @Test
    fun `seekTo in Ready picks up a length the engine learned since the load`() {
        val engine = FakeVideoPlaybackEngine(0L)
        val p: VideoPlayer = player(engine)
        runBlocking { p.prepare(source) }
        assertEquals(VideoPlayerState.Ready(0L), p.stateFlow.value)
        engine.durationMs = 8_000L
        p.seekTo(2_000L)
        assertEquals(VideoPlayerState.Ready(8_000L), p.stateFlow.value)
        assertEquals(2_000L, p.playbackPositionFlow.value)
        p.release()
    }

    @Test
    fun `a player already Completed when One is set stays there until the next play`() {
        val (engine: FakeVideoPlaybackEngine, p: VideoPlayer) = ready()
        p.play()
        engine.completePlayback()
        p.setRepeatMode(RepeatMode.One)
        assertIs<VideoPlayerState.Completed>(p.stateFlow.value)
        p.play()
        assertIs<VideoPlayerState.Playing>(p.stateFlow.value)
        assertEquals(0L, p.playbackPositionFlow.value)
        p.release()
    }

    @Test
    fun `a completion while paused with One rewinds to zero and stays paused and one while Ready is ignored`() {
        val (engine: FakeVideoPlaybackEngine, p: VideoPlayer) = ready()
        p.setRepeatMode(RepeatMode.One)
        engine.rawCompletion()
        assertEquals(VideoPlayerState.Ready(5_000L), p.stateFlow.value)
        p.play()
        engine.advancePositionTo(3_000L)
        p.pause()
        engine.rawCompletion()
        assertIs<VideoPlayerState.Paused>(p.stateFlow.value)
        assertEquals(0L, p.playbackPositionFlow.value)
        p.release()
    }

    /** A completion the platform reports, without the fake's own looping shortcut (it loops silently). */
    private fun FakeVideoPlaybackEngine.rawCompletion() {
        val looping: Boolean = isLooping
        setLooping(false)
        completePlayback()
        setLooping(looping)
    }

    @Test
    fun `release is idempotent and close is the same call and an engine that throws on release still settles`() {
        class ThrowingRelease(val inner: FakeVideoPlaybackEngine) : VideoPlaybackEngine by inner {
            override fun release() {
                inner.release()
                throw IllegalStateException("native release failed")
            }
        }
        val fake = FakeVideoPlaybackEngine(5_000L)
        val p: VideoPlayer = player(ThrowingRelease(fake))
        runBlocking { p.prepare(source) }
        p.play()
        assertFailsWith<IllegalStateException> { p.unload() }
        assertEquals(VideoPlayerState.Idle, p.stateFlow.value, "unload resets to Idle even when the engine throws")
        runBlocking { p.prepare(source) }
        p.play()
        assertFailsWith<IllegalStateException> { p.release() }
        assertEquals(VideoPlayerState.Idle, p.stateFlow.value)
        assertEquals(1, fake.disposeCount, "the engine is disposed even when its release threw")
        p.release()
        p.close()
        assertEquals(1, fake.disposeCount)
        runBlocking { p.prepare(source) }
        val state: VideoPlayerState = p.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertIs<VideoPlayerReleasedException>(state.cause)
    }

    @Test
    fun `an Error from a failed load is the engine's own throwable and progress is never NaN`() {
        val engine = FakeVideoPlaybackEngine(0L)
        val failure = IOException("load failed")
        engine.loadFailure = failure
        val p: VideoPlayer = player(engine)
        runBlocking { p.prepare(source) }
        val state: VideoPlayerState = p.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertSame(failure, state.cause)
        assertEquals(0f, state.progress)
        engine.loadFailure = null
        runBlocking { p.prepare(source) }
        assertEquals(0f, p.stateFlow.value.progress)
        p.release()
    }

    @Test
    fun `a remote source copies its headers prints only their names and compares by value`() {
        val headers: MutableMap<String, String> = mutableMapOf("Authorization" to "Bearer secret-token")
        val remote = VideoSource.Remote("https://example.test/a.mp4", headers)
        headers["Authorization"] = "changed"
        headers["X-Other"] = "x"
        assertEquals(mapOf("Authorization" to "Bearer secret-token"), remote.headers)
        val text: String = remote.toString()
        assertTrue("Authorization" in text, text)
        assertTrue("secret-token" !in text, text)
        assertTrue("changed" !in text, text)
        assertEquals(remote, VideoSource.Remote("https://example.test/a.mp4", mapOf("Authorization" to "Bearer secret-token")))
        assertEquals(remote.hashCode(), VideoSource.Remote("https://example.test/a.mp4", mapOf("Authorization" to "Bearer secret-token")).hashCode())
    }

    @Test
    fun `an invalid configuration throws IllegalArgumentException for every rule`() {
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(positionUpdateIntervalMs = 0L) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(positionUpdateIntervalMs = -1L) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(minPlaybackSpeed = 0f) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(minPlaybackSpeed = 2f, maxPlaybackSpeed = 1f) }
        assertFailsWith<IllegalArgumentException> { VideoSize(0, 10) }
        assertFailsWith<IllegalArgumentException> { VideoSize(10, -1) }
    }
}
