package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 6 (platform differential): the scenario script of `Media3PlatformDifferentialR6AdversarialTest`
 * (kmptoolkit-video-player, androidHostTest, the real Media3 engine over Media3's test ExoPlayer), step for step,
 * over the fake engine. Same trace builder, same literal: a difference between the two engines in anything the
 * player publishes is a red test in one of them. The fake does not move the playhead or end a source on its own,
 * so the test says where a platform would: "reach the end" is `completePlayback()`.
 */
class VideoPlayerPlatformDifferentialR6AdversarialTest {

    private val engine: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(durationMs = LONG_MS)
    private val trace: MutableList<String> = mutableListOf()
    private val sourceA: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val sourceShort: VideoSource = VideoSource.Remote("https://example.test/short.mp4")
    private val sourceBroken: VideoSource = VideoSource.Remote("https://example.test/broken.mp4")
    private val brokenCause: IllegalStateException = IllegalStateException("the source would not build")

    private val player: VideoPlayer = createVideoPlayer(
        engine = engine,
        config = VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS),
        coroutineContext = Dispatchers.Unconfined,
    )

    private fun prepare(source: VideoSource) {
        runBlocking { withTimeout(BOUND_MS) { player.prepare(source) } }
    }

    /**
     * One line of the trace. [showBuffering] is off right after a seek that nothing has waited out: Media3 buffers
     * briefly for a seek (05-platform-notes, "a seek buffers briefly too") and the fake never buffers.
     */
    private fun note(label: String, exactPosition: Boolean = true, showBuffering: Boolean = true) {
        val state: VideoPlayerState = player.stateFlow.value
        val position: String = if (exactPosition) {
            player.playbackPositionFlow.value.toString()
        } else {
            val duration: Long = state.duration ?: 0L
            if (player.playbackPositionFlow.value in 0L..duration) "in-range" else "OUT-OF-RANGE(${player.playbackPositionFlow.value} of $duration)"
        }
        val shown: String = if (exactPosition) state.toString() else state::class.simpleName.orEmpty()
        trace += "$label | $shown | pos=$position | buffering=${if (showBuffering) player.isBufferingFlow.value.toString() else "n/a"} | speed=${player.playbackSpeedFlow.value}"
    }

    private fun reachTheEnd() {
        engine.completePlayback()
    }

    @Test
    fun `the scenario script produces the trace the Media3 engine produces`() {
        try {
            engine.durationMs = LONG_MS
            prepare(sourceA)
            note("prepare")

            player.seekTo(-1L)
            note("seekTo(-1) in Ready")
            player.seekTo(Long.MAX_VALUE)
            note("seekTo(MAX) in Ready")
            player.seekTo(2_000L)
            note("seekTo(2000) in Ready")
            player.seekBackward(Long.MIN_VALUE)
            note("seekBackward(MIN) in Ready")
            player.seekForward(Long.MAX_VALUE)
            note("seekForward(MAX) in Ready")

            player.play()
            note("play from the end of a Ready player", exactPosition = false)

            for (speed: Float in listOf(Float.NaN, 0f, -3f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.5f, 100f)) {
                player.setPlaybackSpeed(speed)
                note("setPlaybackSpeed($speed) while Playing", exactPosition = false)
            }

            player.pause()
            note("pause", exactPosition = false)
            player.seekTo(2_000L)
            note("seekTo(2000) in Paused")
            player.seekForward(10_000L)
            note("seekForward(10000) in Paused")
            player.seekBackward(Long.MAX_VALUE)
            note("seekBackward(MAX) in Paused")
            player.seekBackward(Long.MIN_VALUE)
            note("seekBackward(MIN) in Paused")
            player.seekTo(0L)
            player.pause()
            note("pause while Paused", showBuffering = false)

            engine.durationMs = SHORT_MS
            prepare(sourceShort)
            note("prepare of a shorter source")
            player.play()
            reachTheEnd()
            note("end of media")
            player.pause()
            note("pause in Completed")
            player.seekForward(1_000L)
            note("seekForward(1000) in Completed")
            player.stop()
            note("stop")
            player.play()
            reachTheEnd()
            player.seekTo(1_000L)
            note("seekTo(1000) in Completed")
            player.replay()
            note("replay", exactPosition = false, showBuffering = false)
            player.stop()
            note("stop in Playing")

            player.setPlaybackSpeed(1f)
            player.play()
            engine.durationMs = LONG_MS
            prepare(sourceA)
            note("source swap while Playing")
            player.unload()
            note("unload")
            player.play()
            player.pause()
            player.seekTo(1_000L)
            player.seekForward(1_000L)
            player.stop()
            player.replay()
            note("every transport call in Idle")

            engine.loadFailure = brokenCause
            prepare(sourceBroken)
            engine.loadFailure = null
            val failed: VideoPlayerState = player.stateFlow.value
            trace += "failing prepare | ${(failed as? VideoPlayerState.Error)?.cause?.message}"
            engine.durationMs = LONG_MS
            prepare(sourceA)
            note("prepare after a failure")
            player.release()
            note("release")
            player.play()
            player.seekTo(500L)
            engine.durationMs = LONG_MS
            prepare(sourceA)
            val refused: VideoPlayerState = player.stateFlow.value
            trace += "prepare after release | ${(refused as? VideoPlayerState.Error)?.cause is VideoPlayerReleasedException}"

            assertEquals(EXPECTED_TRACE, trace, "the fake engine's trace differs from Media3's")
        } finally {
            player.release()
        }
    }

    private companion object {
        const val LONG_MS: Long = 360_000_000L
        const val SHORT_MS: Long = 2_000L
        const val ONE_HOUR_MS: Long = 3_600_000L
        const val BOUND_MS: Long = 10_000L

        val EXPECTED_TRACE: List<String> = listOf(
            "prepare | Ready(duration=360000000) | pos=0 | buffering=false | speed=1.0",
            "seekTo(-1) in Ready | Ready(duration=360000000) | pos=0 | buffering=false | speed=1.0",
            "seekTo(MAX) in Ready | Ready(duration=360000000) | pos=360000000 | buffering=false | speed=1.0",
            "seekTo(2000) in Ready | Ready(duration=360000000) | pos=2000 | buffering=false | speed=1.0",
            "seekBackward(MIN) in Ready | Ready(duration=360000000) | pos=360000000 | buffering=false | speed=1.0",
            "seekForward(MAX) in Ready | Ready(duration=360000000) | pos=360000000 | buffering=false | speed=1.0",
            "play from the end of a Ready player | Playing | pos=in-range | buffering=false | speed=1.0",
            "setPlaybackSpeed(NaN) while Playing | Playing | pos=in-range | buffering=false | speed=1.0",
            "setPlaybackSpeed(0.0) while Playing | Playing | pos=in-range | buffering=false | speed=0.25",
            "setPlaybackSpeed(-3.0) while Playing | Playing | pos=in-range | buffering=false | speed=0.25",
            "setPlaybackSpeed(Infinity) while Playing | Playing | pos=in-range | buffering=false | speed=3.0",
            "setPlaybackSpeed(-Infinity) while Playing | Playing | pos=in-range | buffering=false | speed=0.25",
            "setPlaybackSpeed(1.5) while Playing | Playing | pos=in-range | buffering=false | speed=1.5",
            "setPlaybackSpeed(100.0) while Playing | Playing | pos=in-range | buffering=false | speed=3.0",
            "pause | Paused | pos=in-range | buffering=false | speed=3.0",
            "seekTo(2000) in Paused | Paused(duration=360000000, currentPosition=2000) | pos=2000 | buffering=false | speed=3.0",
            "seekForward(10000) in Paused | Paused(duration=360000000, currentPosition=12000) | pos=12000 | buffering=false | speed=3.0",
            "seekBackward(MAX) in Paused | Paused(duration=360000000, currentPosition=0) | pos=0 | buffering=false | speed=3.0",
            "seekBackward(MIN) in Paused | Paused(duration=360000000, currentPosition=360000000) | pos=360000000 | buffering=false | speed=3.0",
            "pause while Paused | Paused(duration=360000000, currentPosition=0) | pos=0 | buffering=n/a | speed=3.0",
            "prepare of a shorter source | Ready(duration=2000) | pos=0 | buffering=false | speed=3.0",
            "end of media | Completed(duration=2000) | pos=2000 | buffering=false | speed=3.0",
            "pause in Completed | Completed(duration=2000) | pos=2000 | buffering=false | speed=3.0",
            "seekForward(1000) in Completed | Paused(duration=2000, currentPosition=2000) | pos=2000 | buffering=false | speed=3.0",
            "stop | Ready(duration=2000) | pos=0 | buffering=false | speed=3.0",
            "seekTo(1000) in Completed | Paused(duration=2000, currentPosition=1000) | pos=1000 | buffering=false | speed=3.0",
            "replay | Playing | pos=in-range | buffering=n/a | speed=3.0",
            "stop in Playing | Ready(duration=2000) | pos=0 | buffering=false | speed=3.0",
            "source swap while Playing | Ready(duration=360000000) | pos=0 | buffering=false | speed=1.0",
            "unload | Idle | pos=0 | buffering=false | speed=1.0",
            "every transport call in Idle | Idle | pos=0 | buffering=false | speed=1.0",
            "failing prepare | the source would not build",
            "prepare after a failure | Ready(duration=360000000) | pos=0 | buffering=false | speed=1.0",
            "release | Idle | pos=0 | buffering=false | speed=1.0",
            "prepare after release | true",
        )
    }
}
