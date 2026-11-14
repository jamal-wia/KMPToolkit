package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Model-tier tests (r11 Q5) for three hand-made mutants of `FxPlayback` that the whole model package let live: a start from
 * the end without the restart seek being stamped, a seek while a resume's speed is held that does not move the settle mark,
 * and a release that detaches the view after the native dispose. Each test is derived from a documented sentence.
 */
class SurvivorKillersR11ModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend, val completedAtMs: MutableList<Double>) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / 1_000_000.0

    private fun FxModelScope.rig(body: Rig.() -> Unit) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        val completed: MutableList<Double> = mutableListOf()
        engine.setListener(object : VideoPlaybackEngineListener {
            override fun onCompleted() {
                completed += nowMs()
            }

            override fun onFailed(cause: Throwable) = throw cause

            override fun onBufferingChanged(isBuffering: Boolean) = Unit

            override fun onVideoSizeChanged(size: VideoSize?) = Unit
        })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend, completed).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    /**
     * CHANGELOG 5.0.0 (Fixed): a speed given right after "playing a completed clip" is held back until at least 300 ms after
     * the seek the restart makes. The restart's seek must be stamped as a native seek for that.
     */
    @Test
    fun aSpeedGivenRightAfterAStartFromTheEndIsHeldPastTheSeekWindowOfTheRestart() = fxModel {
        rig {
            engine.start()
            var waited = 0L
            while (completedAtMs.isEmpty() && waited < 6_000L) {
                advanceBy(50)
                waited += 50
            }
            assertTrue(completedAtMs.isNotEmpty(), "the clip never completed\n${native.log}")
            advanceBy(300)
            val startAt: Double = nowMs()
            engine.start()
            engine.setSpeed(2f)
            advanceBy(3_000)

            assertEquals(1, native.log.calls(NativeCall.SEEK).count { it.tMs >= startAt - 0.01 }, "the restart seeks once\n${native.log}")
            val given: List<NativeCallEntry> =
                native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.arg == 2.0 && it.tMs >= startAt - 0.01 }
            assertEquals(1, given.size, "the speed of 2 reached the native player once\n${native.log}")
            assertTrue(given.single().tMs - startAt >= 300.0 - 0.01, "the speed was written ${given.single().tMs - startAt} ms after the restart seek\n${native.log}")
            assertFalse(composed.hazards.frozen, native.log.tail())
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    /**
     * CHANGELOG 5.0.0 (Fixed): a speed is held until at least 300 ms after the seek AND the playhead has moved on. A resume
     * at a speed holds the speed until the playhead has moved 200 ms; a seek during that wait moves the playhead, so the 200 ms
     * count again from the new place and the speed cannot come before seek + 300 + 200 ms.
     */
    @Test
    fun aSeekWhileAResumeHoldsItsSpeedRestartsTheWaitForTheMovementFromTheNewPlace() = fxModel {
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
            advanceBy(3_000)

            val writes: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs >= seekAt - 0.01 }
            assertTrue(writes.isNotEmpty(), "the held speed never reached the native player\n${native.log}")
            assertTrue(writes.first().tMs - seekAt >= 300.0 + 200.0 - 0.01, "the held speed came ${writes.first().tMs - seekAt} ms after the seek\n${native.log}")
            assertFalse(composed.hazards.frozen, native.log.tail())
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    /** Design (FxPlayback.dispose): the view is detached before the native player is disposed, never after. */
    @Test
    fun theViewIsDetachedBeforeTheNativePlayerIsDisposed() = fxModel {
        rig {
            engine.start()
            advanceBy(500)
            engine.release()
            advanceBy(100)
            val calls: List<NativeCall> = native.log.entries.map { it.call }
            assertTrue(NativeCall.DETACH_VIEW in calls && NativeCall.DISPOSE in calls, "$calls")
            assertTrue(calls.indexOf(NativeCall.DETACH_VIEW) < calls.indexOf(NativeCall.DISPOSE), "detach after dispose: $calls")
        }
    }
}
