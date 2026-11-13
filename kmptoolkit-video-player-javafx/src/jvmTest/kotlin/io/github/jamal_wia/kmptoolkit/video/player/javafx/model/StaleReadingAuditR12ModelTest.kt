package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D2 round 12, Q2: the stale-reading audit of the rate-giving paths on the model tier.
 *
 * Round 11 (S17) found that the first time JavaFX reports after a resume can be stale (about 0.4 s behind the decoder)
 * and followed within a few milliseconds by a fresh one, and that a rate-giving path which counts the jump between the
 * two as movement of the playhead gives the speed too early. `applyResumeRate` was fixed; these tests ask the same
 * question of the other paths with a stale reading that the test injects by hand (a tick whose published time is set
 * and delivered on the FX thread), and pin the edge of the fix at the speeds the round-11 review named.
 *
 * Documented sentences used (`05-platform-notes.md`, "Playback speed"): after a loop wrap the speed is given again
 * "once the playhead has wrapped and moved about 200 ms"; after a seek the native player keeps its old speed "until 300 ms
 * after the seek and until the playhead has then moved about 200 ms of media"; a resume's speed is given "once the playhead
 * has moved about 200 ms at normal speed (counted only as far as the clock allows)".
 */
class StaleReadingAuditR12ModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / 1_000_000.0

    private fun FxModelScope.rig(script: TimeScript = TimeScript(), body: Rig.() -> Unit) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host), timeScript = script)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    private fun ScriptedNativePlayer.effectiveRateWrites(afterMs: Double, rate: Double): List<NativeCallEntry> =
        log.calls(NativeCall.SET_RATE).filter { it.effective && it.arg == rate && it.tMs >= afterMs - EPSILON_MS }

    /** Delivers a published time of [positionMs] as a tick on the FX thread, the way a stale reading arrives. */
    private fun FxModelScope.injectTick(native: ScriptedNativePlayer, positionMs: Double) {
        onFx {
            native.positionMs = positionMs
            native.events?.onCurrentTime()
        }
    }

    // ---- the speed after a seek (applyDeferredRate, seek path) ----

    /**
     * Control of the scenario below: the same seek and speed with no stale tick. The speed is given no sooner than 300 ms
     * after the seek plus 200 ms of movement from the first tick after those 300 ms.
     */
    @Test
    fun aSpeedGivenRightAfterASeekWhilePlayingArrivesAfterTheSettleAndTheMovement() = fxModel {
        rig {
            engine.start()
            advanceBy(800)
            engine.seekTo(2_000L)
            val seekAt: Double = nowMs()
            advanceBy(10)
            engine.setSpeed(2f)
            advanceBy(2_000)

            val given: List<NativeCallEntry> = native.effectiveRateWrites(seekAt, 2.0)
            assertEquals(1, given.size, "the speed was given once\n${native.log}")
            assertTrue(
                given.single().tMs - seekAt >= SEEK_SETTLE_MS + SETTLE_MS - EPSILON_MS,
                "the speed came ${given.single().tMs - seekAt} ms after the seek\n${native.log}",
            )
            assertFalse(composed.hazards.frozen, native.log.tail())
        }
    }

    // ---- the held speed of a resume with a seek (applyResumeRate after seekTo) ----

    /**
     * A resume holds its speed; a seek during the wait restarts the wait from the new place after 300 ms. A stale reading
     * (the time from before the seek) as the first tick after those 300 ms is the anchor of `applyResumeRate`, which anchors
     * the first tick without a check; the fresh tick after it jumps by seconds in no time and re-anchors, so the speed still
     * comes 300 + 200 ms after the seek at the earliest.
     */
    @Test
    fun aStaleTickAfterTheSettleOfASeekDuringAHeldResumeSpeedDoesNotCountAsMovement() = fxModel {
        rig {
            engine.setSpeed(2f)
            engine.start()
            advanceBy(1_300)
            engine.pause()
            advanceBy(400)
            engine.start()
            advanceBy(130)
            val seekAt: Double = nowMs()
            engine.seekTo(2_000L)
            advanceBy(SEEK_SETTLE_MS.toLong() + 1)
            injectTick(native, STALE_BEFORE_SEEK_MS)
            advanceBy(2_500)

            val writes: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs >= seekAt - EPSILON_MS }
            assertTrue(writes.isNotEmpty(), "the held speed never reached the native player\n${native.log}")
            assertTrue(
                writes.first().tMs - seekAt >= SEEK_SETTLE_MS + SETTLE_MS - EPSILON_MS,
                "the held speed came ${writes.first().tMs - seekAt} ms after the seek\n${native.log}",
            )
            assertFalse(composed.hazards.frozen, native.log.tail())
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    // ---- a speed set during playback, and during the held wait ----

    /** A speed set during the held wait of a resume (stale first publication) replaces the held one, and is given once. */
    @Test
    fun aSpeedSetWhileAResumeHoldsItsSpeedReplacesItAndIsGivenOnceAfterTheMovement() = fxModel {
        rig(staleScript(freshAfterMs = 3.0)) {
            engine.setSpeed(2f)
            engine.start()
            advanceBy(400)
            engine.pause()
            advanceBy(300)
            engine.start()
            advanceBy(150)
            engine.setSpeed(4f)
            advanceBy(1_500)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            assertTrue(native.effectiveRateWrites(playAt, 2.0).isEmpty(), "the replaced speed was never given\n${native.log}")
            val given: List<NativeCallEntry> = native.effectiveRateWrites(playAt, 4.0)
            assertEquals(1, given.size, "the new speed was given once\n${native.log}")
            assertTrue(
                given.single().tMs - playAt >= TimeScript.DEFAULT_SAMPLE_INTERVAL_MS + 3.0 + SETTLE_MS - EPSILON_MS,
                "given ${given.single().tMs - playAt} ms after the play\n${native.log}",
            )
            assertEquals(4.0, composed.hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
            assertFalse(composed.hazards.frozen, native.log.tail())
        }
    }

    /** A speed set on a playing player long after the resume is written at once, once, with no wait (no held state is left over). */
    @Test
    fun aSpeedSetLongAfterAStaleResumeIsWrittenAtOnce() = fxModel {
        rig(staleScript(freshAfterMs = 3.0)) {
            engine.setSpeed(2f)
            engine.start()
            advanceBy(400)
            engine.pause()
            advanceBy(300)
            engine.start()
            advanceBy(1_200)
            val setAt: Double = nowMs()
            engine.setSpeed(3f)
            advanceBy(300)

            val given: List<NativeCallEntry> = native.effectiveRateWrites(setAt, 3.0)
            assertEquals(1, given.size, "the speed was written once\n${native.log}")
            assertTrue(given.single().tMs - setAt < 20.0, "written ${given.single().tMs - setAt} ms after the call\n${native.log}")
            assertEquals(3.0, composed.hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
            assertFalse(composed.hazards.frozen, native.log.tail())
        }
    }

    // ---- the edge of the S17 fix at several speeds (SHOULD-1 of the round-11 review) ----

    /**
     * `applyResumeRate` allows the playhead `elapsed * max(1, rate) + 50 ms` since its anchor. On macOS the decoder runs at 1
     * until the speed is given while the property already holds the speed, so a stale reading of lag L followed by a fresh
     * one after F ms is caught only when `L + F <= F * rate + 50` is false, that is F below (L - 50) / (rate - 1): 374 ms at 2,
     * 125 ms at 4, 53 ms at 8 for the 424 ms of the probe. These cases sit just INSIDE that window, where the stale tick is
     * caught and the speed comes 200 ms after the first fresh tick. The cases just outside it are in the round-12 report.
     */
    @Test
    fun aStaleReadingFollowedByAFreshOneInsideTheCatchWindowIsNotCountedAtSpeedsTwoFourEight() {
        for ((speed: Float, freshAfterMs: Double) in listOf(2f to 360.0, 4f to 110.0, 8f to 45.0)) {
            fxModel {
                rig(staleScript(freshAfterMs)) {
                    engine.start()
                    advanceBy(400)
                    engine.pause()
                    engine.setSpeed(speed)
                    advanceBy(300)
                    engine.start()
                    advanceBy(2_500)

                    val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
                    val given: List<NativeCallEntry> = native.effectiveRateWrites(playAt, speed.toDouble())
                    assertTrue(given.isNotEmpty(), "speed $speed: the speed was never given\n${native.log}")
                    val earliest: Double = TimeScript.DEFAULT_SAMPLE_INTERVAL_MS + freshAfterMs + SETTLE_MS
                    assertTrue(
                        given.first().tMs - playAt >= earliest - EPSILON_MS,
                        "speed $speed, fresh after $freshAfterMs ms: given ${given.first().tMs - playAt} ms after the play, earliest $earliest\n${native.log}",
                    )
                    assertFalse(composed.hazards.frozen, native.log.tail())
                }
            }
        }
    }

    /**
     * The property rate stands at 1.0 when a speed arrives within 600 ms of a plain play (the FRESH path holds it and never writes
     * the property), so the clock bound of `applyResumeRate` is `elapsed + 50 ms` and a stale reading is caught however late the
     * fresh one follows: the stale tick is not 200 ms of movement. A fresh tick 400 ms later is beyond the 374 ms window of a
     * bound that assumed a property rate of 2, so this case tells the bound that uses the property's own rate from one that
     * assumes the speed.
     */
    @Test
    fun aStaleReadingFollowedLateByAFreshOneIsStillCaughtWhenThePropertyStandsAtNormalSpeed() = fxModel {
        rig(staleScript(freshAfterMs = 400.0)) {
            engine.start()
            advanceBy(400)
            engine.pause()
            advanceBy(300)
            engine.start()
            advanceBy(50)
            engine.setSpeed(2f)
            advanceBy(2_500)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            val given: List<NativeCallEntry> = native.effectiveRateWrites(playAt, 2.0)
            assertEquals(1, given.size, "the speed was given once\n${native.log}")
            val earliest: Double = TimeScript.DEFAULT_SAMPLE_INTERVAL_MS + 400.0 + SETTLE_MS
            assertTrue(
                given.single().tMs - playAt >= earliest - EPSILON_MS,
                "given ${given.single().tMs - playAt} ms after the play, earliest $earliest\n${native.log}",
            )
            assertFalse(composed.hazards.frozen, native.log.tail())
        }
    }

    /**
     * "A pause meanwhile keeps the speed for the next start": a resume at a speed that is paused again before the speed is given
     * leaves the speed in the property; a speed given while paused replaces it, and the next start plays at the NEW speed. The held
     * value of the first resume must not outlive the pause.
     */
    @Test
    fun aSpeedGivenWhilePausedReplacesTheSpeedAResumeWasHoldingWhenItWasPaused() = fxModel {
        rig {
            engine.setSpeed(2f)
            engine.start()
            advanceBy(1_300)
            engine.pause()
            advanceBy(300)
            engine.start()
            advanceBy(60)
            engine.pause()
            advanceBy(300)
            engine.setSpeed(1f)
            advanceBy(300)
            engine.start()
            advanceBy(1_500)

            assertEquals(1.0, native.rate, "the property holds the new speed\n${native.log}")
            assertEquals(1.0, composed.hazards.effectiveRate, "the decoder runs at the new speed\n${native.log}")
            assertFalse(composed.hazards.frozen, native.log.tail())
        }
    }

    /**
     * A speed given while a resume holds its speed only changes the held value ("a speed that arrives meanwhile is held"): it does
     * not restart the wait for 200 ms of movement. The speed of 4 given 250 ms after the play, with the first tick at 100 ms, comes at
     * the tick that has seen 200 ms of movement (300 ms), not a tick later than the count from the first tick allows.
     */
    @Test
    fun aSpeedGivenWhileAResumeHoldsItsSpeedDoesNotRestartTheCountOfMovement() = fxModel {
        rig {
            engine.setSpeed(2f)
            engine.start()
            advanceBy(1_300)
            engine.pause()
            advanceBy(400)
            engine.start()
            advanceBy(250)
            engine.setSpeed(4f)
            advanceBy(1_500)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            val given: List<NativeCallEntry> = native.effectiveRateWrites(playAt, 4.0)
            assertEquals(1, given.size, "the new speed was given once\n${native.log}")
            assertTrue(
                given.single().tMs - playAt <= TimeScript.DEFAULT_SAMPLE_INTERVAL_MS + SETTLE_MS + EPSILON_MS,
                "given ${given.single().tMs - playAt} ms after the play: the wait was restarted by the speed given meanwhile\n${native.log}",
            )
            assertEquals(4.0, composed.hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    /**
     * A seek during the wait after a loop wrap restarts the wait from the new place, and the half-clip guard that skips the
     * stale near-end reading of the wrap does not apply to a playhead that a seek put past the middle: the speed must come
     * while the playhead is there, not only after the next wrap.
     */
    @Test
    fun aSeekPastTheMiddleDuringTheWaitAfterALoopWrapStillGetsTheSpeedBeforeTheNextWrap() = fxModel {
        rig {
            engine.setLooping(true)
            engine.setSpeed(2f)
            engine.start()
            var wrapAtMs: Double = Double.NaN
            composed.loop.onWrap { wrapAtMs = host.elapsedNanos / 1_000_000.0 }
            var steps = 0
            while (composed.loop.cycle < 2) {
                check(steps++ < MAX_STEPS) { "the first wrap did not come ($host)" }
                check(step()) { "the host ran dry before the first wrap ($host)" }
            }
            advanceBy(100)
            engine.seekTo(2_000L)
            val seekAt: Double = nowMs()
            advanceBy(1_100)

            assertTrue(native.positionMs > 1_500.0 || nowMs() - seekAt > 0, "harness\n${native.log}")
            val given: List<NativeCallEntry> = native.effectiveRateWrites(seekAt, 2.0)
            assertTrue(given.isNotEmpty(), "the speed was not given in the 1100 ms after the seek (the next wrap is about 1000 ms after it)\n${native.log}")
            assertTrue(
                given.first().tMs - seekAt >= SEEK_SETTLE_MS - EPSILON_MS,
                "given ${given.first().tMs - seekAt} ms after the seek, inside its 300 ms window\n${native.log}",
            )
        }
    }

    private fun staleScript(freshAfterMs: Double): TimeScript =
        TimeScript().also { it.staleFirstPublicationAfterPlay = TimeScript.StaleFirstPublication(LAG_MS, freshAfterMs) }

    private companion object {
        const val LAG_MS: Double = 424.0
        const val HOLD_MS: Double = 300.0
        const val SETTLE_MS: Double = 200.0
        const val SEEK_SETTLE_MS: Double = 300.0
        const val STALE_NEAR_END_MS: Double = 2_900.0
        const val STALE_BEFORE_SEEK_MS: Double = 500.0
        const val EPSILON_MS: Double = 0.01
        const val MAX_STEPS: Int = 200_000
    }
}
