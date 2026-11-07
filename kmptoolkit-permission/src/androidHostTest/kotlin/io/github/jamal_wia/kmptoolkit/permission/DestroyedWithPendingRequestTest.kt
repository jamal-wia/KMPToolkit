package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import java.lang.ref.WeakReference
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
 * What the real host keeps after a request is over, measured by garbage-collector reachability:
 * the request's answer callback, and the activities it was attached to.
 */
@RunWith(AndroidJUnit4::class)
class DestroyedWithPendingRequestTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private class Payload

    private fun clearedAfterGc(references: List<WeakReference<*>>): Boolean {
        repeat(30) {
            System.gc()
            System.runFinalization()
            if (references.all { it.get() == null }) return true
            Thread.sleep(25)
        }
        return references.all { it.get() == null }
    }

    private fun resumedActivity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

    /** The platform clears the "a request is up" flag before it calls onRequestPermissionsResult. */
    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    private fun answerGranted(activity: Activity, request: ShadowActivity.PermissionsRequest) {
        shadowOf(application).grantPermissions(camera)
        endPlatformRequest(activity)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED },
        )
        endPlatformRequest(activity)
    }

    /** Launches a dialog whose answer callback captures a payload; returns only a weak reference to it. */
    private fun launchWithPayload(host: ActivityResultPermissionRequestHost): WeakReference<Payload> {
        val payload = Payload()
        assertTrue(host.launch(listOf(camera)) { payload.hashCode() }, "the dialog was not launched")
        return WeakReference(payload)
    }

    private fun destroyedWhileRequestPending(host: ActivityResultPermissionRequestHost): WeakReference<Activity> {
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> = resumedActivity()
        launchWithPayload(host)
        // Destroyed by the system, not finished and not a configuration change: the registry would restore
        // the answer on a recreated activity, but nothing has been recreated and nothing may be held meanwhile.
        screen.pause().stop().destroy()
        activities.remove(screen)
        return WeakReference(screen.get())
    }

    @Test
    fun `an activity destroyed by the system with a request pending is not pinned by the host`() {
        val host = ActivityResultPermissionRequestHost(access)
        assertTrue(
            clearedAfterGc(listOf(destroyedWhileRequestPending(host))),
            "the host still holds the destroyed activity (and with it its whole view hierarchy) while it waits for a recreation",
        )
    }
}
