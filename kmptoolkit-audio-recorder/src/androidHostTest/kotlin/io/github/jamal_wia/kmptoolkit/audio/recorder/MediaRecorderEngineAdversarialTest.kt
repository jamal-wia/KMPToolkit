package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowMediaRecorder

/**
 * Re-initialisation: an engine prepared, listened to and released over and over (a screen that
 * records, is rotated away and records again) must hand back every platform callback and every
 * silence-monitor registration it took, however each cycle ended.
 */
@RunWith(AndroidJUnit4::class)
class MediaRecorderEngineAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val monitors: MutableList<CountingMonitor> = mutableListOf()
    private val factory = InputSilenceMonitor.Factory { _: MediaRecorder, onSilenced: (Boolean) -> Unit ->
        CountingMonitor(onSilenced).also { monitors += it }
    }
    private val engine = MediaRecorderEngine(context, factory)

    private class CountingMonitor(val onSilenced: (Boolean) -> Unit) : InputSilenceMonitor {
        var registered: Boolean = false
        var registrations: Int = 0
        var unregistrations: Int = 0

        override fun register() {
            registered = true
            registrations++
        }

        override fun unregister() {
            registered = false
            unregistrations++
        }

        override fun checkNow() = Unit
    }

    private fun MediaRecorder.errorListener(): Any? = field("errorListener")

    private fun MediaRecorder.infoListener(): Any? = field("infoListener")

    // Robolectric's shadow keeps the listeners in private fields and exposes no getter for them.
    private fun MediaRecorder.field(name: String): Any? {
        val shadow: ShadowMediaRecorder = Shadows.shadowOf(this)
        return ShadowMediaRecorder::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(shadow)
    }

    @Test
    fun `fifty prepare listen release cycles leave no platform callback and no registered monitor`() = runTest {
        repeat(CYCLES) { cycle: Int ->
            engine.prepare("/tmp/cycle-$cycle.m4a", AudioRecorderConfig(), null)
            val platform: MediaRecorder = requireNotNull(engine.activeRecorder) { "cycle $cycle: not prepared" }
            engine.setEventListener { _: EngineEvent -> }
            assertTrue(platform.errorListener() != null, "cycle $cycle: the error callback was not attached")

            when (cycle % 5) {
                0 -> engine.release()
                1 -> {
                    engine.start()
                    engine.stop()
                    engine.release()
                }

                2 -> {
                    // Listener replaced twice: one monitor registered at a time, never stacked.
                    engine.setEventListener { _: EngineEvent -> }
                    engine.setEventListener { _: EngineEvent -> }
                    engine.release()
                }

                3 -> {
                    engine.setEventListener(null)
                    engine.release()
                }

                else -> {
                    // Prepared again on top of a live one: the first recorder is freed by prepare.
                    engine.prepare("/tmp/cycle-$cycle-b.m4a", AudioRecorderConfig(), null)
                    engine.release()
                }
            }

            assertNull(engine.activeRecorder, "cycle $cycle: the platform recorder is still held")
            assertNull(platform.errorListener(), "cycle $cycle: the error callback is still attached")
            assertNull(platform.infoListener(), "cycle $cycle: the info callback is still attached")
            assertEquals(
                ShadowMediaRecorder.STATE_RELEASED,
                Shadows.shadowOf(platform).state,
                "cycle $cycle: the platform recorder was not released",
            )
            assertTrue(monitors.none { it.registered }, "cycle $cycle: a silence monitor is still registered")
        }

        assertEquals(
            monitors.sumOf { it.registrations },
            monitors.sumOf { it.unregistrations },
            "registrations and unregistrations are unbalanced across ${monitors.size} monitors",
        )
        assertTrue(monitors.all { it.registrations == 1 }, "a monitor was registered more than once")
    }

    private companion object {
        const val CYCLES: Int = 50
    }
}
