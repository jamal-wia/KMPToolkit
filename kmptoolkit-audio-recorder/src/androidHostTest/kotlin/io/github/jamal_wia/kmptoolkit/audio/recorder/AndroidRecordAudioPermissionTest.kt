package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows

/**
 * The two halves of this library's `RECORD_AUDIO` position, asserted against a real Android
 * package manager:
 *
 * 1. the permission is **not** declared in the library manifest, so it never appears in a
 *    consumer's merged manifest without them asking for it;
 * 2. a recorder without the grant fails with [RecorderError.PermissionDenied] rather than throwing
 *    the `RuntimeException` `MediaRecorder.start()` would.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRecordAudioPermissionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the library manifest does not contribute the record permission`() {
        val declared: Array<String> = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?: emptyArray()

        assertFalse(
            Manifest.permission.RECORD_AUDIO in declared,
            "a library must never merge RECORD_AUDIO into its consumer's manifest",
        )
    }

    @Test
    fun `preparing without the grant reports permission denied instead of crashing`() = runTest {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.RECORD_AUDIO)
        val recorder: AudioRecorder = createAudioRecorder(context)

        try {
            val result: RecorderResult<String> = recorder.prepare()

            assertEquals(RecorderResult.Failure(RecorderError.PermissionDenied), result)
            assertEquals(
                RecorderState.Failed(RecorderError.PermissionDenied),
                recorder.state.value,
            )
        } finally {
            recorder.release()
        }
    }

    @Test
    fun `a released recorder without the grant still refuses cleanly`() = runTest {
        val recorder: AudioRecorder = createAudioRecorder(context)
        recorder.release()

        assertEquals(
            RecorderError.AlreadyReleased(RecorderOperation.PREPARE),
            recorder.prepare().errorOrNull(),
        )
    }
}
