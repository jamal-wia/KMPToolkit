package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
 * A dialog answered "Allow", then a special access in the same call or the next one, from a caller that runs
 * where the answer is delivered (`viewModelScope` and `lifecycleScope` use `Dispatchers.Main.immediate`;
 * here `UnconfinedTestDispatcher`). Android delivers the answer to a paused activity and resumes it right
 * after, in the same main-thread message. A refusal waits for that resume before the next permission is
 * asked (`settle`); a grant does not, so the Settings screen is opened inside the answer's delivery and the
 * resume that follows is taken for the user coming back: `request` returns while Settings is only opening
 * ("returns only once the user is back").
 */
@RunWith(AndroidJUnit4::class)
class GrantedThenSpecialAccessTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val screens: MutableList<SystemScreenRequest> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun ShadowActivity.answerAllow(activity: Activity) {
        val request: ShadowActivity.PermissionsRequest = assertNotNull(lastRequestedPermission)
        shadowOf(application).grantPermissions(camera)
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

    private fun setUp(): Pair<ActivityController<ComponentActivity>, PermissionController> {
        application.declareInManifest(camera)
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val controller: PermissionController = createPermissionController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            activityAccess = access,
            host = ActivityResultPermissionRequestHost(access),
            systemScreenLauncher = SystemScreenLauncher { request -> screens += request; true },
        )
        return screen to controller
    }

    @Test
    fun `a batch of an allowed dialog and a special access returns only after the user is back from Settings`() =
        runTest(UnconfinedTestDispatcher()) {
            val (screen, controller) = setUp()
            val call = async { controller.request(listOf(CommonPermission.Camera, AndroidPermission.Overlay)) }
            assertNotNull(shadowOf(screen.get()).lastRequestedPermission, "the dialog is up")
            screen.pause() // the activity is paused behind the dialog
            shadowOf(screen.get()).answerAllow(screen.get()) // delivered to the paused activity
            screen.resume() // the resume that follows the answer, in the same message

            assertEquals(1, screens.size, "the special access screen was opened")
            assertFalse(call.isCompleted, "the call returned although the user never left for Settings and came back (the dialog's own resume ended the wait)")

            screen.pause() // Settings is on top
            screen.resume() // the user is back
            assertTrue(call.isCompleted)
        }

    @Test
    fun `an allowed dialog and then a special access in the next call waits for the user to be back from Settings`() =
        runTest(UnconfinedTestDispatcher()) {
            val (screen, controller) = setUp()
            val call = async {
                controller.request(CommonPermission.Camera)
                controller.request(AndroidPermission.Overlay)
            }
            assertNotNull(shadowOf(screen.get()).lastRequestedPermission, "the dialog is up")
            screen.pause()
            shadowOf(screen.get()).answerAllow(screen.get())
            screen.resume()

            assertEquals(1, screens.size, "the special access screen was opened")
            assertFalse(call.isCompleted, "the second call returned although the user never left for Settings and came back")

            screen.pause()
            screen.resume()
            assertTrue(call.isCompleted)
        }
}
