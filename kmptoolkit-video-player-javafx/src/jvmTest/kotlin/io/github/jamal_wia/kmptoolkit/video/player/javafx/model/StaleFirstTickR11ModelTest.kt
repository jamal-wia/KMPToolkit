package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D2 round 11, finding S17, on the model tier: the first time reported after a resume is stale and a fresh one follows
 * a few milliseconds later (measured on real media, 14 of 14 resumes: a stale first tick, a fresh second tick 2 to 5 ms
 * later, 424 ms ahead of it). The documented contract of `05-platform-notes.md` is that the speed of a resume is given
 * "once the playhead has moved about 200 ms at normal speed". The stale-to-fresh jump of the published time is not
 * movement of the playhead, so the speed must not be given because of it: the pair 1.0 then the speed is written
 * only after 200 ms of movement that the clock allows, counted from the first FRESH tick.
 *
 * The scripted player gets the stale first publication through [TimeScript.staleFirstPublicationAfterPlay] (opt-in; no
 * other test sets it). The first tick after a play comes 100 ms after the play, the fresh one [FRESH_AFTER_MS] later, so
 * the earliest the speed may be given is play + 100 + [FRESH_AFTER_MS] + 200.
 *
 * The three paths that give the speed through `applyResumeRate` are covered: the plain settle path of `start`, the
 * FRESH path of `setSpeed` (a speed within 600 ms of a plain play) and the delayed plain path of the idle gap (C2/C3).
 */
class StaleFirstTickR11ModelTest {

    private class EngineRig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
        val hazards: HazardModel get() = composed.hazards
    }

    private fun staleScript(freshAfterMs: Double = FRESH_AFTER_MS): TimeScript =
        TimeScript().also { it.staleFirstPublicationAfterPlay = TimeScript.StaleFirstPublication(LAG_MS, freshAfterMs) }

    private fun FxModelScope.withEngine(script: TimeScript = staleScript(), body: EngineRig.() -> Unit) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host), timeScript = script)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            EngineRig(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / 1_000_000.0

    private fun ScriptedNativePlayer.rateWritesAfter(t: Double): List<NativeCallEntry> =
        log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs > t }

    /** Plays [playMs] at the speed that is already set, pauses and rests [pauseMs]. */
    private fun EngineRig.playAndPause(scope: FxModelScope, playMs: Long = 400L, pauseMs: Long = 300L) {
        engine.start()
        scope.advanceBy(playMs)
        assertTrue(native.positionMs > 250.0, "the clip advanced before the pause, was ${native.positionMs}\n${native.log}")
        engine.pause()
        scope.advanceBy(pauseMs)
    }

    /**
     * The earliest the speed may reach the native player: 200 ms of movement from the first fresh tick, which is
     * [freshAfterMs] after the first tick, which is one sample interval after the play.
     */
    private fun earliestNudge(playAt: Double, freshAfterMs: Double = FRESH_AFTER_MS): Double =
        playAt + TimeScript.DEFAULT_SAMPLE_INTERVAL_MS + freshAfterMs + SETTLE_MS

    private fun EngineRig.assertSettledNotBefore(playAt: Double, earliest: Double, expectedLast: Double) {
        val writes: List<NativeCallEntry> = native.rateWritesAfter(playAt)
        assertTrue(writes.isNotEmpty(), "the speed was never given\n${native.log}")
        assertEquals(expectedLast, writes.last().arg, "the last rate written\n${native.log}")
        assertTrue(
            writes.first().tMs >= earliest - EPSILON_MS,
            "the speed was given at ${writes.first().tMs - playAt} ms after the play, before the first fresh tick plus 200 ms of " +
                "movement (${earliest - playAt} ms)\n${native.log}",
        )
    }

    // ---- the three settle paths ----

    @Test
    fun aPlainResumeAtASpeedWaitsForRealMovementAfterAStaleFirstTick() = fxModel {
        withEngine {
            engine.setSpeed(2f)
            playAndPause(this@fxModel)
            engine.start()
            advanceBy(1_500)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            assertEquals(listOf(1.0, 2.0), native.rateWritesAfter(playAt).map { it.arg }, "one away-and-back\n${native.log}")
            assertSettledNotBefore(playAt, earliestNudge(playAt), 2.0)
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    @Test
    fun aSpeedWithinSixHundredMillisecondsOfAPlainPlayWaitsForRealMovementAfterAStaleFirstTick() = fxModel {
        withEngine {
            playAndPause(this@fxModel)
            engine.start()
            advanceBy(50)
            engine.setSpeed(2f)
            advanceBy(1_500)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            // The property already stood at 1.0, so the away of the away-and-back is not a change: one effective write.
            assertEquals(listOf(2.0), native.rateWritesAfter(playAt).map { it.arg }, "the speed was given once\n${native.log}")
            assertSettledNotBefore(playAt, earliestNudge(playAt), 2.0)
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    @Test
    fun aDelayedPlainResumeAtASpeedWaitsForRealMovementAfterAStaleFirstTick() = fxModel {
        withEngine {
            engine.setSpeed(2f)
            playAndPause(this@fxModel)
            // A rate written while paused makes the next play wait out the idle gap (C2); the speed that arrives meanwhile is held (C3).
            engine.setSpeed(1f)
            advanceBy(50)
            val calledAt: Double = nowMs()
            engine.start()
            engine.setSpeed(2f)
            advanceBy(1_500)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            assertTrue(playAt >= calledAt + 100.0, "the play was delayed by the idle gap, came ${playAt - calledAt} ms after the call\n${native.log}")
            assertSettledNotBefore(playAt, earliestNudge(playAt), 2.0)
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    @Test
    fun aFreshTickThatComesLateStillCountsFromTheFreshTick() = fxModel {
        withEngine(staleScript(freshAfterMs = 150.0)) {
            engine.setSpeed(2f)
            playAndPause(this@fxModel)
            engine.start()
            advanceBy(2_000)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            assertSettledNotBefore(playAt, earliestNudge(playAt, freshAfterMs = 150.0), 2.0)
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    // ---- a tick that runs backwards ----

    /**
     * A tick that reports a time BEHIND the anchor (an old publication that lands late) is not movement either: the
     * count restarts from it. The scripted playhead is smooth here (no stale script); one tick 20 ms after the first
     * one is injected 300 ms behind it.
     */
    @Test
    fun aTickThatRunsBackwardsRestartsTheCount() = fxModel {
        withEngine(TimeScript()) {
            engine.setSpeed(2f)
            playAndPause(this@fxModel)
            engine.start()
            advanceBy(120) // the first tick (anchor) came at +100
            onFx {
                native.positionMs = native.positionMs - 300.0
                native.events?.onCurrentTime()
            }
            advanceBy(1_500)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            val writes: List<NativeCallEntry> = native.rateWritesAfter(playAt)
            assertEquals(listOf(1.0, 2.0), writes.map { it.arg }, "one away-and-back\n${native.log}")
            assertTrue(
                writes.first().tMs >= playAt + 100.0 + SETTLE_MS - EPSILON_MS,
                "the backward tick counted as 200 ms of movement: the speed came ${writes.first().tMs - playAt} ms after the play\n${native.log}",
            )
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    // ---- liveness ----

    /**
     * No hazard part: the decoder runs at the property rate from the play (2 here), so the playhead moves 200 ms in
     * 100 ms of wall time. That is movement the clock allows at the speed the decoder was started at, so it is counted,
     * the speed is given, and a later speed still reaches the property (a settle that never completes would swallow it).
     */
    @Test
    fun aDecoderThatRunsAtTheHeldSpeedFromThePlayStillGetsTheSpeedAndLaterSpeeds() = fxModel {
        val scripted = ScriptedBackend(host)
        allowUnwatched(scripted, "liveness of the settle: a decoder at the property rate needs no hazard part")
        val backend = TimedBackend(scripted)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            val native: ScriptedNativePlayer = scripted.last
            engine.setSpeed(2f)
            engine.start()
            advanceBy(400)
            engine.pause()
            advanceBy(300)
            engine.start()
            advanceBy(1_000)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            assertEquals(listOf(1.0, 2.0), native.rateWritesAfter(playAt).map { it.arg }, "the speed was given\n${native.log}")
            engine.setSpeed(3f)
            advanceBy(300)
            assertEquals(3.0, native.rate, "a later speed reaches the property\n${native.log}")
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    private companion object {
        /** How far behind the decoder the stale first value is: the 424 ms of the probe. */
        const val LAG_MS: Double = 424.0

        /** How soon the fresh value follows the stale one. */
        const val FRESH_AFTER_MS: Double = 3.0

        /** The settle: 200 ms of playhead movement. */
        const val SETTLE_MS: Double = 200.0

        /** Rounding of virtual time. */
        const val EPSILON_MS: Double = 0.01
    }
}
