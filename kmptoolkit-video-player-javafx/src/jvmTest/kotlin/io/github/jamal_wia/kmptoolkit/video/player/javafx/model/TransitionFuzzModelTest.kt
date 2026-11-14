package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.random.Random
import kotlin.test.Test

/**
 * The seeded transition fuzzer of the JavaFX model tier (task J13a): the F twin, in virtual time on the composed and watched
 * scripted player ([composeAll]), of `JavaFxOperationSequenceFuzzR6AdversarialTest` (B2 of the design): seeded random
 * sequences of player calls (play, pause, stop, replay, seeks of every kind, volume, mute, unload and prepare again),
 * each followed by a random wait, with the state after every call checked against the documented transition table.
 *
 * THE STEP TABLE IS THE REAL CLASS'S, draw for draw (`Random(seed)` in the same order, the same `seed % 3 == 0` looping
 * seeds, 26 operations, the same waits of 0 to 259 ms, now virtual), so seed N here is the call sequence of seed N there;
 * seeds 1 to 2000 (`-Pkmptoolkit.test.seed=N` replays one; `-Pkmptoolkit.test.fuzz.seeds=K` runs K). Like the real class it
 * gives no speed (the speeds are the sequence fuzzer's, [SequenceFuzzModelTest]) and calls from the test thread only.
 *
 * THE MODEL. Every player (a new one after every `unload` and `prepare`) is composed and watched with the hazard monitor in
 * [MonitorMode.FAIL_FAST]; every seed runs on a host with the jitter ON ([FuzzModelSupport.JITTER_MAX_MS], seeded from the
 * seed). The time part is the plain one: the real class reads the published playhead and the state only, and the load
 * profile is the sequence fuzzer's.
 *
 * THE CHECKS, the real class's, exact where the real class could only sample:
 * - after every call the state is one the transition table allows given the state just before it (a natural end may land in
 *   between, so a Playing player may also read Completed); a seek on a paused player is published at once, exactly;
 * - a looping source never reads Completed;
 * - a Playing player whose source is not at its end moves on: its published playhead changes within 2500 virtual ms (the
 *   real class's R-6 bound), never a frozen playhead or a stuck Playing;
 * - at the end of every sequence the player still plays its source to the end and completes within 12 virtual seconds, then
 *   releases (twice) and reads Idle;
 * - and no fragile window is violated (FAIL_FAST, with the last 20 native calls and the virtual times).
 *
 * A mutant that turns this file red (a temporary local patch, see the report of task J13a): the plain `native.play()` of
 * `FxPlayback.start` skipped on a resume after a seek past 1500 ms (243 of 2000 seeds red). The speed mutants M-PF1 and M-S13 are
 * the sequence fuzzer's, because this class gives no speed.
 */
class TransitionFuzzModelTest {

    private fun kind(state: VideoPlayerState): String = state::class.simpleName.orEmpty()

    /** One seed, in its own model: the seed's failure (with the trace), or null. */
    private fun runSeed(seed: Int): Pair<Int, String>? {
        val trace = StringBuilder()
        try {
            fxModel(seed = seed.toLong(), jitterMaxMs = FuzzModelSupport.JITTER_MAX_MS) {
                val backend: ComposedBackend = composeAll(ScriptedBackend(host), mode = MonitorMode.FAIL_FAST)
                val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
                val player: VideoPlayer = videoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 50L))
                var failure: Throwable? = null
                try {
                    runOperations(seed, engine, player, trace)
                } catch (t: Throwable) {
                    val traced = AssertionError("${t.message}\nsequence: $trace", t)
                    failure = traced
                    throw traced
                } finally {
                    try {
                        player.release()
                        player.release()
                        advanceBy(50)
                    } catch (cleanup: Throwable) {
                        if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                    }
                }
                if (player.stateFlow.value != VideoPlayerState.Idle) {
                    throw AssertionError("not Idle after release: ${player.stateFlow.value}; seed $seed\nsequence: $trace")
                }
            }
            return null
        } catch (t: Throwable) {
            return seed to FuzzModelSupport.describe(seed, t)
        }
    }

    private fun FxModelScope.runOperations(seed: Int, engine: JavaFxVideoEngine, player: VideoPlayer, trace: StringBuilder) {
        val random = Random(seed)
        val looping: Boolean = seed % 3 == 0
        trace.append("repeat=${if (looping) "One" else "Off"}; ")
        fun nowMs(): Long = host.elapsedNanos / NANOS_PER_MILLI
        fun where(): String = "seed $seed; trace: $trace; state ${player.stateFlow.value}; engine position ${engine.positionMs()}"
        val source = VideoSource.Asset(TestClip.ASSET)
        fuzzPrepare(player, source)
        player.setRepeatMode(if (looping) RepeatMode.One else RepeatMode.Off)
        var lastMoveNanos: Long = host.elapsedNanos
        var lastPosition: Long = -1L

        fun watch(ms: Long) {
            val end: Long = host.elapsedNanos + ms * NANOS_PER_MILLI
            while (host.elapsedNanos < end) {
                val state: VideoPlayerState = player.stateFlow.value
                if (looping && state is VideoPlayerState.Completed) throw AssertionError("a looping source reported Completed; ${where()}")
                val position: Long = engine.positionMs()
                if (position != lastPosition || state !is VideoPlayerState.Playing) {
                    lastPosition = position
                    lastMoveNanos = host.elapsedNanos
                }
                if (state is VideoPlayerState.Playing && position < TestClip.DURATION_MS - 300L &&
                    host.elapsedNanos - lastMoveNanos > FREEZE_BOUND_MS * NANOS_PER_MILLI
                ) {
                    throw AssertionError("Playing for $FREEZE_BOUND_MS virtual ms with the playhead standing at $position of ${TestClip.DURATION_MS}; ${where()}")
                }
                advanceBy(WATCH_STEP_MS)
            }
        }

        fun call(name: String, allowed: (before: VideoPlayerState, after: VideoPlayerState) -> Boolean, action: () -> Unit) {
            val before: VideoPlayerState = player.stateFlow.value
            trace.append("@").append(nowMs()).append(" ").append(name).append(" ")
            action()
            val after: VideoPlayerState = player.stateFlow.value
            if (!allowed(before, after)) throw AssertionError("$name took ${kind(before)} to ${kind(after)}; ${where()}")
            lastMoveNanos = host.elapsedNanos
            watch(random.nextLong(0L, 260L))
        }

        repeat(OPERATIONS) {
            when (random.nextInt(14)) {
                0, 1 -> call("play", { b, a ->
                    when (b) {
                        is VideoPlayerState.Ready, is VideoPlayerState.Paused, is VideoPlayerState.Completed, is VideoPlayerState.Playing ->
                            a is VideoPlayerState.Playing || a is VideoPlayerState.Completed
                        else -> a == b
                    }
                }) { player.play() }

                2, 3 -> call("pause", { b, a ->
                    when (b) {
                        is VideoPlayerState.Playing -> a is VideoPlayerState.Paused || a is VideoPlayerState.Completed
                        else -> kind(a) == kind(b)
                    }
                }) { player.pause() }

                4 -> call("stop", { _, a -> a is VideoPlayerState.Ready }) { player.stop() }

                5, 6 -> {
                    val target: Long = random.nextLong(-50L, TestClip.DURATION_MS + 200L)
                    val clamped: Long = target.coerceIn(0L, TestClip.DURATION_MS)
                    call("seekTo($target)", { b, a ->
                        when (b) {
                            is VideoPlayerState.Paused -> a is VideoPlayerState.Paused && a.currentPosition == clamped
                            is VideoPlayerState.Ready -> a is VideoPlayerState.Ready
                            is VideoPlayerState.Completed -> a is VideoPlayerState.Paused || a is VideoPlayerState.Completed
                            is VideoPlayerState.Playing -> a is VideoPlayerState.Playing || a is VideoPlayerState.Completed
                            else -> a == b
                        }
                    }) { player.seekTo(target) }
                }

                7 -> {
                    val amount: Long = random.nextLong(1L, 2_000L)
                    call("seekForward($amount)", { b, a -> kind(b) == kind(a) || a is VideoPlayerState.Completed || a is VideoPlayerState.Paused && b is VideoPlayerState.Completed }) {
                        player.seekForward(amount)
                    }
                }

                8 -> {
                    val amount: Long = random.nextLong(1L, 2_000L)
                    call("seekBackward($amount)", { b, a -> kind(b) == kind(a) || a is VideoPlayerState.Completed || a is VideoPlayerState.Paused && b is VideoPlayerState.Completed }) {
                        player.seekBackward(amount)
                    }
                }

                9 -> call("replay", { b, a ->
                    if (b.run { this is VideoPlayerState.Ready || this is VideoPlayerState.Playing || this is VideoPlayerState.Paused || this is VideoPlayerState.Completed }) {
                        a is VideoPlayerState.Playing || a is VideoPlayerState.Completed
                    } else {
                        a == b
                    }
                }) { player.replay() }

                10 -> call("seekTo(0)", { b, a ->
                    when (b) {
                        is VideoPlayerState.Paused -> a is VideoPlayerState.Paused && a.currentPosition == 0L
                        is VideoPlayerState.Ready -> a is VideoPlayerState.Ready
                        is VideoPlayerState.Completed -> a is VideoPlayerState.Paused || a is VideoPlayerState.Completed
                        is VideoPlayerState.Playing -> a is VideoPlayerState.Playing || a is VideoPlayerState.Completed
                        else -> a == b
                    }
                }) { player.seekTo(0L) }

                11 -> {
                    val volume: Float = listOf(0f, 0.3f, 1f).random(random)
                    call("volume($volume)", { _, _ -> true }) { player.setVolume(volume) }
                }

                12 -> call("mute", { _, _ -> true }) { player.setMuted(random.nextBoolean()) }

                else -> call("unload+prepare", { _, a -> a is VideoPlayerState.Ready }) {
                    player.unload()
                    fuzzPrepare(player, source)
                }
            }
        }

        // The player must still play its source to the end: no stuck Playing, no frozen playhead.
        trace.append("final ")
        player.setRepeatMode(RepeatMode.Off)
        player.setMuted(true)
        player.replay()
        var waited = 0L
        while (waited < FINAL_BOUND_MS && player.stateFlow.value !is VideoPlayerState.Completed) {
            advanceBy(FINAL_STEP_MS)
            waited += FINAL_STEP_MS
        }
        if (player.stateFlow.value !is VideoPlayerState.Completed) {
            throw AssertionError("a replay never completed within ${FINAL_BOUND_MS / 1_000L} virtual s; ${where()}")
        }
    }

    @Test
    fun randomCallSequencesOnTheModelKeepToTheTransitionTableAndNeverFreeze() {
        val seeds: List<Int> = FuzzModelSupport.seeds()
        val failures: List<Pair<Int, String>> = seeds.mapNotNull { runSeed(it) }
        FuzzModelSupport.assertNoFailures("the R6 transition fuzz on the model", seeds.size, failures)
    }

    private companion object {
        const val OPERATIONS: Int = 26
        const val FREEZE_BOUND_MS: Long = 2_500L
        const val WATCH_STEP_MS: Long = 20L
        const val FINAL_BOUND_MS: Long = 12_000L
        const val FINAL_STEP_MS: Long = 25L
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
