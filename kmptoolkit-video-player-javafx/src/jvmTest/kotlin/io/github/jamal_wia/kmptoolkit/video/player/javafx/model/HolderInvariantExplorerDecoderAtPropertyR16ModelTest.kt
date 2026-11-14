package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxPlayback
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import java.lang.reflect.Field
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * D2 round 16, Q3 (platform breadth): the seeded holder explorer and the three fixed chains of `HolderInvariantExplorerR14ModelTest`, run
 * against the OTHER decoder variant of the hazard part: `HazardConfig(decoderStartsAtUnity = false)`, a platform whose `play()` honours
 * the rate property (the engine's production code has no OS branch: it assumes the macOS fact everywhere, so a Windows or Linux
 * decoder only changes what the native player does with a rate it already holds). The same I1 (one holder), I2 (decoder equals the
 * property when nothing is held) and the oracle (the decoder ends at the last asked speed, no violated window, no freeze) apply.
 * The W-PLAY window (a rate change within 100 ms of a `play()` freezes the playhead) is a measured macOS fact that belongs to the
 * unity-start decoder; a decoder that honours the property is modelled WITHOUT it here (`playWindowMs = 0`). With the window kept, 3 of
 * 20000 seeds violate it (r16-P1, `adversarial/D2/suspects/r16-P1-*`: the away-and-back is given 59 to 91 ms after the play because the
 * clock bound of the movement gauge is `max(1.0, property)`, so a decoder that really runs at the property moves 200 ms of media in
 * 50 ms of wall time at 4x). Everything else is copied from the r14 class; see its KDoc for the generator and the knobs (`-Pkmptoolkit.test.fuzz.seeds=K`,
 * `-Pkmptoolkit.test.seed=N`).
 */
class HolderInvariantExplorerDecoderAtPropertyR16ModelTest {

    private enum class Kind {
        START, PAUSE, SPEED, SEEK, WRAP, END,

        /** `pause()`, a speed, `seekTo` and `start()` in one FX task, which leaves a seek-path play waiting out the idle gap. */
        RESTART,
    }

    /** One operation: [arg] is the speed or the seek target, [gapMs] the virtual time that passes after it. */
    private class Op(val kind: Kind, val arg: Double, val gapMs: Long, val onFx: Boolean) {
        override fun toString(): String {
            val value: String = if (kind == Kind.SPEED || kind == Kind.SEEK || kind == Kind.RESTART) "(${arg.toString().removeSuffix(".0")})" else ""
            return "${if (onFx) "fx:" else "t:"}${kind.name.lowercase()}$value+${when (gapMs) { EDGE_GAP -> "edge"; IDLE_EDGE_GAP -> "idle-edge"; else -> gapMs.toString() }}"
        }
    }

    /** Which read of the playhead the time part uses in a sequence. */
    private enum class TimeProfile { PLAIN, LOAD_AT_TICK, LOAD_AT_PUBLISH }

    // ---- the monitor ----

    private val sessionField: Field = JavaFxVideoEngine::class.java.getDeclaredField("session").apply { isAccessible = true }

    private fun playbackOf(engine: JavaFxVideoEngine): FxPlayback? {
        val session: Any = sessionField.get(engine) ?: return null
        val field: Field = session.javaClass.getDeclaredField("playback").apply { isAccessible = true }
        return field.get(session) as FxPlayback?
    }

    private val holderFields: List<Field> =
        listOf("pendingNudge", "settleNudge", "deferredRate").map { name: String ->
            FxPlayback::class.java.getDeclaredField(name).apply { isAccessible = true }
        }

    /** The holders that are set right now, as `name=value`, or an empty list. */
    private fun setHolders(playback: FxPlayback): List<String> =
        holderFields.mapNotNull { field: Field -> field.get(playback)?.let { value: Any -> "${field.name}=$value" } }

    private val playIssuedField: Field = FxPlayback::class.java.getDeclaredField("playIssued").apply { isAccessible = true }
    private val delayedPlayPendingField: Field = FxPlayback::class.java.getDeclaredField("delayedPlayPending").apply { isAccessible = true }

    /** Whether the engine has handed its native `play()` to the native player: asked for, and not still waiting out a gap. */
    private fun nativePlayIssued(playback: FxPlayback): Boolean =
        playIssuedField.getBoolean(playback) && !delayedPlayPendingField.getBoolean(playback)

    // ---- the driver ----

    private class Trace(val label: String) {
        val lines: MutableList<String> = mutableListOf()
        override fun toString(): String = "$label\nsequence: ${lines.joinToString(" ")}"
    }

    /**
     * Runs [ops] on a fresh engine and the oracle after them; returns the failure text, or null when everything held.
     * [lastAsked] starts at 1.0 (the engine's default speed).
     */
    private fun run(label: String, seed: Long, looping: Boolean, profile: TimeProfile, ops: List<Op>, onePlayPerStart: Boolean = false): String? {
        val trace = Trace(label)
        try {
            fxModel(seed = seed) {
                val timeScript: TimeScript = TimeScript().apply {
                    when (profile) {
                        TimeProfile.PLAIN -> Unit
                        TimeProfile.LOAD_AT_TICK -> load(seed = seed, read = SampleRead.AT_TICK)
                        TimeProfile.LOAD_AT_PUBLISH -> load(seed = seed, read = SampleRead.AT_PUBLISH)
                    }
                }
                val backend: ComposedBackend = composeAll(
                    ScriptedBackend(host, clip = ClipModel(durationMs = CLIP_MS), script = StatusScript()),
                    timeScript = timeScript,
                    hazardConfig = HazardConfig(decoderStartsAtUnity = false, playWindowMs = 0.0),
                )
                val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
                load(engine, VideoSource.Asset(TestClip.ASSET))
                try {
                    explore(this, engine, backend, looping, profile, ops, trace, onePlayPerStart)
                } finally {
                    host.beforeEachTask(null)
                    engine.release()
                    advanceBy(50)
                }
            }
            return null
        } catch (t: Throwable) {
            return "$label: ${t.message}\nsequence: ${trace.lines.joinToString(" ")}" +
                t.suppressed.firstOrNull()?.let { "\n  (also: ${it.message?.lineSequence()?.firstOrNull()})" }.orEmpty() +
                lastNative?.let { "\nnon-snapshot native calls:\n" + it.log.entries.filter { e -> e.call != NativeCall.SNAPSHOT }.takeLast(60).joinToString("\n") { e -> "  $e" } }.orEmpty()
        }
    }

    /** The last native calls without the snapshots of the playhead, which would drown the writes in a message. */
    private var lastNative: ScriptedNativePlayer? = null

    private fun tailOf(native: ScriptedNativePlayer): String =
        native.log.entries.filter { it.call != NativeCall.SNAPSHOT }.takeLast(TAIL_CALLS).joinToString("\n") { "  $it" }

    private fun explore(
        scope: FxModelScope,
        engine: JavaFxVideoEngine,
        backend: ComposedBackend,
        looping: Boolean,
        profile: TimeProfile,
        ops: List<Op>,
        trace: Trace,
        onePlayPerStart: Boolean,
    ) = with(scope) {
        val composed: ComposedPlayer = backend.last
        val native: ScriptedNativePlayer = composed.player
        lastNative = native
        val playback: FxPlayback = checkNotNull(playbackOf(engine)) { "no playback after the load (${host})" }
        var lastAsked = 1.0
        var playing = false
        var step = 0

        fun checkHolders(where: String) {
            val set: List<String> = setHolders(playback)
            if (set.size > 1) {
                throw AssertionError("I1 violated $where (step $step, t=${host.elapsedNanos / NANOS_PER_MS} ms): two holders are set: $set\n${tailOf(native)}")
            }
        }
        fun nowMs(): Double = host.elapsedNanos / NANOS_PER_MS

        // I2 (ruling 4): when no speed is held and the native play has been issued, the decoder runs at the property, once the
        // last play, seek or rate write is 300 ms behind (the decoder takes a write or a start as it comes). The premise of
        // every "decoder equals property" step of the engine; the one path that breaks it on purpose is a plain play() on
        // macOS, which starts at normal speed whatever the property says, with a held 1.0 that gives the speed later.
        fun checkDecoder(where: String) {
            if (setHolders(playback).isNotEmpty() || !nativePlayIssued(playback)) return
            val hazards: HazardModel = composed.hazards
            if (!hazards.decoderRunning) return
            val lastMove: Double = listOf(NativeCall.PLAY, NativeCall.SEEK, NativeCall.SET_RATE)
                .maxOf { call: NativeCall -> native.log.calls(call).lastOrNull()?.tMs ?: Double.NEGATIVE_INFINITY }
            if (nowMs() - lastMove < SETTLED_MS) return
            if (hazards.effectiveRate != native.rate) {
                throw AssertionError(
                    "I2 violated $where (step $step, t=${nowMs().toLong()} ms): no speed is held and the native play was issued, " +
                        "but the decoder runs at ${hazards.effectiveRate} while the property holds ${native.rate}\n${tailOf(native)}",
                )
            }
        }

        engine.setLooping(looping)
        advanceBy(0)
        host.beforeEachTask { _: Int ->
            checkHolders("before an FX task")
            checkDecoder("before an FX task")
        }
        for (op: Op in ops) {
            step++
            if (op.kind == Kind.WRAP) {
                // The earlier operations of this sequence may still be queued (a call from another thread): let them run, so the log has their play and seek.
                advanceBy(0)
                val lastPlay: Double = native.log.calls(NativeCall.PLAY).lastOrNull()?.tMs ?: Double.NEGATIVE_INFINITY
                val lastSeek: Double = native.log.calls(NativeCall.SEEK).lastOrNull()?.tMs ?: Double.NEGATIVE_INFINITY
                val waitMs: Double = maxOf(WRAP_AFTER_PLAY_MS - (nowMs() - lastPlay), WRAP_AFTER_SEEK_MS - (nowMs() - lastSeek))
                if (waitMs > 0.0) advanceBy(ceil(waitMs).toLong())
            }
            trace.lines += "@${nowMs().toLong()} $op"
            val call: () -> Unit = {
                when (op.kind) {
                    Kind.START -> engine.start()
                    Kind.PAUSE -> engine.pause()
                    Kind.SPEED -> engine.setSpeed(op.arg.toFloat())
                    Kind.SEEK -> engine.seekTo(op.arg.toLong())
                    Kind.WRAP, Kind.END -> native.deliverEndOfMedia()
                    Kind.RESTART -> {
                        engine.pause()
                        engine.setSpeed(op.arg.toFloat())
                        engine.seekTo(RESTART_SEEK_MS)
                        engine.start()
                    }
                }
            }
            if (op.onFx || op.kind == Kind.WRAP || op.kind == Kind.END || op.kind == Kind.RESTART) onFx(call) else call()
            when (op.kind) {
                Kind.START -> playing = true
                Kind.PAUSE, Kind.END -> playing = false
                Kind.SPEED -> lastAsked = op.arg
                Kind.RESTART -> {
                    lastAsked = op.arg
                    playing = true
                }
                else -> Unit
            }
            checkHolders("after the operation")
            checkDecoder("after the operation")
            if (op.gapMs == EDGE_GAP) {
                // The instant 1 ms before the earliest queued timer: the edge of a wait, where a call lands just before the block
                // that a start or a pause has left waiting.
                val dueNanos: Long? = host.nextDueNanos
                val toEdgeMs: Long = if (dueNanos == null) 0L else ((dueNanos - host.nanoTime()) / NANOS_PER_MS).toLong() - 1L
                if (toEdgeMs > 0L) advanceBy(toEdgeMs)
            } else if (op.gapMs == IDLE_EDGE_GAP) {
                // The instant the idle gap of the last property write closes (IDLE_GAP_MS after it): a start there finds the gap clear and
                // plays at once, 1 ms before a delayed play that was waiting for the same gap (the window of E2).
                val lastWrite: Double? = native.log.calls(NativeCall.SET_RATE).lastOrNull { it.effective }?.tMs
                val toEdgeMs: Long = if (lastWrite == null) 0L else ceil(lastWrite + IDLE_GAP_MS - nowMs()).toLong()
                if (toEdgeMs > 0L) advanceBy(toEdgeMs)
            } else if (op.gapMs > 0L) {
                advanceBy(op.gapMs)
            }
        }
        step++
        if (!playing) {
            trace.lines += "@${nowMs().toLong()} final:start"
            engine.start()
        }
        advanceBy(if (profile == TimeProfile.LOAD_AT_TICK) FINAL_PLAY_AT_TICK_MS else FINAL_PLAY_MS)
        checkHolders("at the end")
        checkDecoder("at the end")
        val hazards: HazardModel = composed.hazards
        composed.monitor.assertNoViolation()
        if (onePlayPerStart) {
            // Only for a fixed chain, whose every state is exact (a random sequence can deliver an end of the clip before its play ran,
            // a state no player is in): after the first pause() two play() calls with no pause() between them are one start played
            // twice, the play that a cancelled start still had queued (E2). The first start of a player is left out: its seek path
            // issues its own two calls before any pause.
            val transport: List<NativeCall> = native.log.entries.map { it.call }.filter { it == NativeCall.PLAY || it == NativeCall.PAUSE }
            val afterFirstPause: List<NativeCall> = transport.dropWhile { it != NativeCall.PAUSE }
            val doublePlay: Int = afterFirstPause.zipWithNext().indexOfFirst { (a: NativeCall, b: NativeCall) -> a == NativeCall.PLAY && b == NativeCall.PLAY }
            assertEquals(-1, doublePlay, "a play() followed by a second play() with no pause() between them\n${tailOf(native)}")
        }
        assertEquals(false, hazards.frozen, "the decoder is frozen\n${tailOf(native)}")
        assertEquals(
            lastAsked, hazards.effectiveRate,
            "the decoder does not end at the last speed asked for (property ${native.rate}, holders ${setHolders(playback)}, status ${native.status}, playhead ${native.currentTimeMs})\n${tailOf(native)}",
        )
    }

    // ---- the generator ----

    /** A kind the state allows: a start needs a paused clip, a pause a playing one, a wrap a looping one, an end a non-looping playing one. */
    private fun drawKind(rnd: Random, looping: Boolean, playing: Boolean): Kind {
        while (true) {
            val kind: Kind = when (rnd.nextInt(100)) {
                in 0..17 -> Kind.START
                in 18..29 -> Kind.PAUSE
                in 30..57 -> Kind.SPEED
                in 58..71 -> Kind.SEEK
                in 72..83 -> Kind.WRAP
                in 84..91 -> Kind.END
                else -> Kind.RESTART
            }
            val allowed: Boolean = when (kind) {
                Kind.START -> !playing
                Kind.PAUSE, Kind.RESTART -> playing
                Kind.WRAP -> looping
                Kind.END -> !looping && playing
                else -> true
            }
            if (allowed) return kind
        }
    }

    private fun generate(rnd: Random, looping: Boolean): List<Op> {
        val ops: MutableList<Op> = mutableListOf()
        var playing = false
        repeat(rnd.nextInt(MIN_OPS, MAX_OPS + 1)) {
            val onFx: Boolean = rnd.nextBoolean()
            val gap: Long = GAPS[rnd.nextInt(GAPS.size)]
            val kind: Kind = drawKind(rnd, looping, playing)
            val arg: Double = when (kind) {
                Kind.SPEED, Kind.RESTART -> SPEEDS[rnd.nextInt(SPEEDS.size)]
                Kind.SEEK -> SEEKS[rnd.nextInt(SEEKS.size)]
                else -> 0.0
            }
            when (kind) {
                Kind.START -> playing = true
                Kind.PAUSE, Kind.END -> playing = false
                else -> Unit
            }
            ops += Op(kind, arg, gap, onFx)
        }
        return ops
    }

    /**
     * The seeds to run: `-Dkmptoolkit.test.seed=N` replays one, `-Dkmptoolkit.test.fuzz.seeds=K` runs the first K, and by
     * default [SEED_COUNT] run. The count is higher than the 2000 of the other fuzzers because a model sequence is cheap
     * (about half a millisecond) and the family's rarest mutant, E2, needs a start inside the 1 ms window of a cancelled play.
     */
    private fun seeds(): List<Int> {
        System.getProperty("kmptoolkit.test.seed")?.let { text: String ->
            val replay: List<Int> = text.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (replay.isNotEmpty()) return replay
        }
        val count: Int = System.getProperty("kmptoolkit.test.fuzz.seeds")?.toIntOrNull() ?: SEED_COUNT
        return (1..count).toList()
    }

    private fun runSeed(seed: Int): Pair<Int, String>? {
        val rnd = Random(seed.toLong())
        val looping: Boolean = rnd.nextBoolean()
        val profile: TimeProfile = TimeProfile.values()[rnd.nextInt(TimeProfile.values().size)]
        val failure: String? = run("seed $seed (looping=$looping, $profile)", seed.toLong(), looping, profile, generate(rnd, looping))
        return failure?.let { seed to it }
    }

    // ---- the tests ----

    /**
     * The O-1 chain as a fixed case, through the same driver: a wrap with a deferred speed, `setSpeed(1)` while paused, a start
     * in the gap, a speed in the wait, a pause in the wait, 400 ms, a start and the clip must end at the speed asked for.
     */
    @Test
    fun theO1ChainKeepsOneHolderAndEndsAtTheLastSpeedAsked() {
        val chain: List<Op> = listOf(
            Op(Kind.SPEED, 2.0, 0L, onFx = false),
            Op(Kind.START, 0.0, 1_000L, onFx = false),
            Op(Kind.PAUSE, 0.0, 10L, onFx = false),
            Op(Kind.WRAP, 0.0, 10L, onFx = true),
            Op(Kind.SPEED, 1.0, 10L, onFx = false),
            Op(Kind.START, 0.0, 10L, onFx = false),
            Op(Kind.SPEED, 2.0, 10L, onFx = false),
            Op(Kind.PAUSE, 0.0, 400L, onFx = false),
            Op(Kind.START, 0.0, 0L, onFx = false),
        )
        assertNull(run("the O-1 chain", 1L, looping = true, profile = TimeProfile.PLAIN, ops = chain))
    }

    /**
     * The E2 chain as a fixed case, through the same driver: a seek-path play that waits out the idle gap, the end of the
     * clip 10 ms into the wait, and a start at the instant the gap closes, 1 ms before the cancelled play's callback.
     */
    @Test
    fun theE2ChainEndsAtTheLastSpeedAskedWithOnePlay() {
        val chain: List<Op> = listOf(
            Op(Kind.SPEED, 2.0, 0L, onFx = false),
            Op(Kind.START, 0.0, 1_000L, onFx = false),
            Op(Kind.RESTART, 1.5, 10L, onFx = true),
            Op(Kind.END, 0.0, IDLE_EDGE_GAP, onFx = true),
            Op(Kind.START, 0.0, 0L, onFx = false),
        )
        assertNull(run("the E2 chain", 1L, looping = false, profile = TimeProfile.PLAIN, ops = chain, onePlayPerStart = true))
    }

    /**
     * Class A of `adversarial/D2/round-14-fixer4.md` (ruling 4): a speed given into [deferredRate]
     * while the property already holds it was given later by a plain write, which forwards nothing, so the decoder that started at
     * normal speed with the play() stayed there. The chain: a speed asked in the window of a native seek (held, the property keeps
     * 2), a pause, a speed of 1 while paused (the held value becomes 1, the property still 2), a start (plays at 1, the held 1
     * stays), then a speed of 2 (held again, equal to the property). I1 holds throughout; the asked speed 2 must reach the decoder (I2).
     */
    @Test
    fun aSpeedHeldInTheDeferredHolderThatEqualsThePropertyStillReachesTheDecoder() {
        val chain: List<Op> = listOf(
            Op(Kind.SPEED, 2.0, 400L, onFx = true),
            Op(Kind.START, 0.0, 5L, onFx = true),
            Op(Kind.SPEED, 0.5, 50L, onFx = false),
            Op(Kind.SEEK, 20_000.0, 5L, onFx = true),
            Op(Kind.PAUSE, 0.0, 400L, onFx = true),
            Op(Kind.SPEED, 1.0, 250L, onFx = false),
            Op(Kind.START, 0.0, 0L, onFx = true),
            Op(Kind.SPEED, 2.0, 5L, onFx = false),
        )
        assertNull(run("class A chain", 1L, looping = false, profile = TimeProfile.PLAIN, ops = chain))
    }

    /** The seeded explorer: every sequence keeps I1 and I2 and ends at the last speed asked for, with no violated window. */
    @Test
    fun everySeededOperationSequenceKeepsOneHolderAndEndsAtTheLastSpeedAsked() {
        val seeds: List<Int> = seeds()
        val failures: List<Pair<Int, String>> = seeds.mapNotNull { runSeed(it) }
        FuzzModelSupport.assertNoFailures("the holder explorer of D2 r16 (decoder at the property)", seeds.size, failures, shown = SHOWN_FAILURES)
    }

    private companion object {
        const val SEED_COUNT: Int = 20_000
        const val RESTART_SEEK_MS: Long = 1_000L
        const val CLIP_MS: Double = 120_000.0
        const val NANOS_PER_MS: Double = 1_000_000.0
        const val MIN_OPS: Int = 8
        const val MAX_OPS: Int = 10
        const val FINAL_PLAY_MS: Long = 2_500L

        /** How long after the last native play, seek or rate write the decoder must have taken the property (I2). */
        const val SETTLED_MS: Double = 300.0

        /**
         * The window of the "last speed asked arrives" oracle under the stress read of a tick (ruling 4, class B): that read
         * publishes a sample taken at one instant after an independent 50-400 ms delay, out of order, which JavaFX cannot do, so
         * consecutive readings step back or run ahead of the clock and the movement gauge re-anchors often. Giving a held speed
         * then takes longer than 2.5 s without being starved: a run of clean ticks comes with probability 1.
         */
        const val FINAL_PLAY_AT_TICK_MS: Long = 10_000L
        const val WRAP_AFTER_PLAY_MS: Double = 110.0
        const val WRAP_AFTER_SEEK_MS: Double = 310.0
        const val TAIL_CALLS: Int = 24
        const val SHOWN_FAILURES: Int = 20
        /** [EDGE_GAP] is not a duration: it says to advance to 1 ms before the earliest queued timer. */
        const val EDGE_GAP: Long = -1L

        /** Not a duration either: advance to the instant the idle gap of the last property write closes. */
        const val IDLE_EDGE_GAP: Long = -2L
        const val IDLE_GAP_MS: Double = 200.0
        val GAPS: List<Long> = listOf(0L, 5L, 50L, 150L, 250L, 400L, EDGE_GAP, IDLE_EDGE_GAP)
        val SPEEDS: List<Double> = listOf(1.0, 2.0, 0.5, 4.0)
        val SEEKS: List<Double> = listOf(50.0, 5_000.0, 20_000.0)
    }
}
