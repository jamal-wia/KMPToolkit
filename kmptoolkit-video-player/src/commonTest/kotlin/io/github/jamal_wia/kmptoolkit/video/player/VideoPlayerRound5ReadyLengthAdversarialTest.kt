package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Ready.duration` is "total length". A length the platform learns after the load (the same late
 * report round 4 handled for Playing and Paused) must also reach a Ready player the first time it
 * acts on the source.
 */
class VideoPlayerRound5ReadyLengthAdversarialTest {

    @Test
    fun `a seek of a Ready player picks up a length the platform reported after the load`() = runTest {
        val engine = RecordingVideoPlaybackEngine(duration = 0L)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            assertEquals(VideoPlayerState.Ready(0L), player.stateFlow.value)
            engine.duration = 8_000L
            player.seekTo(5_000L)
            assertEquals(VideoPlayerState.Ready(8_000L), player.stateFlow.value, "state after the seek: ${player.stateFlow.value}")
        } finally {
            player.release()
        }
    }
}
