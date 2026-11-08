package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowRoleManager

/**
 * The controller puts system UI on screen only while an activity of the app is resumed: at once when one
 * is, after waiting up to a second for one otherwise, and after a wait that timed out, later launches of the
 * same call do not wait again. The answer to a dialog or a system screen is delivered to the paused activity
 * just before it resumes, so a caller on `Dispatchers.Main.immediate` (here `UnconfinedTestDispatcher`)
 * that goes on to the next request does so inside that delivery.
 */
@RunWith(AndroidJUnit4::class)
class LaunchWaitsForResumeTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val real: ActivityAccess = createActivityAccess(application)
    private val access: CountingAccess = CountingAccess(real)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA
    private val roles: ShadowRoleManager
        get() = Shadow.extract(application.getSystemService(RoleManager::class.java))

    /** An [ActivityAccess] that counts the resume listeners currently subscribed. */
    private class CountingAccess(private val delegate: ActivityAccess) : ActivityAccess by delegate {
        var active: Int = 0
            private set
        var added: Int = 0
            private set

        override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
            added++
            active++
            val inner: ActivitySubscription = delegate.addOnActivityResumedListener(listener)
            var cancelled = false
            return object : ActivitySubscription {
                override fun cancel() {
                    inner.cancel()
                    if (!cancelled) {
                        cancelled = true
                        active--
                    }
                }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        real.release()
        activities.forEach { it.close() }
    }

    private fun resumedActivity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

    private fun controller(launcher: SystemScreenLauncher = SystemScreenLauncher { false }): PermissionController =
        createPermissionController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            activityAccess = access,
            host = ActivityResultPermissionRequestHost(access),
            systemScreenLauncher = launcher,
        )

    private fun ShadowActivity.answerAllow(activity: Activity) {
        val request: ShadowActivity.PermissionsRequest = assertNotNull(lastRequestedPermission)
        shadowOf(application).grantPermissions(*request.requestedPermissions)
        val flag = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").also { it.isAccessible = true }
        flag.setBoolean(activity, false)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED },
        )
        flag.setBoolean(activity, false)
    }

    @Test
    fun `a device admin answered and then a runtime request from the same coroutine still shows the dialog`() =
        runTest(UnconfinedTestDispatcher()) {
            val component = ComponentName(application.packageName, "com.example.AdminReceiver")
            shadowOf(application.packageManager).addOrUpdateReceiver(
                ActivityInfo().apply {
                    packageName = component.packageName
                    name = component.className
                },
            )
            application.declareInManifest(camera)
            shadowOf(application).denyPermissions(camera)
            val screen: ActivityController<ComponentActivity> = resumedActivity()
            val controller: PermissionController = controller()

            val both = async {
                controller.request(AndroidPermission.DeviceAdmin(component))
                controller.request(CommonPermission.Camera)
            }
            val started: Intent = assertNotNull(shadowOf(screen.get()).peekNextStartedActivityForResult()).intent
            assertEquals(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, started.action)
            screen.pause()
            shadowOf(screen.get()).receiveResult(started, Activity.RESULT_CANCELED, null)
            screen.resume()

            assertEquals(
                listOf(camera),
                shadowOf(screen.get()).lastRequestedPermission?.requestedPermissions?.toList(),
                "the runtime request after the device admin screen never put its dialog up",
            )
            both.cancel()
        }

    @Test
    fun `a request made before the first resume shows its dialog once the activity resumes`() =
        runTest(UnconfinedTestDispatcher()) {
            application.declareInManifest(camera)
            shadowOf(application).denyPermissions(camera)
            val screen: ActivityController<ComponentActivity> =
                Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.create().start()
            val controller: PermissionController = controller()

            val call = async { controller.request(CommonPermission.Camera) }
            assertNull(shadowOf(screen.get()).lastRequestedPermission, "nothing is shown while no activity is resumed")
            assertEquals(1, access.active, "the call waits for the resume")

            screen.resume()

            assertEquals(
                listOf(camera),
                shadowOf(screen.get()).lastRequestedPermission?.requestedPermissions?.toList(),
                "the dialog is shown once the activity is resumed",
            )
            // The one listener now is the dialog host's own, held while its dialog is up; once the dialog is
            // answered nothing of the wait, the host or the call may be left.
            screen.pause()
            shadowOf(screen.get()).answerAllow(screen.get())
            screen.resume()
            assertEquals(PermissionStatus.Granted, call.await())
            assertEquals(0, access.active, "the wait's listener is gone once it ended")
        }

    @Test
    fun `a backgrounded app waits one bound for the whole call and shows nothing`() = runTest(UnconfinedTestDispatcher()) {
        roles.addAvailableRole(RoleManager.ROLE_HOME)
        application.declareInManifest(camera)
        shadowOf(application).denyPermissions(camera)
        val controller: PermissionController = controller()

        val result: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.Camera, AndroidPermission.Overlay, AndroidPermission.Role.Home))

        assertEquals(PermissionStatus.NotDetermined, result.getValue(CommonPermission.Camera), "nothing recorded for the dialog that never showed")
        assertTrue(result.getValue(AndroidPermission.Overlay) is PermissionStatus.Denied)
        assertEquals(PermissionStatus.NotDetermined, result.getValue(AndroidPermission.Role.Home))
        assertTrue(testScheduler.currentTime >= 1_000, "the call did not wait for an activity at all: ${testScheduler.currentTime} ms")
        assertTrue(
            testScheduler.currentTime <= 1_200,
            "a three-step call in a backgrounded app waited more than one bound: ${testScheduler.currentTime} ms",
        )
        assertEquals(0, access.active)
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))
    }

    @Test
    fun `an activity that is already resumed costs no wait and leaves no listener`() = runTest(UnconfinedTestDispatcher()) {
        application.declareInManifest(camera)
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> = resumedActivity()
        val controller: PermissionController = controller()
        val baseline: Int = access.active

        val call = async { controller.request(CommonPermission.Camera) }
        assertNotNull(shadowOf(screen.get()).lastRequestedPermission, "the dialog is up at once")
        screen.pause()
        shadowOf(screen.get()).answerAllow(screen.get())
        screen.resume()

        assertEquals(PermissionStatus.Granted, call.await())
        assertEquals(0, testScheduler.currentTime, "a resumed activity must not cost any waiting")
        assertEquals(baseline, access.active, "a listener was left subscribed")
    }

    @Test
    fun `cancelling the caller during the launch wait shows nothing and leaves no listener`() =
        runTest(UnconfinedTestDispatcher()) {
            application.declareInManifest(camera)
            shadowOf(application).denyPermissions(camera)
            val controller: PermissionController = controller()
            val baseline: Int = access.active

            val call = async { controller.request(CommonPermission.Camera) }
            assertEquals(baseline + 1, access.active, "the call is waiting for an activity")
            call.cancel()
            call.join()
            assertEquals(baseline, access.active, "the cancelled wait left its listener")

            val screen: ActivityController<ComponentActivity> = resumedActivity()
            assertNull(shadowOf(screen.get()).lastRequestedPermission, "a cancelled request put its dialog up")
            assertFalse(call.isActive)
        }

    @Test
    fun `an allowed permission followed by another request shows the second dialog for a caller on the real main dispatcher`() =
        runTest {
            application.declareInManifest(camera, Manifest.permission.RECORD_AUDIO)
            shadowOf(application).denyPermissions(camera, Manifest.permission.RECORD_AUDIO)
            val screen: ActivityController<ComponentActivity> = resumedActivity()
            val controller: PermissionController = controller()

            val both = async(Dispatchers.Main) {
                controller.request(CommonPermission.Camera)
                controller.request(CommonPermission.Microphone)
            }
            try {
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(listOf(camera), shadowOf(screen.get()).lastRequestedPermission?.requestedPermissions?.toList(), "the first dialog is up")
                screen.pause()
                shadowOf(screen.get()).answerAllow(screen.get())
                screen.resume()
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals(
                    listOf(Manifest.permission.RECORD_AUDIO),
                    shadowOf(screen.get()).lastRequestedPermission?.requestedPermissions?.toList(),
                    "the second request never put its dialog up",
                )
            } finally {
                // The test thread is the main thread: a failure must end the Main-dispatched child here, or
                // runTest would wait for it with the looper that has to run it blocked.
                both.cancel()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
}
