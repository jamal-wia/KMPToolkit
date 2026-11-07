package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowMediaRecorder

/**
 * A callback the platform had already started delivering when the engine replaced or removed its
 * listener, or released the recorder it came from, must reach nobody - not the listener that was
 * replaced, and above all not the listener of the next recording. The platform listener objects are
 * captured before the engine lets go of them and invoked afterwards, which is that interleaving.
 */
@RunWith(AndroidJUnit4::class)
class MediaRecorderEngineChaosTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val monitors: MutableList<CapturingMonitor> = mutableListOf()
    private val factory = InputSilenceMonitor.Factory { _: MediaRecorder, onSilenced: (Boolean) -> Unit ->
        CapturingMonitor(onSilenced).also { monitors += it }
    }
    private val engine = MediaRecorderEngine(context, factory)

    private class CapturingMonitor(val onSilenced: (Boolean) -> Unit) : InputSilenceMonitor {
        override fun register() = Unit
        override fun unregister() = Unit
        override fun checkNow() = Unit
    }

    private class Platform(
        val recorder: MediaRecorder,
        val error: MediaRecorder.OnErrorListener,
        val info: MediaRecorder.OnInfoListener,
    ) {
        fun fireError() = error.onError(recorder, MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN, 100)
        fun fireMaxSize() =
            info.onInfo(recorder, MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED, 0)
    }

    private fun field(shadow: ShadowMediaRecorder, name: String): Any? =
        ShadowMediaRecorder::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(shadow)

    private fun capture(): Platform {
        val recorder: MediaRecorder = assertNotNull(engine.activeRecorder)
        val shadow: ShadowMediaRecorder = Shadows.shadowOf(recorder)
        return Platform(
            recorder,
            assertNotNull(field(shadow, "errorListener") as MediaRecorder.OnErrorListener?),
            assertNotNull(field(shadow, "infoListener") as MediaRecorder.OnInfoListener?),
        )
    }

    @Test
    fun `callbacks of an earlier recording reach nobody once a later recording has its own listener`() = runTest {
        repeat(CYCLES) { cycle: Int ->
            val received: MutableList<String> = mutableListOf()
            engine.prepare("/tmp/chaos-$cycle.m4a", AudioRecorderConfig(), null)
            engine.setEventListener { event: EngineEvent -> received += "$cycle:$event" }
            val earlier: Platform = capture()
            val earlierMonitor: CapturingMonitor = monitors.last()
            when (cycle % 3) {
                0 -> engine.release()
                1 -> {
                    engine.start()
                    engine.stop()
                    engine.release()
                }
                else -> engine.setEventListener(null)
            }

            // The next recording, with a listener of its own.
            val next: MutableList<String> = mutableListOf()
            engine.prepare("/tmp/chaos-$cycle-next.m4a", AudioRecorderConfig(), null)
            engine.setEventListener { event: EngineEvent -> next += "$cycle:$event" }

            earlier.fireError()
            earlier.fireMaxSize()
            earlierMonitor.onSilenced(true)

            assertEquals(emptyList(), next, "cycle $cycle: the next recording was told about the earlier one")
            assertEquals(emptyList(), received, "cycle $cycle: a removed listener was called")
            engine.release()
        }
    }

    @Test
    fun `callbacks captured before a listener is replaced never reach the replacement or the original`() = runTest {
        repeat(CYCLES) { cycle: Int ->
            engine.prepare("/tmp/replace-$cycle.m4a", AudioRecorderConfig(), null)
            val original: MutableList<EngineEvent> = mutableListOf()
            val replacement: MutableList<EngineEvent> = mutableListOf()
            engine.setEventListener { event: EngineEvent -> original += event }
            val before: Platform = capture()
            val monitorBefore: CapturingMonitor = monitors.last()
            engine.setEventListener { event: EngineEvent -> replacement += event }
            val after: Platform = capture()

            before.fireError()
            before.fireMaxSize()
            monitorBefore.onSilenced(true)
            assertEquals(emptyList(), original, "cycle $cycle: the replaced listener was called")
            assertEquals(emptyList(), replacement, "cycle $cycle: the replacement heard the replaced registration's callbacks")

            // The live registration still works, once and only once.
            after.fireError()
            assertEquals(1, replacement.size, "cycle $cycle: the live callback was lost or doubled: $replacement")
            assertTrue(replacement.single() is EngineEvent.Interrupted, "cycle $cycle")
            engine.release()
        }
    }

    private companion object {
        const val CYCLES: Int = 50
    }
}
