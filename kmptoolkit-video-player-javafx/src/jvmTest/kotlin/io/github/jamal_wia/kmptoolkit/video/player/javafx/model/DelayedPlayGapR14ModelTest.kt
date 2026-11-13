package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 round 14, S20 fix (ruling `reviews/d2-r14-ruling-opus.md`): between a `start()` and the delayed `native.play()` it
 * schedules, the native player is paused by our own command, so every rate written in that time is a rate written while the
 * player was not playing (`05-platform-notes.md`, "Playback speed"). The contract: such a write is stamped, a delayed `play()`
 * checks the idle gap again when its wait ends, and no tick, wrap or nudge writes a rate on the paused decoder at all.
 *
 * - G1, the re-check: a stamp that lands during the wait moves the single `play()` to 200 ms after the stamp.
 * - G2, the bound: two late wraps during one wait still give exactly one `play()`, and the speed arrives.
 * - G3, `applyDeferredRate`: late ticks that read a stale PLAYING during the wait write nothing before the `play()`.
 *   The wrap is the only way a deferred speed exists during a delayed wait (`start` clears [deferredRate] for a speed other
 *   than 1 and every delayed path holds its own speed, so [setSpeed] never defers there), so the wrap supplies it.
 * - G4, `applyResumeRate`: the same ticks with the speed held by the settle path.
 * - G5, `giveNudge`: an FX-thread caller's `start()` on the seek path, `pause()` and a `start()` that delays its play all run
 *   before the first start's nudge; the nudge must not give the new start's speed on the paused decoder.
 *
 * Virtual time; the late events are delivered by hand at fixed instants, so every case is deterministic.
 */
class DelayedPlayGapR14ModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / NANOS_PER_MS

    private fun FxModelScope.rig(
        status: StatusScript = StatusScript(),
        clip: ClipModel = ClipModel(durationMs = 30_000.0),
        body: Rig.() -> Unit,
    ) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, clip = clip, script = status), timeScript = TimeScript())
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    /**
     * Brings the player to the shape of S20: a resume whose speed of 2 waits in the settle phase (the shared player gives the
     * speed right after `start()`), 120 ms in, then `pause()` (which flushes and stamps the 2.0), 5 ms, `start()`: its play
     * is delayed by the gap of that stamp. Returns the instant of the second `pause()`.
     */
    private fun Rig.pausedAndResumedAtTwoWithADelayedPlay(fx: FxModelScope): Double = with(fx) {
        engine.setLooping(true)
        engine.start()
        advanceBy(1_000)
        engine.pause()
        advanceBy(1_000)
        engine.start()
        engine.setSpeed(2f)
        advanceBy(120)
        val pausedAt: Double = nowMs()
        engine.pause()
        advanceBy(5)
        engine.start()
        pausedAt
    }

    /** The effective rate writes at or after [fromMs] and before [untilMs], oldest first. */
    private fun ScriptedNativePlayer.writes(fromMs: Double, untilMs: Double = Double.MAX_VALUE): List<NativeCallEntry> =
        log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs >= fromMs - EPSILON_MS && it.tMs < untilMs }

    // ---- G1. the re-check: a stamp that lands during the wait moves the single play ----

    @Test
    fun aStampThatLandsDuringTheWaitOfADelayedPlayMovesThePlayToTwoHundredMillisecondsAfterIt() = fxModel {
        rig {
            val pausedAt: Double = pausedAndResumedAtTwoWithADelayedPlay(this@fxModel)
            advanceBy(60)
            onFx { native.deliverEndOfMedia() }
            advanceBy(1_500)
            val plays: List<NativeCallEntry> = native.log.calls(NativeCall.PLAY).filter { it.tMs > pausedAt }
            assertEquals(1, plays.size, "the second resume issued exactly one play()\n${native.log.tail()}")
            val play: NativeCallEntry = plays.single()
            val stamp: NativeCallEntry = native.writes(pausedAt, play.tMs).last()
            assertTrue(
                play.tMs - stamp.tMs >= IDLE_GAP_MS - EPSILON_MS,
                "the play came ${play.tMs - stamp.tMs} ms after the last rate written on the paused player (${stamp.arg} at ${stamp.tMs})\n${native.log.tail()}",
            )
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed that waited reached the decoder\n${native.log.tail()}")
        }
    }

    // ---- G2. the bound: two late wraps during one wait still give one play, at the last stamp plus the gap ----

    @Test
    fun twoLoopWrapsDeliveredDuringTheWaitOfOneDelayedPlayGiveOnePlayAfterTheLastStampAndTheSpeedStillArrives() = fxModel {
        rig {
            val pausedAt: Double = pausedAndResumedAtTwoWithADelayedPlay(this@fxModel)
            advanceBy(60)
            onFx { native.deliverEndOfMedia() }
            advanceBy(125)
            onFx { native.deliverEndOfMedia() }
            advanceBy(2_000)
            val plays: List<NativeCallEntry> = native.log.calls(NativeCall.PLAY).filter { it.tMs > pausedAt }
            assertEquals(1, plays.size, "the second resume issued exactly one play()\n${native.log.tail()}")
            val play: NativeCallEntry = plays.single()
            val stamp: NativeCallEntry = native.writes(pausedAt, play.tMs).last()
            assertTrue(
                play.tMs - stamp.tMs >= IDLE_GAP_MS - EPSILON_MS,
                "the play came ${play.tMs - stamp.tMs} ms after the last rate written on the paused player (${stamp.arg} at ${stamp.tMs})\n${native.log.tail()}",
            )
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed that waited reached the decoder\n${native.log.tail()}")
        }
    }

    // ---- G3. applyDeferredRate: late ticks with a stale PLAYING during a delayed play write nothing ----

    /**
     * After the second `pause()` JavaFX still reads PLAYING (the status of a pause is late, 190 ms here). The wrap that arrives
     * during the wait leaves the speed deferred ([deferredRate]) and puts the property to 1.0. Two ticks of the playhead then
     * carry 200 ms of movement inside the clock bound, which completes the count of the deferred speed: it must not be written
     * on the paused decoder, whose play() is still waiting.
     */
    @Test
    fun lateTicksThatReadAStalePlayingDuringADelayedPlayDoNotGiveTheDeferredSpeedBeforeThePlay() = fxModel {
        rig(status = StatusScript().statusLatency(NativeCall.PAUSE, 2, SLOW_PAUSE_STATUS_MS)) {
            val pausedAt: Double = pausedAndResumedAtTwoWithADelayedPlay(this@fxModel)
            advanceBy(10)
            onFx { native.deliverEndOfMedia() }
            val wrapAt: Double = nowMs()
            val events: NativeEvents = checkNotNull(native.events) { "the player has no listener" }
            advanceBy(10)
            val from: Double = native.positionMs
            onFx { events.onCurrentTime() }
            advanceBy(155)
            native.positionMs = from + SETTLE_MOVEMENT_MS
            onFx { events.onCurrentTime() }
            advanceBy(1_500)
            val plays: List<NativeCallEntry> = native.log.calls(NativeCall.PLAY).filter { it.tMs > pausedAt }
            assertEquals(1, plays.size, "the second resume issued exactly one play()\n${native.log.tail()}")
            val between: List<NativeCallEntry> = native.writes(wrapAt, plays.single().tMs).drop(1)
            assertEquals(emptyList(), between, "a rate was written on the paused decoder after the wrap's own 1.0\n${native.log.tail()}")
            assertEquals(2.0, composed.hazards.effectiveRate, "the deferred speed reached the decoder after the play\n${native.log.tail()}")
        }
    }

    // ---- G4. applyResumeRate: the same ticks with the speed held by the settle path ----

    /** As G3, without a wrap: the speed waits in [settleNudge] and two late ticks 80 ms apart carry 200 ms of movement at the held 2.0 (inside the clock bound of 80 ms at 2x plus the slack). */
    @Test
    fun lateTicksThatReadAStalePlayingDuringADelayedPlayDoNotGiveTheHeldSpeedBeforeThePlay() = fxModel {
        rig(status = StatusScript().statusLatency(NativeCall.PAUSE, 2, SLOW_PAUSE_STATUS_MS)) {
            val pausedAt: Double = pausedAndResumedAtTwoWithADelayedPlay(this@fxModel)
            val startedAt: Double = nowMs()
            val events: NativeEvents = checkNotNull(native.events) { "the player has no listener" }
            advanceBy(20)
            val from: Double = native.positionMs
            onFx { events.onCurrentTime() }
            advanceBy(80)
            native.positionMs = from + SETTLE_MOVEMENT_MS
            onFx { events.onCurrentTime() }
            advanceBy(1_500)
            val plays: List<NativeCallEntry> = native.log.calls(NativeCall.PLAY).filter { it.tMs > pausedAt }
            assertEquals(1, plays.size, "the second resume issued exactly one play()\n${native.log.tail()}")
            val between: List<NativeCallEntry> = native.writes(startedAt, plays.single().tMs)
            assertEquals(emptyList(), between, "a rate was written on the paused decoder before the delayed play\n${native.log.tail()}")
            assertEquals(2.0, composed.hazards.effectiveRate, "the held speed reached the decoder after the play\n${native.log.tail()}")
        }
    }

    // ---- G5. giveNudge: a nudge queued by a start that was cancelled does not give the next start's speed early ----

    /**
     * From the FX thread, in one task: `start()` on the seek path (its nudge is two hops away), a speed of 3 (replaces the
     * pending nudge), `pause()` (flushes the 3.0 and stamps it), a `seekTo` and a `start()` that delays its play on the seek
     * path (its own nudge pending). Queued behind the first hop, the calls all run before the first start's nudge, which
     * would then consume the NEW start's pending speed and write 1.0 and 3.0 on the paused decoder.
     */
    @Test
    fun aNudgeQueuedByAnEarlierStartDoesNotGiveTheSpeedOfALaterStartWhoseSeekPathPlayStillWaits() = fxModel {
        rig {
            engine.setSpeed(2f)
            advanceBy(300)
            engine.seekTo(3_000L)
            advanceBy(300)
            val playsBefore: Int = native.log.calls(NativeCall.PLAY).size
            val calledAt: Double = nowMs()
            onFx {
                engine.start()
                engine.setSpeed(3f)
                engine.pause()
                engine.seekTo(4_000L)
                engine.start()
            }
            advanceBy(1_500)
            val plays: List<NativeCallEntry> = native.log.calls(NativeCall.PLAY).drop(playsBefore)
            assertEquals(2, plays.size, "the first start played at once and the second after its wait\n${native.log.tail()}")
            val delayedPlay: NativeCallEntry = plays.last()
            val before: List<Double> = native.writes(calledAt, delayedPlay.tMs).map { checkNotNull(it.arg) }
            assertEquals(listOf(3.0), before, "before the delayed play only the flush of pause() may write a rate\n${native.log.tail()}")
            assertEquals(3.0, composed.hazards.effectiveRate, "the speed of the second start reached the decoder after its play\n${native.log.tail()}")
        }
    }

    private companion object {
        /** Past the second tick of G3 (180 ms after the second `pause()`), short of the earliest delayed play (about 205 ms). */
        const val SLOW_PAUSE_STATUS_MS: Double = 190.0
        const val SETTLE_MOVEMENT_MS: Double = 200.0
        const val IDLE_GAP_MS: Double = 200.0
        const val EPSILON_MS: Double = 0.01
        const val NANOS_PER_MS: Double = 1_000_000.0
    }
}
