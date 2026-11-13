package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.random.Random
import kotlin.test.Test

/**
 * The seeded sequence fuzzer of the JavaFX model tier (task J13a): the F twin, in virtual time on the composed and watched
 * scripted player ([composeAll]), of `JavaFxFxThreadSequenceFuzzR9AdversarialTest` (A12 of the design), the real-media fuzz
 * that sweeps the whole S13 family: a JavaFX application calls the shared player from the FX application thread (a click
 * handler) or from another thread, at ANY point of the playback state machine, speeds included.
 *
 * THE STEP TABLE IS THE REAL CLASS'S, draw for draw: `Random(seed)` is drawn in the same order (the caller, the call, the
 * call's own arguments, the pause), so seed N of this class is the call sequence of seed N of the real class, with the
 * pauses (0 to 900 ms) in virtual time. 22 steps, seeds 1 to 2000 (`-Pkmptoolkit.test.seed=N` replays one seed;
 * `-Pkmptoolkit.test.fuzz.seeds=K` runs K).
 *
 * THE CALLERS. A call "from the FX thread" runs in [VirtualFxHost.onFx]: inline, as a click handler does, so a call right
 * after another call finds the queue not empty and is queued behind what the first one left (S13). A call "from another
 * thread" is a plain call of the test thread, queued behind what is due; half of those (a second, independent
 * `Random` decides, so the step table above is untouched) are held back and made by [VirtualFxHost.beforeEachTask] before
 * the 0th to 3rd FX task that runs after it, which is where another thread can really land between two blocks of the
 * engine (between the two hops of a nudge, say).
 *
 * THE MODEL. Every player is composed and watched with the hazard monitor in [MonitorMode.FAIL_FAST]: the call that violates a
 * fragile window throws at once, with the last 20 native calls and the virtual times, and the seed. Every seed runs on a
 * host with the jitter ON ([FuzzModelSupport.JITTER_MAX_MS], design B1, seeded from the seed). Every other seed (the odd
 * ones) also runs the time part in the LOAD profile read AT_PUBLISH (published samples wait 50 to 400 ms and 10 % are dropped,
 * the playhead is read when the publication runs, as JavaFX does), because this fuzz reads the engine's playhead.
 *
 * THE CHECKS, the real class's with the R-5 bounds unchanged (3500 ms), in virtual time and exact where the real class could only
 * sample: (1) no fragile window violated (FAIL_FAST) and the decoder not frozen; (2) a player left Playing moves on: its
 * published playhead changes, or it leaves Playing, within 3500 virtual ms; (3) a final `replay()` at a speed of two, made
 * from the FX thread, completes the clip within 14 virtual seconds.
 *
 * Not ported: the real class's second test (three players driven at once from three threads), which is about three real
 * toolkit players sharing one real FX thread and has no meaning on one virtual queue.
 *
 * Mutants that turn this file red (temporary local patches, see the report of task J13a): M-PF1 (the seek-window branch of
 * `FxPlayback.setSpeed` deleted; 824 of 1970 seeds red) and M-S13 (one `postLater` instead of two in the nudge of
 * `FxPlayback.start`; seed 1814 alone).
 *
 * THE LEDGER. 19 seeds are red on the current production, all of them a product defect (class A: `FxPlayback.pause()` and the
 * non-looping end of media write the held speed before `native.pause()`; class C: a play inside the 200 ms idle gap of a rate
 * written while paused), ruled by the advisor in reviews/fxport-j13a-opus.md (decision D-95) and fixed by task D-pi. They are not
 * ignored and not hidden: they are the ledger [KNOWN_RED] (seed, window, trigger, class, task), the green test leaves exactly those
 * out, and [theKnownRedSeedsStillFailExactlyAsRecorded] runs them and fails when one turns green or fails in another way
 * (`FuzzModelSupport.KnownRed`). The ledger is empty when D-pi merges.
 */
class SequenceFuzzModelTest {

    private class Held(var skipTasks: Int, val name: String, val run: () -> Unit)

    private class Sequence(
        val seed: Int,
        val scope: FxModelScope,
        val backend: ComposedBackend,
        val player: VideoPlayer,
        val engine: JavaFxVideoEngine,
        val random: Random,
        val injectRandom: Random,
    ) {
        val trace: MutableList<String> = mutableListOf()
        val held: MutableList<Held> = mutableListOf()

        private fun nowMs(): Long = scope.host.elapsedNanos / NANOS_PER_MILLI

        /** Runs [op] as the caller says; [name] is what the trace and a failure show. */
        fun call(onFx: Boolean, name: String, op: () -> Unit) {
            if (onFx) {
                trace += "@${nowMs()} fx:$name"
                scope.onFx(op)
                return
            }
            if (injectRandom.nextInt(2) == 0) {
                held += Held(injectRandom.nextInt(4), name) {
                    trace += "@${nowMs()} t(held):$name"
                    op()
                }
            } else {
                trace += "@${nowMs()} t:$name"
                op()
            }
        }

        /** The hook of [VirtualFxHost.beforeEachTask]: makes the held calls whose turn it is. */
        fun beforeTask() {
            if (held.isEmpty()) return
            val due: List<Held> = held.filter { it.skipTasks <= 0 }
            held.forEach { it.skipTasks-- }
            held.removeAll(due)
            due.forEach { it.run() }
        }

        /** Makes every held call now, as a plain call, in the order they were held. */
        fun flush() {
            val rest: List<Held> = held.toList()
            held.clear()
            rest.forEach { it.run() }
        }
    }

    private fun Sequence.step() {
        val onFx: Boolean = random.nextInt(100) < FX_PERCENT
        when (random.nextInt(14)) {
            0, 1 -> call(onFx, "play") { player.play() }
            2 -> call(onFx, "pause") { player.pause() }
            3 -> call(onFx, "stop") { player.stop() }
            4 -> call(onFx, "replay") { player.replay() }
            5, 6 -> {
                val target: Long = random.nextLong(0L, 3_100L)
                call(onFx, "seekTo($target)") { player.seekTo(target) }
            }
            7 -> {
                val amount: Long = random.nextLong(100L, 2_000L)
                call(onFx, "seekForward($amount)") { player.seekForward(amount) }
            }
            8 -> {
                val amount: Long = random.nextLong(100L, 2_000L)
                call(onFx, "seekBackward($amount)") { player.seekBackward(amount) }
            }
            9, 10, 11 -> {
                val speed: Float = listOf(0.5f, 1f, 1.5f, 2f, 3f)[random.nextInt(5)]
                call(onFx, "speed($speed)") { player.setPlaybackSpeed(speed) }
            }
            12 -> {
                val volume: Float = random.nextInt(0, 11) / 10f
                call(onFx, "volume($volume)") { player.setVolume(volume) }
            }
            else -> {
                val muted: Boolean = random.nextBoolean()
                call(onFx, "muted($muted)") { player.setMuted(muted) }
            }
        }
        val pauseMs: Long = listOf(0L, 0L, 0L, 1L, 5L, 20L, 80L, 150L, 400L, 900L)[random.nextInt(10)]
        if (pauseMs > 0L) scope.advanceBy(pauseMs)
    }

    /** One seed, in its own model: the seed's failure (with the trace), or null. */
    private fun runSeed(seed: Int): Pair<Int, String>? {
        val loaded: Boolean = seed % 2 == 1
        try {
            fxModel(seed = seed.toLong(), jitterMaxMs = FuzzModelSupport.JITTER_MAX_MS) {
                val timeScript: TimeScript = TimeScript().apply {
                    if (loaded) load(seed = seed.toLong(), read = SampleRead.AT_PUBLISH)
                }
                val backend: ComposedBackend = composeAll(ScriptedBackend(host), timeScript = timeScript, mode = MonitorMode.FAIL_FAST)
                val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
                val player: VideoPlayer = videoPlayer(engine)
                val sequence = Sequence(seed, this, backend, player, engine, Random(seed), Random(seed.toLong() xor INJECT_SALT))
                var failure: Throwable? = null
                try {
                    fuzzPrepare(player, VideoSource.Asset(TestClip.ASSET))
                    // The player pushes its volume, loop mode and speed after the prepare: let them run, so that an `onFx` block
                    // is the FX thread with nothing queued (the case a click handler is in).
                    advanceBy(0)
                    host.beforeEachTask { sequence.beforeTask() }
                    repeat(STEPS) { sequence.step() }
                    sequence.flush()
                    host.beforeEachTask(null)
                    check(sequence)
                } catch (t: Throwable) {
                    val traced = AssertionError("${t.message}\nsequence: ${sequence.trace.joinToString(" ")}", t)
                    failure = traced
                    throw traced
                } finally {
                    host.beforeEachTask(null)
                    try {
                        player.release()
                        advanceBy(50)
                    } catch (cleanup: Throwable) {
                        if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                    }
                }
            }
            return null
        } catch (t: Throwable) {
            return seed to FuzzModelSupport.describe(seed, t)
        }
    }

    /** The end-of-sequence checks of the real class, in virtual time. */
    private fun FxModelScope.check(sequence: Sequence) {
        val seed: Int = sequence.seed
        val player: VideoPlayer = sequence.player
        val engine: JavaFxVideoEngine = sequence.engine
        val tail: String = sequence.trace.takeLast(8).joinToString(" ")
        val loaded: String = if (seed % 2 == 1) "LOAD AT_PUBLISH" else "plain time"
        val hazards: HazardModel = composedOf(sequence).hazards
        if (hazards.frozen) {
            throw AssertionError("seed $seed ($loaded): the decoder is frozen after [$tail]\n${composedOf(sequence).player.log.tail()}")
        }
        // 1. A player left Playing must move on: its playhead changes, or it leaves Playing, within the bound.
        if (player.stateFlow.value is VideoPlayerState.Playing) {
            val first: Long = engine.positionMs()
            var waited = 0L
            var moved = false
            while (waited < STUCK_BOUND_MS) {
                if (player.stateFlow.value !is VideoPlayerState.Playing || engine.positionMs() != first) {
                    moved = true
                    break
                }
                advanceBy(STUCK_STEP_MS)
                waited += STUCK_STEP_MS
            }
            if (!moved) {
                throw AssertionError(
                    "seed $seed ($loaded): Playing with the playhead standing at $first for $STUCK_BOUND_MS virtual ms after [$tail] " +
                        "(speed ${player.playbackSpeedFlow.value})\n${composedOf(sequence).player.log.tail()}",
                )
            }
        }
        // 2. A final replay at a speed of two from the FX thread completes the clip.
        player.setRepeatMode(RepeatMode.Off)
        onFx {
            player.setPlaybackSpeed(2f)
            player.replay()
        }
        var waited = 0L
        while (waited < FINAL_BOUND_MS && player.stateFlow.value !is VideoPlayerState.Completed) {
            advanceBy(FINAL_STEP_MS)
            waited += FINAL_STEP_MS
        }
        if (player.stateFlow.value !is VideoPlayerState.Completed) {
            throw AssertionError(
                "seed $seed ($loaded): the final replay at 2x never completed within ${FINAL_BOUND_MS / 1_000L} virtual s: " +
                    "${player.stateFlow.value}, engine position ${engine.positionMs()}, after [$tail]\n${composedOf(sequence).player.log.tail()}",
            )
        }
    }

    private fun composedOf(sequence: Sequence): ComposedPlayer = sequence.backend.last

    @Test
    fun randomPlayerCallsFromTheFxThreadAndFromAnotherThreadNeverLeaveThePlayerStuckOrFreezeIt() {
        // A seed named by -Pkmptoolkit.test.seed runs even when it is on the ledger, so a ledger seed can be replayed.
        val replay: Boolean = System.getProperty("kmptoolkit.test.seed") != null
        val seeds: List<Int> = FuzzModelSupport.seeds().filter { replay || it !in KNOWN_RED }
        val failures: List<Pair<Int, String>> = seeds.mapNotNull { runSeed(it) }
        FuzzModelSupport.assertNoFailures("the R9 step table on the model", seeds.size, failures)
    }

    /**
     * The ledger [KNOWN_RED] still holds: every listed seed fails, with the recorded window and trigger. Not ignored: a seed
     * that turns green (its fix landed) or fails in another way fails this test, so the list shrinks only on purpose.
     */
    @Test
    fun theKnownRedSeedsStillFailExactlyAsRecorded() {
        KNOWN_RED.check("the KNOWN_RED seeds of the R9 step table on the model", run = { seed: Int -> runSeed(seed)?.second })
    }

    private companion object {
        /**
         * THE LEDGER (decision D-95, reviews/fxport-j13a-opus.md): the seeds of 1 to 2000 on which the CURRENT production code violates
         * a fragile window of the hazard model, measured on top of D-ns and J12. Each one is a DEFAULT hit (never the strict
         * reading) and a product defect of one task, D-pi. Class A: `FxPlayback.pause()` (and so `stop()`) writes the held settle speed
         * before `native.pause()` while the decoder still runs inside W-PLAY or W-SEEK-RUN; 1424 is the same slip in the non-looping
         * `onEndOfMedia`. Class C: the paused-again write of `giveNudge` does not stamp the idle gap and the next `start()` plays
         * inside it (W-IDLE). An entry leaves by turning green and by no other route but a new advisor ruling; the ledger is empty
         * when D-pi merges and before J15 starts.
         */
        val KNOWN_RED: KnownRed<Int> = KnownRed(
            buildMap {
                val playSetRate = KnownRedEntry("W-PLAY", "SET_RATE", "A", "D-pi")
                val seekRunSetRate = KnownRedEntry("W-SEEK-RUN", "SET_RATE", "A", "D-pi")
                val idlePlay = KnownRedEntry("W-IDLE", "PLAY", "C", "D-pi")
                for (seed: Int in listOf(98, 211, 369, 685, 1002, 1738, 1424)) put(seed, playSetRate)
                for (seed: Int in listOf(471, 1088, 1209, 1287, 1385, 1598, 1819, 1859, 1921)) put(seed, seekRunSetRate)
                for (seed: Int in listOf(467, 943, 1189)) put(seed, idlePlay)
            },
        )

        const val STEPS: Int = 22
        const val FX_PERCENT: Int = 65
        const val STUCK_BOUND_MS: Long = 3_500L
        const val STUCK_STEP_MS: Long = 10L
        const val FINAL_BOUND_MS: Long = 14_000L
        const val FINAL_STEP_MS: Long = 25L
        const val NANOS_PER_MILLI: Long = 1_000_000L

        /** Separates the held-call draws of a seed from its step table, so the step table of seed N is the real class's. */
        const val INJECT_SALT: Long = 0x5EEDL
    }
}
