package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D2 round 13, Q1: branches of the speed, seek, loop and resume logic of `FxPlayback` that no earlier test names, each
 * as the cheapest deterministic scenario on the model tier (virtual time, composed and watched players: the end-of-test
 * hazard check of `fxModel` fails a case that wrote a rate inside a fragile window).
 *
 * Contract sentences (`05-platform-notes.md`, "Playback speed"): a pause keeps the speed; a speed given while a resume,
 * a loop wrap or a seek waits only replaces the waiting speed; after a wrap the speed comes back once, "and the speed is
 * never lost"; a `play()` that follows a rate written while the player was not playing waits out the 200 ms gap.
 */
class DecoderBranchesR13ModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / 1_000_000.0

    private fun FxModelScope.rig(
        script: TimeScript = TimeScript(),
        status: StatusScript = StatusScript(),
        clip: ClipModel = ClipModel(),
        body: Rig.() -> Unit,
    ) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, clip = clip, script = status), timeScript = script)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    private fun ScriptedNativePlayer.writes(afterMs: Double, rate: Double): List<NativeCallEntry> =
        log.calls(NativeCall.SET_RATE).filter { it.effective && it.arg == rate && it.tMs >= afterMs - EPSILON_MS }

    // ---- 1. a loop wrap while a resume still holds its speed ----

    /**
     * A resume at a speed, 150 ms before the clip wraps: the resume's own wait (200 ms of movement) is still open at the
     * wrap, so `deferRate` takes the speed from the resume (`settleNudge`) and gives it after the wrap instead. The speed
     * comes once, not within W-SEEK-RUN of the wrap, and the decoder ends at it.
     */
    @Test
    fun aLoopWrapDuringTheWaitOfAResumeGivesTheHeldSpeedOnceAfterTheWrap() = fxModel {
        rig {
            engine.setLooping(true)
            engine.start()
            advanceBy(2_850)
            engine.pause()
            advanceBy(300)
            engine.setSpeed(2f)
            advanceBy(300)
            engine.start()
            val resumeAt: Double = nowMs()
            var wrapAt: Double = Double.NaN
            composed.loop.onWrap { if (wrapAt.isNaN()) wrapAt = nowMs() }
            advanceBy(1_000)
            assertFalse(wrapAt.isNaN(), "the clip wrapped\n${native.log}")
            val given: List<NativeCallEntry> = native.writes(resumeAt, 2.0)
            assertEquals(1, given.size, "the speed was given once\n${native.log}")
            assertTrue(given.single().tMs >= wrapAt, "the speed was given ${given.single().tMs - wrapAt} ms from the wrap\n" +
                native.log.calls(NativeCall.SET_RATE).joinToString("\n") { "t=${it.tMs} arg=${it.arg} eff=${it.effective}" } + "\nresumeAt=$resumeAt wrapAt=$wrapAt")
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    // ---- 2. a speed of 1 given while a deferred speed waits ----

    @Test
    fun aSpeedOfOneGivenWhileASpeedAfterASeekWaitsReplacesItAndTwoIsNeverWritten() = fxModel {
        rig {
            engine.start()
            advanceBy(800)
            engine.seekTo(2_000L)
            val seekAt: Double = nowMs()
            advanceBy(10)
            engine.setSpeed(2f)
            advanceBy(10)
            engine.setSpeed(1f)
            advanceBy(1_000)
            assertTrue(native.writes(seekAt, 2.0).isEmpty(), "2.0 was written although the last speed given was 1.0\n${native.log}")
            assertEquals(1.0, composed.hazards.effectiveRate, "the decoder runs at 1.0\n${native.log}")
        }
    }

    @Test
    fun aSpeedOfOneGivenAfterALoopWrapReplacesTheSpeedTheWrapHeldBack() = fxModel {
        rig {
            engine.setLooping(true)
            engine.setSpeed(2f)
            engine.start()
            var wrapAt: Double = Double.NaN
            composed.loop.onWrap { wrapAt = nowMs() }
            advanceBy(3_000)
            assertFalse(wrapAt.isNaN(), "the clip wrapped\n${native.log}")
            advanceBy(50)
            engine.setSpeed(1f)
            advanceBy(1_500)
            assertTrue(native.writes(wrapAt, 2.0).isEmpty(), "2.0 was written after the wrap although the last speed given was 1.0\n${native.log}")
            assertEquals(1.0, composed.hazards.effectiveRate, "the decoder runs at 1.0\n${native.log}")
        }
    }

    // ---- 3. replay after the end while a deferred speed waits ----

    /**
     * A speed given right after a seek while playing, and the clip ends before the playhead has moved 200 ms: the
     * deferred speed is not written at the end (`onEndOfMedia` writes only the held speed of a resume), and a replay must
     * still give it, once, after its seek.
     */
    @Test
    fun aReplayAfterTheEndStillGivesTheSpeedThatWasWaitingWhenTheClipEnded() = fxModel {
        rig {
            engine.start()
            advanceBy(2_600)
            engine.seekTo(2_850L)
            advanceBy(10)
            engine.setSpeed(2f)
            advanceBy(1_000)
            val endedAt: Double = nowMs()
            assertTrue(native.log.calls(NativeCall.PAUSE).isNotEmpty(), "the clip ended and paused\n${native.log}")
            engine.start()
            advanceBy(1_500)
            assertEquals(2.0, composed.hazards.effectiveRate, "the replay runs at the speed given before the end\n${native.log}")
            assertTrue(native.positionMs > 100.0, "the replay moved: ${native.positionMs}\n${native.log}")
            assertTrue(native.writes(endedAt, 2.0).size <= 1, "the speed was written more than once after the end\n${native.log}")
        }
    }

    // ---- 4. a looping clip shorter than the wait of the deferred speed ----

    /**
     * "The speed is never lost" (05, "Playback speed"): a looping clip of 450 ms at 2x. After every wrap the clip plays at
     * 1x and the speed is given back once the playhead has moved 200 ms, counted from a tick; the loop's second cycle
     * holds its published time for 300 ms. The case asks that the decoder reaches 2.0 within 10 s.
     */
    @Test
    fun aLoopingClipShorterThanTheWaitStillGetsItsSpeedBack() = fxModel {
        rig(clip = ClipModel(durationMs = SHORT_CLIP_MS)) {
            engine.setLooping(true)
            engine.setSpeed(2f)
            engine.start()
            var reached = false
            repeat(100) {
                advanceBy(100)
                if (native.writes(0.0, 2.0).any { it.tMs > 0.0 }) reached = true
            }
            assertTrue(reached, "the speed never reached the decoder in 10 s on a $SHORT_CLIP_MS ms loop\n${native.log.tail()}")
        }
    }

    // ---- 5. a pause inside the wait after a wrap, then a resume ----

    @Test
    fun aPauseDuringTheWaitAfterALoopWrapKeepsTheSpeedForTheNextStart() = fxModel {
        rig {
            engine.setLooping(true)
            engine.setSpeed(2f)
            engine.start()
            var wrapAt: Double = Double.NaN
            composed.loop.onWrap { wrapAt = nowMs() }
            advanceBy(3_000)
            assertFalse(wrapAt.isNaN(), "the clip wrapped\n${native.log}")
            advanceBy(50)
            engine.pause()
            advanceBy(400)
            engine.start()
            val resumeAt: Double = nowMs()
            advanceBy(RESUME_OBSERVE_MS) // short of the next wrap, which gives the speed again
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed came back after the pause and the resume\n${native.log}")
            assertEquals(1, native.writes(resumeAt, 2.0).size, "the speed was given once after the resume\n${native.log}")
            assertTrue(composed.hazards.frozen.not(), native.log.tail())
        }
    }

    // ---- 6. the resume speed after the end, a speed given while Completed ----

    /**
     * A resume at a speed, 150 ms before the end of a clip that does not loop: the end writes the held speed after its
     * pause (A2) and clears it. A speed given while the clip stands Completed is a plain write on a player that is not
     * playing; had the end left the held speed pending, the new speed would be swallowed into it and the replay would
     * play at the old one.
     */
    @Test
    fun aSpeedGivenWhileCompletedAfterAResumeThatEndedHoldingItsSpeedReachesTheReplay() = fxModel {
        rig {
            engine.start()
            advanceBy(2_850)
            engine.pause()
            advanceBy(300)
            engine.setSpeed(2f)
            advanceBy(300)
            engine.start()
            advanceBy(1_000)
            assertTrue(native.log.calls(NativeCall.PAUSE).size >= 2, "the clip ended\n${native.log}")
            val givenAt: Double = nowMs()
            engine.setSpeed(4f)
            advanceBy(5)
            assertTrue(native.writes(givenAt, 4.0).isNotEmpty(), "a speed given while Completed is written at once, on a player that is not playing\n${native.log}")
            advanceBy(295)
            engine.start()
            advanceBy(2_000)
            assertEquals(4.0, composed.hazards.effectiveRate, "the replay runs at the speed given while Completed\n${native.log}")
        }
    }

    // ---- 7. samples every 20 ms: nothing is written inside the 300 ms after a native seek ----

    /** Samples of the playhead every 20 ms, so a wait that counts only movement ends 220 ms after the seek, before 300 ms. */
    private fun fastSamples(): TimeScript = TimeScript().also { it.sampleIntervalMs = FAST_SAMPLE_MS }

    @Test
    fun aSpeedGivenRightAfterASeekWhilePlayingIsNotWrittenBefore300MsEvenWithFastSamples() = fxModel {
        rig(script = fastSamples()) {
            engine.start()
            advanceBy(800)
            engine.seekTo(2_000L)
            val seekAt: Double = nowMs()
            advanceBy(10)
            engine.setSpeed(2f)
            advanceBy(1_000)
            val given: List<NativeCallEntry> = native.writes(seekAt, 2.0)
            assertEquals(1, given.size, "the speed was given once\n${native.log}")
            assertTrue(given.single().tMs - seekAt >= SEEK_WINDOW_MS - EPSILON_MS, "the speed came ${given.single().tMs - seekAt} ms after the seek\n${native.log}")
        }
    }

    @Test
    fun aSeekDuringTheWaitOfAResumeRestartsThe300MsWaitEvenWithFastSamples() = fxModel {
        rig(script = fastSamples()) {
            engine.setSpeed(2f)
            engine.start()
            advanceBy(400)
            engine.pause()
            advanceBy(300)
            engine.start()
            advanceBy(60)
            engine.seekTo(1_000L)
            val seekAt: Double = nowMs()
            advanceBy(1_000)
            val given: List<NativeCallEntry> = native.writes(seekAt, 2.0)
            assertEquals(1, given.size, "the speed was given once after the seek\n${native.log}")
            assertTrue(given.single().tMs - seekAt >= SEEK_WINDOW_MS - EPSILON_MS, "the speed came ${given.single().tMs - seekAt} ms after the seek\n${native.log}")
        }
    }

    // ---- 8. a tick while the native status is not PLAYING ----

    /**
     * A reading delivered while the status reads PAUSED (a tick the native side queued before the pause landed late):
     * the deferred speed is not written on it, however much movement the readings seem to show.
     */
    @Test
    fun theDeferredSpeedIsNotWrittenOnTicksThatArriveWhileThePlayerIsPaused() = fxModel {
        rig {
            engine.start()
            advanceBy(800)
            engine.seekTo(2_000L)
            advanceBy(10)
            engine.setSpeed(2f)
            engine.pause()
            advanceBy(500) // the status reads PAUSED, and the 300 ms seek window is long over
            val pausedAt: Double = nowMs()
            onFx { native.positionMs = 1_000.0; native.events?.onCurrentTime() } // would be the anchor
            advanceBy(250)
            onFx { native.positionMs = 1_200.0; native.events?.onCurrentTime() } // 200 ms in 250 ms: movement the clock allows
            advanceBy(100)
            assertTrue(native.writes(pausedAt, 2.0).isEmpty(), "the speed was written on a tick while PAUSED\n${native.log}")
        }
    }

    // ---- 9. a loop wrap during a resume that holds a speed given while it waited ----

    /**
     * The resume holds 2x in the property; a speed of 3x given while the resume waits replaces the held value only
     * (`settleNudge`), and the clip wraps before the wait is over. The wrap gives back the speed in force (3x), not the
     * one the property still holds.
     */
    @Test
    fun aLoopWrapDuringTheWaitOfAResumeGivesBackTheSpeedGivenWhileItWaited() = fxModel {
        rig {
            engine.setLooping(true)
            engine.start()
            advanceBy(2_850)
            engine.pause()
            advanceBy(300)
            engine.setSpeed(2f)
            advanceBy(300)
            engine.start()
            advanceBy(20)
            engine.setSpeed(3f)
            advanceBy(RESUME_WRAP_OBSERVE_MS) // past the wrap and the 550 ms after it, short of the next wrap
            assertEquals(3.0, composed.hazards.effectiveRate, "the speed given while the resume waited is the one the wrap gives back\n${native.log}")
        }
    }

    // ---- 10. the speed comes back although the playhead passes the middle of a short clip on the way ----

    /** A 900 ms loop: the first tick after the 300 ms hold of the second cycle is below the middle, the tick that completes the 200 ms of movement is above it. */
    @Test
    fun aLoopOf900MsStillGetsItsSpeedBackWhenTheWaitEndsPastTheMiddle() = fxModel {
        rig(clip = ClipModel(durationMs = MID_CLIP_MS)) {
            engine.setLooping(true)
            engine.setSpeed(2f)
            engine.start()
            var reached = false
            repeat(60) {
                advanceBy(100)
                if (native.writes(0.0, 2.0).any { it.tMs > 1_200.0 }) reached = true
            }
            assertTrue(reached, "the speed never came back on a $MID_CLIP_MS ms loop in 6 s\n${native.log.tail()}")
        }
    }

    private companion object {
        const val SHORT_CLIP_MS: Double = 450.0
        const val RESUME_OBSERVE_MS: Long = 320L
        const val RESUME_WRAP_OBSERVE_MS: Long = 900L
        const val MID_CLIP_MS: Double = 900.0
        const val FAST_SAMPLE_MS: Double = 20.0
        const val SEEK_WINDOW_MS: Double = 300.0
        const val EPSILON_MS: Double = 0.01
    }
}
