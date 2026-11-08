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
class HostStarvationTest {

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

    @Test
    fun `control - an answered request leaves nothing of its callback in the registry`() {
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        val payload: WeakReference<Payload> = launchWithPayload(host)

        answerGranted(screen.get(), assertNotNull(shadowOf(screen.get()).lastRequestedPermission))

        assertTrue(clearedAfterGc(listOf(payload)), "the control did not clear: the test's GC is unreliable")
    }

    private fun finishedActivityWithoutRequest(): WeakReference<Activity> {
        val screen: ActivityController<ComponentActivity> = resumedActivity()
        screen.get().finish()
        screen.pause().stop().destroy()
        activities.remove(screen)
        return WeakReference(screen.get())
    }

    @Test
    fun `control - a finished and destroyed component activity with no request is collectable`() {
        assertTrue(clearedAfterGc(listOf(finishedActivityWithoutRequest())), "the control did not clear: the test's GC is unreliable")
    }

    private fun finishedActivityAfterPlainRegistryRequest(): WeakReference<Activity> {
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> = resumedActivity()
        val launcher = screen.get().activityResultRegistry.register(
            "plain-key",
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
        ) { }
        launcher.launch(arrayOf(camera))
        answerGranted(screen.get(), assertNotNull(shadowOf(screen.get()).lastRequestedPermission))
        launcher.unregister()
        screen.get().finish()
        screen.pause().stop().destroy()
        activities.remove(screen)
        return WeakReference(screen.get())
    }

    @Test
    fun `control - an activity that asked the platform for a permission through its own registry is collectable`() {
        assertTrue(clearedAfterGc(listOf(finishedActivityAfterPlainRegistryRequest())), "harness retains the activity after a permission request")
    }

    private fun activityFinishedAfterAnsweredRequest(host: ActivityResultPermissionRequestHost): WeakReference<Activity> {
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> = resumedActivity()
        launchWithPayload(host)
        answerGranted(screen.get(), assertNotNull(shadowOf(screen.get()).lastRequestedPermission))
        screen.get().finish()
        screen.pause().stop().destroy()
        activities.remove(screen)
        return WeakReference(screen.get())
    }

    @Test
    fun `the activity a request was started from is collectable once the request is answered and the activity finished while the host lives on`() {
        val host = ActivityResultPermissionRequestHost(access)

        assertTrue(
            clearedAfterGc(listOf(activityFinishedAfterAnsweredRequest(host))),
            "the host or the tracker still holds the activity after its request ended",
        )
    }

    private fun rotate(screen: ActivityController<ComponentActivity>): WeakReference<Activity> {
        val old: Activity = screen.get()
        screen.recreate()
        return WeakReference(old)
    }

    @Test
    fun `control - twenty rotations with no request leave every old instance collectable`() {
        val screen: ActivityController<ComponentActivity> = resumedActivity()
        val old: List<WeakReference<Activity>> = List(20) { rotate(screen) }

        assertTrue(clearedAfterGc(old), "${old.count { it.get() != null }} of 20 old instances retained: the test's GC is unreliable")
    }

    @Test
    fun `twenty rotations with a request pending leave every old instance collectable and the answer still lands`() {
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        val answers: MutableList<Map<String, Boolean>> = mutableListOf()
        assertTrue(host.launch(listOf(camera)) { answers += it })
        val request: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(screen.get()).lastRequestedPermission)

        val old: List<WeakReference<Activity>> = List(20) { rotate(screen) }

        assertTrue(
            clearedAfterGc(old),
            "${old.count { it.get() != null }} of 20 old instances are still pinned by the host with its request pending",
        )
        answerGranted(screen.get(), request)
        assertEquals(1, answers.size, "the answer did not reach the request after the rotations")
    }
}
