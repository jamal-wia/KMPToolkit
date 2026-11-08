package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The host's second member, `launchForResult` (roles, device admins), through the same lifecycle events as
 * the permission dialog: the result reaches the request across rotations and a pause, it is reported once,
 * and a finished activity ends it as interrupted.
 */
@RunWith(AndroidJUnit4::class)
class HostResultScreenLifecycleTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun resumedActivity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

    @Test
    fun `the result of a screen started for a result reaches the request across two rotations and a pause`() {
        val controller: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        val codes: MutableList<Int> = mutableListOf()
        assertTrue(host.launchForResult(Intent("com.example.ROLE")) { codes += it })
        val started = assertNotNull(shadowOf(controller.get()).nextStartedActivityForResult)

        controller.recreate()
        controller.pause()
        controller.resume()
        controller.recreate()
        assertTrue(codes.isEmpty(), "answered before the user did")
        assertFalse(host.launchForResult(Intent("x")) {}, "one request at a time")

        controller.get().activityResultRegistry.dispatchResult(started.requestCode, Activity.RESULT_OK, Intent())

        assertEquals(listOf(Activity.RESULT_OK), codes)
        assertTrue(host.launchForResult(Intent("again")) {}, "the host takes the next request")
    }

    @Test
    fun `a second delivery of the same result is not reported again`() {
        val controller: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        val codes: MutableList<Int> = mutableListOf()
        assertTrue(host.launchForResult(Intent("com.example.ROLE")) { codes += it })
        val started = assertNotNull(shadowOf(controller.get()).nextStartedActivityForResult)

        controller.get().activityResultRegistry.dispatchResult(started.requestCode, Activity.RESULT_CANCELED, null)
        controller.get().activityResultRegistry.dispatchResult(started.requestCode, Activity.RESULT_OK, null)

        assertEquals(listOf(Activity.RESULT_CANCELED), codes)
    }

    @Test
    fun `a screen request whose activity is finished ends as cancelled and is reported once`() {
        val controller: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        val codes: MutableList<Int> = mutableListOf()
        assertTrue(host.launchForResult(Intent("com.example.ROLE")) { codes += it })

        controller.get().finish()
        controller.pause().stop().destroy()
        activities.remove(controller)
        resumedActivity()

        assertEquals(listOf(Activity.RESULT_CANCELED), codes)
        assertTrue(host.launchForResult(Intent("next")) {})
    }
}
