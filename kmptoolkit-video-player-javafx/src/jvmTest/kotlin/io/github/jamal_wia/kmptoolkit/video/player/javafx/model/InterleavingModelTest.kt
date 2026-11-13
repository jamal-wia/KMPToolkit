package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Exhaustive short interleavings (task J13b): every short script of calls on the shared player over {play, pause,
 * seekTo, replay, speed, stop}, at every injection point, on the composed scripted player ([composeAll]), in virtual time. The random fuzzers of J13a sample long sequences; this file covers every short one, so a bug that needs
 * "play, then a seek while the decoder runs, then a speed within a few milliseconds" cannot hide behind a seed.
 *
 * THE MODEL OF A SCRIPT. A scenario is a list of calls and, between them, the number of FX-queue tasks that ran since the
 * previous call was issued (the OFFSET, see [Scenario.offsets]). The first call is made at once, on a prepared player (state
 * Ready, an idle queue). An offset of 0 means "in the same breath": for [Caller.OTHER] the calls are made one after the
 * other on a plain thread (each one queues its own blocks), for [Caller.FX] they are made inside one FX task, which is the
 * inline path of `FxQueue.post` (the shape of suspect S13). An offset of N above 0 means "between the N-th and the
 * (N+1)-th task after the previous call", injected from [VirtualFxHost.beforeEachTask]: a plain-thread call queued behind
 * whatever is due, or, for [Caller.FX], an FX block run at that instant. The tasks are the status landings, the position
 * ticks, the publications, the settle timers and the picture pulses (every 16.7 ms while a clip plays), so the offsets walk
 * the second call through every ordering of the engine's own blocks and through every moment of the first second of a play.
 * The exhaustive part has the jitter off (exhaustive means no random); the 3-call part is seeded, see
 * [aSeededSampleOfThreeCallScriptsKeepsTheContract]. A jittered variant is NOT in this file: the model's status events land
 * through `host.after`, so with a jitter a PAUSED landing can overtake a later PLAYING landing (the real player's events keep
 * their order), which leaves a running decoder under a PAUSED status and a speed that waits for PLAYING forever; that is a
 * model gap to close before a jittered script file can say anything (see the report).
 *
 * THE SIZE OF THE SPACE (what each test runs, measured): 2-call scripts, both callers, from Ready at every offset 0 to 47
 * (3456 scenarios), from the five played preludes at ten offsets (3600), from the paused, stopped and completed preludes at
 * ten offsets (2160); 3-call scripts: 216 scripts x 2 callers x 48 x 48 offsets from Ready alone is 995328 scenarios at about
 * 0.4 ms, so a fixed-seed sample of 800 is run. The play-led 3-call scripts at the moments that matter (`play, wait, a, b`
 * with the wait at 60, 150, 300, 500 and 1000 ms) are in the exhaustive part as the played preludes.
 *
 * WHAT EVERY SCENARIO MUST KEEP (the contract, not the implementation; the same list as the real-media fuzzers R6 and R9):
 * - the state each call leaves is one the documented transition table allows (R6): `play` and `replay` leave Playing (or
 *   Completed), `pause` of a Playing player leaves Paused, `stop` leaves Ready, a seek of a Paused player is published at
 *   once and exactly, a speed changes no state;
 * - no fragile window of the hazard model is violated and the decoder never freezes (the hazard monitor, plus `hazards.frozen`
 *   read at the end), except the scripts of the ledger [KNOWN_RED] (empty since task D-pi);
 * - the calls of one breath with a play or a replay in it leave the decoder only 1.0 and the speed in force at the end of the
 *   breath (the S13 contract, on the call log), see [checkOnlyTheSpeedInForceAfterAPlay];
 * - when the script has settled: a player left Playing has the decoder running and its playhead moves on (R9); a player left
 *   Paused or Ready has no running decoder; the last speed given to a Playing player is the rate the decoder plays at;
 *   no rate other than 1.0 and the speeds the script gave ever reached the native player, no seek went outside the clip;
 * - the player is not silently stuck (R9): a final `setSpeed` and `replay` from the FX thread completes the clip.
 *
 * Mutants that turn this file red (temporary local patches, see the report): M-PF1 (the `setSpeed` branch order before the
 * D-a fix: the FRESH branch ahead of the seek-window branch) and M-S13 (one `postLater` instead of two in the nudge of
 * `FxPlayback.start`).
 */
class InterleavingModelTest {

    /** The calls of a script; each is one method of the shared player. */
    private enum class Op(val label: String) {
        PLAY("play"), PAUSE("pause"), SEEK("seekTo"), REPLAY("replay"), SPEED("speed"), STOP("stop"),
    }

    /** How the caller reaches the player: from a plain thread (blocks queue in call order) or from the FX thread. */
    private enum class Caller { OTHER, FX }

    /**
     * The state a script starts from: the calls and waits that bring the prepared player there before the script begins.
     * A prelude is what turns a 2-call script into the tail of a longer one at a chosen moment: `PLAYED_150` then
     * `[seekTo, speed]` is the script `play, 150 ms, seekTo, speed`, the shape of D2-PF1.
     */
    private enum class Prelude(val steps: List<Any>) {
        READY(emptyList()),
        PLAYED_60(listOf(Op.PLAY, 60L)),
        PLAYED_150(listOf(Op.PLAY, 150L)),
        PLAYED_300(listOf(Op.PLAY, 300L)),
        PLAYED_500(listOf(Op.PLAY, 500L)),
        PLAYED_1000(listOf(Op.PLAY, 1_000L)),
        PAUSED(listOf(Op.PLAY, 500L, Op.PAUSE, 150L)),
        STOPPED(listOf(Op.PLAY, 300L, Op.STOP, 150L)),
        COMPLETED(listOf(Op.SPEED, Op.PLAY, 2_000L)),
    }

    /**
     * One script. [offsets] has one entry per call after the first: the tasks that ran since the previous call was issued
     * (0 = the same breath). It starts from [prelude].
     */
    private data class Scenario(val ops: List<Op>, val offsets: List<Int>, val caller: Caller, val prelude: Prelude = Prelude.READY) {
        init {
            require(offsets.size == ops.size - 1) { "one offset per call after the first" }
        }

        override fun toString(): String = buildString {
            append(prelude).append(" ").append(caller).append(": ").append(ops[0].label)
            for (i in 1 until ops.size) append(" +").append(offsets[i - 1]).append(" ").append(ops[i].label)
        }
    }

    /** One breath of calls (a group of the script): whether it had a play or replay, where the call log stood, the speed at its end. */
    private class Breath(val hasPlay: Boolean, val logMark: Int, val speedAtEnd: Double)

    /** The calls of one scenario made so far, with what they did to the player's state (the R6 table reads it). */
    private class Trace {
        val failures: MutableList<String> = mutableListOf()
        var lastSpeed: Float? = null
        val speedsGiven: MutableList<Float> = mutableListOf()
    }

    // ---- the scenario runner ----

    /** Calls that were injected by the hook, and calls that found the queue dry (or past the window) and were made at idle. */
    private var injectedByHook: Int = 0
    private var injectedAtIdle: Int = 0

    /** `player.prepare` driven the way `FxModelScope.load` drives an engine load: the IO hop is real, the FX queue is drained here. */
    private fun FxModelScope.prepare(player: VideoPlayer) {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
        val deadline: Long = System.nanoTime() + PREPARE_BOUND_MS * NANOS_PER_MILLI_LONG
        runBlocking {
            val preparing = async { player.prepare(VideoSource.Asset(TestClip.ASSET)) }
            while (!preparing.isCompleted) {
                host.runUntilIdle()
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
                if (!preparing.isCompleted && System.nanoTime() - deadline > 0L) {
                    preparing.cancel()
                    throw AssertionError("prepare did not finish within $PREPARE_BOUND_MS ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            preparing.await()
        }
    }

    private fun kind(state: VideoPlayerState): String = state::class.simpleName.orEmpty()

    /**
     * Makes call [op] on [player] and checks the state it left against the transition table of the real-media fuzz R6.
     * [seekTargets] and [speeds] give the values of the n-th seek and speed of the script.
     */
    private fun call(player: VideoPlayer, op: Op, trace: Trace, seekTargets: IntArray, speeds: FloatArray, counters: IntArray, label: String) {
        val before: VideoPlayerState = player.stateFlow.value
        var target = -1L
        when (op) {
            Op.PLAY -> player.play()
            Op.PAUSE -> player.pause()
            Op.STOP -> player.stop()
            Op.REPLAY -> player.replay()
            Op.SEEK -> {
                target = seekTargets[counters[0]++ % seekTargets.size].toLong()
                player.seekTo(target)
            }
            Op.SPEED -> {
                val speed: Float = speeds[counters[1]++ % speeds.size]
                trace.lastSpeed = speed
                trace.speedsGiven += speed
                player.setPlaybackSpeed(speed)
            }
        }
        val after: VideoPlayerState = player.stateFlow.value
        val ok: Boolean = when (op) {
            Op.PLAY, Op.REPLAY -> when (before) {
                is VideoPlayerState.Ready, is VideoPlayerState.Paused, is VideoPlayerState.Completed, is VideoPlayerState.Playing ->
                    after is VideoPlayerState.Playing || after is VideoPlayerState.Completed
                else -> after == before
            }
            Op.PAUSE -> if (before is VideoPlayerState.Playing) {
                after is VideoPlayerState.Paused || after is VideoPlayerState.Completed
            } else {
                kind(after) == kind(before)
            }
            Op.STOP -> after is VideoPlayerState.Ready
            Op.SEEK -> when (before) {
                is VideoPlayerState.Paused -> after is VideoPlayerState.Paused && after.currentPosition == target.coerceIn(0L, TestClip.DURATION_MS)
                is VideoPlayerState.Ready -> after is VideoPlayerState.Ready
                is VideoPlayerState.Completed -> after is VideoPlayerState.Paused || after is VideoPlayerState.Completed
                is VideoPlayerState.Playing -> after is VideoPlayerState.Playing || after is VideoPlayerState.Completed
                else -> after == before
            }
            Op.SPEED -> kind(after) == kind(before)
        }
        if (!ok) trace.failures += "$label: ${op.label} took ${kind(before)} to ${kind(after)}"
    }

    /** Runs [scenario] in its own model; returns the first failure line (the scenario named in it), or null when it held. */
    private fun run(scenario: Scenario): String? {
        var failure: Throwable? = null
        try {
            fxModel {
                execute(scenario)
            }
        } catch (t: Throwable) {
            failure = t
        }
        return failure?.let { "[$scenario] ${it.message.orEmpty()}" }
    }

    private fun FxModelScope.execute(scenario: Scenario) {
        val scripted = ScriptedBackend(host)
        // Unwatched by the end-of-test guard only so a violation comes back as the scenario's own failure text (the ledger test
        // reads it); the monitor and the dispose part are checked below, by hand, in every scenario.
        val backend: ComposedBackend = composeAll(scripted, watch = false)
        allowUnwatched(scripted, "the hazard monitor is read by hand at the end of every scenario, see execute")
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        val player: VideoPlayer = videoPlayer(engine)
        prepare(player)
        // The player pushes its volume, loop mode and speed after the prepare: let them run, so an FX block is the FX thread
        // with nothing queued (the case of a button handler), not a block queued behind them.
        advanceBy(0)
        try {
            assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "state ${player.stateFlow.value}")
            val native: ScriptedNativePlayer = backend.last.player
            val hazards: HazardModel = backend.last.hazards
            val trace = Trace()
            val seekTargets: IntArray = SEEK_TARGETS
            val speeds: FloatArray = SPEEDS
            val counters = IntArray(2)
            val label: String = scenario.toString()
            val ops: List<Op> = scenario.ops

            for (step: Any in scenario.prelude.steps) {
                when (step) {
                    is Op -> call(player, step, trace, seekTargets, speeds, counters, "$label (prelude)")
                    is Long -> advanceBy(step)
                    else -> error("unknown prelude step $step")
                }
            }
            assertEquals(emptyList(), trace.failures, "a prelude transition left the table")
            if (scenario.prelude == Prelude.COMPLETED) {
                assertTrue(player.stateFlow.value is VideoPlayerState.Completed, "the prelude did not complete: ${player.stateFlow.value}")
            }

            // The calls, grouped: a call with offset 0 joins the group of the one before it.
            val groups: MutableList<MutableList<Int>> = mutableListOf(mutableListOf(0))
            val groupOffsets: MutableList<Int> = mutableListOf(0)
            for (i in 1 until ops.size) {
                if (scenario.offsets[i - 1] == 0) {
                    groups.last() += i
                } else {
                    groups += mutableListOf(i)
                    groupOffsets += scenario.offsets[i - 1]
                }
            }
            val breaths: MutableList<Breath> = mutableListOf()
            fun issue(group: List<Int>) {
                val logMark: Int = native.log.size
                val calls: () -> Unit = { for (i in group) call(player, ops[i], trace, seekTargets, speeds, counters, label) }
                if (scenario.caller == Caller.FX) onFx(calls) else calls()
                breaths += Breath(group.any { ops[it] == Op.PLAY || ops[it] == Op.REPLAY }, logMark, player.playbackSpeedFlow.value.toDouble())
            }

            var next = 1
            var markIndex: Int = host.tasksRun
            issue(groups[0])
            if (groups.size > 1) {
                host.beforeEachTask { index: Int ->
                    if (next < groups.size && index - markIndex >= groupOffsets[next]) {
                        val group: List<Int> = groups[next]
                        next++
                        markIndex = index
                        injectedByHook++
                        issue(group)
                    }
                }
            }
            var waited = 0L
            while (next < groups.size) {
                if (host.pendingCount == 0 || waited >= INJECT_LIMIT_MS) {
                    // The queue ran dry (or the point is later than the injection window): the call is made at idle.
                    val group: List<Int> = groups[next]
                    next++
                    markIndex = host.tasksRun
                    injectedAtIdle++
                    issue(group)
                } else {
                    advanceBy(INJECT_STEP_MS)
                    waited += INJECT_STEP_MS
                }
            }
            host.beforeEachTask(null)
            advanceBy(SETTLE_MS)

            assertEquals(emptyList(), trace.failures, "a transition left the table: ${trace.failures.firstOrNull()}")
            val composed: ComposedPlayer = backend.last
            assertEquals(emptyList(), composed.dispose.violations, "the dispose part recorded a violation")
            composed.monitor.assertNoViolation()
            assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
            checkSettled(player, native, hazards, composed.time, trace, label)
            checkNothingForeignReachedTheDecoder(native, trace)
            checkOnlyTheSpeedInForceAfterAPlay(native, breaths, label)
            checkNotStuck(player, native, hazards)
        } finally {
            player.release()
            advanceBy(50)
        }
    }

    private fun FxModelScope.checkSettled(player: VideoPlayer, native: ScriptedNativePlayer, hazards: HazardModel, time: TimeModel, trace: Trace, label: String) {
        val state: VideoPlayerState = player.stateFlow.value
        when (state) {
            is VideoPlayerState.Playing -> {
                assertTrue(hazards.decoderRunning, "Playing but the decoder is not running ($label)\n${native.log.tail()}")
                assertEquals(NativeStatus.PLAYING, native.status, "Playing but the native status is ${native.status} ($label)\n${native.log.tail()}")
                val expected: Double = (trace.lastSpeed ?: 1f).coerceIn(1f, 3f).toDouble()
                assertEquals(expected, hazards.effectiveRate, "the decoder plays at ${hazards.effectiveRate}, the last speed given was ${trace.lastSpeed} ($label)\n${native.log.tail()}")
                val from: Double = time.nativePositionMs
                advanceBy(MOVE_CHECK_MS)
                if (player.stateFlow.value is VideoPlayerState.Playing) {
                    val moved: Double = time.nativePositionMs - from
                    assertTrue(moved >= MOVE_CHECK_MS * 0.9, "Playing for $MOVE_CHECK_MS ms and the decoder moved only $moved ms ($label)\n${native.log.tail()}")
                }
            }
            is VideoPlayerState.Paused, is VideoPlayerState.Ready -> {
                assertTrue(!hazards.decoderRunning, "${kind(state)} but the decoder is running ($label)\n${native.log.tail()}")
                assertTrue(native.status != NativeStatus.PLAYING, "${kind(state)} but the native status is PLAYING ($label)\n${native.log.tail()}")
            }
            is VideoPlayerState.Completed -> assertEquals(TestClip.DURATION_MS.toDouble(), time.nativePositionMs, "Completed away from the end ($label)\n${native.log.tail()}")
            else -> throw AssertionError("unexpected state $state ($label)")
        }
    }

    private fun checkNothingForeignReachedTheDecoder(native: ScriptedNativePlayer, trace: Trace) {
        val allowed: Set<Double> = setOf(1.0) + trace.speedsGiven.map { it.toDouble() }
        for (entry: NativeCallEntry in native.log.entries) {
            if (entry.call == NativeCall.SET_RATE) {
                assertTrue(entry.arg != null && entry.arg in allowed,"a rate of ${entry.arg} reached the native player, the script gave $allowed\n${native.log.tail()}")
            }
            if (entry.call == NativeCall.SEEK) {
                val target: Double = checkNotNull(entry.arg)
                assertTrue(target in 0.0..TestClip.DURATION_MS.toDouble(), "a native seek to $target left the clip\n${native.log.tail()}")
            }
        }
    }

    /**
     * The S13 contract, on the call log (design 4.2: S6 and S13 "are not windows: they are a second rate change on top of the
     * first"): the calls made in one breath with a `play` or a `replay` in it (the shared player's `play` is `start` and then
     * `setSpeed`, from the FX thread in one task) leave the decoder at most 1.0 and the speed the player stands at when the
     * breath ends, until the next breath: a speed that was replaced inside the breath never reaches the native player (the
     * nudge of `FxPlayback.start` is given two tasks later, behind the `setSpeed` that replaces it; one task later it would
     * write the old speed first and the new one right after).
     */
    private fun checkOnlyTheSpeedInForceAfterAPlay(native: ScriptedNativePlayer, breaths: List<Breath>, label: String) {
        val entries: List<NativeCallEntry> = native.log.entries
        for ((index: Int, breath: Breath) in breaths.withIndex()) {
            if (!breath.hasPlay) continue
            val end: Int = if (index + 1 < breaths.size) breaths[index + 1].logMark else entries.size
            for (entry: NativeCallEntry in entries.subList(breath.logMark, end)) {
                if (entry.call == NativeCall.SET_RATE && entry.effective) {
                    val rate: Double = checkNotNull(entry.arg)
                    assertTrue(
                        rate == 1.0 || rate == breath.speedAtEnd,
                        "after a play a rate of $rate reached the decoder, the speed in force was ${breath.speedAtEnd} ($label)\n${native.log.tail()}",
                    )
                }
            }
        }
    }

    /** The R9 check: a final replay at a speed of three from the FX thread completes the clip, whatever the script left behind. */
    private fun FxModelScope.checkNotStuck(player: VideoPlayer, native: ScriptedNativePlayer, hazards: HazardModel) {
        onFx {
            player.setPlaybackSpeed(3f)
            player.replay()
        }
        var waited = 0L
        while (player.stateFlow.value !is VideoPlayerState.Completed && waited < FINAL_REPLAY_LIMIT_MS) {
            advanceBy(FINAL_REPLAY_STEP_MS)
            waited += FINAL_REPLAY_STEP_MS
        }
        assertTrue(player.stateFlow.value is VideoPlayerState.Completed, "the final replay at 3x never completed within $FINAL_REPLAY_LIMIT_MS ms: ${player.stateFlow.value}\n${native.log.tail()}")
        assertTrue(!hazards.frozen, "the decoder froze in the final replay\n${native.log.tail()}")
    }

    /** A failure as one or two lines: its first line and the line that names the violated window, if any. */
    private fun brief(failure: String): String {
        val lines: List<String> = failure.lines()
        val window: String? = lines.drop(1).firstOrNull { it.contains("W-") }
        return lines.first() + if (window != null) " |" + window.trim() else ""
    }

    // ---- the exhaustive part ----

    private fun runAll(scenarios: Sequence<Scenario>, what: String): Int {
        var count = 0
        val failures: MutableList<String> = mutableListOf()
        for (scenario: Scenario in scenarios) {
            count++
            run(scenario)?.let { failures += brief(it) }
        }
        assertEquals(emptyList(), failures.take(MAX_FAILURES_SHOWN), "${failures.size} of $count $what scenarios failed")
        return count
    }

    /** Every 2-call script over the six calls, for both callers, from [prelude], at each of the offsets [points]. */
    private fun twoCallScenarios(prelude: Prelude, points: List<Int>): Sequence<Scenario> = sequence {
        for (caller: Caller in Caller.values()) {
            for (first: Op in Op.values()) {
                for (second: Op in Op.values()) {
                    for (offset: Int in points) yield(Scenario(listOf(first, second), listOf(offset), caller, prelude))
                }
            }
        }
    }

    /**
     * Why a script is on the ledger [KNOWN_RED]: the violated window and the offending call as the monitor names them
     * (`W-PLAY: SET_RATE`), the defect class of the ruling (reviews/fxport-j13a-opus.md) and the task that fixes it.
     */
    private data class KnownRed(val window: String, val trigger: String, val defectClass: String, val task: String)

    /**
     * THE LEDGER of scripts that are red on the current production and are classified as a product defect, not hidden: the
     * exhaustive tests leave exactly these keys out, and [theKnownRedScriptsStillFailExactlyAsRecorded] runs every one of them
     * and fails when one turns green or fails in another way. EMPTY since task D-pi (decision D-95): the 14 scripts it held
     * (class A of the J13a ruling, a speed written before the pause of `pause()` and `stop()` inside W-PLAY) are green with its
     * fix. An entry joins only with an advisor class and leaves only by turning green. The key is the script id: prelude,
     * caller, calls and offsets.
     */
    private val KNOWN_RED: Map<String, KnownRed> = emptyMap()

    /** The ledger scripts, rebuilt from their keys' parts: those of [KNOWN_RED] that [twoCallScenarios] would have run. */
    private fun knownRedScenarios(): List<Scenario> = twoCallScenarios(Prelude.PLAYED_60, LATE_POINTS).filter { it.toString() in KNOWN_RED }.toList()

    @Test
    fun theKnownRedScriptsStillFailExactlyAsRecorded() {
        val scenarios: List<Scenario> = knownRedScenarios()
        assertEquals(KNOWN_RED.keys, scenarios.map { it.toString() }.toSet(), "every KNOWN_RED key must be a script of the 2-call space")
        val problems: MutableList<String> = mutableListOf()
        for (scenario: Scenario in scenarios) {
            val recorded: KnownRed = checkNotNull(KNOWN_RED[scenario.toString()])
            val failure: String? = run(scenario)
            when {
                failure == null -> problems += "script $scenario is green now: remove it from KNOWN_RED in the commit that fixed it"
                !failure.contains("fragile window may be violated") || !failure.contains("${recorded.window}: ${recorded.trigger}") ->
                    problems += "script $scenario fails differently than recorded (${recorded.window} ${recorded.trigger}, class ${recorded.defectClass}, ${recorded.task}): ${brief(failure)}"
            }
        }
        assertEquals(emptyList(), problems)
    }

    private fun checkExhaustive(preludes: List<Prelude>, points: List<Int>) {
        val failures: MutableList<String> = mutableListOf()
        var count = 0
        var onLedger = 0
        for (prelude: Prelude in preludes) {
            for (scenario: Scenario in twoCallScenarios(prelude, points)) {
                count++
                if (scenario.toString() in KNOWN_RED) {
                    onLedger++
                    continue
                }
                run(scenario)?.let { failures += brief(it) }
            }
        }
        assertEquals(emptyList(), failures.take(MAX_FAILURES_SHOWN), "${failures.size} of $count 2-call scenarios from $preludes failed ($onLedger more are on the KNOWN_RED ledger)")
        assertEquals(preludes.size * Caller.values().size * Op.values().size * Op.values().size * points.size, count)
        // Non-vacuity: most second calls really were injected between two tasks, not made at idle.
        assertTrue(injectedByHook > injectedAtIdle, "only $injectedByHook of ${injectedByHook + injectedAtIdle} injections came from the hook")
    }

    /** From a prepared player: every 2-call script is a whole script from Ready (the first call is made on an idle queue). */
    @Test
    fun everyTwoCallScriptFromAReadyPlayerKeepsTheContractAtEveryInjectionPoint() {
        checkExhaustive(listOf(Prelude.READY), (0 until READY_POINTS).toList())
    }

    /**
     * From a player that was played a moment ago (60, 150, 300, 500 and 1000 ms): the 2-call script is the tail of the
     * 3-call script `play, wait, a, b`, at the moments that matter: before the status landed, inside the FRESH period of
     * `FxPlayback.start` (600 ms), and after it. This is where the D2-PF1 shape (a seek of a playing clip, then a speed)
     * lives.
     */
    @Test
    fun everyTwoCallScriptFromAPlayerPlayedAMomentAgoKeepsTheContractAtEveryInjectionPoint() {
        checkExhaustive(listOf(Prelude.PLAYED_60, Prelude.PLAYED_150, Prelude.PLAYED_300, Prelude.PLAYED_500, Prelude.PLAYED_1000), LATE_POINTS)
    }

    /** From a paused, a stopped and a completed player: the resume, replay and seek-after-the-end paths. */
    @Test
    fun everyTwoCallScriptFromAPausedAStoppedAndACompletedPlayerKeepsTheContractAtEveryInjectionPoint() {
        checkExhaustive(listOf(Prelude.PAUSED, Prelude.STOPPED, Prelude.COMPLETED), LATE_POINTS)
    }

    /**
     * A seeded sample of the 3-call scripts from Ready: the full space (216 scripts, two callers, [READY_POINTS] points for
     * each of the two offsets) is [FULL_THREE_CALL_SPACE] scenarios, hours of virtual-time runs and far over the 10 s the
     * file is allowed (a scenario costs about 0.3 ms), so each run draws [THREE_CALL_SAMPLES] of them from a fixed
     * [Random]; the count is in the failure message. The play-led 3-call scripts at the moments that matter are covered
     * exhaustively by the preludes above.
     */
    @Test
    fun aSeededSampleOfThreeCallScriptsKeepsTheContract() {
        val random = Random(THREE_CALL_SEED)
        val ops: Array<Op> = Op.values()
        val scenarios: Sequence<Scenario> = sequence {
            repeat(THREE_CALL_SAMPLES) {
                yield(
                    Scenario(
                        listOf(ops[random.nextInt(ops.size)], ops[random.nextInt(ops.size)], ops[random.nextInt(ops.size)]),
                        listOf(random.nextInt(READY_POINTS), random.nextInt(READY_POINTS)),
                        if (random.nextBoolean()) Caller.FX else Caller.OTHER,
                    ),
                )
            }
        }
        val count: Int = runAll(scenarios, "seeded 3-call (seed $THREE_CALL_SEED)")
        assertEquals(THREE_CALL_SAMPLES, count)
    }

    private companion object {
        const val NANOS_PER_MILLI_LONG: Long = 1_000_000L
        const val PREPARE_BOUND_MS: Long = 10_000L

        /** The values of the n-th seek and speed of a script: the middle of the clip, then near its start; 2x, then 3x. */
        val SEEK_TARGETS: IntArray = intArrayOf(1_500, 600)
        val SPEEDS: FloatArray = floatArrayOf(2f, 3f)

        /** Injection points of a 2-call script: the second call comes 0 until this many tasks after the first. */
        const val READY_POINTS: Int = 48

        /**
         * The offsets of a script that starts from a played, paused, stopped or completed player: every task order of the same
         * instant (0 to 4), then the first pulses and the 100 ms tick (6 to 22 tasks), then well past the FRESH period of a play.
         */
        val LATE_POINTS: List<Int> = listOf(0, 1, 2, 3, 4, 6, 9, 14, 22, 36)
        const val THREE_CALL_SAMPLES: Int = 800

        /** 216 scripts x 2 callers x READY_POINTS x READY_POINTS. */
        const val FULL_THREE_CALL_SPACE: Int = 216 * 2 * READY_POINTS * READY_POINTS
        const val THREE_CALL_SEED: Long = 13_000_002L


        /** A call whose point lies beyond the first two virtual seconds is made at idle instead. */
        const val INJECT_LIMIT_MS: Long = 2_000L
        const val INJECT_STEP_MS: Long = 100L
        const val SETTLE_MS: Long = 1_800L
        const val MOVE_CHECK_MS: Long = 500L
        const val FINAL_REPLAY_LIMIT_MS: Long = 3_000L
        const val FINAL_REPLAY_STEP_MS: Long = 100L
        const val MAX_FAILURES_SHOWN: Int = 12
    }
}
