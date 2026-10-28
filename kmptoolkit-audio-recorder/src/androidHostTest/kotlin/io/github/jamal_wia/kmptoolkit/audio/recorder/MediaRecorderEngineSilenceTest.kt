package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * How [MediaRecorderEngine] drives the Android 10+ silencing monitor — registered when a listener
 * is attached, asked once after `start()` and `resume()`, released on every way out — checked
 * against a stand-in for the platform class, which has no Robolectric shadow. Which silences end a
 * recording is decided in common code and tested there.
 */
@RunWith(AndroidJUnit4::class)
class MediaRecorderEngineSilenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val monitors: MutableList<FakeMonitor> = mutableListOf()
    private val factory = InputSilenceMonitor.Factory { _: MediaRecorder, onSilenced: (Boolean) -> Unit ->
        FakeMonitor(onSilenced).also { monitors += it }
    }
    private val engine = MediaRecorderEngine(context, factory)
    private val events: MutableList<EngineEvent> = mutableListOf()

    private class FakeMonitor(val onSilenced: (Boolean) -> Unit) : InputSilenceMonitor {
        var registered: Boolean = false
        var registrations: Int = 0
        var unregistrations: Int = 0
        var checks: Int = 0

        override fun register() {
            registered = true
            registrations++
        }

        override fun unregister() {
            registered = false
            unregistrations++
        }

        override fun checkNow() {
            checks++
        }
    }

    private suspend fun prepared() {
        engine.prepare("/tmp/engine-silence.m4a", AudioRecorderConfig(), null)
    }

    @Test
    fun `a monitor is registered when the listener is attached and not before`() = runTest {
        prepared()
        assertTrue(monitors.isEmpty(), "nothing is registered until someone listens")

        engine.setEventListener { event: EngineEvent -> events += event }

        assertEquals(1, monitors.size)
        assertTrue(monitors.single().registered)
    }

    @Test
    fun `the monitor is asked once after start and once after resume`() = runTest {
        prepared()
        engine.setEventListener { event: EngineEvent -> events += event }
        val monitor: FakeMonitor = monitors.single()

        engine.start()
        assertEquals(1, monitor.checks)
        engine.pause()
        assertEquals(1, monitor.checks, "nothing is captured while paused")
        engine.resume()
        assertEquals(2, monitor.checks)
    }

    @Test
    fun `the monitor is released when the listener is removed`() = runTest {
        prepared()
        engine.setEventListener { event: EngineEvent -> events += event }

        engine.setEventListener(null)

        val monitor: FakeMonitor = monitors.single()
        assertEquals(1, monitor.unregistrations)
        assertTrue(!monitor.registered)
    }

    @Test
    fun `the monitor is released before a normal stop`() = runTest {
        prepared()
        engine.setEventListener { event: EngineEvent -> events += event }
        engine.start()

        engine.stop()

        assertTrue(!monitors.single().registered)
    }

    @Test
    fun `the monitor is released with the recorder`() = runTest {
        prepared()
        engine.setEventListener { event: EngineEvent -> events += event }

        engine.release()

        assertTrue(!monitors.single().registered)
    }

    @Test
    fun `setting a listener twice does not leave two monitors registered`() = runTest {
        prepared()
        engine.setEventListener { event: EngineEvent -> events += event }
        engine.setEventListener { event: EngineEvent -> events += event }

        assertEquals(2, monitors.size)
        assertEquals(1, monitors.first().unregistrations, "the first registration is released first")
        assertEquals(1, monitors.count { it.registered }, "exactly one is live")
    }

    @Test
    fun `a change reported by the monitor reaches the listener`() = runTest {
        prepared()
        engine.setEventListener { event: EngineEvent -> events += event }
        val monitor: FakeMonitor = monitors.single()

        monitor.onSilenced(true)
        monitor.onSilenced(false)

        assertContentEquals(
            listOf(EngineEvent.InputSilenced(true), EngineEvent.InputSilenced(false)),
            events,
        )
    }

    @Test
    fun `a change from a monitor of a replaced or removed listener reaches nobody`() = runTest {
        prepared()
        val first: MutableList<EngineEvent> = mutableListOf()
        engine.setEventListener { event: EngineEvent -> first += event }
        val stale: FakeMonitor = monitors.single()
        engine.setEventListener { event: EngineEvent -> events += event }

        stale.onSilenced(true)
        assertTrue(first.isEmpty(), "the replaced listener hears nothing more")
        assertTrue(events.isEmpty(), "and its callback does not reach the new listener either")

        engine.setEventListener(null)
        monitors.last().onSilenced(true)
        assertTrue(events.isEmpty())
    }

    @Test
    @Config(sdk = [28])
    fun `below Android 10 the monitor is never created`() = runTest {
        prepared()
        engine.setEventListener { event: EngineEvent -> events += event }
        engine.start()
        engine.pause()
        engine.resume()
        engine.setEventListener(null)
        engine.release()

        assertTrue(monitors.isEmpty(), "AudioRecordingMonitor does not exist before API 29")
    }
}
