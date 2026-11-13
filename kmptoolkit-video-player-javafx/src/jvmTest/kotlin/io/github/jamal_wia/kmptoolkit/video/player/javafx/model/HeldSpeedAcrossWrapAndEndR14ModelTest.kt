package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D2 round 14, fix review SHOULD-1 and SHOULD-2 (ruling `reviews/d2-r14-ruling2-opus.md`). The contract is the sentence of
 * `05-platform-notes.md`, "Playback speed": a speed given while a `play()` waits is held and given after the play.
 *
 * - W1, a wrap while a seek-path play waits: a speed given after the wrap replaces the held one, and the speed that waited
 *   before the wrap does not come back over it.
 * - W2, a held speed of 1 at a wrap: the property is put to 1.0 (a wrap whose held speed is 1 used to write nothing and
 *   left the old speed in the property), and a later speed reaches the decoder.
 * - E1, the end of a non-looping clip cancels a waiting seek-path play: the speed held for it is flushed, so a speed
 *   given after the end is written and not swallowed into a holder nobody gives.
 * - E2, the same end cancels the play for good: a `start()` that plays at once right after it is the only play, the
 *   cancelled callback runs no second `play()`.
 *
 * Virtual time; the late events are delivered by hand at fixed instants, so every case is deterministic.
 */
class HeldSpeedAcrossWrapAndEndR14ModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    /** The last calls of the player without the snapshots of the playhead, which drown the writes in a failure message. */
    private fun ScriptedNativePlayer.trace(): String =
        log.toString().lines().filter { l: String -> !l.contains("SNAPSHOT") }.takeLast(TRACE_LINES).joinToString("\n")

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / NANOS_PER_MS

    private fun FxModelScope.rig(
        hazardConfig: HazardConfig = HazardConfig(),
        body: Rig.() -> Unit,
    ) {
        val backend: ComposedBackend = composeAll(
            ScriptedBackend(host, clip = ClipModel(durationMs = 30_000.0), script = StatusScript()),
            timeScript = TimeScript(),
            hazardConfig = hazardConfig,
        )
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
     * The seek shape of the reviewer: playing at 2x, then in one FX task `pause()`, a speed of [heldSpeed] (written on the
     * paused player and stamped), `seekTo` and `start()`, which waits out the idle gap on the seek path with [heldSpeed]
     * in `pendingNudge`. Returns the instant of the task.
     */
    private fun Rig.playingThenPausedSeekedAndRestartedWithADelayedPlay(fx: FxModelScope, heldSpeed: Float): Double = with(fx) {
        engine.setSpeed(2f)
        engine.start()
        advanceBy(1_000)
        val calledAt: Double = nowMs()
        onFx {
            engine.pause()
            engine.setSpeed(heldSpeed)
            engine.seekTo(1_000L)
            engine.start()
        }
        calledAt
    }

    // ---- W1. a wrap while a seek-path play waits: the speed given after it is not overwritten by the older one ----

    @Test
    fun aSpeedGivenAfterALoopWrapThatCameWhileASeekPathPlayWaitsIsNotOverwrittenByTheOlderHeldSpeed() = fxModel {
        rig {
            engine.setLooping(true)
            val calledAt: Double = playingThenPausedSeekedAndRestartedWithADelayedPlay(this@fxModel, 2.5f)
            advanceBy(10)
            onFx { native.deliverEndOfMedia() }
            advanceBy(20)
            engine.setSpeed(3f)
            advanceBy(2_000)
            val plays: List<NativeCallEntry> = native.log.calls(NativeCall.PLAY).filter { it.tMs > calledAt }
            assertEquals(1, plays.size, "the restart issued exactly one play()\n${native.trace()}")
            val given: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.arg == OLD_HELD_SPEED && it.tMs > calledAt + 25.0 }
            assertEquals(emptyList(), given, "the speed that waited before the wrap was written again after the speed given later\n${native.trace()}")
            assertEquals(3.0, composed.hazards.effectiveRate, "the speed given after the wrap reached the decoder\n${native.trace()}")
        }
    }

    // ---- W2. a held speed of 1 at a wrap: the property is put to 1.0, and a later speed reaches the decoder ----

    /**
     * A decoder that runs at the rate the property holds (`decoderStartsAtUnity = false`). A resume at 2x takes the settle path
     * (held in `settleNudge`), the user asks for 1x while that wait runs, and the clip wraps: the held 1.0 is the newest speed,
     * so the property must come to 1.0 at the wrap. The old code found the held value 1.0 and wrote nothing, which left the
     * property at 2.0, the clip at 2x, and a later `setSpeed(2f)` a write that forwards nothing.
     */
    @Test
    fun aSpeedOfOneHeldWhenALoopWrapComesPutsThePropertyAtOneAndALaterSpeedStillReachesTheDecoder() = fxModel {
        rig(hazardConfig = HazardConfig(decoderStartsAtUnity = false)) {
            engine.setLooping(true)
            engine.setSpeed(2f)
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(1_000)
            engine.start()
            advanceBy(20)
            engine.setSpeed(1f)
            // 120 ms after the play: past W-PLAY (100 ms), inside the 200 ms of movement the resume waits for.
            advanceBy(100)
            onFx { native.deliverEndOfMedia() }
            advanceBy(100)
            assertEquals(1.0, native.rate, "the property holds the speed asked for after the wrap\n${native.trace()}")
            assertEquals(1.0, composed.hazards.effectiveRate, "the decoder runs at the speed asked for after the wrap\n${native.trace()}")
            advanceBy(1_000)
            engine.setSpeed(2f)
            advanceBy(500)
            assertEquals(2.0, native.rate, "a later speed is written to the property\n${native.trace()}")
            assertEquals(2.0, composed.hazards.effectiveRate, "a later speed reaches the decoder\n${native.trace()}")
        }
    }

    // ---- E1. the end of a non-looping clip flushes the speed held for the play it cancels ----

    @Test
    fun aSpeedGivenAfterTheClipEndedOverAWaitingSeekPathPlayIsWrittenAndNotSwallowedByTheHeldOne() = fxModel {
        rig {
            val calledAt: Double = playingThenPausedSeekedAndRestartedWithADelayedPlay(this@fxModel, 1.5f)
            advanceBy(10)
            onFx { native.deliverEndOfMedia() }
            advanceBy(10)
            engine.setSpeed(2.5f)
            advanceBy(10)
            engine.start()
            advanceBy(2_000)
            val plays: List<NativeCallEntry> = native.log.calls(NativeCall.PLAY).filter { it.tMs > calledAt }
            assertEquals(1, plays.size, "only the replay after the end played, the cancelled play did not\n${native.trace()}")
            assertEquals(2.5, native.rate, "the property holds the speed given after the end\n${native.trace()}")
            assertEquals(2.5, composed.hazards.effectiveRate, "the speed given after the end reached the decoder\n${native.trace()}")
        }
    }

    // ---- E2. the end of a non-looping clip cancels the waiting play for good: a play right after it is the only one ----

    /**
     * The first start's wait is 201 ms from the task (the gap of the stamped write is 200 ms). The clip ends 10 ms in, which
     * cancels that play. A `start()` at 200 ms finds the gap clear and plays at once, 1 ms before the cancelled callback
     * runs. The cancelled callback must not play a second time (it used to pass its guard: the generation was not bumped) and the start's own nudge is given, so a stale
     * `delayedPlayPending` left behind by the end would not hold it back either.
     */
    @Test
    fun aStartThatPlaysRightAfterTheClipEndedOverAWaitingPlayIsNotFollowedByASecondPlayFromTheCancelledOne() = fxModel {
        rig {
            val calledAt: Double = playingThenPausedSeekedAndRestartedWithADelayedPlay(this@fxModel, 1.5f)
            advanceBy(10)
            onFx { native.deliverEndOfMedia() }
            advanceBy(190)
            engine.start()
            advanceBy(2_000)
            val plays: List<NativeCallEntry> = native.log.calls(NativeCall.PLAY).filter { it.tMs > calledAt }
            assertEquals(1, plays.size, "exactly one play() after the end, the one of the start at ${calledAt + 200.0}\n${native.trace()}")
            val after: List<Double?> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs >= plays.single().tMs }.map { it.arg }
            assertEquals(listOf<Double?>(1.0, 1.5), after, "after the one play only its own nudge (away and back) was given\n${native.trace()}")
            assertEquals(1.5, composed.hazards.effectiveRate, "the speed of the start reached the decoder through its nudge\n${native.trace()}")
        }
    }

    private companion object {
        const val OLD_HELD_SPEED: Double = 2.5
        const val NANOS_PER_MS: Double = 1_000_000.0
        const val TRACE_LINES: Int = 24
    }
}
