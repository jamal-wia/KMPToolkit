package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.app.Application
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * A dialog that was interrupted without an answer "leaves no flag, so a host bug cannot turn a permission
 * permanently denied" (platform notes, remembered flags). The real host ends a request whose activity was
 * finished as interrupted; for a role the only channel it has is a result code, so an interrupted role
 * request must not be read as the user's refusal either.
 */
@RunWith(AndroidJUnit4::class)
class InterruptedRoleRequestTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val storage: KeyValueStorage = InMemoryKeyValueStorage()
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val roles: ShadowRoleManager
        get() = Shadow.extract(application.getSystemService(RoleManager::class.java))

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun resumedActivity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

    @Test
    fun `a role request whose activity finishes with the dialog up is not remembered as a refusal`() = runTest(UnconfinedTestDispatcher()) {
        roles.addAvailableRole(RoleManager.ROLE_HOME)
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = storage,
            host = ActivityResultPermissionRequestHost(access),
            sdkInt = Build.VERSION.SDK_INT,
        )

        repeat(2) {
            val screen: ActivityController<ComponentActivity> = resumedActivity()
            val request = async { controller.request(AndroidPermission.Role.Home) }
            screen.get().finish()
            screen.pause().stop().destroy()
            activities.remove(screen)

            assertEquals(PermissionStatus.NotDetermined, request.await(), "the interrupted request changed the status")
        }

        assertNull(storage.getStringOrNull(askedKey("test", AndroidPermission.Role.Home)), "an interrupted dialog was recorded as the user's refusal")
        assertEquals(PermissionStatus.NotDetermined, controller.check(AndroidPermission.Role.Home), "twice interrupted reads as refused for good")
    }

    @Test
    fun `control - the user backing out of the role screen is still recorded as the first refusal`() = runTest(UnconfinedTestDispatcher()) {
        roles.addAvailableRole(RoleManager.ROLE_HOME)
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = storage,
            host = ActivityResultPermissionRequestHost(access),
            sdkInt = Build.VERSION.SDK_INT,
        )
        val screen: ActivityController<ComponentActivity> = resumedActivity()

        val request = async { controller.request(AndroidPermission.Role.Home) }
        val started: Intent = assertNotNull(shadowOf(screen.get()).peekNextStartedActivityForResult()).intent
        shadowOf(screen.get()).receiveResult(started, Activity.RESULT_CANCELED, null)

        assertEquals(PermissionStatus.Denied(explainFirst = true), request.await())
    }
}
