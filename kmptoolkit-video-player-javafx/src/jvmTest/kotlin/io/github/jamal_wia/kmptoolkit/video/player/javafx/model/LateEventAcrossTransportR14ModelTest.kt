package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 round 14, Q1 (the r13 fix review SHOULD-1): a loop wrap that JavaFX queued before a `pause()` and delivers after a LATER
 * `start()` whose native `play()` is still delayed. The contract is the sentence of `05-platform-notes.md`, "Playback speed": a
 * `play()` that follows a rate written while the player was not playing within about 200 ms waits for the rest of those
 * 200 ms. The end-of-test hazard check of `fxModel` fails a case whose `play()` came inside that gap (W-IDLE).
 *
 * Virtual time; the late event is delivered by hand (`deliverEndOfMedia`) at fixed instants, so every case is deterministic.
 */
class LateEventAcrossTransportR14ModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / NANOS_PER_MS

    private fun FxModelScope.rig(clip: ClipModel = ClipModel(durationMs = 30_000.0), body: Rig.() -> Unit) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, clip = clip, script = StatusScript()), timeScript = TimeScript())
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
     * Brings the player to the shape of the candidate: a resume whose speed of 2 waits in [settleNudge] (the shared player gives
     * the speed right after `start()`, so the property still holds 1.0), 120 ms into its settle phase (past the near-start zone,
     * short of the 200 ms of movement the speed needs).
     */
    private fun Rig.resumeAtTwoInItsSettlePhase(fx: FxModelScope) = with(fx) {
        engine.setLooping(true)
        engine.start()
        advanceBy(1_000)
        engine.pause()
        advanceBy(1_000)
        engine.start()
        engine.setSpeed(2f)
        advanceBy(120)
    }

    // ---- the candidate: pause, a later start() whose play() is delayed, then the wrap JavaFX queued before the pause ----

    @Test
    fun aLoopWrapDeliveredAfterALaterStartWhosePlayIsStillDelayedLeavesThePlayOutsideTheIdleGap() = fxModel {
        rig {
            resumeAtTwoInItsSettlePhase(this@fxModel)
            engine.pause()
            advanceBy(5)
            engine.start()
            advanceBy(5)
            onFx { native.deliverEndOfMedia() }
            advanceBy(1_000)
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed that waited reached the decoder\n${native.log.tail()}")
        }
    }

    @Test
    fun theSameWrapDeliveredAtEveryInstantOfTheDelayedPlayWaitKeepsThePlayOutsideTheIdleGap() {
        val findings: MutableList<String> = mutableListOf()
        for (lateMs: Int in listOf(0, 1, 5, 20, 60, 120, 190, 199)) {
            try {
                fxModel {
                    rig {
                        resumeAtTwoInItsSettlePhase(this@fxModel)
                        engine.pause()
                        advanceBy(5)
                        engine.start()
                        advanceBy(lateMs.toLong())
                        onFx { native.deliverEndOfMedia() }
                        advanceBy(1_000)
                    }
                }
            } catch (t: Throwable) {
                findings += "late wrap at +$lateMs ms after the second start: ${t.message?.take(500)}"
            }
        }
        assertTrue(findings.isEmpty(), findings.joinToString("\n---\n"))
    }

    private companion object {
        const val NANOS_PER_MS: Double = 1_000_000.0
    }
}
