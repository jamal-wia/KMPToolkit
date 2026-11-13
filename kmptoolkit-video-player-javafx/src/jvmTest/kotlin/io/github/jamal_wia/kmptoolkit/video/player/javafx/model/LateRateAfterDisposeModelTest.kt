package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N1 of the D2-PF1 review: a late event of a disposed `FxPlayback` must not write a rate.
 *
 * OpenJFX can run a status update that its native side had queued before `dispose()` afterwards (B12, [DisposeModel]
 * `lateStatus`): the status of the native player reads PLAYING again, and the tick of the playhead that follows reaches
 * `FxPlayback.onCurrentTime`, which gives a pending speed to the native player: `applyDeferredRate` (a speed deferred at a
 * loop wrap or after a seek) and `applyResumeRate` (the speed of a resume without a seek). Both guard on the status and
 * on nothing else, so the late PLAYING let them through. A rate write on a player whose native half is gone is the same
 * class as the races `FxPlayback.dispose` already guards against.
 *
 * Each scenario leaves one speed pending, disposes the playback, lets the late PLAYING land, and then delivers two ticks
 * of the playhead (the second one 500 ms of media after the first, so the pending speed is due). The contract: not one
 * rate write is attempted after the late status landed. The log records an attempt on a disposed player too (as a
 * write with no effect), so the check is on the attempt, not on the decoder. The control is the same two ticks on a
 * playback that was NOT disposed: the speed is written there, which proves the ticks are enough to give it.
 */
class LateRateAfterDisposeModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val composed: ComposedPlayer) {
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.rig(lateStatus: NativeStatus?, body: Rig.() -> Unit) {
        val scripted = ScriptedBackend(host)
        val backend: ComposedBackend = composeAll(scripted, lateStatus = lateStatus)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend.last).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    /** Two ticks of the playhead, the second one [MOVE_MS] of media after the first: what a pending speed waits for. */
    private fun FxModelScope.tickTwice(native: ScriptedNativePlayer) {
        val events: NativeEvents = checkNotNull(native.events) { "the player has no listener ($host)" }
        onFx { events.onCurrentTime() }
        native.positionMs += MOVE_MS
        onFx { events.onCurrentTime() }
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / NANOS_PER_MS

    /** Drives a looping clip at 2x to the instant of its first wrap: `deferRate` leaves 2.0 pending. */
    private fun FxModelScope.toLoopWrapAt2x(rig: Rig) {
        rig.engine.setLooping(true)
        rig.engine.start()
        rig.engine.setSpeed(2f)
        var steps = 0
        while (rig.composed.loop.cycle < 2) {
            check(steps++ < MAX_STEPS) { "the first wrap did not come ($host)" }
            check(step()) { "the host ran dry before the first wrap ($host)" }
        }
        // Past W-WRAP (100 ms): the two ticks of the control give the speed outside every window.
        advanceBy(120)
    }

    /** Drives a plain resume at 3x: `start()` leaves 3.0 pending as a resume nudge, to be given once the playhead moved. */
    private fun FxModelScope.toResumeAt3x(rig: Rig) {
        rig.engine.start()
        advanceBy(1_000)
        rig.engine.pause()
        advanceBy(100)
        rig.engine.setSpeed(3f)
        advanceBy(500)
        rig.engine.start()
        // The native status PLAYING lands after the play() and W-PLAY (100 ms) closes: the control's ticks need both.
        advanceBy(120)
    }

    private fun Rig.attemptsFrom(thenMs: Double): List<NativeCallEntry> =
        native.log.calls(NativeCall.SET_RATE).filter { it.tMs >= thenMs }

    private fun FxModelScope.assertNoRateAfterTheLateStatus(rig: Rig) {
        rig.engine.release()
        advanceBy(DisposeModel.DEFAULT_LATE_DELAY_MS.toLong() + 10)
        assertTrue(rig.composed.dispose.lateStatusLanded, "the late status did not land\n${rig.native.log.tail()}")
        assertEquals(NativeStatus.PLAYING, rig.native.status, "the late PLAYING did not overwrite DISPOSED")
        val landedAtMs: Double = nowMs()
        tickTwice(rig.native)
        val attempts: List<NativeCallEntry> = rig.attemptsFrom(landedAtMs)
        assertEquals(emptyList(), attempts, "a rate write was attempted on a disposed playback\n${rig.native.log.tail()}")
    }

    @Test
    fun aSpeedDeferredAtALoopWrapIsNotGivenByALateTickAfterDispose() = fxModel {
        rig(lateStatus = NativeStatus.PLAYING) {
            toLoopWrapAt2x(this)
            assertNoRateAfterTheLateStatus(this)
        }
    }

    @Test
    fun theSpeedOfAResumeIsNotGivenByALateTickAfterDispose() = fxModel {
        rig(lateStatus = NativeStatus.PLAYING) {
            toResumeAt3x(this)
            assertNoRateAfterTheLateStatus(this)
        }
    }

    /** The control: the same two ticks on a playback that was not disposed give the speed deferred at the wrap. */
    @Test
    fun theSameTicksGiveTheSpeedDeferredAtALoopWrapOnALivePlayback() = fxModel {
        rig(lateStatus = null) {
            toLoopWrapAt2x(this)
            val thenMs: Double = nowMs()
            tickTwice(native)
            assertTrue(attemptsFrom(thenMs).any { it.effective && it.arg == 2.0 }, "the deferred 2x was not given\n${native.log.tail()}")
        }
    }

    /** The control: the same two ticks on a playback that was not disposed give the speed of the resume. */
    @Test
    fun theSameTicksGiveTheSpeedOfAResumeOnALivePlayback() = fxModel {
        rig(lateStatus = null) {
            toResumeAt3x(this)
            val thenMs: Double = nowMs()
            tickTwice(native)
            assertTrue(attemptsFrom(thenMs).any { it.effective && it.arg == 3.0 }, "the resume's 3x was not given\n${native.log.tail()}")
        }
    }

    private companion object {
        const val MOVE_MS: Double = 500.0
        const val NANOS_PER_MS: Double = 1_000_000.0
        const val MAX_STEPS: Int = 100_000
    }
}
