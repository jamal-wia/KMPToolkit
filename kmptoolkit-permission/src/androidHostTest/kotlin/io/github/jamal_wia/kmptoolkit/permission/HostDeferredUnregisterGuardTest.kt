package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * The host frees the request code of an answered dialog from a post to the main looper. A caller that asks
 * again from inside the answer registers the same key before that post runs, and the post must not remove
 * the new request's callback.
 */
@RunWith(AndroidJUnit4::class)
class HostDeferredUnregisterGuardTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    private fun answerDenied(activity: Activity, request: ShadowActivity.PermissionsRequest) {
        endPlatformRequest(activity)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
        )
        endPlatformRequest(activity)
    }

    private fun keyCount(registry: ActivityResultRegistry): Int {
        val field = ActivityResultRegistry::class.java.getDeclaredField("keyToRc")
        field.isAccessible = true
        return (field.get(registry) as Map<*, *>).size
    }

    @Test
    fun `a request launched inside the previous answer keeps its callback after the deferred unregister`() {
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val registry: ActivityResultRegistry = screen.get().activityResultRegistry
        val host = ActivityResultPermissionRequestHost(access)
        var first = 0
        var second = 0
        var secondLaunched = false
        assertTrue(
            host.launch(listOf(camera)) {
                first++
                // Same main-thread turn as the answer, before the looper runs the deferred unregister.
                secondLaunched = host.launch(listOf(camera)) { second++ }
            },
            "first request not launched",
        )
        answerDenied(screen.get(), assertNotNull(shadowOf(screen.get()).lastRequestedPermission))
        generateSequence { shadowOf(screen.get()).nextStartedActivityForResult }.count()
        assertEquals(1, first)
        assertTrue(secondLaunched, "the request from inside the answer was not launched")

        shadowOf(Looper.getMainLooper()).idle()
        answerDenied(screen.get(), assertNotNull(shadowOf(screen.get()).lastRequestedPermission))
        generateSequence { shadowOf(screen.get()).nextStartedActivityForResult }.count()
        assertEquals(1, second, "the second request's answer was lost: its callback was unregistered")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(keyCount(registry) <= 2, "the registry kept keys of finished requests: ${keyCount(registry)}")
    }
}
