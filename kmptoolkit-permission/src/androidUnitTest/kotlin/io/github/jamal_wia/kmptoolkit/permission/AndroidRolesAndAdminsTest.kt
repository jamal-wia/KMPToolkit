package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowRoleManager

/**
 * Roles, device admins and accessibility services — the grants that are a system screen rather than
 * the permission dialog — as their KDoc on [AndroidPermission] states.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRolesAndAdminsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val storage: KeyValueStorage = InMemoryKeyValueStorage()
    private val resumes: ResumeListeners = ResumeListeners()
    private val opened: MutableList<SystemScreenRequest> = mutableListOf()

    private fun controller(host: RecordingHost = RecordingHost()): AndroidPermissionController = testController(
        context = application,
        storage = storage,
        host = host,
        sdkInt = Build.VERSION.SDK_INT,
        resumeListeners = resumes,
        launchScreen = { request ->
            opened += request
            true
        },
    )

    /** A host whose result screen answers [resultCode], after [onScreen] — the user acting on it. */
    private fun answering(resultCode: Int, onScreen: () -> Unit = {}): RecordingHost =
        object : RecordingHost(resultCode = resultCode) {
            override fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean {
                onScreen()
                return super.launchForResult(intent, onResult)
            }
        }

    // --- Roles ----------------------------------------------------------------------------------

    private val roles: ShadowRoleManager
        get() = Shadow.extract(application.getSystemService(RoleManager::class.java))

    @Test
    fun `a role the device does not offer is unavailable and shows nothing`() = runTest {
        val host = RecordingHost(resultCode = Activity.RESULT_OK)
        val controller: AndroidPermissionController = controller(host)

        assertEquals(PermissionStatus.Unavailable, controller.check(AndroidPermission.Role.Home))
        assertEquals(PermissionStatus.Unavailable, controller.request(AndroidPermission.Role.Home))
        assertTrue(host.intents.isEmpty())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `roles below API 29 are unavailable`() = runTest {
        assertEquals(PermissionStatus.Unavailable, controller().check(AndroidPermission.Role.Home))
    }

    @Test
    fun `a held role is granted and a request shows nothing`() = runTest {
        roles.addAvailableRole(RoleManager.ROLE_HOME)
        roles.addHeldRole(RoleManager.ROLE_HOME)
        val host = RecordingHost(resultCode = Activity.RESULT_OK)

        assertEquals(PermissionStatus.Granted, controller(host).request(AndroidPermission.Role.Home))
        assertTrue(host.intents.isEmpty())
    }

    @Test
    fun `a role not yet asked for is not determined`() = runTest {
        roles.addAvailableRole(RoleManager.ROLE_HOME)

        assertEquals(PermissionStatus.NotDetermined, controller().check(AndroidPermission.Role.Home))
    }

    @Test
    fun `a role granted in its dialog is granted`() = runTest {
        roles.addAvailableRole(RoleManager.ROLE_HOME)
        val host: RecordingHost = answering(Activity.RESULT_OK) { roles.addHeldRole(RoleManager.ROLE_HOME) }

        assertEquals(PermissionStatus.Granted, controller(host).request(AndroidPermission.Role.Home))
        assertEquals(1, host.intents.size)
    }

    @Test
    fun `a role refused once is denied and refused twice is permanently denied`() = runTest {
        roles.addAvailableRole(RoleManager.ROLE_HOME)
        val host: RecordingHost = answering(Activity.RESULT_CANCELED)
        val controller: AndroidPermissionController = controller(host)

        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.request(AndroidPermission.Role.Home))
        assertEquals(PermissionStatus.PermanentlyDenied, controller.request(AndroidPermission.Role.Home))
        // Android shows the dialog no more: neither does the controller.
        assertEquals(PermissionStatus.PermanentlyDenied, controller.request(AndroidPermission.Role.Home))
        assertEquals(2, host.intents.size)
    }

    @Test
    fun `a role granted from settings after a permanent refusal is granted and its refusals forgotten`() = runTest {
        roles.addAvailableRole(RoleManager.ROLE_HOME)
        val controller: AndroidPermissionController = controller(answering(Activity.RESULT_CANCELED))
        controller.request(AndroidPermission.Role.Home)
        controller.request(AndroidPermission.Role.Home)

        roles.addHeldRole(RoleManager.ROLE_HOME)
        assertEquals(PermissionStatus.Granted, controller.check(AndroidPermission.Role.Home))
        roles.removeHeldRole(RoleManager.ROLE_HOME)
        // Robolectric's removeHeldRole forgets the role's availability as well; a device does not.
        roles.addAvailableRole(RoleManager.ROLE_HOME)

        assertEquals(PermissionStatus.NotDetermined, controller.check(AndroidPermission.Role.Home))
        assertNull(storage.getStringOrNull(askedKey("test", AndroidPermission.Role.Home)))
    }

    @Test
    fun `a role dialog that could not be shown records nothing`() = runTest {
        roles.addAvailableRole(RoleManager.ROLE_HOME)
        // No result code: the host reports it showed nothing.
        val host = RecordingHost(resultCode = null)

        assertEquals(PermissionStatus.NotDetermined, controller(host).request(AndroidPermission.Role.Home))
        assertNull(storage.getStringOrNull(askedKey("test", AndroidPermission.Role.Home)))
    }

    @Test
    fun `a role's settings are the default apps screen`() {
        assertTrue(controller().openSettings(AndroidPermission.Role.Home))

        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, opened.single().candidates.first().action)
        assertEquals(PermissionScreen(AndroidPermission.Role.Home), opened.single().kind)
    }

    @Test
    fun `a role name must not be blank and is the role's id`() {
        assertFailsWith<IllegalArgumentException> { AndroidPermission.Role("") }
        assertEquals(RoleManager.ROLE_HOME, AndroidPermission.Role.Home.name)
        assertEquals(RoleManager.ROLE_HOME, AndroidPermission.Role.Home.id)
    }

    // --- Device admin ---------------------------------------------------------------------------

    private val adminComponent = ComponentName("io.github.jamal_wia.kmptoolkit.permission.test", "com.example.AdminReceiver")
    private val admin = AndroidPermission.DeviceAdmin(adminComponent)

    private fun declareAdminReceiver() {
        shadowOf(application.packageManager).addOrUpdateReceiver(
            ActivityInfo().apply {
                packageName = adminComponent.packageName
                name = adminComponent.className
            },
        )
    }

    private fun activateAdmin() {
        val policyManager = application.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadowOf(policyManager).setActiveAdmin(adminComponent)
    }

    @Test
    fun `a device admin receiver the app does not have is unavailable`() = runTest {
        val host = RecordingHost(resultCode = Activity.RESULT_OK)

        assertEquals(PermissionStatus.Unavailable, controller(host).request(admin))
        assertTrue(host.intents.isEmpty())
    }

    @Test
    fun `an inactive device admin is denied and asks to be explained first`() = runTest {
        declareAdminReceiver()

        assertEquals(PermissionStatus.Denied(explainFirst = true), controller().check(admin))
    }

    @Test
    fun `a device admin request shows the activation screen for its receiver and reads the result`() = runTest {
        declareAdminReceiver()
        val host: RecordingHost = answering(Activity.RESULT_OK) { activateAdmin() }

        assertEquals(PermissionStatus.Granted, controller(host).request(admin))

        val intent: Intent = host.intents.single()
        assertEquals(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, intent.action)
        @Suppress("DEPRECATION")
        assertEquals(adminComponent, intent.getParcelableExtra<ComponentName>(DevicePolicyManager.EXTRA_DEVICE_ADMIN))
    }

    @Test
    fun `a refused device admin is never permanently denied`() = runTest {
        declareAdminReceiver()
        val host: RecordingHost = answering(Activity.RESULT_CANCELED)
        val controller: AndroidPermissionController = controller(host)

        repeat(3) { assertEquals(PermissionStatus.Denied(explainFirst = true), controller.request(admin)) }
        assertEquals(3, host.intents.size)
        assertNull(storage.getStringOrNull(askedKey("test", admin)))
    }

    @Test
    fun `an active device admin is granted`() = runTest {
        declareAdminReceiver()
        activateAdmin()

        assertEquals(PermissionStatus.Granted, controller().check(admin))
    }

    // --- Accessibility service ------------------------------------------------------------------

    private val serviceComponent = ComponentName(
        "io.github.jamal_wia.kmptoolkit.permission.test",
        "com.example.ReaderService",
    )
    private val service = AndroidPermission.AccessibilityService(serviceComponent)

    private fun enableServices(value: String?) {
        Settings.Secure.putString(application.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
    }

    @Test
    fun `an accessibility service the app does not have is unavailable`() = runTest {
        assertEquals(PermissionStatus.Unavailable, controller().check(service))
    }

    @Test
    fun `an accessibility service is granted only while it is in the enabled list`() = runTest {
        shadowOf(application.packageManager).addServiceIfNotPresent(serviceComponent)
        val controller: AndroidPermissionController = controller()

        enableServices(null)
        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.check(service))
        enableServices("com.other/.Service:${serviceComponent.flattenToShortString()}")
        assertEquals(PermissionStatus.Granted, controller.check(service))
        enableServices("com.other/.Service")
        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.check(service))
    }

    @Test
    fun `an accessibility service request opens the accessibility settings and rereads on return`() =
        runTest(UnconfinedTestDispatcher()) {
            shadowOf(application.packageManager).addServiceIfNotPresent(serviceComponent)
            enableServices(null)
            val controller: AndroidPermissionController = controller()

            val result = async { controller.request(service) }
            assertFalse(result.isCompleted)
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, opened.single().candidates.single().action)

            enableServices(serviceComponent.flattenToString())
            resumes.fire()

            assertEquals(PermissionStatus.Granted, result.await())
        }

    @Test
    fun `components with different classes are different entries`() {
        val other = ComponentName(serviceComponent.packageName, "com.example.Other")

        assertTrue(service.id != AndroidPermission.AccessibilityService(other).id)
        assertTrue(admin.id != AndroidPermission.DeviceAdmin(other).id)
    }
}
