package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The calibration scenarios of the JavaFX model (task J8): one canonical scenario per fragile window and fix family,
 * run through the real `JavaFxVideoEngine` and `FxPlayback` on the composed scripted player ([ComposedBackend]: time,
 * hazard and loop parts) in virtual time. Calibration rule of the design (4.2): every scenario is green on the current
 * code, and the mutation that reverts the fix of its family turns it red, deterministically.
 *
 * Each scenario is the first test of its family: R5 resume ([W-PLAY][FragileWindow.PLAY]), the idle gap
 * ([W-IDLE][FragileWindow.IDLE]), the loop wrap ([W-WRAP][FragileWindow.WRAP]), D2-PF1 and S9
 * ([W-SEEK-RUN][FragileWindow.SEEK_RUN]). The end-of-test hazard check of [FxModelScope.watchHazards] makes every one
 * of them also fail when ANY window is violated, so a mutation is caught by two independent assertions: the scenario's
 * own expectation (the playhead moves, the speed arrives) and the monitor.
 *
 * The engine is driven the way the shared player drives it: `start()` followed by `setSpeed()` for a play, `seekTo(0)`
 * and a play for a replay, `setSpeed()` only while playing. A call from "the FX thread" is made inside `onFx`; any
 * other call is made on the test thread, which `FxPlayback` sees as another thread and which the host queues.
 *
 * Timings are chosen by the scenarios, never by the model: the model's defaults (design 4.1, 4.2) are not touched.
 */
class CalibrationModelTest {

    private class Loaded(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
        val hazards: HazardModel get() = composed.hazards
        val time: TimeModel get() = composed.time
    }

    private fun FxModelScope.loadClip(config: HazardConfig = HazardConfig(), body: Loaded.() -> Unit) {
        val scripted = ScriptedBackend(host)
        val backend: ComposedBackend = composedBackend(scripted, hazardConfig = config)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Loaded(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    /** The shared player's `play()`: `engine.start()` then the speed in force. */
    private fun JavaFxVideoEngine.sharedPlay(speed: Float) {
        start()
        setSpeed(speed)
    }

    /** The shared player's `replay()` of a playing clip: a seek to 0, then a play at the speed in force. */
    private fun JavaFxVideoEngine.sharedReplay(speed: Float) {
        seekTo(0L)
        sharedPlay(speed)
    }

    // ---- R5 resume: a plain resume at a speed other than 1 (W-PLAY) ----

    @Test
    fun aResumeAtSpeedGivesTheSpeedOnlyAfterTheDecoderHasRunForAMoment() = fxModel {
        loadClip {
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(100)
            engine.setSpeed(3f)
            advanceBy(500)
            val position: Double = native.positionMs
            assertTrue(position in 800.0..1_000.0, "the playhead stands past the start of the clip, was $position")

            engine.start()
            advanceBy(2_000)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            val rates: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs > playAt }
            assertEquals(listOf(1.0, 3.0), rates.map { it.arg }, "the resume gives one away-and-back\n${native.log}")
            assertTrue(
                rates.first().tMs - playAt >= HazardConfig.DEFAULT_PLAY_MS,
                "the speed came ${rates.first().tMs - playAt} ms after the play, inside W-PLAY\n${native.log}",
            )
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(3.0, hazards.effectiveRate)
            assertTrue(native.positionMs > position + 1_000.0, "the playhead moved on at speed, was ${native.positionMs}")
        }
    }

    @Test
    fun aSpeedGivenRightAfterAPlainResumeIsGivenOnlyAfterTheDecoderHasRunForAMoment() = fxModel {
        loadClip {
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(300)
            val position: Double = native.positionMs
            assertTrue(position in 800.0..1_000.0, "the playhead stands past the start of the clip, was $position")

            // The shared player's resume at a speed it holds: start() at the speed of the property (1.0), then the speed.
            engine.sharedPlay(3f)
            advanceBy(2_000)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            val rates: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs > playAt }
            // The property held 1.0, so the away write of the away-and-back is no change (B1): one effective rate.
            assertEquals(listOf(3.0), rates.map { it.arg }, "the speed reached the native player once\n${native.log}")
            assertTrue(
                rates.first().tMs - playAt >= HazardConfig.DEFAULT_PLAY_MS,
                "the speed came ${rates.first().tMs - playAt} ms after the play, inside W-PLAY\n${native.log}",
            )
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(3.0, hazards.effectiveRate)
            assertTrue(native.positionMs > position + 1_000.0, "the playhead moved on at speed, was ${native.positionMs}")
        }
    }

    // ---- the idle gap: a play right after a rate written while not playing (W-IDLE) ----

    @Test
    fun aResumeRightAfterASpeedWrittenWhilePausedWaitsOutTheIdleGap() = fxModel {
        loadClip {
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(100)
            engine.setSpeed(3f)
            engine.start()
            advanceBy(2_000)

            val written: Double = native.log.calls(NativeCall.SET_RATE).first { it.effective && it.arg == 3.0 }.tMs
            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            assertTrue(playAt - written >= HazardConfig.DEFAULT_IDLE_MS, "the play came ${playAt - written} ms after the rate\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(3.0, hazards.effectiveRate)
            assertTrue(native.positionMs > 1_900.0, "the playhead moved on, was ${native.positionMs}")
        }
    }

    // ---- the loop wrap (W-WRAP) ----

    /**
     * QUESTION J8-W1 (see the J8 report): on the CURRENT code the fake flags a loop wrap at a speed other than 1. At the
     * wrap `FxPlayback.deferRate` writes the property 1.0 (the decoder already runs at 1.0 there, the loop part sets
     * it, B8), and the hazard part counts that write as a rate change inside W-WRAP and freezes the decoder. The code is
     * green on real media (`JavaFxLoopSpeedR5AdversarialTest`), so the model is stricter than the measured native
     * behaviour: a write of the rate the decoder already plays at has no native effect. The advisor decides; this test
     * pins what the composition does today, with an UNWIRED monitor, and is to be replaced by the wrap scenario of the
     * ruling once the model is settled.
     */
    @Test
    fun theFakeFlagsTheUnityWriteOfALoopWrapAtSpeedOnTheCurrentCode() = fxModel {
        val scripted = ScriptedBackend(host)
        val backend: ComposedBackend = composedBackend(scripted, watch = false)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            val native: ScriptedNativePlayer = backend.last.player
            val hazards: HazardModel = backend.last.hazards
            engine.setLooping(true)
            engine.setSpeed(2f)
            advanceBy(300)
            engine.sharedPlay(2f)
            val steps: Int = advanceUntil { backend.last.loop.cycle == 2 }
            val wrapAt: Double = host.elapsedNanos / 1_000_000.0
            assertTrue(steps > 0, "the clip wrapped")

            // The composition: the time part reached the end, the loop part wrapped the playhead and told the hazard
            // part, which opened W-WRAP before onEndOfMedia reached FxPlayback, and FxPlayback answered with deferRate.
            assertEquals(0.0, native.positionMs, "the loop part wrapped the playhead to the start")
            assertEquals(setOf(FragileWindow.WRAP), hazards.openWindows)
            val violation: HazardViolation = hazards.violations.single()
            assertEquals(FragileWindow.WRAP, violation.window)
            assertEquals(NativeCall.SET_RATE, violation.trigger)
            assertEquals(1.0, violation.arg, "the flagged write is the unity write of deferRate")
            assertEquals(wrapAt, violation.tMs, "it is flagged at the instant of the wrap")
            assertTrue(hazards.frozen, "the decoder froze: the model's own consequence of the flag")
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    /**
     * The composition of the three parts, on a loop wrap that needs no speed: the time part reaches the end of the cycle
     * and calls the loop part (nothing else does), the loop part wraps the playhead and opens W-WRAP through the hazard
     * part, `onEndOfMedia` reaches `FxPlayback` as a loop and not as an end, and the next cycle wraps again.
     */
    @Test
    fun aLoopWrapAtNormalSpeedRunsThroughTheTimeLoopAndHazardPartsAndNeverEnds() = fxModel {
        loadClip {
            engine.setLooping(true)
            engine.sharedPlay(1f)
            advanceBy(100)
            assertEquals(1, composed.loop.cycle)
            advanceUntil { composed.loop.cycle == 2 }
            assertEquals(setOf(FragileWindow.WRAP), hazards.openWindows, "the wrap opened W-WRAP")
            assertEquals(0.0, native.positionMs, "the playhead wrapped to the start")
            advanceBy(HazardConfig.DEFAULT_WRAP_MS.toLong())
            assertEquals(emptySet(), hazards.openWindows, "W-WRAP closed after its width")
            advanceUntil { composed.loop.cycle == 3 }
            assertEquals(0, time.endsReached, "a wrap is not an end of the source")
            assertEquals(1, native.log.calls(NativeCall.PLAY).size, "one play was enough to go on looping")
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(emptyList(), native.log.rates().filter { it != 1.0 }, "no speed was involved")
        }
    }

    // ---- D2-PF1: a speed right after a replay of a playing clip (W-SEEK-RUN) ----

    @Test
    fun aSpeedGivenRightAfterAReplayOfAPlayingClipNeverFreezesThePlayhead() = fxModel {
        loadClip {
            engine.sharedPlay(1f)
            advanceBy(300)
            onFx {
                engine.sharedReplay(1f)
                engine.sharedReplay(1f)
                engine.setSpeed(1.5f)
            }
            advanceBy(2_000)

            assertFalse(hazards.frozen, native.log.tail())
            assertTrue(native.positionMs >= 1_500.0, "the playhead moved for 2 s after the 300 ms window, was ${native.positionMs}")
            assertEquals(1.5, hazards.effectiveRate, "the deferred speed was given once the window closed\n${native.log}")
        }
    }

    // ---- S9: a second seek while a deferred speed waits (W-SEEK-RUN again, the same predicate) ----

    @Test
    fun aSeekWhileADeferredSpeedWaitsRestartsTheWaitInsteadOfFreezingThePlayhead() = fxModel {
        loadClip {
            engine.sharedPlay(1f)
            advanceBy(300)
            // A replay then a speed: the speed is deferred past W-SEEK-RUN (D2-PF1), the property held at 1.0.
            val replayAt: Double = host.elapsedNanos / 1_000_000.0
            onFx {
                engine.sharedReplay(1f)
                engine.setSpeed(1.5f)
            }
            advanceBy(450)
            assertTrue(
                native.log.calls(NativeCall.SET_RATE).none { it.effective && it.tMs >= replayAt },
                "no speed was given yet\n${native.log}",
            )
            // The wait has passed its 300 ms and is counting the playhead's own movement; a seek restarts it.
            engine.seekTo(2_000L)
            advanceBy(2_000)

            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(1.5, hazards.effectiveRate, "the deferred speed was given once the new window closed\n${native.log}")
            val seekAt: Double = native.log.calls(NativeCall.SEEK).last { it.arg == 2_000.0 }.tMs
            val given: NativeCallEntry = native.log.calls(NativeCall.SET_RATE).last { it.effective }
            assertTrue(
                given.tMs - seekAt >= HazardConfig.DEFAULT_SEEK_RUN_MS,
                "the speed came ${given.tMs - seekAt} ms after the seek\n${native.log}",
            )
        }
    }

    /** Advances in steps of 10 virtual ms until [condition] holds, at most 10 s; returns the steps taken. */
    private fun FxModelScope.advanceUntil(condition: () -> Boolean): Int {
        var steps = 0
        while (!condition()) {
            check(steps < 1_000) { "the condition did not hold within 10 s of virtual time ($host)" }
            advanceBy(10)
            steps++
        }
        return steps
    }
}
