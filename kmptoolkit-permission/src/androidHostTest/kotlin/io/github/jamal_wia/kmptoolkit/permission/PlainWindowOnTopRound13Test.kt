package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Round 13 (E1, topology), the real controller, host and tracker with two windows of the app resumed, the newer
 * one an activity that is not a `ComponentActivity` (an SDK's screen beside the app's own window). The host
 * refuses a launch from it (its KDoc), and the controller's contract for a launch that showed nothing is that
 * nothing is recorded: a dialog that never appeared must not turn into a refusal the user never made.
 */
@RunWith(AndroidJUnit4::class)
class PlainWindowOnTopRound13Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val windows: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        windows.forEach { it.close() }
    }

    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    @Test
    fun `a request while a plain window is the newest resumed one shows nothing and records nothing and a later request shows its dialog`() {
        shadowOf(application).denyPermissions(camera)
        application.declareInManifest(camera)
        val own: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { windows += it }.setup()
        val beside: ActivityController<Round13PlainActivity> =
            Robolectric.buildActivity(Round13PlainActivity::class.java).also { windows += it }.setup()
        val controller: PermissionController =
            createPermissionController(application, InMemoryKeyValueStorage(), access, ActivityResultPermissionRequestHost(access))

        val before: PermissionStatus = runBlocking { controller.check(CommonPermission.Camera) }
        assertEquals(PermissionStatus.NotDetermined, before, "precondition: declared, denied, never asked")

        // The newest resumed window is not a ComponentActivity: nothing can be shown.
        val refused: PermissionStatus = runBlocking { withTimeout(15_000) { controller.request(CommonPermission.Camera) } }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(own.get()).lastRequestedPermission, "a dialog was shown from a window that is not the newest")
        assertEquals(before, refused, "a request that showed nothing reports the status as it was")
        assertEquals(
            before,
            runBlocking { controller.check(CommonPermission.Camera) },
            "a dialog that never appeared was recorded as a refusal",
        )

        // With no window resumed the platform cannot be asked for a rationale, and a recorded "asked" would read as Denied.
        beside.pause()
        own.pause()
        assertEquals(
            before,
            runBlocking { controller.check(CommonPermission.Camera) },
            "a request that showed nothing left an asked flag behind: with no window resumed the status reads as a refusal",
        )

        // The app's own window is the newest again, and the same request now shows its dialog.
        own.resume()
        shadowOf(application.packageManager).setShouldShowRequestPermissionRationale(camera, true)
        val answered: PermissionStatus = runBlocking {
            val request = async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
            shadowOf(Looper.getMainLooper()).idle()
            val shown = assertNotNull(shadowOf(own.get()).lastRequestedPermission, "no dialog from the app's own window")
            endPlatformRequest(own.get())
            @Suppress("DEPRECATION")
            own.get().onRequestPermissionsResult(
                shown.requestCode,
                shown.requestedPermissions,
                IntArray(shown.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
            )
            endPlatformRequest(own.get())
            shadowOf(Looper.getMainLooper()).idle()
            withTimeout(15_000) { request.await() }
        }
        assertEquals(PermissionStatus.Denied(explainFirst = true), answered, "the answered refusal is read with the platform's rationale")
    }
}
