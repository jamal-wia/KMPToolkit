package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guarantees the headless player shares with `kmptoolkit-audio-player` (clamped initial rate, a late
 * duration, a finite speed range, no stall shown after the end), tested literally with edge inputs.
 * Virtual time, explicit advances only.
 */
class VideoPlayerSiblingGapsAdversarialTest {

    private val source: VideoSource = VideoSource.Asset("clip.mp4")

    private fun playerTest(
        engine: RecordingVideoPlaybackEngine = RecordingVideoPlaybackEngine(),
        config: VideoPlayerConfig = VideoPlayerConfig(),
        body: suspend TestScope.(RecordingVideoPlaybackEngine, VideoPlayer) -> Unit,
    ): TestResult = runTest {
        val player: VideoPlayer = createVideoPlayer(
            engine = engine,
            config = config,
            coroutineContext = StandardTestDispatcher(testScheduler),
        )
        try {
            body(engine, player)
        } finally {
            player.release()
        }
    }

    @Test
    fun `the rate pushed to the engine is always inside the configured range, also the initial one`() = playerTest(
        config = VideoPlayerConfig(minPlaybackSpeed = 2.0f, maxPlaybackSpeed = 3.0f),
    ) { engine, player ->
        // playbackSpeedFlow: "Rate in effect, already clamped to the configured range".
        assertTrue(
            player.playbackSpeedFlow.value in 2.0f..3.0f,
            "initial rate ${player.playbackSpeedFlow.value} is outside the configured 2..3",
        )
        player.prepare(source)
        player.play()
        runCurrent()
        assertTrue(engine.appliedSpeeds.isNotEmpty())
        assertTrue(
            engine.appliedSpeeds.all { it in 2.0f..3.0f },
            "the engine was given ${engine.appliedSpeeds}, outside the configured 2..3",
        )
        assertTrue(player.playbackSpeedFlow.value in 2.0f..3.0f)
    }

    @Test
    fun `a duration the engine reports after playback started replaces the zero of not known yet`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 0L),
    ) { engine, player ->
        player.prepare(source)
        assertEquals(VideoPlayerState.Ready(0L), player.stateFlow.value)
        player.play()
        assertEquals(0L, (player.stateFlow.value as VideoPlayerState.Playing).duration)

        // "a duration of 0 means the platform has not reported one (a live stream, or a header not
        // parsed yet)": the header gets parsed, the length becomes known.
        engine.duration = 60_000L
        engine.position = 5_000L
        advanceTimeBy(1_000L)
        runCurrent()

        val playing: VideoPlayerState.Playing = assertIs(player.stateFlow.value)
        assertEquals(60_000L, playing.duration, "the progress bar would stay at 0 for the whole video")
        assertTrue(player.stateFlow.value.progress > 0f)
    }

    @Test
    fun `a duration reported late shows on pause and on seek as well`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 0L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.duration = 60_000L
        player.pause()
        assertEquals(60_000L, assertIs<VideoPlayerState.Paused>(player.stateFlow.value).duration)
    }

    @Test
    fun `a config with an infinite speed bound is refused`() {
        var refused = 0
        for (max in listOf(Float.POSITIVE_INFINITY)) {
            try {
                VideoPlayerConfig(maxPlaybackSpeed = max)
            } catch (_: IllegalArgumentException) {
                refused++
            }
        }
        try {
            VideoPlayerConfig(minPlaybackSpeed = Float.POSITIVE_INFINITY, maxPlaybackSpeed = Float.POSITIVE_INFINITY)
        } catch (_: IllegalArgumentException) {
            refused++
        }
        assertEquals(2, refused, "an infinite bound lets the engine receive an infinite rate")
    }

    @Test
    fun `buffering is false whenever the player has completed`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        player.setRepeatMode(RepeatMode.Off)
        player.onEngine { onCompleted() }
        assertIs<VideoPlayerState.Completed>(player.stateFlow.value)
        player.onEngine { onBufferingChanged(true) }
        assertFalse(player.isBufferingFlow.value, "isBufferingFlow is documented false whenever completed")
    }

    @Test
    fun `buffering is false after a failure even when the engine reports a stall afterwards`() = playerTest { _, player ->
        player.prepare(source)
        player.play()
        player.onEngine { onFailed(IllegalStateException("boom")) }
        assertIs<VideoPlayerState.Error>(player.stateFlow.value)
        player.onEngine { onBufferingChanged(true) }
        assertFalse(player.isBufferingFlow.value)
        assertNull(player.videoSizeFlow.value)
    }

    @Test
    fun `a known duration never falls back to zero and a seek refreshes a late one`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 0L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.duration = 20_000L
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(20_000L, assertIs<VideoPlayerState.Playing>(player.stateFlow.value).duration)
        // A live stream that flaps back to "unknown" must not reset a progress bar.
        engine.duration = 0L
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(20_000L, assertIs<VideoPlayerState.Playing>(player.stateFlow.value).duration)

        val late = RecordingVideoPlaybackEngine(duration = 0L)
        val second: VideoPlayer = createVideoPlayer(late, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        second.prepare(source)
        second.play()
        late.duration = 8_000L
        second.seekTo(4_000L)
        assertEquals(8_000L, assertIs<VideoPlayerState.Playing>(second.stateFlow.value).duration)
        second.release()
    }

    @Test
    fun `a stall reported after the end is not shown but one after seeking away from the end is`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        player.onEngine { onCompleted() }
        player.onEngine { onBufferingChanged(true) }
        assertFalse(player.isBufferingFlow.value)
        player.seekTo(1_000L)
        player.onEngine { onBufferingChanged(true) }
        assertTrue(player.isBufferingFlow.value)
    }

    private fun VideoPlayer.onEngine(event: VideoPlaybackEngineListener.() -> Unit) {
        val engine: VideoPlaybackEngine = (this as EngineVideoPlayer).engine
        (engine as RecordingVideoPlaybackEngine).listener!!.event()
    }
}
