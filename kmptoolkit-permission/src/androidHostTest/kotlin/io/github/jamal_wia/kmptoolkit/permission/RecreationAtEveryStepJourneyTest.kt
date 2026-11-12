package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * The whole refusal journey of 03-guide "Denied once versus permanently denied" through the real factory,
 * the real host and a real activity, with the activity recreated (a configuration change) at every point
 * of each of two dialogs: before the pause, while paused behind the dialog, between the answer and the
 * resume, and after the resume. Every combination must read `Denied(explainFirst = true)` after the first
 * refusal, `PermanentlyDenied` after the second, show exactly two dialogs, and show none for a third
 * request.
 */
@RunWith(AndroidJUnit4::class)
class RecreationAtEveryStepJourneyTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val camera: String = Manifest.permission.CAMERA

    private class CountingHost(private val inner: ActivityResultPermissionRequestHost) : PermissionRequestHost {
        var launches: Int = 0
        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            launches++
            return inner.launch(androidPermissions, onResult)
        }

        override fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean =
            inner.launchForResult(intent, onResult)
    }

    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    private fun journey(first: Int, second: Int) = runTest(UnconfinedTestDispatcher()) {
        val tag = "recreate at $first then $second"
        val access: ActivityAccess = createActivityAccess(application)
        val screen: ActivityController<ComponentActivity> = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            application.declareInManifest(camera)
            shadowOf(application).denyPermissions(camera)
            val packageManager = shadowOf(application.packageManager)
            packageManager.setShouldShowRequestPermissionRationale(camera, false)
            val host = CountingHost(ActivityResultPermissionRequestHost(access))
            val controller: PermissionController = createPermissionController(
                context = application,
                storage = InMemoryKeyValueStorage(),
                activityAccess = access,
                host = host,
            )

            suspend fun dialog(recreateAt: Int, rationaleAfter: Boolean): PermissionStatus {
                val call: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Camera) }
                val up: ShadowActivity.PermissionsRequest = assertNotNull(
                    shadowOf(screen.get()).lastRequestedPermission,
                    "$tag: the dialog is not up",
                )
                if (recreateAt == 0) screen.recreate()
                screen.pause()
                if (recreateAt == 1) screen.recreate().pause()
                packageManager.setShouldShowRequestPermissionRationale(camera, rationaleAfter)
                endPlatformRequest(screen.get())
                @Suppress("DEPRECATION")
                screen.get().onRequestPermissionsResult(up.requestCode, up.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
                endPlatformRequest(screen.get())
                if (recreateAt == 2) screen.recreate().pause()
                screen.resume()
                if (recreateAt == 3) screen.recreate()
                assertEquals(true, call.isCompleted, "$tag: the request did not return after the resume")
                return call.await()
            }

            assertEquals(PermissionStatus.Denied(explainFirst = true), dialog(first % 4, rationaleAfter = true), "$tag: after the first refusal")
            assertEquals(PermissionStatus.PermanentlyDenied, dialog(second % 4, rationaleAfter = false), "$tag: after the second refusal")
            assertEquals(2, host.launches, "$tag: dialogs shown")
            assertEquals(PermissionStatus.PermanentlyDenied, controller.request(CommonPermission.Camera), "$tag: third request")
            assertEquals(2, host.launches, "$tag: the third request showed a dialog")
        } finally {
            access.release()
            screen.close()
        }
    }

    @Test
    fun `the refusal journey reads the same whichever step of either dialog the activity is recreated at`() {
        for (first in 0..3) for (second in 0..3) journey(first, second)
    }
}
