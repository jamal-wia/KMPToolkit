package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One engine smoke on the scripted player: `JavaFxVideoEngine` built over a [TimedBackend] (under the hazard monitor)
 * is loaded, played, seeked and played to its end. Everything runs in virtual time on the model's FX thread, with no
 * JavaFX toolkit and no real media; the only real time is the hang bound of the load helper.
 */
class TimeModelEngineSmokeTest {

    private class RecordingListener : VideoPlaybackEngineListener {
        var completed: Int = 0
        var failure: Throwable? = null

        override fun onCompleted() {
            completed++
        }

        override fun onFailed(cause: Throwable) {
            failure = cause
        }

        override fun onBufferingChanged(isBuffering: Boolean) = Unit

        override fun onVideoSizeChanged(size: VideoSize?) = Unit
    }

    @Test
    fun loadPlaySeekAndCompleteRunThroughTheEngineOnTheScriptedPlayer() = fxModel {
        val scripted = ScriptedBackend(host)
        val backend = TimedBackend(hazardBackend(scripted))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        val listener = RecordingListener()
        engine.setListener(listener)

        load(engine, VideoSource.Asset("red-64x48-3s.mp4"))

        val player: ScriptedNativePlayer = scripted.last
        assertEquals(3_000L, engine.durationMs(), "the duration the model gave at ready")
        assertEquals(0L, engine.positionMs())

        engine.start()
        advanceBy(500)
        assertTrue(engine.positionMs() in 300L..500L, "position ${engine.positionMs()} after 500 ms of playing")
        engine.seekTo(1_540L)
        runUntilIdle()

        assertEquals(1, listener.completed, "the end was delivered once, as a completion")
        assertEquals(null, listener.failure)
        assertEquals(3_000L, engine.positionMs(), "the engine reports the duration at the end")
        assertEquals(1, backend.lastModel.endsReached)
        assertEquals(0, host.pendingCount)
        assertTrue(player.log.entries.any { it.call == NativeCall.SEEK }, "the seek reached the native player")
        engine.release()
    }
}
