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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * The host gives a request up when "the activity is finished (not recreated) while the dialog is up". The
 * activity meant is the one the dialog was shown from: another activity of the app that comes and goes
 * (a trampoline, a deep-link screen) while the dialog is up must not end the request, because the answer
 * is still delivered to the registry the dialog was started from.
 */
@RunWith(AndroidJUnit4::class)
class HostOriginActivityTest {

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

    @Test
    fun `a trampoline activity that finishes while the dialog is up does not end the request when the first activity resumes`() {
        shadowOf(application).denyPermissions(camera)
        val origin: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        var answers: Map<String, Boolean>? = null
        assertTrue(host.launch(listOf(camera)) { answers = it })
        val request: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(origin.get()).lastRequestedPermission)

        origin.pause() // the dialog is on top of it
        val trampoline: ActivityController<ComponentActivity> = resumedActivity()
        trampoline.get().finish()
        trampoline.pause()
        origin.resume() // the screen the dialog belongs to is back, still alive

        assertNull(answers, "the request was ended although the activity its dialog belongs to is alive")
        answerGranted(origin.get(), request)
        assertEquals(mapOf(camera to true), answers)
    }

    @Test
    fun `a trampoline activity destroyed while the dialog is up does not end the request`() {
        shadowOf(application).denyPermissions(camera)
        val origin: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        var answers: Map<String, Boolean>? = null
        assertTrue(host.launch(listOf(camera)) { answers = it })
        val request: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(origin.get()).lastRequestedPermission)

        origin.pause()
        val trampoline: ActivityController<ComponentActivity> = resumedActivity()
        trampoline.get().finish()
        trampoline.pause().stop().destroy()
        activities.remove(trampoline)

        assertNull(answers, "the request was ended although the activity its dialog belongs to is alive")
        origin.resume()
        answerGranted(origin.get(), request)
        assertEquals(mapOf(camera to true), answers)
    }

    @Test
    fun `the answer of a request that was ended early is not taken for the answer of the next one`() {
        shadowOf(application).denyPermissions(camera)
        val origin: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        assertTrue(host.launch(listOf(camera)) {})
        val request: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(origin.get()).lastRequestedPermission)
        origin.pause()
        val trampoline: ActivityController<ComponentActivity> = resumedActivity()
        trampoline.get().finish()
        trampoline.pause()
        origin.resume() // the host ends the request here, with the dialog still up

        answerGranted(origin.get(), request) // the user answers the first dialog: no callback is left for it
        var next: Map<String, Boolean>? = null
        assertTrue(host.launch(listOf(camera)) { next = it })

        assertNull(next, "the next request was answered at once with the previous dialog's answer")
    }

    @Test
    fun `control - the activity the dialog belongs to finishing ends the request when another resumes`() {
        shadowOf(application).denyPermissions(camera)
        val origin: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        var answers: Map<String, Boolean>? = null
        assertTrue(host.launch(listOf(camera)) { answers = it })

        origin.get().finish()
        origin.pause()
        resumedActivity()

        assertEquals(emptyMap(), answers)
    }
}
