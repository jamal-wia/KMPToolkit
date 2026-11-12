package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 7, mutation survivors of the round 5 and 6 speed work, through the shared player (the order an app
 * produces: `engine.start()`, then `engine.setSpeed(speed)`):
 *
 * - a seek made while paused is the playhead after the next play at a changed speed: the speed work must not
 *   seek the native player back to where it stood before the seek;
 * - the speed given last wins over the speed an earlier play left in the engine;
 * - a pause right after a play at a speed holds the playhead, and the next play moves on.
 *
 * Every wait is bounded. The clip has a key frame at every whole second, so seek targets are whole seconds.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSpeedAndSeekOrderR7AdversarialTest {

    private fun newPlayer(): Pair<JavaFxVideoEngine, VideoPlayer> {
        assumeJavaFxMedia()
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        return engine to createVideoPlayer(engine)
    }

    private fun prepare(player: VideoPlayer) {
        runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
        assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "state ${player.stateFlow.value}")
    }

    private fun awaitState(player: VideoPlayer, message: String, timeoutMs: Long = 8_000L, predicate: (VideoPlayerState) -> Boolean) =
        awaitTrue(timeoutMs = timeoutMs, message = { "$message (state ${player.stateFlow.value})" }) { predicate(player.stateFlow.value) }

    @Test
    fun `a seek made while paused is not undone by the next play at a changed speed`() {
        val failures: MutableList<String> = mutableListOf()
        repeat(TRIALS) { trial: Int ->
            val (engine, player) = newPlayer()
            try {
                prepare(player)
                player.play()
                awaitTrue(message = { "trial $trial: never moved" }) { engine.positionMs() > 500L }
                player.pause()
                awaitState(player, "trial $trial: never paused") { it is VideoPlayerState.Paused }
                Thread.sleep(200L)
                player.setPlaybackSpeed(2f)
                player.seekTo(2_000L)
                player.play()
                val lowest: Long = sampleLowestUntilCompleted(engine, player, 4_500L)
                if (lowest < 1_800L) failures += "trial $trial: after a seek to 2000 while paused and a play at 2x the playhead read $lowest"
                if (player.stateFlow.value !is VideoPlayerState.Completed) failures += "trial $trial: ended in ${player.stateFlow.value}"
            } finally {
                player.release()
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS trials")
    }

    @Test
    fun `a seek made right after play at a changed speed still wins`() {
        val failures: MutableList<String> = mutableListOf()
        repeat(TRIALS) { trial: Int ->
            val (engine, player) = newPlayer()
            try {
                prepare(player)
                player.setPlaybackSpeed(2f)
                player.play()
                player.seekTo(2_000L)
                val lowest: Long = sampleLowestUntilCompleted(engine, player, 4_500L)
                if (lowest < 1_800L) failures += "trial $trial: a seek to 2000 right after play() at 2x left the playhead at $lowest"
                if (player.stateFlow.value !is VideoPlayerState.Completed) failures += "trial $trial: ended in ${player.stateFlow.value}"
            } finally {
                player.release()
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS trials")
    }

    @Test
    fun `the speed given last wins over the speed an earlier play left in the engine`() {
        val failures: MutableList<String> = mutableListOf()
        repeat(TRIALS) { trial: Int ->
            val (engine, player) = newPlayer()
            try {
                prepare(player)
                player.setPlaybackSpeed(4f)
                player.play()
                awaitState(player, "trial $trial: never completed at 4x", 6_000L) { it is VideoPlayerState.Completed }
                player.setPlaybackSpeed(2f)
                player.replay()
                awaitTrue(message = { "trial $trial: the replay never moved" }) { engine.positionMs() in 300L..2_000L }
                val slope: Double = slope(engine, windowMs = 600L)
                val rate: Double? = runBlocking { engine.inspectPlayback()?.rate }
                if (rate != 2.0) failures += "trial $trial: the rate property reads $rate after a replay at a speed changed from 4 to 2"
                if (slope > 3.0 || slope < 1.4) failures += "trial $trial: the playhead moved $slope ms per ms after a replay at a speed of 2"
            } finally {
                player.release()
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS trials")
    }

    @Test
    fun `a pause right after a play at a speed holds the playhead and the next play moves on`() {
        val failures: MutableList<String> = mutableListOf()
        repeat(TRIALS) { trial: Int ->
            val (engine, player) = newPlayer()
            try {
                prepare(player)
                player.setPlaybackSpeed(4f)
                player.play()
                awaitState(player, "trial $trial: never playing") { it is VideoPlayerState.Playing }
                player.pause()
                awaitState(player, "trial $trial: never paused") { it is VideoPlayerState.Paused }
                Thread.sleep(300L)
                val held: Long = engine.positionMs()
                Thread.sleep(500L)
                val later: Long = engine.positionMs()
                if (later - held > 150L) failures += "trial $trial: a paused player's playhead moved from $held to $later"
                player.play()
                val until: Long = System.nanoTime() + 3_500_000_000L
                while (System.nanoTime() < until && engine.positionMs() < held + 600L && player.stateFlow.value !is VideoPlayerState.Completed) Thread.sleep(10L)
                if (engine.positionMs() < held + 600L && player.stateFlow.value !is VideoPlayerState.Completed) {
                    failures += "trial $trial: after pause at $held and a second play the playhead stood at ${engine.positionMs()}"
                }
            } finally {
                player.release()
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS trials")
    }

    /** Polls the engine's playhead until the player completes or [boundMs] passes; the lowest reading seen. */
    private fun sampleLowestUntilCompleted(engine: JavaFxVideoEngine, player: VideoPlayer, boundMs: Long): Long {
        val until: Long = System.nanoTime() + boundMs * 1_000_000L
        var lowest: Long = Long.MAX_VALUE
        while (System.nanoTime() < until && player.stateFlow.value !is VideoPlayerState.Completed) {
            lowest = minOf(lowest, engine.positionMs())
            Thread.sleep(5L)
        }
        return lowest
    }

    /**
     * Media milliseconds per real millisecond over a window whose two edges are timed at a change of the playhead
     * (JavaFX publishes it in steps of about 100 ms; see ruling D-22).
     */
    private fun slope(engine: JavaFxVideoEngine, windowMs: Long): Double {
        fun nextChange(bound: Long): Pair<Long, Long> {
            val first: Long = engine.positionMs()
            val until: Long = System.nanoTime() + bound * 1_000_000L
            while (System.nanoTime() < until) {
                val now: Long = engine.positionMs()
                if (now != first) return System.nanoTime() to now
                Thread.sleep(5L)
            }
            return System.nanoTime() to engine.positionMs()
        }
        val (t0: Long, p0: Long) = nextChange(400L)
        Thread.sleep(windowMs)
        val (t1: Long, p1: Long) = nextChange(250L)
        return (p1 - p0).toDouble() / ((t1 - t0) / 1_000_000.0)
    }

    private companion object {
        val TRIALS: Int = System.getenv("KMPTOOLKIT_R7_TRIALS")?.toIntOrNull() ?: 6
    }
}
