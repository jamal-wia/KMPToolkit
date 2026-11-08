package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity

/**
 * The real factory, with the platform's own reads (`NotificationManagerCompat`, `PackageManager`, the
 * manifest, a resumed `ComponentActivity`'s rationale), on the API levels the library supports: the
 * module's default image is the newest one, and everything the runtime tests inject is a seam.
 * Written from 05-platform-notes: what each entry reads on each level, that nothing throws, which Settings
 * screens `openSettings` offers, and one full refusal journey through the real dialog host.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 29, 30, 33, 34])
class FactoryApiMatrixTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val screens: MutableList<SystemScreenRequest> = mutableListOf()
    private val sdk: Int = Build.VERSION.SDK_INT

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private val catalog: List<Permission> = CommonPermission.entries + listOf(
        AndroidPermission.NearbyWifiDevices,
        AndroidPermission.LocalNetwork,
        AndroidPermission.BodySensors,
        AndroidPermission.BodySensorsBackground,
    )

    private fun declareEverything(): List<String> {
        val strings: List<String> = catalog
            .mapNotNull { runtimeRule(it, sdk, 35) { Protection.Dangerous } as? RuntimeRule.Dialog }
            .flatMap { it.androidPermissions }
            .distinct()
        application.declareInManifest(*strings.toTypedArray())
        shadowOf(application).denyPermissions(*strings.toTypedArray())
        return strings
    }

    private fun factory(host: PermissionRequestHost = RecordingHost(answers = { false })): PermissionController =
        createPermissionController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            activityAccess = access,
            host = host,
            systemScreenLauncher = SystemScreenLauncher { request -> screens += request; true },
        )

    @Test
    fun `every catalog entry reads what the platform notes say it reads on this level`() = runTest {
        declareEverything()
        val controller: PermissionController = factory()
        val expected: Map<Permission, PermissionStatus> = buildMap {
            for (entry in catalog) put(entry, PermissionStatus.NotDetermined)
            // No runtime grant below these levels, or none at all for this app.
            put(CommonPermission.Notifications, if (sdk < 33) PermissionStatus.Granted else PermissionStatus.NotDetermined)
            if (sdk < 31) {
                put(CommonPermission.BluetoothConnect, PermissionStatus.Granted)
                put(CommonPermission.BluetoothAdvertise, PermissionStatus.Granted)
            } else {
                // Declared without neverForLocation: the scan entry reads Unavailable on 31 and up.
                put(CommonPermission.BluetoothScan, PermissionStatus.Unavailable)
            }
            if (sdk < 29) put(CommonPermission.MotionActivity, PermissionStatus.Granted)
            if (sdk >= 29) put(CommonPermission.PhotoLibraryAdd, PermissionStatus.Granted)
            put(AndroidPermission.LocalNetwork, PermissionStatus.Granted)
        }
        for (entry in catalog) {
            assertEquals(expected.getValue(entry), controller.check(entry), "$entry on API $sdk")
        }
    }

    @Test
    fun `nothing throws for any entry, special access, role or admin, and a request opens no dialog for a status that cannot prompt`() = runTest {
        declareEverything()
        val host = RecordingHost(answers = { false })
        val controller: PermissionController = factory(host)
        val others: List<Permission> = AndroidPermission.specialAccesses + listOf(
            AndroidPermission.Role("android.app.role.HOME"),
            AndroidPermission.DeviceAdmin(ComponentName(application, "com.example.NoSuchAdmin")),
            AndroidPermission.AccessibilityService(ComponentName(application, "com.example.NoSuchService")),
            AndroidPermission.Runtime("com.example.CUSTOM"),
        )
        for (entry in catalog + others) {
            controller.check(entry)
            controller.openSettings(entry)
        }
        for (entry in catalog.filter { it != CommonPermission.BluetoothScan || sdk < 31 }) {
            controller.request(entry)
        }
        assertTrue(host.launchCount <= catalog.size, "API $sdk: more dialogs than entries")
    }

    @Test
    fun `notification settings offer the channel screen only where it exists`() {
        declareEverything()
        val controller: PermissionController = factory()

        assertTrue(controller.openSettings(CommonPermission.Notifications))

        val request: SystemScreenRequest = assertNotNull(screens.singleOrNull())
        val actions: List<String?> = request.candidates.map { it.action }
        val expected: List<String?> = if (sdk >= Build.VERSION_CODES.O) {
            listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        } else {
            listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        }
        assertEquals(expected, actions, "API $sdk")
    }

    @Suppress("DEPRECATION") // PermissionInfo's constructor and protectionLevel are what the platform reads below API 28.
    @Test
    fun `a raw permission reads its protection level through the real package manager on this level`() = runTest {
        val phone: String = Manifest.permission.READ_PHONE_STATE
        val internet: String = Manifest.permission.INTERNET
        application.declareInManifest(phone, internet)
        shadowOf(application).denyPermissions(phone)
        // What a device's own package manager answers for the two (Robolectric does not know the platform's).
        // The flag bits above the base level are what a real device adds (here "instant"), which must not matter.
        fun define(name: String, level: Int) = shadowOf(application.packageManager)
            .addPermissionInfo(android.content.pm.PermissionInfo().apply { this.name = name; protectionLevel = level })
        define(phone, android.content.pm.PermissionInfo.PROTECTION_DANGEROUS)
        define(internet, android.content.pm.PermissionInfo.PROTECTION_NORMAL or android.content.pm.PermissionInfo.PROTECTION_FLAG_INSTANT)
        val host = RecordingHost(answers = { false })
        val controller: PermissionController = factory(host)

        // Dangerous: a runtime permission with a dialog of its own.
        assertEquals(PermissionStatus.NotDetermined, controller.check(AndroidPermission.Runtime(phone)), "API $sdk")
        // Normal: install-time, never a dialog.
        val normal: PermissionStatus = controller.check(AndroidPermission.Runtime(internet))
        assertTrue(normal == PermissionStatus.Granted || normal == PermissionStatus.Unavailable, "API $sdk: INTERNET reads $normal")
        controller.request(AndroidPermission.Runtime(internet))
        assertEquals(emptyList(), host.groups, "API $sdk: a dialog for an install-time permission")
        // Unknown to the device.
        assertEquals(PermissionStatus.Unavailable, controller.check(AndroidPermission.Runtime("com.example.NO_SUCH_PERMISSION")), "API $sdk")
        // The dangerous one is asked, once.
        controller.request(AndroidPermission.Runtime(phone))
        assertEquals(listOf(listOf(phone)), host.groups, "API $sdk")
    }

    private fun ShadowActivity.answer(activity: Activity, granted: Boolean) {
        val request: ShadowActivity.PermissionsRequest = assertNotNull(lastRequestedPermission)
        val flag = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").also { it.isAccessible = true }
        flag.setBoolean(activity, false)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) {
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            },
        )
        flag.setBoolean(activity, false)
    }

    @Test
    fun `a refusal journey through the real dialog host reads as the platform notes say on this level`() = runTest(UnconfinedTestDispatcher()) {
        val camera: String = Manifest.permission.CAMERA
        application.declareInManifest(camera)
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val activity: ComponentActivity = screen.get()
        val controller: PermissionController = createPermissionController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            activityAccess = access,
            host = ActivityResultPermissionRequestHost(access),
        )
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))

        // First refusal: Android now wants the rationale shown.
        val first = async { controller.request(CommonPermission.Camera) }
        shadowOf(application.packageManager).setShouldShowRequestPermissionRationale(camera, true)
        shadowOf(activity).answer(activity, granted = false)
        assertEquals(PermissionStatus.Denied(explainFirst = true), first.await(), "API $sdk")

        // Second refusal: the dialog will not show again.
        val second = async { controller.request(CommonPermission.Camera) }
        shadowOf(application.packageManager).setShouldShowRequestPermissionRationale(camera, false)
        shadowOf(activity).answer(activity, granted = false)
        assertEquals(PermissionStatus.PermanentlyDenied, second.await(), "API $sdk")
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Camera))

        // Granted in system settings: the refusals are forgotten.
        shadowOf(application).grantPermissions(camera)
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.Camera))
        shadowOf(application).denyPermissions(camera)
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera), "revoked later reads as never asked, API $sdk")
    }
}
