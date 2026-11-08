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
 * A request ended as interrupted hands control back to its caller from inside the host (the resume
 * notification that found the dialog's activity finishing, or that activity's destroy). The caller may ask
 * again right there, as a controller queued behind it does: the host must already be free, and the second
 * request must be a request of its own, answered once with its own answer.
 */
@RunWith(AndroidJUnit4::class)
class HostRelaunchFromAbandonTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun resumedActivity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

    private fun answer(activity: Activity, request: ShadowActivity.PermissionsRequest, granted: Boolean) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").also { it.isAccessible = true }
        if (granted) shadowOf(application).grantPermissions(camera)
        field.setBoolean(activity, false)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
        )
        field.setBoolean(activity, false)
    }

    @Test
    fun `asking again from inside the abandon that a resume found gets a request of its own`() {
        shadowOf(application).denyPermissions(camera)
        val origin: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        val first: MutableList<Map<String, Boolean>> = mutableListOf()
        val second: MutableList<Map<String, Boolean>> = mutableListOf()
        var relaunched: Boolean? = null
        assertTrue(host.launch(listOf(camera)) { answers ->
            first += answers
            relaunched = host.launch(listOf(camera)) { second += it }
        })
        origin.pause()
        origin.get().finish()

        val next: ActivityController<ComponentActivity> = resumedActivity() // its resume finds the origin finishing

        assertEquals(listOf<Map<String, Boolean>>(emptyMap()), first, "the finished origin's request did not end exactly once")
        assertEquals(true, relaunched, "the host was not free inside the abandon callback")
        val request: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(next.get()).lastRequestedPermission)
        assertEquals(emptyList(), second, "the second request was resolved with the first one's end")
        answer(next.get(), request, granted = true)
        assertEquals(listOf(mapOf(camera to true)), second)
        assertEquals(1, first.size)
    }

    @Test
    fun `asking again from inside the abandon that the origin's destroy caused gets a request of its own`() {
        shadowOf(application).denyPermissions(camera)
        val origin: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        val first: MutableList<Map<String, Boolean>> = mutableListOf()
        val second: MutableList<Map<String, Boolean>> = mutableListOf()
        var relaunched: Boolean? = null
        assertTrue(host.launch(listOf(camera)) { answers ->
            first += answers
            relaunched = host.launch(listOf(camera)) { second += it }
        })
        origin.pause()
        val other: ActivityController<ComponentActivity> = resumedActivity() // alive and resumed, never the dialog's activity
        assertEquals(emptyList(), first)

        origin.get().finish()
        origin.stop().destroy()
        activities.remove(origin)

        assertEquals(listOf<Map<String, Boolean>>(emptyMap()), first)
        assertEquals(true, relaunched, "the host was not free inside the abandon callback")
        val request: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(other.get()).lastRequestedPermission)
        answer(other.get(), request, granted = false)
        assertEquals(listOf(mapOf(camera to false)), second)
        assertEquals(1, first.size)
    }
}
