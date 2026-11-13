package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.EngineEvent
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.RecordingListener
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rate-giving family of the JavaFX model tier (task J11a): the F twins, on the call log of the composed and watched
 * scripted player ([composeAll]) in virtual time, of the 11 cases of `JavaFxRateGivingR8AdversarialTest`. That class
 * watches the native rate property of a real `MediaPlayer` (a recorder installed by reflection) to see HOW and WHEN the
 * engine gives a speed to the native player; here the same thing is an exact sequence of `SET_RATE` entries of the call
 * log with their virtual timestamps. Where the real class reads a private field by reflection or measures with sleeps, the
 * twin asserts the observable consequence of the same contract on the log.
 *
 * Real-media twins, one test each unless said:
 * - `a speed given right after a resume replaces the pending nudge and reaches the native player once`:
 *   [aSpeedGivenRightAfterAResumeReplacesThePendingNudgeAndReachesTheNativePlayerOnce];
 * - `a resume that is paused again at once leaves the native rate alone`:
 *   [aResumeThatIsPausedAgainAtOnceLeavesTheNativeRateAlone];
 * - `a replay after the end at a speed gives the speed away and back`: [aReplayAfterTheEndAtASpeedGivesTheSpeedAwayAndBack];
 * - `a speed given right after a loop wrap at normal speed reaches the native player at once`:
 *   [aSpeedGivenAfterALoopWrapAtNormalSpeedReachesTheNativePlayerAtOnce] (outside the seek window) and
 *   [aSpeedGivenInsideTheSeekWindowOfALoopWrapAtNormalSpeedIsGivenOnceAtTheEndOfTheWindow] (inside it, see below);
 * - `at a loop wrap the speed is given back only after the playhead has moved 200 ms`:
 *   [atALoopWrapTheSpeedIsGivenBackOnlyAfterThePlayheadHasMoved200Ms];
 * - `a seek while the speed is deferred still gets the speed back`: [aSeekWhileTheSpeedIsDeferredStillGetsTheSpeedBack];
 * - `a speed changed a short while after a plain play is given as a resume and clears the fresh-play mark`:
 *   [aSpeedChangedAShortWhileAfterAPlainPlayIsGivenAsAResumeAndClearsTheFreshPlayMark];
 * - `a play after a native seek leaves no fresh-play mark`: [aPlayAfterANativeSeekLeavesNoFreshPlayMark];
 * - `a seek posted before a resume at a speed is not undone by the resume`:
 *   [aSeekPostedBeforeAResumeAtASpeedIsNotUndoneByTheResume];
 * - `a replay after the end at a speed starts over and does not complete again at once`:
 *   [aReplayAfterTheEndAtASpeedStartsOverAndDoesNotCompleteAgainAtOnce];
 * - `a seek posted right after a play is not undone by a speed given in the fresh-play window`:
 *   [aSeekPostedRightAfterAPlayIsNotUndoneByASpeedGivenInTheFreshPlayWindow].
 *
 * The real class's "at once" after a loop wrap is the premise of the time before D-b (M2): `FxPlayback` now stamps the
 * restart of a looping source as a native seek, so a speed given less than 300 ms after a wrap at normal speed is held to
 * the end of that seek window (`SpeedAtLoopWrapModelTest`). The "nothing was deferred" property of the real class is kept
 * on the log of the rate property: at a wrap at normal speed the rate is not touched at all, and a speed given outside the
 * window is written at once, as one change.
 *
 * Real-media measures and their model reading: the real class waits for events and sleeps 300-400 ms to prove "nothing
 * more arrives"; here the call log is complete at the end of the run, so the whole sequence is asserted with
 * `assertEquals` on the list of rates, not "contains". The stale-reading trials of the real class (the native `currentTime`
 * does not always read a seek just posted) are modelled with the time part's `publishDelayMs`: the published position then
 * lags the native one by that much, deterministically.
 *
 * Mutants that turn this file red (temporary local patches, see the report): M-S13 (one `postLater` before the nudge
 * instead of two), M-S6 (a speed right after a plain play is written at once), and the ones named in the report.
 */
class RateGivingModelTest {

    // ---- rigs ----

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
        val hazards: HazardModel get() = composed.hazards
    }

    /** How the caller reaches the engine: from the test thread (another thread, always queued) or from the FX thread. */
    private enum class Caller { OTHER_THREAD, FX_THREAD }

    private fun FxModelScope.withEngine(
        timeScript: TimeScript = TimeScript(),
        listener: RecordingListener? = null,
        body: Rig.() -> Unit,
    ) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host), timeScript = timeScript)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        listener?.let { engine.setListener(it) }
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / NANOS_PER_MILLI

    private fun FxModelScope.call(caller: Caller, block: () -> Unit) {
        when (caller) {
            Caller.OTHER_THREAD -> block()
            Caller.FX_THREAD -> onFx { block() }
        }
    }

    /** Advances in steps of [stepMs] virtual ms until [condition] holds, at most [limitMs]; returns the virtual ms waited. */
    private fun FxModelScope.advanceUntil(limitMs: Long = 10_000L, stepMs: Long = 10L, condition: () -> Boolean): Long {
        var waited = 0L
        while (!condition()) {
            check(waited < limitMs) { "the condition did not hold within $limitMs virtual ms ($host)" }
            advanceBy(stepMs)
            waited += stepMs
        }
        return waited
    }

    /** The effective rate writes at or after [t] virtual ms, oldest first. */
    private fun ScriptedNativePlayer.rateWritesFrom(t: Double): List<NativeCallEntry> =
        log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs >= t - TIME_TOLERANCE_MS }

    private fun assertAt(expected: Double, actual: Double, message: String) =
        assertEquals(expected, actual, TIME_TOLERANCE_MS, message)

    /** Runs [cases] each in its own model and fails with every failing label, like the other family files. */
    private fun <T> runAll(cases: List<T>, label: (T) -> String, run: (T) -> Unit) {
        val failures: MutableList<String> = mutableListOf()
        for (case: T in cases) {
            try {
                run(case)
            } catch (t: Throwable) {
                failures += "[${label(case)}] ${t.message?.lineSequence()?.firstOrNull()}"
            }
        }
        assertEquals(emptyList(), failures, "${failures.size} of ${cases.size} cases failed")
    }

    /** The scenario of a loop test up to the first wrap, with [speed] set on a fresh engine; returns the virtual ms of the wrap. */
    private fun FxModelScope.driveToFirstWrap(rig: Rig, speed: Float): Double {
        var wrapAtMs = Double.NaN
        rig.composed.loop.onWrap { wrapAtMs = nowMs() }
        if (speed != 1f) rig.engine.setSpeed(speed)
        rig.engine.setLooping(true)
        rig.engine.start()
        var steps = 0
        while (rig.composed.loop.cycle < 2) {
            check(steps++ < MAX_STEPS) { "the first wrap did not come ($host)" }
            check(step()) { "the host ran dry before the first wrap ($host)" }
        }
        return wrapAtMs
    }

    // ---- 1: the nudge a resume leaves pending is replaced by a speed given right after it ----

    /**
     * Twin of `a speed given right after a resume replaces the pending nudge and reaches the native player once`. A speed of
     * 2 is in force, then `start()` and `setSpeed(3f)` follow at once, from another thread (queued in that order, the real
     * class holds the FX thread to get the same) or from the FX thread (the shared player's way). The resume at the start
     * of the clip leaves the nudge for two FX tasks later; the new speed replaces it, so the native player sees exactly
     * "away and back to the NEW speed", both in the same virtual instant as the play, and never the replaced speed.
     */
    @Test
    fun aSpeedGivenRightAfterAResumeReplacesThePendingNudgeAndReachesTheNativePlayerOnce() {
        runAll(Caller.values().toList(), { "from $it" }) { caller: Caller ->
            fxModel {
                withEngine {
                    engine.setSpeed(2f)
                    advanceBy(300)
                    assertEquals(2.0, native.rate, "the speed was written while idle\n${native.log}")
                    val calledAt: Double = nowMs()
                    call(caller) { engine.start(); engine.setSpeed(3f) }
                    advanceBy(2_000)

                    val rates: List<NativeCallEntry> = native.rateWritesFrom(calledAt)
                    assertEquals(listOf(1.0, 3.0), rates.map { it.arg }, "away and back to the NEW speed, no change to the replaced one\n${native.log}")
                    for (rate: NativeCallEntry in rates) assertAt(calledAt, rate.tMs, "given in the FX tasks of the play\n${native.log}")
                    assertEquals(1, native.log.calls(NativeCall.PLAY).count { it.tMs >= calledAt - TIME_TOLERANCE_MS }, "one play\n${native.log}")
                    assertFalse(hazards.frozen, native.log.tail())
                    assertEquals(3.0, hazards.effectiveRate, "the new speed reached the decoder\n${native.log}")
                }
            }
        }
    }

    // ---- 2: a resume that is paused again at once ----

    /**
     * Twin of `a resume that is paused again at once leaves the native rate alone`: the nudge a resume left pending finds
     * the player paused and only re-states the property, which changes nothing native, so no rate is written at all and the
     * property keeps the speed. The play and the pause reached the native player, in that order.
     */
    @Test
    fun aResumeThatIsPausedAgainAtOnceLeavesTheNativeRateAlone() {
        runAll(Caller.values().toList(), { "from $it" }) { caller: Caller ->
            fxModel {
                withEngine {
                    engine.setSpeed(2f)
                    advanceBy(300)
                    val calledAt: Double = nowMs()
                    call(caller) { engine.start(); engine.pause() }
                    advanceBy(1_000)

                    assertEquals(emptyList(), native.rateWritesFrom(calledAt).map { it.arg }, "no away-and-back on a player that is paused again\n${native.log}")
                    assertEquals(2.0, native.rate, "the speed is kept for the next start")
                    val after: List<NativeCallEntry> = native.log.entries.filter { it.tMs >= calledAt - TIME_TOLERANCE_MS && it.call in setOf(NativeCall.PLAY, NativeCall.PAUSE) }
                    assertEquals(listOf(NativeCall.PLAY, NativeCall.PAUSE), after.map { it.call }, native.log.tail())
                    assertFalse(hazards.frozen, native.log.tail())
                }
            }
        }
    }

    // ---- 3 and 10: a replay after the end ----

    private fun Rig.playToTheEndAt(scope: FxModelScope, speed: Float, listener: RecordingListener) {
        engine.setSpeed(speed)
        engine.start()
        scope.advanceUntil(limitMs = 5_000L, stepMs = 10L) { listener.events.any { it is EngineEvent.Completed } }
    }

    /**
     * Twin of `a replay after the end at a speed gives the speed away and back`: after a completion at a speed of 2,
     * `start()` seeks to the start, plays, and gives the speed as one away-and-back two FX tasks later, all in the instant of
     * the call. [gapsMs] is the rest after the completion; the answer does not depend on it.
     */
    @Test
    fun aReplayAfterTheEndAtASpeedGivesTheSpeedAwayAndBack() {
        runAll(listOf(0L, 100L, 1_000L), { "replay $it ms after the end" }) { gapMs: Long ->
            fxModel {
                val listener = RecordingListener()
                withEngine(listener = listener) {
                    playToTheEndAt(this@fxModel, 2f, listener)
                    advanceBy(gapMs)
                    val calledAt: Double = nowMs()
                    engine.start()
                    advanceBy(300)

                    val rates: List<NativeCallEntry> = native.rateWritesFrom(calledAt)
                    assertEquals(listOf(1.0, 2.0), rates.map { it.arg }, "away and back\n${native.log}")
                    for (rate: NativeCallEntry in rates) assertAt(calledAt, rate.tMs, "given in the instant of the replay\n${native.log}")
                    val seek: NativeCallEntry = native.log.calls(NativeCall.SEEK).last()
                    assertAt(calledAt, seek.tMs, "the replay seeks to the start in its own task\n${native.log}")
                    assertEquals(0.0, seek.arg, "to the start\n${native.log}")
                    assertFalse(hazards.frozen, native.log.tail())
                    assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
                }
            }
        }
    }

    /**
     * Twin of `a replay after the end at a speed starts over and does not complete again at once` (6 trials there): the
     * replay completes once, 300 ms later it stands at the speed-2 distance from the start (at most 600 ms, less the
     * status lag, and at least 200) and the end of media has not fired again. The six trials are the rests 0 to 1000 ms.
     */
    @Test
    fun aReplayAfterTheEndAtASpeedStartsOverAndDoesNotCompleteAgainAtOnce() {
        runAll(listOf(0L, 20L, 100L, 250L, 400L, 1_000L), { "replay $it ms after the end" }) { gapMs: Long ->
            fxModel {
                val listener = RecordingListener()
                withEngine(listener = listener) {
                    playToTheEndAt(this@fxModel, 2f, listener)
                    advanceBy(gapMs)
                    engine.start()
                    advanceBy(300)

                    val completions: Int = listener.events.count { it is EngineEvent.Completed }
                    assertEquals(1, completions, "the replay completed again within 300 ms (playhead ${native.positionMs})\n${native.log}")
                    assertTrue(native.positionMs in 200.0..600.0, "the replay stands at ${native.positionMs} ms\n${native.log}")
                    assertTrue(engine.positionMs() < 2_500L, "the engine reads ${engine.positionMs()} ms")
                    assertFalse(hazards.frozen, native.log.tail())
                }
            }
        }
    }

    // ---- 4: a speed after a loop wrap at normal speed ----

    /**
     * Twin of `a speed given right after a loop wrap at normal speed reaches the native player at once`, for a speed given
     * outside the seek window of the wrap (400 ms and 600 ms after it): nothing was deferred at the wrap (a wrap at normal
     * speed leaves the rate property alone), so the speed is written at the instant of the call, as ONE change, and the
     * rate property was never put away from 1.0 between the wrap and the call.
     */
    @Test
    fun aSpeedGivenAfterALoopWrapAtNormalSpeedReachesTheNativePlayerAtOnce() {
        runAll(listOf(400L, 600L), { "gap $it ms" }) { gapMs: Long ->
            fxModel {
                withEngine {
                    val wrapAt: Double = driveToFirstWrap(this, 1f)
                    advanceBy(gapMs)
                    val calledAt: Double = nowMs()
                    engine.setSpeed(2f)
                    advanceBy(100)

                    val rates: List<NativeCallEntry> = native.rateWritesFrom(wrapAt)
                    assertEquals(listOf(2.0), rates.map { it.arg }, "nothing was deferred at the wrap: one write, no drop\n${native.log}")
                    assertAt(calledAt, rates.single().tMs, "written at once\n${native.log}")
                    assertEquals(2.0, native.rate, "the property holds the speed")
                    assertFalse(hazards.frozen, native.log.tail())
                    assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
                }
            }
        }
    }

    /**
     * The same twin, for a speed given inside the seek window (0 to 250 ms after the wrap), which D-b changed: the
     * property is not touched at the wrap and not at the call; the speed is written once, as a plain change, when the
     * window has passed and the playhead has moved on. The real class's "at once" does not hold inside the window any more,
     * by design (M2 of the D2-PF1 review); outside the window it is the test above.
     */
    @Test
    fun aSpeedGivenInsideTheSeekWindowOfALoopWrapAtNormalSpeedIsGivenOnceAtTheEndOfTheWindow() {
        runAll(listOf(0L, 100L, 250L), { "gap $it ms" }) { gapMs: Long ->
            fxModel {
                withEngine {
                    val wrapAt: Double = driveToFirstWrap(this, 1f)
                    advanceBy(gapMs)
                    engine.setSpeed(2f)
                    advanceBy(1_000)

                    val rates: List<NativeCallEntry> = native.rateWritesFrom(wrapAt)
                    assertEquals(listOf(2.0), rates.map { it.arg }, "the property is not touched at the wrap: one write\n${native.log}")
                    assertTrue(
                        rates.single().tMs - wrapAt >= HazardConfig.DEFAULT_SEEK_RUN_MS - TIME_TOLERANCE_MS,
                        "the speed was written ${rates.single().tMs - wrapAt} ms after the wrap, inside the seek window\n${native.log}",
                    )
                    // Whatever the gap: the write is timed from the wrap (window 300 ms, then the first tick and 200 ms of movement).
                    assertAt(wrapAt + LOOP_HOLD_MS + LOOP_RATE_SETTLE_MS, rates.single().tMs, "the speed was written at the wrap + 500 ms\n${native.log}")
                    assertFalse(hazards.frozen, native.log.tail())
                    assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
                }
            }
        }
    }

    // ---- 5 and 6: the speed of a wrap at a speed, and a seek while it is deferred ----

    /**
     * Twin of `at a loop wrap the speed is given back only after the playhead has moved 200 ms`: a looping clip at a speed
     * of 2 drops the property to 1.0 at the wrap (the next cycle starts at normal speed) and gives the speed back as one
     * change once the playhead has moved 200 ms at normal speed, measured from the first tick after the wrap. The real class
     * reads the playhead at the event (190 ms to 1500 ms); the log has the native position of the call.
     */
    @Test
    fun atALoopWrapTheSpeedIsGivenBackOnlyAfterThePlayheadHasMoved200Ms() = fxModel {
        withEngine {
            val wrapAt: Double = driveToFirstWrap(this, 2f)
            advanceBy(1_500)

            val rates: List<NativeCallEntry> = native.rateWritesFrom(wrapAt)
            assertEquals(listOf(1.0, 2.0), rates.map { it.arg }, "the drop at the wrap and one change back\n${native.log}")
            assertAt(wrapAt, rates[0].tMs, "the drop is at the wrap\n${native.log}")
            val back: NativeCallEntry = rates[1]
            assertTrue(back.positionMs >= LOOP_RATE_SETTLE_MS, "the speed came back with the playhead at ${back.positionMs} ms: it had not moved 200 ms\n${native.log}")
            assertTrue(back.positionMs < 1_500.0, "the speed came back only at ${back.positionMs} ms\n${native.log}")
            assertTrue(back.tMs - wrapAt >= LOOP_RATE_SETTLE_MS, "the speed came back ${back.tMs - wrapAt} ms after the wrap\n${native.log}")
            // Exactly: the published time of the new cycle holds for 300 ms (LoopModel), the first tick after that marks where
            // the playhead stands, and 200 ms of movement later (two ticks) the speed is given, the playhead at 500 ms.
            assertAt(wrapAt + LOOP_HOLD_MS + LOOP_RATE_SETTLE_MS, back.tMs, "the speed came back at the wrap + 500 ms\n${native.log}")
            assertEquals(500.0, back.positionMs, "the playhead stood at 500 ms\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    /**
     * Twin of `a seek while the speed is deferred still gets the speed back`: the same wrap, a seek 50 ms after the drop
     * (the speed is held), and the speed arrives after the seek window of that seek (300 ms) and 200 ms of playhead movement
     * from the first tick after it, which is at least 500 ms after the seek and, on the 100 ms ticks, within 700 ms of it.
     */
    @Test
    fun aSeekWhileTheSpeedIsDeferredStillGetsTheSpeedBack() = fxModel {
        withEngine {
            val wrapAt: Double = driveToFirstWrap(this, 2f)
            advanceBy(50)
            assertEquals(listOf(1.0), native.rateWritesFrom(wrapAt).map { it.arg }, "the speed is held at the wrap\n${native.log}")
            val seekAt: Double = nowMs()
            engine.seekTo(500L)
            advanceBy(1_500)

            val rates: List<NativeCallEntry> = native.rateWritesFrom(wrapAt)
            assertEquals(listOf(1.0, 2.0), rates.map { it.arg }, "the drop, then the speed back once\n${native.log}")
            val back: NativeCallEntry = rates[1]
            assertTrue(back.tMs - seekAt >= SEEK_WINDOW_MS + LOOP_RATE_SETTLE_MS, "the speed came back ${back.tMs - seekAt} ms after the seek\n${native.log}")
            assertTrue(back.tMs - seekAt <= SEEK_WINDOW_MS + LOOP_RATE_SETTLE_MS + 2 * TICK_MS, "the speed came back only ${back.tMs - seekAt} ms after the seek\n${native.log}")
            // Exactly: the ticks of the playhead keep the grid of the wrap (every 100 ms). The seek came 50 ms after the wrap, so its
            // window ends at the wrap + 350 ms, the next tick (wrap + 400) marks where the playhead stands, and two ticks later
            // (wrap + 600, which is the seek + 550) the speed is given.
            assertAt(wrapAt + 600.0, back.tMs, "the speed came back at the wrap + 600 ms\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    // ---- 7 and 8: the fresh-play mark ----

    /**
     * Twin of `a speed changed a short while after a plain play is given as a resume and clears the fresh-play mark`: the
     * real class reads the private mark by reflection, the log shows its two consequences. A plain resume at normal speed
     * leaves the mark, so a speed given in the instant of the resume is NOT written at once: it is given as a resume gives
     * it, 200 ms of playhead movement after the first tick of the playhead (the ticks are 100 ms apart), which is 300 ms
     * after the play. That gift spends the mark, so a second speed 400 ms after the play (inside the 600 ms the mark would
     * have lasted) is written at once, as one plain change, with no further seek, play or away-and-back.
     */
    @Test
    fun aSpeedChangedAShortWhileAfterAPlainPlayIsGivenAsAResumeAndClearsTheFreshPlayMark() = fxModel {
        withEngine {
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(300)
            val playAt: Double = nowMs()
            engine.start()
            engine.setSpeed(2f)
            advanceBy(50)
            assertAt(playAt, native.log.calls(NativeCall.PLAY).last().tMs, "the resume plays at once\n${native.log}")
            assertEquals(emptyList(), native.rateWritesFrom(playAt).map { it.arg }, "the mark holds the speed back: nothing is written at once\n${native.log}")
            advanceBy(350)
            val secondAt: Double = nowMs()
            assertAt(playAt + 400.0, secondAt, "the second speed comes 400 ms after the play")
            val callsBefore: Int = native.log.size
            engine.setSpeed(3f)
            advanceBy(300)
            val commands: List<NativeCall> = native.log.entries.drop(callsBefore).map { it.call }.filter { it in COMMANDS }
            advanceBy(1_200)

            val rates: List<NativeCallEntry> = native.rateWritesFrom(playAt)
            assertEquals(listOf(2.0, 3.0), rates.map { it.arg }, native.log.tail())
            assertAt(playAt + 300.0, rates[0].tMs, "the first speed is given 300 ms after the play\n${native.log}")
            assertAt(secondAt, rates[1].tMs, "the second speed is written at once: the mark was spent\n${native.log}")
            assertEquals(listOf(NativeCall.SET_RATE), commands, "no further seek, play or away-and-back\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(3.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    /**
     * Twin of `a play after a native seek leaves no fresh-play mark`: after `seekTo(500)` and a play, a speed given
     * 350 ms later, past the seek window (300 ms) and inside what would be the fresh window of a play (600 ms), is written
     * at once as one plain change. With a fresh-play mark it would be held and given as a resume instead.
     */
    @Test
    fun aPlayAfterANativeSeekLeavesNoFreshPlayMark() = fxModel {
        withEngine {
            engine.seekTo(500L)
            engine.start()
            advanceBy(350)
            val calledAt: Double = nowMs()
            engine.setSpeed(2f)
            advanceBy(100)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            val rates: List<NativeCallEntry> = native.rateWritesFrom(0.0)
            assertEquals(listOf(2.0), rates.map { it.arg }, "the speed is the only rate written\n${native.log}")
            assertAt(calledAt, rates.single().tMs, "written at once, with no fresh-play mark to hold it back (play at $playAt)\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    // ---- 9 and 11: a seek next to a play is not undone ----

    private val stalePublishDelaysMs: List<Double> = listOf(0.0, 40.0, 120.0, 250.0)

    private data class SeekTrial(val publishDelayMs: Double, val gapMs: Long) {
        override fun toString(): String = "published position lags $publishDelayMs ms, gap $gapMs ms"
    }

    private fun seekTrials(): List<SeekTrial> = buildList {
        for (delay: Double in stalePublishDelaysMs) for (gap: Long in listOf(0L, 20L, 150L)) add(SeekTrial(delay, gap))
    }

    private fun scriptWith(publishDelayMs: Double): TimeScript = TimeScript().apply { this.publishDelayMs = publishDelayMs }

    /**
     * Twin of `a seek posted before a resume at a speed is not undone by the resume` (12 trials there, hitting the stale
     * native `currentTime` sometimes): the time part's `publishDelayMs` makes the published position lag the native one on
     * purpose, 12 trials = 4 lags x 3 gaps between the seek and the resume. With a speed in force, `seekTo(2000)` and
     * `start()`: the only native seek is the caller's (a seek to "where the player stands" would use the stale reading and
     * undo it), and 350 ms after the resume the playhead stands past 1900.
     */
    @Test
    fun aSeekPostedBeforeAResumeAtASpeedIsNotUndoneByTheResume() {
        runAll(seekTrials(), { it.toString() }) { trial: SeekTrial ->
            fxModel {
                withEngine(timeScript = scriptWith(trial.publishDelayMs)) {
                    engine.setSpeed(2f)
                    advanceBy(300)
                    engine.seekTo(2_000L)
                    advanceBy(trial.gapMs)
                    engine.start()
                    advanceBy(350)

                    assertEquals(listOf(2_000.0), native.log.calls(NativeCall.SEEK).map { it.arg }, "the resume issued a seek of its own\n${native.log}")
                    assertTrue(native.positionMs >= 1_900.0, "the playhead is at ${native.positionMs} ms after seekTo(2000) and a resume at speed 2\n${native.log}")
                    assertFalse(hazards.frozen, native.log.tail())
                }
            }
        }
    }

    /**
     * Twin of `a seek posted right after a play is not undone by a speed given in the fresh-play window` (12 trials, as
     * above): `start()`, `seekTo(2000)`, `setSpeed(2f)`. The only native seek is the caller's, the playhead stands past 1900
     * 350 ms later, and the speed reaches the decoder once, after the seek window.
     */
    @Test
    fun aSeekPostedRightAfterAPlayIsNotUndoneByASpeedGivenInTheFreshPlayWindow() {
        runAll(seekTrials(), { it.toString() }) { trial: SeekTrial ->
            fxModel {
                withEngine(timeScript = scriptWith(trial.publishDelayMs)) {
                    val calledAt: Double = nowMs()
                    engine.start()
                    advanceBy(trial.gapMs)
                    val seekAt: Double = nowMs()
                    engine.seekTo(2_000L)
                    engine.setSpeed(2f)
                    advanceBy(350)

                    assertEquals(listOf(2_000.0), native.log.calls(NativeCall.SEEK).map { it.arg }, "the speed issued a seek of its own\n${native.log}")
                    assertTrue(native.positionMs >= 1_900.0, "the playhead is at ${native.positionMs} ms after play, seekTo(2000), speed 2\n${native.log}")
                    advanceBy(1_000)
                    val rates: List<NativeCallEntry> = native.rateWritesFrom(calledAt)
                    assertEquals(2.0, rates.last().arg, "the speed arrived\n${native.log}")
                    assertEquals(1, rates.count { it.arg == 2.0 }, "once\n${native.log}")
                    assertTrue(
                        rates.single { it.arg == 2.0 }.tMs - seekAt >= SEEK_WINDOW_MS - TIME_TOLERANCE_MS,
                        "the speed was written ${rates.single { it.arg == 2.0 }.tMs - seekAt} ms after the seek, inside its window\n${native.log}",
                    )
                    assertFalse(hazards.frozen, native.log.tail())
                    assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
                }
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI: Double = 1_000_000.0

        /** The virtual clock is in nanoseconds and the log in milliseconds, with rounding at the 7th decimal. */
        const val TIME_TOLERANCE_MS: Double = 0.01

        /** `LOOP_RATE_SETTLE_MS` of `FxPlayback`: the distance the playhead moves before a deferred speed is given. */
        const val LOOP_RATE_SETTLE_MS: Double = 200.0

        /** How long the published time of a new loop cycle holds still, see `LoopModel`. */
        const val LOOP_HOLD_MS: Double = 300.0

        /** `SEEK_RATE_SETTLE_NANOS` of `FxPlayback`, in ms. */
        const val SEEK_WINDOW_MS: Double = 300.0

        /** The sample interval of the playhead, design B3. */
        const val TICK_MS: Double = 100.0

        const val MAX_STEPS: Int = 100_000

        /** The calls that change what the native player does; the log also holds the pulse and the snapshots. */
        val COMMANDS: Set<NativeCall> = setOf(NativeCall.PLAY, NativeCall.PAUSE, NativeCall.SEEK, NativeCall.SET_RATE)
    }
}
