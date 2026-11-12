package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 9, the S13 family swept as a whole: a JavaFX application calls the player from the FX application thread
 * (a click handler) at ANY point of the playback state machine, mixed with calls from other threads, speeds included.
 * Round 7 drove three fixed sequences from the FX thread and round 6's fuzz gave up speeds after it found freezes.
 *
 * A seeded random sequence of player calls, each made on the FX thread or on the test thread and followed by a
 * random pause (0 to 900 ms, so every moment of the start-up latency and of the rate nudges is hit). After the
 * sequence the player must not be silently stuck: a final `replay()` at a speed of two, made from the FX thread,
 * completes the clip, and a player left `Playing` moves its playhead within a few seconds. The checks are edge based
 * (the JavaFX playhead steps in about 100 ms), never a slope.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxFxThreadSequenceFuzzR9AdversarialTest {

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
        check(done.await(15L, TimeUnit.SECONDS)) { "the FX thread did not run the block" }
        failure?.let { throw it }
    }

    private fun call(player: VideoPlayer, rnd: Random, log: MutableList<String>, onFx: Boolean, name: String, op: (VideoPlayer) -> Unit) {
        log += (if (onFx) "fx:" else "t:") + name
        if (onFx) onFxThread { op(player) } else op(player)
    }

    private fun step(player: VideoPlayer, rnd: Random, log: MutableList<String>) {
        val onFx: Boolean = rnd.nextInt(100) < 65
        when (rnd.nextInt(14)) {
            0, 1 -> call(player, rnd, log, onFx, "play") { it.play() }
            2 -> call(player, rnd, log, onFx, "pause") { it.pause() }
            3 -> call(player, rnd, log, onFx, "stop") { it.stop() }
            4 -> call(player, rnd, log, onFx, "replay") { it.replay() }
            5, 6 -> {
                val target: Long = rnd.nextLong(0L, 3_100L)
                call(player, rnd, log, onFx, "seekTo($target)") { it.seekTo(target) }
            }
            7 -> call(player, rnd, log, onFx, "seekForward") { it.seekForward(rnd.nextLong(100L, 2_000L)) }
            8 -> call(player, rnd, log, onFx, "seekBackward") { it.seekBackward(rnd.nextLong(100L, 2_000L)) }
            9, 10, 11 -> {
                val speed: Float = listOf(0.5f, 1f, 1.5f, 2f, 3f)[rnd.nextInt(5)]
                call(player, rnd, log, onFx, "speed($speed)") { it.setPlaybackSpeed(speed) }
            }
            12 -> {
                val volume: Float = rnd.nextInt(0, 11) / 10f
                call(player, rnd, log, onFx, "volume($volume)") { it.setVolume(volume) }
            }
            else -> call(player, rnd, log, onFx, "muted") { it.setMuted(rnd.nextBoolean()) }
        }
        val pauseMs: Long = listOf(0L, 0L, 0L, 1L, 5L, 20L, 80L, 150L, 400L, 900L)[rnd.nextInt(10)]
        if (pauseMs > 0L) Thread.sleep(pauseMs)
    }

    private fun runSequence(seed: Int, steps: Int, stuckBoundMs: Long, label: String): String? {
        val rnd = Random(seed)
        val log: MutableList<String> = mutableListOf()
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        val player: VideoPlayer = createVideoPlayer(engine)
        try {
            runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.AUDIO_ASSET)) } }
            repeat(steps) { step(player, rnd, log) }
            val tail: String = log.takeLast(8).joinToString(" ")
            // 1. A player left Playing must move on: its playhead changes, or it leaves Playing, within the bound.
            val state: VideoPlayerState = player.stateFlow.value
            if (state is VideoPlayerState.Playing) {
                val first: Long = engine.positionMs()
                val until: Long = System.nanoTime() + stuckBoundMs * 1_000_000L
                var moved = false
                while (System.nanoTime() < until) {
                    if (player.stateFlow.value !is VideoPlayerState.Playing || engine.positionMs() != first) {
                        moved = true
                        break
                    }
                    Thread.sleep(5L)
                }
                // A Playing player with a muted/low volume is still playing: the playhead must change.
                if (!moved) return "$label seed $seed: Playing with the playhead standing at $first for $stuckBoundMs ms after [$tail] (speed ${player.playbackSpeedFlow.value})"
            }
            // 2. A final replay at a speed of two from the FX thread completes the clip.
            player.setRepeatMode(RepeatMode.Off)
            onFxThread {
                player.setPlaybackSpeed(2f)
                player.replay()
            }
            val until: Long = System.nanoTime() + 14_000_000_000L
            while (System.nanoTime() < until && player.stateFlow.value !is VideoPlayerState.Completed) Thread.sleep(10L)
            if (player.stateFlow.value !is VideoPlayerState.Completed) {
                return "$label seed $seed: the final replay at 2x never completed within 14 s: ${player.stateFlow.value}, engine position ${engine.positionMs()}, after [$tail]"
            }
            return null
        } finally {
            player.release()
        }
    }

    @Test
    fun `random player calls from the FX thread and from a plain thread never leave the player stuck`() {
        assumeJavaFxMedia()
        val from: Int = System.getenv("KMPTOOLKIT_R9_SEED_FROM")?.toIntOrNull() ?: 1
        val count: Int = System.getenv("KMPTOOLKIT_R9_SEEDS")?.toIntOrNull() ?: 5
        val failures: List<String> = (from until from + count).mapNotNull { seed: Int -> runSequence(seed, steps = 22, stuckBoundMs = 3_500L, label = "single") }
        assertEquals(emptyList(), failures, "of $count seeds from $from")
    }

    @Test
    fun `three players driven at once, calls from the FX thread among them, are not left stuck`() {
        assumeJavaFxMedia()
        val from: Int = System.getenv("KMPTOOLKIT_R9_SEED_FROM")?.toIntOrNull() ?: 1
        val rounds: Int = System.getenv("KMPTOOLKIT_R9_PARALLEL_ROUNDS")?.toIntOrNull() ?: 2
        val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        repeat(rounds) { round: Int ->
            val threads: List<Thread> = (0 until 3).map { index: Int ->
                Thread {
                    runSequence(1_000 + from + round * 3 + index, steps = 18, stuckBoundMs = 5_000L, label = "parallel")?.let { failures += it }
                }.apply { isDaemon = true; start() }
            }
            threads.forEach { it.join(120_000L) }
            threads.forEach { check(!it.isAlive) { "a fuzz thread did not finish within 120 s" } }
        }
        assertEquals(emptyList(), failures.toList(), "of $rounds rounds of three players")
    }
}
