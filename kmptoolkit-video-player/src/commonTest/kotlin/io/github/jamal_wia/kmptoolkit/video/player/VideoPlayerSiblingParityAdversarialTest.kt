package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Behaviours kmptoolkit-audio-player already guarantees for the same state machine (relative skips
 * on an unknown length, play from the end), asked of the video player.
 */
class VideoPlayerSiblingParityAdversarialTest {

    private val source: VideoSource = VideoSource.Asset("clip.mp4")

    private fun playerTest(
        engine: RecordingVideoPlaybackEngine = RecordingVideoPlaybackEngine(),
        body: suspend TestScope.(RecordingVideoPlaybackEngine, VideoPlayer) -> Unit,
    ): TestResult = runTest {
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            body(engine, player)
        } finally {
            player.release()
        }
    }

    @Test
    fun `a relative skip on a source of unknown length does not rewind to the start`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 0L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.position = 30_000L
        player.seekForward(5_000L)
        assertEquals(35_000L, engine.seekTargets.last(), "a live stream's skip-forward went to ${engine.seekTargets.last()}")
        engine.position = 30_000L
        player.seekBackward(5_000L)
        assertEquals(25_000L, engine.seekTargets.last(), "a live stream's skip-back went to ${engine.seekTargets.last()}")
    }

    @Test
    fun `play with the playhead sitting on the end of a paused source starts over from zero`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        player.pause()
        player.seekTo(Long.MAX_VALUE)
        assertEquals(VideoPlayerState.Paused(10_000L, 10_000L), player.stateFlow.value)

        player.play()

        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
        // An engine that sits at the end without completing again (AVPlayer) would play nothing.
        assertEquals(0L, engine.position, "the engine was started from ${engine.position}, at the end")
        assertEquals(0L, player.playbackPositionFlow.value)
    }

    @Test
    fun `play on a ready source whose playhead was moved onto the end starts over from zero`() = playerTest { engine, player ->
        player.prepare(source)
        player.seekTo(10_000L)
        player.play()
        assertEquals(0L, engine.position, "the engine was started from ${engine.position}, at the end")
    }
}
