package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.role.RoleManager
import android.content.Intent
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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowRoleManager

/**
 * A role screen answered, then a runtime dialog from the same coroutine, where the answer is delivered
 * (`Dispatchers.Main.immediate`, here `UnconfinedTestDispatcher`): Android delivers the result to a paused
 * activity and resumes it right after, so a dialog launched inside the delivery finds no resumed activity and
 * is "could not be shown". The role path has no wait for the resume at all (only a refused runtime dialog has).
 */
@RunWith(AndroidJUnit4::class)
class RoleThenNextDialogTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA
    private val roles: ShadowRoleManager
        get() = Shadow.extract(application.getSystemService(RoleManager::class.java))

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    @Test
    fun `a role answered and then a runtime request from the same coroutine still shows the dialog`() = runTest(UnconfinedTestDispatcher()) {
        roles.addAvailableRole(RoleManager.ROLE_HOME)
        shadowOf(application).denyPermissions(camera)
        application.declareInManifest(camera)
        val controller: PermissionController = createPermissionController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            activityAccess = access,
            host = ActivityResultPermissionRequestHost(access),
        )
        val screen: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

        val both = async {
            controller.request(AndroidPermission.Role.Home)
            controller.request(CommonPermission.Camera)
        }
        val started: Intent = assertNotNull(shadowOf(screen.get()).peekNextStartedActivityForResult()).intent
        screen.pause() // the activity is paused behind the role screen
        shadowOf(screen.get()).receiveResult(started, Activity.RESULT_CANCELED, null) // delivered to the paused activity
        screen.resume() // the resume that follows the result

        assertEquals(
            listOf(camera),
            shadowOf(screen.get()).lastRequestedPermission?.requestedPermissions?.toList(),
            "the runtime request after the role screen never put its dialog up",
        )
        both.cancel()
    }
}
