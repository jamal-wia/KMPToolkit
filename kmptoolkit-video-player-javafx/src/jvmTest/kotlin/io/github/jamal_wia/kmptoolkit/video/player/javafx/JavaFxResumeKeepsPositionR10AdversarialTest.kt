package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 10: `pause()` then `play()` resumes from the paused position (04-api-reference: Paused -> Playing, "Suspended at
 * currentPosition"; only Completed or a playhead on the end starts over from 0). Found by the position-edges class: after
 * a pause at 401 ms and a speed set while paused, the resume read 0 and played the clip from the start.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxResumeKeepsPositionR10AdversarialTest {

    private fun trial(label: String, speedWhilePlaying: Float?, speedWhilePaused: Float?, pausedForMs: Long): String? {
        val player: VideoPlayer = createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 15_000L), VideoPlayerConfig(positionUpdateIntervalMs = 25L))
        try {
            runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
            player.play()
            awaitTrue(5_000L, { "never advanced" }) { player.playbackPositionFlow.value > 1_500L }
            if (speedWhilePlaying != null) {
                player.setPlaybackSpeed(speedWhilePlaying)
                Thread.sleep(150L)
            }
            player.pause()
            val at: Long = (player.stateFlow.value as VideoPlayerState.Paused).currentPosition
            Thread.sleep(pausedForMs)
            if (speedWhilePaused != null) player.setPlaybackSpeed(speedWhilePaused)
            player.play()
            var lowest: Long = Long.MAX_VALUE
            val until: Long = System.nanoTime() + 600L * 1_000_000L
            while (System.nanoTime() < until) {
                val p: Long = player.playbackPositionFlow.value
                if (p > 0L || System.nanoTime() > until - 450L * 1_000_000L) lowest = minOf(lowest, p)
                Thread.sleep(10L)
            }
            val first: Long = player.playbackPositionFlow.value
            // keyframe snapping may move the resume point a little back; a restart from 0 is something else
            return if (first + 300L < at) "$label: paused at $at, 600 ms after the resume the playhead reads $first (lowest $lowest)" else null
        } finally {
            player.release()
        }
    }

    @Test
    fun `a resume after a pause continues from the paused position whatever speed was set meanwhile`() {
        assumeJavaFxMedia()
        val failures: MutableList<String> = mutableListOf()
        var runs = 0
        for ((whilePlaying: Float?, whilePaused: Float?) in listOf(null to null, null to 0.5f, null to 2f, 2f to 0.5f, 2f to null, 0.5f to 1f)) {
            repeat(4) { n: Int ->
                runs++
                trial("speeds playing=$whilePlaying paused=$whilePaused #$n", whilePlaying, whilePaused, 400L)?.let { failures += it }
            }
        }
        assertEquals(emptyList(), failures, "of $runs resumes")
    }
}
