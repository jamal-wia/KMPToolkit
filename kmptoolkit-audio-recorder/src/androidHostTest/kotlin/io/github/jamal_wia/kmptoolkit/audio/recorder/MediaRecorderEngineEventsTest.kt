package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowMediaRecorder

/**
 * What [MediaRecorderEngine] reports on its own: the platform's error and info callbacks, mapped
 * to [EngineEvent]s, registered only while a listener is set and removed before a normal stop and
 * on release. The Robolectric shadow exposes the listeners the engine registered, so the tests call
 * them directly the way the platform would.
 *
 * The Android 10 silencing glue (`AudioRecordingMonitor`) has no shadow and is deliberately thin;
 * how the engine drives it is covered against a stand-in in [MediaRecorderEngineSilenceTest], the
 * debounce and the decision behind it in common tests.
 */
@RunWith(AndroidJUnit4::class)
class MediaRecorderEngineEventsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = MediaRecorderEngine(context)
    private val events: MutableList<EngineEvent> = mutableListOf()

    private suspend fun prepared(maxFileSizeBytes: Long? = null): Pair<MediaRecorder, ShadowMediaRecorder> {
        engine.prepare("/tmp/engine-events.m4a", AudioRecorderConfig(), maxFileSizeBytes)
        val recorder: MediaRecorder = assertNotNull(engine.activeRecorder)
        return recorder to Shadows.shadowOf(recorder)
    }

    // --- mapping ---

    @Test
    fun `a dead media server is an engine death carrying its code`() {
        assertEquals(
            InterruptionReason.EngineDied(MediaRecorder.MEDIA_ERROR_SERVER_DIED),
            mapMediaRecorderError(MediaRecorder.MEDIA_ERROR_SERVER_DIED, extra = 0),
        )
        assertEquals(
            InterruptionReason.EngineDied(MediaRecorder.MEDIA_ERROR_SERVER_DIED),
            mapMediaRecorderError(MediaRecorder.MEDIA_ERROR_SERVER_DIED, extra = 7),
            "extra is ignored when what already names the failure",
        )
    }

    @Test
    fun `an unknown recorder error carries its extra code when it has one`() {
        assertEquals(
            InterruptionReason.EngineDied(-1007),
            mapMediaRecorderError(MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN, extra = -1007),
        )
        assertEquals(
            InterruptionReason.EngineDied(MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN),
            mapMediaRecorderError(MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN, extra = 0),
        )
    }

    @Test
    fun `any other error code still means the recorder is broken`() {
        assertEquals(InterruptionReason.EngineDied(4242), mapMediaRecorderError(4242, extra = 0))
    }

    @Test
    fun `reaching the file size limit is storage low`() {
        assertEquals(
            InterruptionReason.StorageLow,
            mapMediaRecorderInfo(MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED),
        )
    }

    @Test
    fun `informational codes that end nothing map to null`() {
        // No duration limit is ever set, so this one cannot fire; if it did it would not be ours.
        assertNull(mapMediaRecorderInfo(MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED))
        assertNull(mapMediaRecorderInfo(MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN))
        assertNull(mapMediaRecorderInfo(MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING))
    }

    // --- registration ---

    @Test
    fun `no platform listener is registered until an event listener is set`() = runTest {
        val (_, shadow) = prepared()

        assertNull(shadow.errorListener)
        assertNull(shadow.infoListener)
    }

    @Test
    fun `an error callback reaches the listener as an engine death`() = runTest {
        val (recorder, shadow) = prepared()
        engine.setEventListener { event: EngineEvent -> events += event }

        shadow.errorListener.onError(recorder, MediaRecorder.MEDIA_ERROR_SERVER_DIED, 0)

        assertContentEquals(
            listOf(EngineEvent.Interrupted(InterruptionReason.EngineDied(100))),
            events,
        )
    }

    @Test
    fun `an info callback for the size limit reaches the listener as storage low`() = runTest {
        val (recorder, shadow) = prepared()
        engine.setEventListener { event: EngineEvent -> events += event }

        shadow.infoListener.onInfo(recorder, MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED, 0)
        shadow.infoListener.onInfo(recorder, MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, 0)

        assertContentEquals(listOf(EngineEvent.Interrupted(InterruptionReason.StorageLow)), events)
    }

    @Test
    fun `removing the listener removes the platform listeners and silences late callbacks`() = runTest {
        val (recorder, shadow) = prepared()
        engine.setEventListener { event: EngineEvent -> events += event }
        val lateError: MediaRecorder.OnErrorListener = shadow.errorListener

        engine.setEventListener(null)
        lateError.onError(recorder, MediaRecorder.MEDIA_ERROR_SERVER_DIED, 0)

        assertNull(shadow.errorListener)
        assertNull(shadow.infoListener)
        assertTrue(events.isEmpty(), "a callback already in flight must not reach a removed listener")
    }

    @Test
    fun `a normal stop removes the platform listeners before the recorder is stopped`() = runTest {
        val (recorder, shadow) = prepared()
        engine.setEventListener { event: EngineEvent -> events += event }
        engine.start()
        val lateInfo: MediaRecorder.OnInfoListener = shadow.infoListener

        engine.stop()
        lateInfo.onInfo(recorder, MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED, 0)

        assertNull(shadow.errorListener)
        assertNull(shadow.infoListener)
        assertTrue(events.isEmpty(), "stopping is not something the system did")
    }

    @Test
    fun `release removes the platform listeners`() = runTest {
        val (_, shadow) = prepared()
        engine.setEventListener { event: EngineEvent -> events += event }

        engine.release()

        assertNull(shadow.errorListener)
        assertNull(shadow.infoListener)
        assertNull(engine.activeRecorder)
    }

    @Test
    fun `a callback that arrives after release reports nothing`() = runTest {
        val (recorder, shadow) = prepared()
        engine.setEventListener { event: EngineEvent -> events += event }
        val lateError: MediaRecorder.OnErrorListener = shadow.errorListener
        val lateInfo: MediaRecorder.OnInfoListener = shadow.infoListener

        engine.release()
        lateError.onError(recorder, MediaRecorder.MEDIA_ERROR_SERVER_DIED, 0)
        lateInfo.onInfo(recorder, MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED, 0)

        assertTrue(events.isEmpty(), "a released engine has nobody to tell")
    }

    @Test
    fun `an error callback that arrives after a normal stop reports nothing`() = runTest {
        val (recorder, shadow) = prepared()
        engine.setEventListener { event: EngineEvent -> events += event }
        engine.start()
        val lateError: MediaRecorder.OnErrorListener = shadow.errorListener

        engine.stop()
        lateError.onError(recorder, MediaRecorder.MEDIA_ERROR_SERVER_DIED, 0)

        assertTrue(events.isEmpty(), "stopping is not something the system did")
    }

    @Test
    fun `a callback of a replaced listener does not reach the one that replaced it`() = runTest {
        val (recorder, shadow) = prepared()
        val first: MutableList<EngineEvent> = mutableListOf()
        engine.setEventListener { event: EngineEvent -> first += event }
        val staleError: MediaRecorder.OnErrorListener = shadow.errorListener
        engine.setEventListener { event: EngineEvent -> events += event }

        staleError.onError(recorder, MediaRecorder.MEDIA_ERROR_SERVER_DIED, 0)

        assertTrue(first.isEmpty())
        assertTrue(events.isEmpty(), "an in-flight callback belongs to the listener it was installed for")
    }

    @Test
    fun `setting a listener on an engine that never prepared is harmless`() {
        engine.setEventListener { event: EngineEvent -> events += event }
        engine.setEventListener(null)
        engine.release()
    }

    // --- size limit ---

    @Test
    fun `the free space backstop becomes the platform's maximum file size`() = runTest {
        val (_, shadow) = prepared(maxFileSizeBytes = 123_456_789L)

        assertEquals(123_456_789L, shadow.maxFileSize)
    }

    @Test
    fun `no backstop leaves the platform's file size unlimited`() = runTest {
        val (_, shadow) = prepared(maxFileSizeBytes = null)

        assertEquals(0L, shadow.maxFileSize)
    }
}
