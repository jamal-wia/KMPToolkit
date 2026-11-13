package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
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
 * Product finding D2-PF1: on macOS (AVFoundation) a player that is Playing can stand still at the position of a native
 * seek when a speed change reaches the native player right after that seek (`replay()` or `seekTo()` on a playing
 * clip). The player keeps reporting Playing, its playhead never moves again, and nothing kicks it.
 *
 * Each round loads the video clip afresh, plays it for a moment, then calls `replay()` twice and `setPlaybackSpeed()`
 * with no wait between the calls (a click handler on the FX thread, or a plain thread with gaps of 0 to 5 ms). The
 * contract: a playing player's playhead moves (or the player leaves Playing) within a few seconds, whatever speed was
 * given. The check is edge based (the JavaFX playhead steps in about 100 ms), never a slope. The control test leaves a
 * pause of 400 ms or more before the speed change and holds the same contract.
 *
 * The first three tests (replay rounds from a clip playing at 1x) are controls: they were green on the code before the
 * fix and stay green. The reproducers are the two recorded-sequence tests (`a stop then play then speed then double
 * replay then speed sequence ...` and `the recorded seed three call sequence ...`): the freeze needs the preceding
 * stop, play and speed of 3.0 state, and a plain replay at 1x does not reproduce it. Both were red in 10 of 10 runs on
 * the code before the fix.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSpeedAfterSeekFreezeAdversarialTest {

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

    /** Null when the playhead moved (or the player left Playing) within [boundMs], else a description of the freeze. */
    private fun frozenAt(engine: JavaFxVideoEngine, player: VideoPlayer, boundMs: Long): String? {
        val first: Long = engine.positionMs()
        val until: Long = System.nanoTime() + boundMs * 1_000_000L
        while (System.nanoTime() < until) {
            if (player.stateFlow.value !is VideoPlayerState.Playing || engine.positionMs() != first) return null
            Thread.sleep(5L)
        }
        return "Playing with the playhead standing at $first for $boundMs ms (speed ${player.playbackSpeedFlow.value})"
    }

    private fun rounds(variant: String, seedBase: Int, drive: (VideoPlayer, Random) -> Unit): List<String> {
        val count: Int = System.getenv("KMPTOOLKIT_SPEED_FREEZE_ROUNDS")?.toIntOrNull() ?: 30
        val failures: MutableList<String> = mutableListOf()
        for (round: Int in 0 until count) {
            val rnd = Random(seedBase + round)
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            val player: VideoPlayer = createVideoPlayer(engine)
            try {
                runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                player.play()
                Thread.sleep(300L)
                drive(player, rnd)
                frozenAt(engine, player, boundMs = 2_000L)?.let { failures += "$variant round $round: $it" }
            } finally {
                // Not waiting for the code under test: the player is let run for a moment before it is released. A
                // video player disposed right after its first frames can hit the OpenJFX CoreVideo display-link race
                // that kills the JVM (docs/kmptoolkit-video-player-javafx/05-platform-notes.md, "Known issue"); this
                // is the documented mitigation, a real-time pause for that native-crash workaround only.
                Thread.sleep(300L)
                player.release()
            }
        }
        return failures
    }

    private fun speed(rnd: Random): Float = listOf(0.5f, 1.5f, 2f, 3f)[rnd.nextInt(4)]

    @Test
    fun `a speed given right after a replay of a playing clip on the FX thread never freezes the playhead`() {
        assumeJavaFxMedia()
        val failures: List<String> = rounds("fx", seedBase = 100) { player: VideoPlayer, rnd: Random ->
            val target: Float = speed(rnd)
            onFxThread {
                player.replay()
                player.replay()
                player.setPlaybackSpeed(target)
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `a speed given right after a replay of a playing clip from a plain thread never freezes the playhead`() {
        assumeJavaFxMedia()
        val failures: List<String> = rounds("thread", seedBase = 200) { player: VideoPlayer, rnd: Random ->
            val target: Float = speed(rnd)
            player.replay()
            Thread.sleep(rnd.nextLong(0L, 6L))
            player.replay()
            Thread.sleep(rnd.nextLong(0L, 6L))
            player.setPlaybackSpeed(target)
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `a speed given long after a replay of a playing clip never freezes the playhead`() {
        assumeJavaFxMedia()
        val failures: List<String> = rounds("settled", seedBase = 300) { player: VideoPlayer, rnd: Random ->
            val target: Float = speed(rnd)
            onFxThread {
                player.replay()
                player.replay()
            }
            Thread.sleep(400L + rnd.nextLong(0L, 300L))
            player.setPlaybackSpeed(target)
        }
        assertEquals(emptyList(), failures)
    }

    /** One call of a recorded sequence: made on the FX thread or on the test thread, then followed by a pause. */
    private class Op(val onFx: Boolean, val pauseMs: Long, val run: (VideoPlayer) -> Unit)

    private fun fx(pauseMs: Long, run: (VideoPlayer) -> Unit): Op = Op(onFx = true, pauseMs = pauseMs, run = run)

    private fun thread(pauseMs: Long, run: (VideoPlayer) -> Unit): Op = Op(onFx = false, pauseMs = pauseMs, run = run)

    /** Plays [ops] on a fresh player [reps] times; a round fails when the player is left Playing with a standing playhead. */
    private fun recorded(variant: String, reps: Int, ops: List<Op>): List<String> {
        val failures: MutableList<String> = mutableListOf()
        for (round: Int in 0 until reps) {
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            val player: VideoPlayer = createVideoPlayer(engine)
            try {
                runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                for (op: Op in ops) {
                    if (op.onFx) onFxThread { op.run(player) } else op.run(player)
                    if (op.pauseMs > 0L) Thread.sleep(op.pauseMs)
                }
                frozenAt(engine, player, boundMs = 3_500L)?.let { failures += "$variant round $round: $it" }
            } finally {
                // Not waiting for the code under test: the player is let run for a moment before it is released. A
                // video player disposed right after its first frames can hit the OpenJFX CoreVideo display-link race
                // that kills the JVM (docs/kmptoolkit-video-player-javafx/05-platform-notes.md, "Known issue"); this
                // is the documented mitigation, a real-time pause for that native-crash workaround only.
                Thread.sleep(300L)
                player.release()
            }
        }
        return failures
    }

    /** The tail of the sequence that the random fuzz found frozen (seed 3): a play, a stop and a play, 3x, two replays, 1.5x. */
    private fun stopPlaySpeedReplayTail(): List<Op> = listOf(
        fx(400L) { it.play() },
        thread(80L) { it.stop() },
        fx(150L) { it.play() },
        thread(150L) { it.setPlaybackSpeed(3.0f) },
        fx(5L) { it.replay() },
        fx(0L) { it.replay() },
        thread(80L) { it.setMuted(true) },
        thread(1L) { it.setPlaybackSpeed(1.5f) },
    )

    @Test
    fun `a stop then play then speed then double replay then speed sequence never freezes the playhead`() {
        assumeJavaFxMedia()
        assertEquals(emptyList(), recorded("tail", reps = 12, ops = stopPlaySpeedReplayTail()))
    }

    /** All 22 calls of the fuzz class's seed 3, with its own pauses, copied without the randomness. */
    @Test
    fun `the recorded seed three call sequence of the fuzz never freezes the playhead`() {
        assumeJavaFxMedia()
        val ops: List<Op> = listOf(
            fx(1L) { it.stop() },
            fx(900L) { it.pause() },
            fx(0L) { it.setMuted(true) },
            fx(20L) { it.setMuted(false) },
            fx(0L) { it.seekBackward(1320L) },
            thread(0L) { it.setVolume(0.3f) },
            fx(0L) { it.play() },
            thread(20L) { it.setVolume(0.0f) },
            fx(0L) { it.seekTo(1696L) },
            thread(150L) { it.seekTo(1064L) },
            fx(1L) { it.seekBackward(305L) },
            fx(150L) { it.setPlaybackSpeed(1.0f) },
            fx(900L) { it.setMuted(false) },
            thread(150L) { it.pause() },
        ) + stopPlaySpeedReplayTail()
        assertEquals(emptyList(), recorded("seed3", reps = 8, ops = ops))
    }
}
