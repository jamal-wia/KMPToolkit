package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows

/**
 * The parts of [MediaRecorderEngine] that are real logic rather than a pass-through to
 * `MediaRecorder`: which formats it claims to support, whether its permission check tracks the
 * platform, and whether releasing an engine that never prepared is safe.
 *
 * Everything that genuinely drives the encoder — `prepare`, `start`, `pause`, `stop` — needs a
 * microphone and is left to a device.
 */
@RunWith(AndroidJUnit4::class)
class MediaRecorderEngineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = MediaRecorderEngine(context)

    @Test
    fun `AAC formats are supported on Android`() {
        assertTrue(engine.supportsFormat(AudioFormat.M4A))
        assertTrue(engine.supportsFormat(AudioFormat.AAC))
    }

    @Test
    fun `WAV is refused because MediaRecorder has no linear PCM output format`() {
        assertFalse(
            engine.supportsFormat(AudioFormat.WAV),
            "claiming WAV support would write AAC into a file named .wav",
        )
    }

    @Test
    fun `releasing an engine that never prepared is a no-op rather than a crash`() {
        engine.release()
        engine.release()
    }

    @Test
    fun `the permission check tracks the platform rather than being hardcoded`() {
        val application: Application = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(engine.hasRecordAudioPermission())

        Shadows.shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(engine.hasRecordAudioPermission())
    }

    @Test
    fun `a granted permission lets prepare past the permission gate`() = runTest {
        val application: Application = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val recorder: AudioRecorder = createAudioRecorder(context)

        try {
            // Whether the shadow MediaRecorder can actually prepare is not the point and is not
            // asserted — only that the permission check no longer stops the call, which is what
            // pins hasRecordAudioPermission() to the real PackageManager rather than a constant.
            val error: RecorderError? = recorder.prepare().errorOrNull()

            assertNotEquals(RecorderError.PermissionDenied, error)
        } finally {
            recorder.release()
        }
    }
}
