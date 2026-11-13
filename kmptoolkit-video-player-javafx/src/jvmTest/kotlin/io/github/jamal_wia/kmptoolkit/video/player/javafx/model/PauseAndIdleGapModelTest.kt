package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RED FIRST tests of task D-pi (ruling `reviews/fxport-j13a-opus.md`, decision D-95): the pause order and the idle gap
 * before a play, on the composed and watched scripted player ([composeAll]) in virtual time. Part 1 of 3 of the task: these
 * tests are red on the current `FxPlayback` by design, the fixes (A1, A2, C1 to C4 of the ruling) come in part 2 and 3,
 * and this branch merges only with them.
 *
 * Each test stands for one seed of the J13a sequence fuzzer (`SequenceFuzzModelTest`, 2000 seeds, replay one with
 * `-Pkmptoolkit.test.seed=<n>`) and keeps only the shape that makes that seed fail, with the clock exact (no jitter) and
 * every call at an instant the test names:
 * - the pause site (class A): [aSpeedGivenInsideThePlayWindowIsNotWrittenBeforeThePauseThatFollowsIt] (seeds 211 and 369);
 * - the end-of-media site (class A): [aSpeedHeldAtTheEndOfAClipIsNotWrittenBeforeThePauseThatEndsIt] (seed 1424);
 * - the idle gap before a play on the seek path (class C): [aPlayRightAfterANudgeOvertakenByAStopWaitsOutTheIdleGap]
 *   (seed 467);
 * - the idle gap created by an A-only fix (class C): [aPauseAndAPlayInTheSameInstantWithAHeldSpeedAndAStaleSeekMarkDoNotBreakTheIdleGap]
 *   (seed 1369), which is GREEN on the current code and turns red under the pause reorder alone, see its KDoc;
 * - a speed given during the delayed plain play (class C): [aSpeedGivenWhileAPlainPlayWaitsOutTheIdleGapIsHeldNotWritten]
 *   (seed 943);
 * - the lost speed of the seek-path hole (C4): [aSpeedGivenAfterAPauseThatInterruptedADelayedSeekPathPlayIsNotLost].
 *
 * The contract the tests read, not the implementation: a native `pause()` is never preceded by a rate write on the same
 * decoder within W-PLAY or W-SEEK-RUN (a property write after the pause is a write on a player that does not run); a
 * `play()` is never issued within the idle gap (200 ms) of a rate written while the player did not run, unless a native
 * seek came in between (the model's MUST-2 reading); a speed the caller gave is never lost.
 */
class PauseAndIdleGapModelTest {

    // ---- rig ----

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.withEngine(clip: ClipModel = ClipModel(), body: Rig.() -> Unit) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, clip = clip))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / NANOS_PER_MILLI

    /** Runs [cases] each in its own model and fails with every failing label, like the other family files. */
    private fun <T> runAll(cases: List<T>, label: (T) -> String, run: (T) -> Unit) {
        val failures: MutableList<String> = mutableListOf()
        for (case: T in cases) {
            try {
                run(case)
            } catch (t: Throwable) {
                failures += "[${label(case)}] ${t.message?.lineSequence()?.take(MESSAGE_LINES)?.joinToString("\n")}"
            }
        }
        assertEquals(emptyList(), failures, "${failures.size} of ${cases.size} cases failed")
    }

    /** An assertion whose message is built only when it fails (several messages read the first element of a list). */
    private fun expect(condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError(message())
    }

    private fun assertAt(expected: Double, actual: Double, message: String) =
        assertEquals(expected, actual, TIME_TOLERANCE_MS, message)

    /** The decoder did not freeze and no fragile window was violated, with the whole call log in the message. */
    private fun Rig.assertSound(what: String) {
        assertTrue(!composed.hazards.frozen, "$what: the decoder froze\n${native.trace()}")
        val violations: List<HazardViolation> = composed.hazards.violations
        expect(violations.isEmpty()) { "$what: a fragile window was violated: ${violations.first().describe()}\n${native.trace()}" }
    }

    /**
     * The rate writes that changed the property while the decoder did not run: the commands decide, as in the hazard model
     * (the native status lags a command), so a write is idle when the last PLAY or PAUSE before it was a PAUSE, or there was
     * none. Each one with the index of its entry in the log.
     */
    private fun ScriptedNativePlayer.idleWrites(): List<Pair<Int, NativeCallEntry>> {
        var running = false
        val writes: MutableList<Pair<Int, NativeCallEntry>> = mutableListOf()
        for ((index: Int, entry: NativeCallEntry) in log.entries.withIndex()) {
            when {
                entry.call == NativeCall.PLAY -> running = true
                entry.call == NativeCall.PAUSE -> running = false
                entry.call == NativeCall.SET_RATE && entry.effective && !running -> writes += index to entry
            }
        }
        return writes
    }

    /**
     * The first PLAY entry at or after [fromMs] that stands inside the idle gap of an idle write ([idleWrites]) with no
     * native seek between the write and the play (the model's MUST-2 reading); null when there is none. The contract of the
     * idle gap, from the platform notes: a play right next to a rate write froze the playhead in the measurements, the gap
     * is 200 ms.
     */
    private fun ScriptedNativePlayer.playInsideTheIdleGap(fromMs: Double): NativeCallEntry? {
        val entries: List<NativeCallEntry> = log.entries
        val writes: List<Pair<Int, NativeCallEntry>> = idleWrites()
        for ((index: Int, entry: NativeCallEntry) in entries.withIndex()) {
            if (entry.call != NativeCall.PLAY || entry.tMs < fromMs - TIME_TOLERANCE_MS) continue
            val write: Pair<Int, NativeCallEntry> = writes.lastOrNull { it.first < index } ?: continue
            val seekBetween: Boolean = entries.subList(write.first, index).any { it.call == NativeCall.SEEK }
            if (!seekBetween && entry.tMs - write.second.tMs < IDLE_GAP_MS - TIME_TOLERANCE_MS) return entry
        }
        return null
    }

    /** The commands of the log without the picture snapshots and the pulse, the last [count]: what a failure needs to show. */
    private fun ScriptedNativePlayer.trace(count: Int = 24): String {
        val shown: List<NativeCallEntry> = log.entries.filter {
            it.call != NativeCall.SNAPSHOT && it.call != NativeCall.START_PULSE && it.call != NativeCall.STOP_PULSE
        }
        return "native commands (the last $count of ${shown.size}, snapshots and pulse left out):" +
            shown.takeLast(count).joinToString(separator = "") { "\n  $it" }
    }

    // ---- 1: the pause site (seeds 211 and 369) ----

    private enum class Caller { OTHER_THREAD, FX_THREAD }

    private data class PauseCase(val waitAfterPlayMs: Long, val speed: Float, val caller: Caller) {
        override fun toString(): String = "play, $waitAfterPlayMs ms, setSpeed($speed) and pause from $caller"
    }

    /**
     * A speed given within 100 ms of a plain play (seed 369: 80 ms, speed 2; seed 211: 80 ms, speed 0.5), then a pause in
     * the same instant. The speed is held for a later settle (a rate change right after a plain play can freeze the
     * decoder), and the pause writes it: the native `pause()` must come FIRST, then the property write, never a rate write
     * on the running decoder inside W-PLAY. After the pause the property holds the speed for the next start.
     */
    @Test
    fun aSpeedGivenInsideThePlayWindowIsNotWrittenBeforeThePauseThatFollowsIt() {
        val cases: List<PauseCase> = buildList {
            for (wait: Long in listOf(60L, 80L)) {
                for (speed: Float in listOf(2f, 0.5f)) {
                    add(PauseCase(wait, speed, Caller.OTHER_THREAD))
                    add(PauseCase(wait, speed, Caller.FX_THREAD))
                }
            }
        }
        runAll(cases, { it.toString() }) { case: PauseCase ->
            fxModel {
                withEngine {
                    engine.start()
                    advanceBy(case.waitAfterPlayMs)
                    val at: Double = nowMs()
                    when (case.caller) {
                        Caller.OTHER_THREAD -> {
                            engine.setSpeed(case.speed)
                            engine.pause()
                        }
                        Caller.FX_THREAD -> onFx {
                            engine.setSpeed(case.speed)
                            engine.pause()
                        }
                    }
                    advanceBy(500)

                    val play: NativeCallEntry = native.log.calls(NativeCall.PLAY).first()
                    val pause: NativeCallEntry = native.log.calls(NativeCall.PAUSE).first { it.tMs >= at - TIME_TOLERANCE_MS }
                    assertAt(at, pause.tMs, "the pause reached the native player at the instant it was called\n${native.trace()}")
                    val written: List<NativeCallEntry> =
                        native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs >= play.tMs }
                    val entries: List<NativeCallEntry> = native.log.entries
                    expect(written.all { entries.indexOf(it) > entries.indexOf(pause) }) {
                        "a rate was written on the running decoder, ${pause.tMs - written.first().tMs} ms before the pause " +
                            "and ${written.first().tMs - play.tMs} ms after the play, inside W-PLAY\n${native.trace()}"
                    }
                    assertEquals(
                        case.speed.toDouble(), native.log.rates().last(),
                        "the pause did not leave the speed in the property for the next start\n${native.trace()}",
                    )
                    assertSound("pause site")
                }
            }
        }
    }

    // ---- 2: the end-of-media site (seed 1424) ----

    private data class EndCase(val seekToMs: Long, val speedAfterPlayMs: Long, val speed: Float) {
        override fun toString(): String = "seekTo($seekToMs), a second play, setSpeed($speed) $speedAfterPlayMs ms after it"
    }

    /**
     * Seed 1424: a speed given 20 ms after a plain play, and the clip ends 57 ms after that play (non-looping). The held
     * speed is written by `onEndOfMedia`: the native `pause()` it issues must come FIRST, the property write after it,
     * never a rate write on the decoder that runs inside W-PLAY. The shape needs a plain play with no seek before it (the
     * fresh-play mark), so the clip is first played and paused after a seek near its end, then played again.
     */
    @Test
    fun aSpeedHeldAtTheEndOfAClipIsNotWrittenBeforeThePauseThatEndsIt() {
        val cases: List<EndCase> = listOf(
            EndCase(2_940L, 10L, 2f),
            EndCase(2_940L, 20L, 0.5f),
            EndCase(2_920L, 20L, 2f),
        )
        runAll(cases, { it.toString() }) { case: EndCase ->
            fxModel {
                withEngine(ClipModel(keyFrameIntervalMs = 10.0)) {
                    engine.seekTo(case.seekToMs)
                    engine.start()
                    advanceBy(10)
                    engine.pause()
                    advanceBy(400)
                    engine.start()
                    advanceBy(case.speedAfterPlayMs)
                    engine.setSpeed(case.speed)
                    advanceBy(1_000)

                    val play: NativeCallEntry = native.log.calls(NativeCall.PLAY).last()
                    val end: NativeCallEntry = native.log.calls(NativeCall.PAUSE).last { it.tMs > play.tMs }
                    assertTrue(end.tMs - play.tMs < 100.0, "the clip did not end inside W-PLAY of the second play (${end.tMs - play.tMs} ms)\n${native.trace()}")
                    val written: List<NativeCallEntry> =
                        native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs >= play.tMs }
                    val entries: List<NativeCallEntry> = native.log.entries
                    expect(written.all { entries.indexOf(it) > entries.indexOf(end) }) {
                        "a rate was written on the running decoder ${end.tMs - written.first().tMs} ms before the end of media " +
                            "pause, ${written.first().tMs - play.tMs} ms after the play, inside W-PLAY\n${native.trace()}"
                    }
                    assertEquals(
                        case.speed.toDouble(), native.log.rates().last(),
                        "the end of the clip did not leave the speed in the property for the next start\n${native.trace()}",
                    )
                    assertSound("end-of-media site")
                }
            }
        }
    }

    // ---- 3: a nudge overtaken by a stop, then a play 20 ms later (seed 467) ----

    /**
     * Seed 467: a resume with a seek pending leaves the speed as a nudge two FX tasks after the play; a pause and a seek (a
     * `stop()`) overtake it, so the nudge finds the player paused and writes the speed to the property (an idle write, class
     * C1). The next play follows 20 ms later through the seek path (the stop's seek is still marked), which must wait out the
     * rest of the 200 ms idle gap: the seek came BEFORE the write, so nothing clears the gap. The speed 2 reaches the decoder
     * after that play, and the decoder never freezes.
     */
    @Test
    fun aPlayRightAfterANudgeOvertakenByAStopWaitsOutTheIdleGap() {
        runAll(listOf(1L, 20L, 80L), { "play $it ms after the stop" }) { playAfterMs: Long ->
            fxModel {
                withEngine {
                    advanceBy(100)
                    engine.setSpeed(3f)
                    advanceBy(300)
                    engine.seekTo(900)
                    advanceBy(10)
                    onFx {
                        engine.start()
                        engine.setSpeed(2f)
                        engine.pause()
                        engine.seekTo(914)
                    }
                    advanceBy(playAfterMs)
                    val resumeAt: Double = nowMs()
                    engine.start()
                    advanceBy(2_500)

                    val writeBeforeResume: NativeCallEntry =
                        native.idleWrites().map { it.second }.last { it.tMs <= resumeAt + TIME_TOLERANCE_MS }
                    val play: NativeCallEntry = native.log.calls(NativeCall.PLAY).first { it.tMs >= resumeAt - TIME_TOLERANCE_MS }
                    expect(play.tMs - writeBeforeResume.tMs >= IDLE_GAP_MS - TIME_TOLERANCE_MS) {
                        "the play came ${play.tMs - writeBeforeResume.tMs} ms after the rate write of the overtaken nudge " +
                            "(idle gap $IDLE_GAP_MS ms)\n${native.trace()}"
                    }
                    assertEquals(null, native.playInsideTheIdleGap(resumeAt), "a play inside the idle gap\n${native.trace()}")
                    assertEquals(2.0, composed.hazards.effectiveRate, "the speed never reached the decoder\n${native.trace()}")
                    assertSound("nudge overtaken by stop")
                }
            }
        }
    }

    // ---- 4: pause and play in the same instant, a held speed, a stale seek mark (seed 1369) ----

    /**
     * Seed 1369: a plain play, a seek of the playing clip (the seek mark stays set until the next start consumes it), then
     * a speed given 400 ms into the play: the seek window and the play window are over, so it is held for the settle. Then a
     * pause and a play in the same instant. On the current code this is GREEN: the pause writes the held speed BEFORE its
     * `native.pause()`, on a decoder with no window open, so there is no idle write for the play to follow. It is RED under
     * the pause reorder alone (class A1 without C): the moved write is an idle write, and the resume takes the seek path on
     * the stale mark and plays at once, inside the gap. It must be GREEN again with the whole fix (the resume waits the gap
     * out). The test states the contract, not the order: no play inside the idle gap, no violation, and the speed reaches
     * the decoder.
     */
    @Test
    fun aPauseAndAPlayInTheSameInstantWithAHeldSpeedAndAStaleSeekMarkDoNotBreakTheIdleGap() {
        runAll(listOf(Caller.OTHER_THREAD, Caller.FX_THREAD), { "pause and play from $it" }) { caller: Caller ->
            fxModel {
                withEngine {
                    engine.start()
                    engine.seekTo(1_886)
                    advanceBy(400)
                    engine.setSpeed(1.5f)
                    advanceBy(150)
                    val at: Double = nowMs()
                    when (caller) {
                        Caller.OTHER_THREAD -> {
                            engine.pause()
                            engine.start()
                        }
                        Caller.FX_THREAD -> onFx {
                            engine.pause()
                            engine.start()
                        }
                    }
                    advanceBy(2_500)

                    assertEquals(null, native.playInsideTheIdleGap(at), "a play inside the idle gap\n${native.trace()}")
                    assertEquals(1.5, composed.hazards.effectiveRate, "the speed never reached the decoder\n${native.trace()}")
                    assertSound("pause and play in the same instant")
                }
            }
        }
    }

    // ---- 5: a speed given during the delayed plain play (seed 943) ----

    /**
     * Seed 943: a rate written while the player does not run, back to normal speed (an idle write of 1.0), then a play 20 ms
     * later through the PLAIN path (rate 1.0), which must wait out the gap like every other resume; a speed that arrives
     * during that wait is HELD (a write during the gap would be another idle write right before the play) and given after
     * the play as a settle. So: no rate write between the idle write and the play, the play no sooner than 200 ms after it,
     * and the speed the caller gave in the meantime reaches the decoder.
     */
    @Test
    fun aSpeedGivenWhileAPlainPlayWaitsOutTheIdleGapIsHeldNotWritten() {
        runAll(listOf(20L, 60L), { "play $it ms after the write to 1.0" }) { playAfterMs: Long ->
            fxModel {
                withEngine {
                    advanceBy(100)
                    engine.setSpeed(2f)
                    advanceBy(300)
                    engine.setSpeed(1f)
                    advanceBy(playAfterMs)
                    val writeAt: Double = native.idleWrites().map { it.second }.last().tMs
                    engine.start()
                    advanceBy(20)
                    engine.setSpeed(1.5f)
                    advanceBy(2_500)

                    val play: NativeCallEntry = native.log.calls(NativeCall.PLAY).last()
                    assertTrue(
                        play.tMs - writeAt >= IDLE_GAP_MS - TIME_TOLERANCE_MS,
                        "the play came ${play.tMs - writeAt} ms after the idle write (gap $IDLE_GAP_MS ms)\n${native.trace()}",
                    )
                    val duringTheWait: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE)
                        .filter { it.effective && it.tMs > writeAt + TIME_TOLERANCE_MS && it.tMs < play.tMs - TIME_TOLERANCE_MS }
                    expect(duringTheWait.isEmpty()) { "a rate was written during the wait: ${duringTheWait.first()}\n${native.trace()}" }
                    assertTrue(
                        play.tMs - writeAt <= IDLE_GAP_MS + PLAY_LATE_MS,
                        "the play came ${play.tMs - writeAt} ms after the idle write, more than $PLAY_LATE_MS ms after the end of the gap\n${native.trace()}",
                    )
                    assertEquals(1.5, composed.hazards.effectiveRate, "the speed given during the wait never reached the decoder\n${native.trace()}")
                    assertSound("speed during the delayed plain play")
                }
            }
        }
    }

    // ---- 6: the lost speed of the hole C4 ----

    /**
     * The hole of the ruling's prototype (C4): a resume inside the idle gap on the seek path is delayed, its nudge is
     * pending; a pause before the gap ends cancels the play but leaves the nudge pending, and the next speeds are swallowed
     * into it and lost. Here: a speed written while paused, a seek, a resume 20 ms later (delayed), a pause 40 or 120 ms
     * after it (before the gap ends), then a NEW speed and a play: the new speed must reach the decoder. On the current code
     * this is GREEN: no play is delayed, so no nudge is left pending. It is RED under the prototype of C2 and C3 without C4
     * (the new speed is swallowed into the stale nudge and never written), and GREEN under the whole prototype. It is the pin
     * the ruling asks for on a hole the fuzzers cannot see: their checks are freeze and stuck, not a lost speed.
     */
    @Test
    fun aSpeedGivenAfterAPauseThatInterruptedADelayedSeekPathPlayIsNotLost() {
        runAll(listOf(40L, 120L), { "pause $it ms after the resume" }) { pauseAfterMs: Long ->
            fxModel {
                withEngine {
                    advanceBy(100)
                    engine.setSpeed(2f)
                    engine.seekTo(500)
                    advanceBy(20)
                    engine.start()
                    advanceBy(pauseAfterMs)
                    engine.pause()
                    advanceBy(300)
                    engine.setSpeed(3f)
                    advanceBy(10)
                    engine.start()
                    advanceBy(2_500)

                    assertEquals(
                        3.0, composed.hazards.effectiveRate,
                        "the speed given after the pause never reached the decoder: it was swallowed by a nudge left pending\n${native.trace()}",
                    )
                    assertEquals(3.0, native.log.rates().last(), "the last rate written is not the speed the caller gave\n${native.trace()}")
                    assertSound("speed after an interrupted delayed play")
                }
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI: Double = 1_000_000.0

        /** How many lines of a failure each case reports: the verdict and the head of the call log. */
        const val MESSAGE_LINES: Int = 30

        /** The virtual clock is in nanoseconds and the log in milliseconds. */
        const val TIME_TOLERANCE_MS: Double = 0.001

        /** `IDLE_RATE_GAP_NANOS` of `FxPlayback`, the measured gap of a play after a rate write (fa36712d). */
        const val IDLE_GAP_MS: Double = 200.0

        /** How much later than the end of the gap a delayed play may come. */
        const val PLAY_LATE_MS: Double = 5.0
    }
}
