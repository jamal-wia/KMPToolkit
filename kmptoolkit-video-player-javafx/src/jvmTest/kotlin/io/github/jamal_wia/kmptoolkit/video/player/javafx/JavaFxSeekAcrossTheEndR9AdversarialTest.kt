package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 9: the contract of D-16 (Q-B) says that a seek made just as the source reaches its end WINS: "an engine whose
 * platform stops at its end must then resume output from the seek's target (not replay from the start, not ignore it)".
 * The VLC engine got its test in round 5 (the target is checked); the JavaFX test of that round (`JavaFxSeekAtTheEnd`)
 * only seeks to 0 and accepts "completed or playing", so a restart from the START where the target was elsewhere cannot
 * show there. `FxPlayback.start()` seeks to the start of the media when its `atEnd` mark is set; a stale completion makes
 * the player call `start()` again, and the mark is set by an end-of-media handler that can run on the FX thread AFTER
 * the seek block the caller posted. This test seeks to 1000 at random moments around the natural end of a 3 s clip
 * (played from 2.6 s at normal speed) and checks that the playhead is never put back to the start.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSeekAcrossTheEndR9AdversarialTest {

    @Test
    fun `a seek away from the end made around the natural end resumes at the target and not at the start`() {
        assumeJavaFxMedia()
        val seed: Int = System.getenv("KMPTOOLKIT_R9_END_SEED")?.toIntOrNull() ?: 9
        val rnd = Random(seed)
        val failures: MutableList<String> = mutableListOf()
        val outcomes: MutableMap<String, Int> = sortedMapOf()
        repeat(TRIALS) { trial: Int ->
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            val player: VideoPlayer = createVideoPlayer(engine)
            try {
                runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                player.seekTo(2_600L)
                player.play()
                // The end comes 400 ms of playback after the first moving reading; start-up latency adds up to a second.
                val delayMs: Long = rnd.nextLong(0L, 1_500L)
                Thread.sleep(delayMs)
                val before: VideoPlayerState = player.stateFlow.value
                player.seekTo(1_000L)
                val stateAtSeek: String = before::class.simpleName ?: "?"
                val until: Long = System.nanoTime() + 700_000_000L
                var lowest: Long = Long.MAX_VALUE
                var completed = false
                while (System.nanoTime() < until) {
                    lowest = minOf(lowest, engine.positionMs())
                    if (player.stateFlow.value is VideoPlayerState.Completed) completed = true
                    Thread.sleep(2L)
                }
                val after: VideoPlayerState = player.stateFlow.value
                outcomes.merge("$stateAtSeek -> ${after::class.simpleName}", 1, Int::plus)
                if (lowest < 800L || completed) {
                    failures += "trial $trial (seek ${delayMs} ms after play, state then $stateAtSeek): a seek to 1000 left the playhead reading $lowest, completed=$completed, state now $after"
                }
            } finally {
                player.release()
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS trials, outcomes $outcomes")
    }

    private companion object {
        val TRIALS: Int = System.getenv("KMPTOOLKIT_R9_END_TRIALS")?.toIntOrNull() ?: 12
    }
}
