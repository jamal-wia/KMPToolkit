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
        val monitor: HazardMonitor get() = composed.monitor
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
     * W-WRAP, the wrap at a speed (ruling 1 of the J5-J8 review): looping at 2x, the time part reaches the end, the loop
     * part sets the decoder to 1.0 (B8) and opens W-WRAP, and `FxPlayback.onEndOfMedia` writes the property 2.0 -> 1.0
     * through `deferRate`. That write changes nothing native (the decoder already plays at 1.0), so it is no violation;
     * the speed is given back once, after the wrap window, and the monitor is wired.
     */
    @Test
    fun aLoopWrapAtSpeedGivesTheSpeedBackOnlyAfterTheWrapWindow() = fxModel {
        loadClip {
            engine.setLooping(true)
            engine.setSpeed(2f)
            advanceBy(300)
            engine.sharedPlay(2f)
            advanceUntil { composed.loop.cycle == 2 }
            val wrapAt: Double = host.elapsedNanos / 1_000_000.0
            advanceBy(700)

            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(2.0, hazards.effectiveRate, "the speed was given back\n${native.log}")
            val given: NativeCallEntry = native.log.calls(NativeCall.SET_RATE).last { it.effective }
            assertTrue(given.tMs - wrapAt >= HazardConfig.DEFAULT_WRAP_MS, "given ${given.tMs - wrapAt} ms after the wrap\n${native.log}")
        }
    }

    /**
     * S9 in its wrap form: the speed after a wrap waits (deferred past W-WRAP), and a seek that arrives while it waits
     * restarts the wait (W-SEEK-RUN) instead of freezing the playhead; the speed is then given once, after the new
     * window, and the monitor is wired.
     */
    @Test
    fun aSeekWhileTheSpeedAfterALoopWrapWaitsRestartsTheWaitInsteadOfFreezingThePlayhead() = fxModel {
        loadClip {
            engine.setLooping(true)
            engine.setSpeed(2f)
            advanceBy(300)
            engine.sharedPlay(2f)
            advanceUntil { composed.loop.cycle == 2 }
            advanceBy(450)
            engine.seekTo(2_000L)
            advanceBy(600)

            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(2.0, hazards.effectiveRate, "the speed was given back\n${native.log}")
            val seekAt: Double = native.log.calls(NativeCall.SEEK).last { it.arg == 2_000.0 }.tMs
            val given: NativeCallEntry = native.log.calls(NativeCall.SET_RATE).last { it.effective }
            assertTrue(
                given.tMs - seekAt >= HazardConfig.DEFAULT_SEEK_RUN_MS,
                "the speed came ${given.tMs - seekAt} ms after the seek\n${native.log}",
            )
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
            assertTrue(native.positionMs >= 2_000.0, "the playhead moved for 2 s after the 300 ms window, was ${native.positionMs}")
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

    // ---- twins of real-media sequences (calibration rule (a): a sequence real media runs green is green here too) ----

    /**
     * Twin (i) of `JavaFxVideoEngineTest.speedSetWhilePausedHoldsAfterResuming`: played from 2 s, paused, a speed written
     * while paused, a seek back to 0, then `start()` (the seeked path of `start`, which plays 0 ms after the idle write).
     * Real media is green for it, so the monitor must be too.
     */
    @Test
    fun theTwinOfASpeedSetWhilePausedHoldingAfterResumingIsGreenWithTheMonitorWired() = fxModel {
        loadClip {
            engine.seekTo(2_000L)
            engine.start()
            advanceUntil { engine.positionMs() >= 2_100L }
            engine.pause()
            engine.setSpeed(2f)
            engine.seekTo(0L)
            engine.start()
            advanceUntil { engine.positionMs() in 100L until 1_500L }
            val from: Long = engine.positionMs()
            advanceBy(1_000)
            val covered: Long = engine.positionMs() - from

            assertFalse(hazards.frozen, native.log.tail())
            assertTrue(covered >= 1_500L, "covered $covered ms of media in 1 s at speed 2\n${native.log}")
            println("STRICT[speedSetWhilePausedHoldsAfterResuming] ${monitor.strictReport()}")
        }
    }

    /**
     * Twin (ii) of `JavaFxLoopSpeedR5AdversarialTest` (A14): looping, `setSpeed(4f)`, `start()`, until the third cycle.
     * The slope of the second cycle between 300 and 2300 ms of the playhead is at least 2.5, on the native positions
     * sampled every 15 virtual ms (the real test samples `positionMs()` every 15 ms).
     */
    @Test
    fun theTwinOfASpeedOfFourAcrossLoopWrapsKeepsTheSpeedInTheSecondCycle() = fxModel {
        loadClip {
            engine.setLooping(true)
            engine.setSpeed(4f)
            engine.start()
            val cycles: MutableList<MutableList<Pair<Double, Double>>> = mutableListOf(mutableListOf())
            var last = 0.0
            var steps = 0
            while (cycles.size < 3) {
                check(steps++ < 600) { "the source wrapped only ${cycles.size - 1} times in 9 s\n${native.log}" }
                val position: Double = time.nativePositionMs
                if (position + 1_000.0 < last) cycles += mutableListOf<Pair<Double, Double>>()
                last = position
                cycles.last() += (host.elapsedNanos / 1_000_000.0) to position
                advanceBy(15)
            }
            val window: List<Pair<Double, Double>> = cycles[1].filter { it.second in 300.0..2_300.0 }
            assertTrue(window.size >= 2, "the second cycle had no samples between 300 ms and 2300 ms: ${cycles[1].size}")
            val slope: Double = (window.last().second - window.first().second) / (window.last().first - window.first().first)

            assertFalse(hazards.frozen, native.log.tail())
            assertTrue(slope > 2.5, "the second cycle at a speed of 4 moved the playhead $slope ms per ms\n${native.log}")
            println("STRICT[loopSpeedOfFour] slope=$slope ${monitor.strictReport()}")
        }
    }

    /**
     * Twin (iii), the shared player's first play at a speed (J8-F1): `start()` then `setSpeed(2f)` on a fresh clip. The
     * decoder has not started when the speed's seek and play follow the first play, so there is no violation, and the
     * playhead moves at 2x within a second.
     */
    @Test
    fun theTwinOfTheFirstPlayOfASharedPlayerAtASpeedMovesThePlayheadAtSpeedWithNoViolation() = fxModel {
        loadClip {
            engine.sharedPlay(2f)
            advanceBy(300)
            val from: Double = native.positionMs
            advanceBy(1_000)
            val covered: Double = native.positionMs - from

            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(emptyList(), hazards.violations.map { it.describe() })
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
            assertTrue(covered >= 1_500.0, "covered $covered ms of media in 1 s at speed 2\n${native.log}")
            // D-89: H0 NEARSTART 1/120, FIRSTPLAY 0/40, NUDGE 0/120; D-q rejected, stays report-only.
            println("STRICT[firstPlayAtSpeed] ${monitor.strictReport()}")
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
