package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D2 round 12, S18 and its survivors: the speed that is given after a seek while playing (`applyDeferredRate`) counts the
 * movement of the playhead the way the speed of a resume does (`applyResumeRate`), and the resume path's own edges.
 *
 * `05-platform-notes.md`, "Playback speed": after a seek the native player keeps its old speed "until 300 ms after the seek
 * and until the playhead has then moved about 200 ms of media (counted as for a resume: a reading that runs ahead of the
 * clock or steps back restarts the count)". A reading that carries the time from before the seek (stale), or one that
 * steps back, is not movement of the playhead; the first two tests inject such a reading by hand (a tick whose published
 * time is set and delivered on the FX thread) and ask that the speed is not given before 300 ms plus 200 ms of real
 * movement. The next two pin edges of the resume path that the round-12 mutation sweep left alive: the re-anchor takes the
 * time of the reading as well as its position (R5), and no speed is given before the native status is PLAYING (R16).
 */
class StaleReadingDeferredR12ModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / 1_000_000.0

    private fun FxModelScope.rig(
        script: TimeScript = TimeScript(),
        status: StatusScript = StatusScript(),
        body: Rig.() -> Unit,
    ) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, script = status), timeScript = script)
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

    private fun ScriptedNativePlayer.rateCalls(): String =
        log.calls(NativeCall.SET_RATE).joinToString(prefix = "rate calls: ", separator = "; ") { "t=${it.tMs} arg=${it.arg} effective=${it.effective}" }

    /** Delivers a published time of [positionMs] as a tick on the FX thread, the way a stale reading arrives. */
    private fun FxModelScope.injectTick(native: ScriptedNativePlayer, positionMs: Double) {
        onFx {
            native.positionMs = positionMs
            native.events?.onCurrentTime()
        }
    }

    // ---- S18: the speed given after a seek while playing ----

    /**
     * A stale pre-seek reading (the time from before the seek, 500 ms) as the first tick after the 300 ms settle is not the
     * anchor of the count: the fresh tick after it is a jump of seconds in no time, which restarts the count. The speed
     * comes 300 ms plus 200 ms of real movement after the seek at the earliest.
     */
    @Test
    fun aStaleTickAfterTheSettleOfASpeedGivenAfterASeekIsNotTheAnchor() = fxModel {
        rig {
            engine.start()
            advanceBy(800)
            engine.seekTo(2_000L)
            val seekAt: Double = nowMs()
            advanceBy(10)
            engine.setSpeed(2f)
            advanceBy(SEEK_SETTLE_MS.toLong() - 10 + 1)
            injectTick(native, STALE_BEFORE_SEEK_MS)
            advanceBy(2_000)
            val given: List<NativeCallEntry> = native.effectiveRateWrites(seekAt, 2.0)
            assertEquals(1, given.size, "the speed was given once\n${native.log}")
            assertTrue(
                given.single().tMs - seekAt >= SEEK_SETTLE_MS + SETTLE_MS - EPSILON_MS,
                "the speed came ${given.single().tMs - seekAt} ms after the seek\n${native.log}",
            )
        }
    }

    /**
     * A backward tick (an old publication that lands late) after the anchor of a speed given after a seek is not movement:
     * counted as a jump it would give the speed before the playhead has really moved 200 ms.
     */
    @Test
    fun aBackwardTickAfterTheAnchorOfASpeedGivenAfterASeekIsNotMovement() = fxModel {
        rig {
            engine.start()
            advanceBy(800)
            engine.seekTo(2_000L)
            val seekAt: Double = nowMs()
            advanceBy(10)
            engine.setSpeed(2f)
            advanceBy(SEEK_SETTLE_MS.toLong() - 10 + 150) // the first tick after the 300 ms settle has anchored
            injectTick(native, 1_500.0) // 400-500 ms behind
            advanceBy(2_000)
            val given: List<NativeCallEntry> = native.effectiveRateWrites(seekAt, 2.0)
            assertEquals(1, given.size, "the speed was given once\n${native.log}")
            assertTrue(
                given.single().tMs - seekAt >= SEEK_SETTLE_MS + SETTLE_MS - EPSILON_MS,
                "the speed came ${given.single().tMs - seekAt} ms after the seek\n${native.log}",
            )
        }
    }

    /**
     * A seek BACK while playing, a speed within 300 ms, and a stale pre-seek reading that lands after the settle and is
     * AHEAD of the playhead (the clip played to about 2 500 ms before the seek to 500 ms). The first tick past the settle
     * anchors near 800 ms; the stale reading (2 500 ms) is a jump above the clock and becomes the anchor; the fresh ticks
     * after it step back from it. Counting that step back as a re-anchor gives the speed 200 ms of real movement after the
     * fresh reading; without the re-anchor the anchor stays at 2 500 ms and the speed waits until the playhead has passed
     * 2 700 ms (never, on a short loop). The speed must come once, no later than the seek window, the 200 ms and two
     * ticks after the seek (the bound `RateGivingModelTest` uses for a seek while the speed is deferred).
     */
    @Test
    fun aStaleReadingAheadOfThePlayheadAfterASeekBackDoesNotHoldTheSpeedBackForGood() = fxModel {
        rig {
            engine.start()
            advanceBy(2_500)
            engine.seekTo(500L)
            val seekAt: Double = nowMs()
            advanceBy(10)
            engine.setSpeed(2f)
            advanceBy(SEEK_SETTLE_MS.toLong() - 10 + 1) // the first tick after the 300 ms settle has anchored near 800 ms
            injectTick(native, STALE_BEFORE_SEEK_BACK_MS)
            advanceBy(1_000) // short of the end of the 3 s clip, which writes the held speed again
            val given: List<NativeCallEntry> = native.effectiveRateWrites(seekAt, 2.0)
            assertEquals(1, given.size, "the speed was given once\n${native.rateCalls()}\n${native.log}")
            val after: Double = given.single().tMs - seekAt
            assertTrue(after >= SEEK_SETTLE_MS + SETTLE_MS - EPSILON_MS, "the speed came $after ms after the seek, before 200 ms of movement\n${native.log}")
            assertTrue(
                after <= SEEK_SETTLE_MS + SETTLE_MS + 2 * TICK_MS + EPSILON_MS,
                "the speed came only $after ms after the seek: the stale reading ahead of the playhead held it back\n${native.log}",
            )
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    // ---- R5: the re-anchor takes the time of the reading ----

    /**
     * Stale, fresh, a second stale, fresh, on a resume that holds its speed. The first pair re-anchors the count (the
     * stale reading steps back, the fresh one runs ahead of the clock); the playhead then really moves for 155 ms and the
     * second pair comes: a stale reading 200 ms behind the clock and a fresh one 5 ms later, which is a jump of 205 ms in
     * 5 ms. The bound of that jump is the wall time since the LAST re-anchor (5 ms plus the slack), not since the first
     * anchor (215 ms plus the slack, which would let the jump count as 200 ms of movement). The speed comes 200 ms of
     * real movement after the second fresh reading at the earliest.
     */
    @Test
    fun theSecondStaleThenFreshPairIsMeasuredFromTheLastReanchorAndNotFromTheFirstAnchor() = fxModel {
        rig {
            engine.setSpeed(2f)
            engine.start()
            advanceBy(400)
            engine.pause()
            advanceBy(300)
            engine.start()
            advanceBy(1)
            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            advanceBy(149) // the first tick (the anchor) came at +100
            // First pair: a stale reading far behind the anchor, then the fresh one.
            injectTick(native, composed.time.nativePositionMs - FIRST_LAG_MS)
            advanceBy(5)
            injectTick(native, composed.time.nativePositionMs)
            // The playhead moves for 150 ms (ticks at +200 and +300 count from the last re-anchor, 145 ms in all).
            advanceBy(155)
            // Second pair: a stale reading that steps back from the anchor, then the fresh one.
            injectTick(native, composed.time.nativePositionMs - SECOND_LAG_MS)
            advanceBy(5)
            injectTick(native, composed.time.nativePositionMs)
            val secondFreshAt: Double = nowMs()
            advanceBy(450) // short of the end of the 3 s clip, which writes the held speed again

            val writes: List<NativeCallEntry> = native.effectiveRateWrites(playAt, 2.0)
            assertEquals(1, writes.size, "the speed was given once\n${native.rateCalls()}\n${native.log}")
            assertTrue(
                writes.single().tMs >= secondFreshAt + SETTLE_MS - EPSILON_MS,
                "the speed was given ${writes.single().tMs - secondFreshAt} ms after the second fresh reading, before 200 ms of " +
                    "real movement from it\n${native.log}",
            )
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    // ---- R16: no speed before the status is PLAYING ----

    /**
     * The play of the resume turns the status after 400 ms (a slow start-up). The playhead already ticks in that time;
     * the speed must not be written before the native player reports PLAYING (a rate change while the decoder is
     * starting is the measured freeze), even though the ticks before it carry 200 ms of movement the clock allows.
     */
    @Test
    fun theSpeedOfAResumeIsNotGivenBeforeTheStatusTurnsPlaying() = fxModel {
        rig(status = StatusScript().statusLatency(NativeCall.PLAY, 2, SLOW_PLAY_STATUS_MS)) {
            engine.setSpeed(2f)
            engine.start()
            advanceBy(400)
            engine.pause()
            advanceBy(300)
            engine.start()
            advanceBy(1)
            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            advanceBy(SLOW_PLAY_STATUS_MS.toLong() - 11)
            assertTrue(native.status != NativeStatus.PLAYING, "the status is still not PLAYING\n${native.log}")
            assertTrue(
                native.effectiveRateWrites(playAt, 2.0).isEmpty() && native.effectiveRateWrites(playAt, 1.0).isEmpty(),
                "a speed was written before the status turned PLAYING\n${native.rateCalls()}\n${native.log}",
            )
            advanceBy(800) // short of the end of the 3 s clip, which writes the held speed again

            val writes: List<NativeCallEntry> = native.effectiveRateWrites(playAt, 2.0)
            assertEquals(1, writes.size, "the speed was given once\n${native.rateCalls()}\n${native.log}")
            assertTrue(
                writes.single().tMs >= playAt + SLOW_PLAY_STATUS_MS - EPSILON_MS,
                "the speed came ${writes.single().tMs - playAt} ms after the play, before the status turned PLAYING\n${native.log}",
            )
            assertFalse(composed.hazards.frozen, native.log.tail())
        }
    }

    private companion object {
        const val SETTLE_MS: Double = 200.0
        const val SEEK_SETTLE_MS: Double = 300.0
        const val STALE_BEFORE_SEEK_MS: Double = 500.0
        const val STALE_BEFORE_SEEK_BACK_MS: Double = 2_500.0
        const val TICK_MS: Double = 100.0
        const val FIRST_LAG_MS: Double = 424.0
        const val SECOND_LAG_MS: Double = 200.0
        const val SLOW_PLAY_STATUS_MS: Double = 400.0
        const val EPSILON_MS: Double = 0.01
    }
}
