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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * [ActivityResultPermissionRequestHost] against a real `ComponentActivity` and its
 * `ActivityResultRegistry`, as Robolectric runs them: the dialog's answer is delivered the way the
 * platform delivers it — `onRequestPermissionsResult`, `onActivityResult` — including to an activity
 * that was recreated while the dialog was up.
 */
@RunWith(AndroidJUnit4::class)
class ActivityResultPermissionRequestHostTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    /** Created before any activity, as the controller's own access must be. */
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun resumedComponentActivity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

    private fun ShadowActivity.answerPermissions(activity: Activity, granted: Boolean) {
        val request: ShadowActivity.PermissionsRequest = assertNotNull(lastRequestedPermission)
        val results = IntArray(request.requestedPermissions.size) {
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        @Suppress("DEPRECATION") // What the platform calls; ComponentActivity routes it to its registry.
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, results)
    }

    @Test
    fun `a permission dialog is shown from the resumed activity and its answer delivered`() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val activity: ComponentActivity = resumedComponentActivity().get()
        val host = ActivityResultPermissionRequestHost(access)
        var answers: Map<String, Boolean>? = null

        assertTrue(host.launch(listOf(Manifest.permission.CAMERA)) { answers = it })
        assertEquals(listOf(Manifest.permission.CAMERA), shadowOf(activity).lastRequestedPermission?.requestedPermissions?.toList())
        assertNull(answers, "nothing is answered before the user answers")

        shadowOf(activity).answerPermissions(activity, granted = false)

        assertEquals(mapOf(Manifest.permission.CAMERA to false), answers)
    }

    @Test
    fun `the answer reaches the request across an activity recreated while the dialog was up`() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val controller: ActivityController<ComponentActivity> = resumedComponentActivity()
        val host = ActivityResultPermissionRequestHost(access)
        var answers: Map<String, Boolean>? = null
        assertTrue(host.launch(listOf(Manifest.permission.CAMERA)) { answers = it })
        val request: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(controller.get()).lastRequestedPermission)

        controller.recreate()
        val recreated: ComponentActivity = controller.get()
        @Suppress("DEPRECATION")
        recreated.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )

        assertEquals(mapOf(Manifest.permission.CAMERA to true), answers)
    }

    @Test
    fun `a second launch while one is pending shows nothing`() {
        resumedComponentActivity()
        val host = ActivityResultPermissionRequestHost(access)

        assertTrue(host.launch(listOf(Manifest.permission.CAMERA)) {})
        assertFalse(host.launch(listOf(Manifest.permission.RECORD_AUDIO)) {})
        assertFalse(host.launchForResult(Intent("x")) {})
    }

    @Test
    fun `once answered the host takes the next request`() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val activity: ComponentActivity = resumedComponentActivity().get()
        val host = ActivityResultPermissionRequestHost(access)
        assertTrue(host.launch(listOf(Manifest.permission.CAMERA)) {})
        shadowOf(activity).answerPermissions(activity, granted = true)

        assertTrue(host.launch(listOf(Manifest.permission.RECORD_AUDIO)) {})
    }

    @Test
    fun `with no activity resumed nothing is shown`() {
        val host = ActivityResultPermissionRequestHost(access)

        assertFalse(host.launch(listOf(Manifest.permission.CAMERA)) {})
        assertFalse(host.launchForResult(Intent("x")) {})
    }

    @Test
    fun `an activity that is not a ComponentActivity shows nothing`() {
        Robolectric.buildActivity(Activity::class.java).also { activities += it }.setup()
        val host = ActivityResultPermissionRequestHost(access)

        assertFalse(host.launch(listOf(Manifest.permission.CAMERA)) {})
    }

    @Test
    fun `a screen started for a result reports its result code`() {
        val activity: ComponentActivity = resumedComponentActivity().get()
        val host = ActivityResultPermissionRequestHost(access)
        var code: Int? = null

        assertTrue(host.launchForResult(Intent("com.example.ROLE")) { code = it })
        val started: Intent = assertNotNull(shadowOf(activity).nextStartedActivityForResult).intent
        assertEquals("com.example.ROLE", started.action)

        shadowOf(activity).receiveResult(started, Activity.RESULT_OK, null)

        assertEquals(Activity.RESULT_OK, code)
    }

    @Test
    fun `the controller's default host is this one, needing no wiring`() = runTest(UnconfinedTestDispatcher()) {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        application.declareInManifest(Manifest.permission.CAMERA)
        val controller: PermissionController = createPermissionController(application, InMemoryKeyValueStorage(), access)
        val activity: ComponentActivity = resumedComponentActivity().get()

        val status = async { controller.request(CommonPermission.Camera) }
        assertFalse(status.isCompleted)
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        shadowOf(activity).answerPermissions(activity, granted = true)

        assertEquals(PermissionStatus.Granted, status.await())
    }
}
