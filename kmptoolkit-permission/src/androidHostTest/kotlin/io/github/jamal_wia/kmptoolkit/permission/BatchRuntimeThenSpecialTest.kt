package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity

/**
 * `request(listOf(runtime, specialAccess))` through the real stack, in the order Android really delivers it:
 * the activity is paused while the dialog is up, its answer arrives, and only then does the activity resume.
 * The resume that follows the dialog is not the user coming back from Settings: the special access screen is
 * opened after it, and the call returns only after the next resume ("returning after the app is in the
 * foreground again"), on the levels the library supports.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 30, 34])
class BatchRuntimeThenSpecialTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val screens: MutableList<SystemScreenRequest> = mutableListOf()

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun ShadowActivity.answerDenied(activity: Activity) {
        val request: ShadowActivity.PermissionsRequest = assertNotNull(lastRequestedPermission)
        val flag = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").also { it.isAccessible = true }
        flag.setBoolean(activity, false)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
        )
        flag.setBoolean(activity, false)
    }

    @Test
    fun `the resume that follows the dialog does not end the wait for the special access screen`() = runTest(UnconfinedTestDispatcher()) {
        val camera: String = Manifest.permission.CAMERA
        application.declareInManifest(camera)
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val controller: PermissionController = createPermissionController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            activityAccess = access,
            host = ActivityResultPermissionRequestHost(access),
            systemScreenLauncher = SystemScreenLauncher { request -> screens += request; true },
        )

        val call = async { controller.request(listOf(CommonPermission.Camera, AndroidPermission.Overlay)) }
        // The runtime permission is asked first, in one dialog, and the activity is paused behind it.
        assertNotNull(shadowOf(screen.get()).lastRequestedPermission, "the dialog is up")
        screen.pause()
        shadowOf(screen.get()).answerDenied(screen.get())
        assertEquals(0, screens.size, "no special access screen before the dialog's activity is back")

        screen.resume() // the resume that follows the dialog's answer
        assertEquals(1, screens.size, "the special access screen opens after the dialog")
        assertFalse(call.isCompleted, "the call returned although Settings was only just opened (the dialog's own resume ended the wait)")

        screen.pause() // Settings comes up
        assertFalse(call.isCompleted)
        screen.resume() // the user is back
        assertTrue(call.isCompleted, "the user is back and the call is still waiting")
        assertEquals(PermissionStatus.Denied(explainFirst = true), call.await().getValue(AndroidPermission.Overlay))
    }
}
