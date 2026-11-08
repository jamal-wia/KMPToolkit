package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * `request(Collection)`: "Cancellation mid-batch abandons the permissions not reached yet; the ones already
 * answered are in the OS". And a dialog interrupted or abandoned records nothing.
 */
@RunWith(AndroidJUnit4::class)
class BatchCancellationTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val storage: KeyValueStorage = InMemoryKeyValueStorage()
    private val resumes: ResumeListeners = ResumeListeners()
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO

    /** A host that keeps the dialog up until the test answers it. */
    private class HeldHost : PermissionRequestHost {
        var callback: ((Map<String, Boolean>) -> Unit)? = null
        var launches: Int = 0
        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            launches++
            callback = onResult
            return true
        }
        override fun launchForResult(intent: android.content.Intent, onResult: (resultCode: Int) -> Unit): Boolean = false
        fun answer(answers: Map<String, Boolean>) = callback!!.invoke(answers)
    }

    private fun controller(host: PermissionRequestHost): AndroidPermissionController = testController(
        context = application,
        storage = storage,
        host = host,
        sdkInt = Build.VERSION_CODES.TIRAMISU,
        resumeListeners = resumes,
    )

    @Test
    fun `a batch cancelled while a special access screen is open never reaches the later permissions`() = runTest(UnconfinedTestDispatcher()) {
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
        shadowOf(application).denyPermissions(camera)
        val host = RecordingHost(answers = { true })
        val controller: AndroidPermissionController = controller(host)

        val batch = launch { controller.request(listOf(AndroidPermission.InstallPackages, CommonPermission.Camera)) }
        assertEquals(1, resumes.count, "the batch is waiting for the user to come back")
        batch.cancel()

        assertEquals(0, resumes.count)
        assertEquals(0, host.launchCount, "a permission not reached yet was requested after the cancel")
        // the controller is free again
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))
        val next = async { controller.request(CommonPermission.Camera) }
        assertTrue(next.isCompleted)
        assertEquals(1, host.launchCount)
    }

    @Test
    fun `a batch cancelled with the shared dialog up records nothing when the dialog is answered later`() = runTest(UnconfinedTestDispatcher()) {
        shadowOf(application).denyPermissions(camera, microphone)
        val host = HeldHost()
        val controller: AndroidPermissionController = controller(host)

        val batch = launch { controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone)) }
        assertEquals(1, host.launches)
        batch.cancel()
        host.answer(mapOf(camera to false, microphone to false))

        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.Camera)))
        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.Microphone)))
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))
    }

    @Test
    fun `a shared dialog interrupted without an answer records nothing and returns what the statuses were`() = runTest {
        shadowOf(application).denyPermissions(camera, microphone)
        val host = object : RecordingHost(answers = { false }) {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                groups += androidPermissions
                onResult(emptyMap())
                return true
            }
        }
        val controller: AndroidPermissionController = controller(host)

        val result: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone))

        assertEquals(
            mapOf<Permission, PermissionStatus>(
                CommonPermission.Camera to PermissionStatus.NotDetermined,
                CommonPermission.Microphone to PermissionStatus.NotDetermined,
            ),
            result,
        )
        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.Camera)))
    }

    @Test
    fun `a partial answer in a shared dialog settles each permission by its own strings`() = runTest {
        shadowOf(application).denyPermissions(camera, microphone)
        val host = object : RecordingHost(answers = { false }) {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                groups += androidPermissions
                shadowOf(application).grantPermissions(camera)
                onResult(mapOf(camera to true, microphone to false))
                return true
            }
        }
        val controller: AndroidPermissionController = controller(host)

        val result: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone))

        assertEquals(PermissionStatus.Granted, result.getValue(CommonPermission.Camera))
        assertFalse(result.getValue(CommonPermission.Microphone).isUsable)
        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.Camera)), "a grant leaves no flag")
        assertEquals("dialog-shown", storage.getStringOrNull(askedKey("test", CommonPermission.Microphone)))
    }
}
