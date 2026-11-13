package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 round 14, Q1 variations of the r13 fix review SHOULD-1, the orders that are NOT the open hole (a wrap JavaFX delivers
 * after a later `start()` whose play is still delayed is suspect S20 and lives in `suspects/`): a late loop wrap
 * with a `seekTo`, a second `pause()` or a `release()` between the first `pause()` and the event. Each case must keep every
 * `play()` out of the idle gap of any rate written on a player that is not playing (the end-of-test hazard check of
 * `fxModel`), keep the speed that waited, and write nothing on a released player. Virtual time, fixed instants.
 */
class LateWrapVariantsR14ModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.rig(body: Rig.() -> Unit) {
        val backend: ComposedBackend = composeAll(
            ScriptedBackend(host, clip = ClipModel(durationMs = 30_000.0), script = StatusScript()),
            timeScript = TimeScript(),
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

    /** A looping resume whose speed of 2 waits in its settle phase, 120 ms in, then `pause()`. */
    private fun Rig.pausedInTheSettlePhase(fx: FxModelScope) = with(fx) {
        engine.setLooping(true)
        engine.start()
        advanceBy(1_000)
        engine.pause()
        advanceBy(1_000)
        engine.start()
        engine.setSpeed(2f)
        advanceBy(120)
        engine.pause()
    }

    @Test
    fun aLateWrapAfterAPauseAndASeekKeepsTheNextPlayClearOfTheIdleGapAndTheSpeedComesBack() = fxModel {
        rig {
            pausedInTheSettlePhase(this@fxModel)
            advanceBy(5)
            engine.seekTo(9_000L)
            advanceBy(5)
            onFx { native.deliverEndOfMedia() }
            advanceBy(5)
            engine.start()
            advanceBy(1_500)
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed that waited reached the decoder\n${native.log.tail()}")
        }
    }

    @Test
    fun aLateWrapBetweenTwoPausesOfTheSameResumeKeepsTheNextPlayClearOfTheIdleGap() = fxModel {
        rig {
            pausedInTheSettlePhase(this@fxModel)
            advanceBy(5)
            engine.start()
            advanceBy(30)
            engine.pause()
            advanceBy(1)
            onFx { native.deliverEndOfMedia() }
            advanceBy(5)
            engine.start()
            advanceBy(1_500)
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed that waited reached the decoder\n${native.log.tail()}")
        }
    }

    @Test
    fun aLateWrapAfterReleaseWritesNothingOnTheReleasedPlayer() = fxModel {
        rig {
            pausedInTheSettlePhase(this@fxModel)
            advanceBy(5)
            engine.start()
            advanceBy(5)
            engine.release()
            onFx { native.deliverEndOfMedia(ignoreClearedHandler = true) }
            advanceBy(500)
            val entries: List<NativeCallEntry> = native.log.entries
            val disposeAt: Int = entries.indexOfFirst { it.call == NativeCall.DISPOSE }
            assertTrue(disposeAt >= 0, "release() disposed the player\n${native.log.tail()}")
            val late: List<NativeCallEntry> = entries.drop(disposeAt + 1).filter { it.call == NativeCall.SET_RATE || it.call == NativeCall.PLAY || it.call == NativeCall.SEEK || it.call == NativeCall.PAUSE }
            assertEquals(emptyList(), late, "native calls after dispose()\n${native.log.tail()}")
        }
    }
}
