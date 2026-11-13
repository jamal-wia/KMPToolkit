package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 7: a JavaFX application calls the player from the FX application thread (a button handler), which is the
 * main way this engine is used. The engine's `FxThread.post` then runs a block inline when nothing is queued, so
 * the order of the native calls differs from the order every round-5 and round-6 test produced from a plain
 * thread: `start()` runs inline and queues its nudge, and the shared player's `setSpeed` right after it queues
 * behind the nudge instead of replacing it. The same scenarios as round 6 (a speed changed while paused or
 * completed, then play), driven from the FX thread: the playhead must move on, at the speed asked for.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxFxThreadCallerR7AdversarialTest {

    private fun onFxThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        Platform.runLater {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        assertTrue(done.await(10L, TimeUnit.SECONDS), "the FX thread did not run the block")
        failure?.let { throw it }
    }

    private fun measure(engine: JavaFxVideoEngine, player: VideoPlayer, from: Long, trial: Int, label: String, failures: MutableList<String>) {
        // The playhead must pass from + 600 ms within 3.5 s (or the clip completes), at about the speed of two.
        val until: Long = System.nanoTime() + 3_500_000_000L
        while (System.nanoTime() < until && engine.positionMs() < from + 600L && player.stateFlow.value !is VideoPlayerState.Completed) Thread.sleep(5L)
        if (engine.positionMs() < from + 600L && player.stateFlow.value !is VideoPlayerState.Completed) {
            failures += "trial $trial ($label): stood at ${engine.positionMs()} from $from"
            return
        }
        if (player.stateFlow.value is VideoPlayerState.Completed) return
        fun nextChange(bound: Long): Pair<Long, Long> {
            val first: Long = engine.positionMs()
            val limit: Long = System.nanoTime() + bound * 1_000_000L
            while (System.nanoTime() < limit) {
                val now: Long = engine.positionMs()
                if (now != first) return System.nanoTime() to now
                Thread.sleep(5L)
            }
            return System.nanoTime() to engine.positionMs()
        }
        val (t0: Long, p0: Long) = nextChange(400L)
        Thread.sleep(450L)
        val (t1: Long, p1: Long) = nextChange(250L)
        val slope: Double = (p1 - p0).toDouble() / ((t1 - t0) / 1_000_000.0)
        if (player.stateFlow.value is VideoPlayerState.Completed) return
        if (slope < 1.5 || slope > 3.0) failures += "trial $trial ($label): the playhead moved $slope ms per ms, expected about 2"
        val rate: Double? = runBlocking { engine.inspectPlayback()?.rate }
        if (rate != 2.0) failures += "trial $trial ($label): the rate property reads $rate, expected 2.0"
    }

    @Test
    fun `a speed changed while paused then play, called from the FX thread, plays on at that speed`() {
        assumeJavaFxMedia()
        val failures: MutableList<String> = mutableListOf()
        repeat(TRIALS) { trial: Int ->
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            val player: VideoPlayer = createVideoPlayer(engine)
            try {
                runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                player.play()
                awaitTrue(message = { "trial $trial: never moved" }) { engine.positionMs() > 300L }
                player.pause()
                awaitTrue(message = { "trial $trial: never paused" }) { player.stateFlow.value is VideoPlayerState.Paused }
                Thread.sleep(250L)
                val paused: Long = engine.positionMs()
                onFxThread {
                    player.setPlaybackSpeed(2f)
                    player.play()
                }
                measure(engine, player, paused, trial, "paused", failures)
            } finally {
                player.release()
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS trials")
    }

    @Test
    fun `a speed changed after completion at another speed then replay, called from the FX thread, plays at the new speed`() {
        assumeJavaFxMedia()
        val failures: MutableList<String> = mutableListOf()
        repeat(TRIALS) { trial: Int ->
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            val player: VideoPlayer = createVideoPlayer(engine)
            try {
                runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                player.setPlaybackSpeed(4f)
                player.play()
                awaitTrue(timeoutMs = 6_000L, message = { "trial $trial: never completed at 4x" }) { player.stateFlow.value is VideoPlayerState.Completed }
                onFxThread {
                    player.setPlaybackSpeed(2f)
                    player.replay()
                }
                measure(engine, player, 0L, trial, "completed", failures)
            } finally {
                player.release()
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS trials")
    }

    @Test
    fun `play, speed and seek called back to back from the FX thread keep every call`() {
        assumeJavaFxMedia()
        val failures: MutableList<String> = mutableListOf()
        repeat(TRIALS) { trial: Int ->
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            val player: VideoPlayer = createVideoPlayer(engine)
            try {
                runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                onFxThread {
                    player.setPlaybackSpeed(2f)
                    player.seekTo(1_000L)
                    player.play()
                }
                val until: Long = System.nanoTime() + 4_000_000_000L
                var lowest: Long = Long.MAX_VALUE
                while (System.nanoTime() < until && player.stateFlow.value !is VideoPlayerState.Completed) {
                    lowest = minOf(lowest, engine.positionMs())
                    Thread.sleep(5L)
                }
                if (lowest < 900L) failures += "trial $trial: a seek to 1000 made before play() on the FX thread left the playhead at $lowest"
                if (player.stateFlow.value !is VideoPlayerState.Completed) failures += "trial $trial: ended in ${player.stateFlow.value} at ${engine.positionMs()}"
            } finally {
                player.release()
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS trials")
    }

    private companion object {
        val TRIALS: Int = System.getProperty("kmptoolkit.test.r7.trials")?.toIntOrNull() ?: 8
    }
}
