package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.pm.PermissionInfo
import android.health.connect.HealthPermissions
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The Android-only runtime permissions — nearby Wi-Fi devices, local network, body sensors — on each
 * side of the API levels where they change, and the [AndroidPermission.Runtime] escape hatch by
 * protection level, as their KDoc on [AndroidPermission] states.
 */
@RunWith(AndroidJUnit4::class)
class AndroidOnlyRuntimeTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val storage: KeyValueStorage = InMemoryKeyValueStorage()

    private fun controller(
        host: RecordingHost = RecordingHost(answers = { false }),
        sdkInt: Int = Build.VERSION_CODES.TIRAMISU,
        targetSdk: Int = sdkInt,
        protectionOf: (String) -> Protection? = { Protection.Dangerous },
    ): AndroidPermissionController = testController(
        context = application,
        storage = storage,
        host = host,
        sdkInt = sdkInt,
        targetSdk = targetSdk,
        protectionOf = protectionOf,
    )

    /** A host whose dialog grants exactly [granted] — the platform agreeing from then on. */
    private fun granting(vararg granted: String): RecordingHost = object : RecordingHost(answers = { it in granted }) {
        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            shadowOf(application).grantPermissions(*granted)
            return super.launch(androidPermissions, onResult)
        }
    }

    private fun deny(vararg permissions: String) = shadowOf(application).denyPermissions(*permissions)

    // --- Nearby Wi-Fi devices -------------------------------------------------------------------

    @Test
    fun `nearby wifi devices is its own runtime permission from API 33`() = runTest {
        deny(Manifest.permission.NEARBY_WIFI_DEVICES)
        val host: RecordingHost = granting(Manifest.permission.NEARBY_WIFI_DEVICES)

        assertEquals(PermissionStatus.Granted, controller(host).request(AndroidPermission.NearbyWifiDevices))
        assertEquals(listOf(listOf(Manifest.permission.NEARBY_WIFI_DEVICES)), host.groups)
    }

    @Test
    fun `nearby wifi devices below API 33 is location in every respect`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val host = RecordingHost(answers = { false })
        val controller: AndroidPermissionController = controller(host, sdkInt = Build.VERSION_CODES.S_V2)

        controller.request(AndroidPermission.NearbyWifiDevices)

        assertEquals(
            listOf(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
            host.groups,
        )
        // The refusal is remembered as one of location, so the two can never disagree.
        assertNotNull(storage.getStringOrNull(askedKey("test", CommonPermission.Location)))
        assertNull(storage.getStringOrNull(askedKey("test", AndroidPermission.NearbyWifiDevices)))
        assertEquals(controller.check(CommonPermission.Location), controller.check(AndroidPermission.NearbyWifiDevices))
    }

    // --- Local network --------------------------------------------------------------------------

    @Test
    fun `local network is a runtime permission on API 37 for an app targeting 37`() = runTest {
        deny(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val host: RecordingHost = granting(Manifest.permission.ACCESS_LOCAL_NETWORK)

        val status: PermissionStatus =
            controller(host, sdkInt = Build.VERSION_CODES.CINNAMON_BUN).request(AndroidPermission.LocalNetwork)

        assertEquals(PermissionStatus.Granted, status)
        assertEquals(listOf(listOf(Manifest.permission.ACCESS_LOCAL_NETWORK)), host.groups)
    }

    @Test
    fun `local network needs no grant for an app targeting below 37 even on API 37`() = runTest {
        deny(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val host = RecordingHost()

        val controller: AndroidPermissionController = controller(
            host,
            sdkInt = Build.VERSION_CODES.CINNAMON_BUN,
            targetSdk = Build.VERSION_CODES.BAKLAVA,
        )

        assertEquals(PermissionStatus.Granted, controller.check(AndroidPermission.LocalNetwork))
        assertEquals(PermissionStatus.Granted, controller.request(AndroidPermission.LocalNetwork))
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `local network needs no grant below API 37 whatever the app targets`() = runTest {
        deny(Manifest.permission.ACCESS_LOCAL_NETWORK)

        val controller: AndroidPermissionController = controller(
            sdkInt = Build.VERSION_CODES.BAKLAVA,
            targetSdk = Build.VERSION_CODES.CINNAMON_BUN,
        )

        assertEquals(PermissionStatus.Granted, controller.check(AndroidPermission.LocalNetwork))
    }

    // --- Body sensors ---------------------------------------------------------------------------

    @Test
    fun `body sensors up to API 35 is BODY_SENSORS`() = runTest {
        deny(Manifest.permission.BODY_SENSORS)
        val host = RecordingHost(answers = { false })

        controller(host, sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM).request(AndroidPermission.BodySensors)

        assertEquals(listOf(listOf(Manifest.permission.BODY_SENSORS)), host.groups)
    }

    @Test
    fun `body sensors from API 36 is the health heart-rate permission`() = runTest {
        deny(HealthPermissions.READ_HEART_RATE)
        val host: RecordingHost = granting(HealthPermissions.READ_HEART_RATE)

        val status: PermissionStatus =
            controller(host, sdkInt = Build.VERSION_CODES.BAKLAVA).request(AndroidPermission.BodySensors)

        assertEquals(PermissionStatus.Granted, status)
        assertEquals(listOf(listOf(HealthPermissions.READ_HEART_RATE)), host.groups)
    }

    @Test
    fun `background body sensors on API 33 to 35 is BODY_SENSORS_BACKGROUND on top of the foreground grant`() = runTest {
        shadowOf(application).grantPermissions(Manifest.permission.BODY_SENSORS)
        deny(Manifest.permission.BODY_SENSORS_BACKGROUND)
        val host: RecordingHost = granting(Manifest.permission.BODY_SENSORS_BACKGROUND)

        val status: PermissionStatus =
            controller(host, sdkInt = Build.VERSION_CODES.TIRAMISU).request(AndroidPermission.BodySensorsBackground)

        assertEquals(PermissionStatus.Granted, status)
        assertEquals(listOf(listOf(Manifest.permission.BODY_SENSORS_BACKGROUND)), host.groups)
    }

    @Test
    fun `background body sensors from API 36 is the health background permission`() = runTest {
        shadowOf(application).grantPermissions(HealthPermissions.READ_HEART_RATE)
        deny(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
        val host = RecordingHost(answers = { false })

        controller(host, sdkInt = Build.VERSION_CODES.BAKLAVA).request(AndroidPermission.BodySensorsBackground)

        assertEquals(listOf(listOf(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)), host.groups)
    }

    @Test
    fun `background body sensors without the foreground grant shows nothing`() = runTest {
        deny(Manifest.permission.BODY_SENSORS, Manifest.permission.BODY_SENSORS_BACKGROUND)
        val host = RecordingHost()

        val status: PermissionStatus =
            controller(host, sdkInt = Build.VERSION_CODES.TIRAMISU).request(AndroidPermission.BodySensorsBackground)

        assertEquals(PermissionStatus.NotDetermined, status)
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `background body sensors below API 33 is the foreground grant`() = runTest {
        deny(Manifest.permission.BODY_SENSORS)
        val host = RecordingHost(answers = { false })
        val controller: AndroidPermissionController = controller(host, sdkInt = Build.VERSION_CODES.S_V2)

        controller.request(AndroidPermission.BodySensorsBackground)

        assertEquals(listOf(listOf(Manifest.permission.BODY_SENSORS)), host.groups)
        assertNotNull(storage.getStringOrNull(askedKey("test", AndroidPermission.BodySensors)))
    }

    @Test
    fun `background body sensors never shares the foreground dialog in a batch`() = runTest {
        deny(Manifest.permission.BODY_SENSORS, Manifest.permission.BODY_SENSORS_BACKGROUND, Manifest.permission.CAMERA)
        val host = RecordingHost(answers = { false })

        controller(host).request(
            listOf(CommonPermission.Camera, AndroidPermission.BodySensors, AndroidPermission.BodySensorsBackground),
        )

        // Camera and the foreground grant share one dialog; background, refused foreground, shows none.
        assertEquals(listOf(listOf(Manifest.permission.CAMERA, Manifest.permission.BODY_SENSORS)), host.groups)
    }

    // --- Runtime(name) --------------------------------------------------------------------------

    private val phoneState: AndroidPermission.Runtime = AndroidPermission.Runtime(Manifest.permission.READ_PHONE_STATE)

    @Test
    fun `a raw dangerous permission gets its own dialog and the full refusal bookkeeping under its name`() = runTest {
        deny(Manifest.permission.READ_PHONE_STATE)
        val host = RecordingHost(answers = { false })
        val controller: AndroidPermissionController = controller(host)

        controller.request(phoneState)

        assertEquals(listOf(listOf(Manifest.permission.READ_PHONE_STATE)), host.groups)
        assertEquals("test.asked.android.permission.READ_PHONE_STATE", askedKey("test", phoneState))
        assertNotNull(storage.getStringOrNull(askedKey("test", phoneState)))
    }

    @Test
    fun `a raw dangerous permission granted in the dialog is granted`() = runTest {
        deny(Manifest.permission.READ_PHONE_STATE)
        val host: RecordingHost = granting(Manifest.permission.READ_PHONE_STATE)

        assertEquals(PermissionStatus.Granted, controller(host).request(phoneState))
    }

    @Test
    fun `a raw non-dangerous permission reports whether the app holds it and never prompts`() = runTest {
        val secureSettings = AndroidPermission.Runtime("android.permission.WRITE_SECURE_SETTINGS")
        deny(secureSettings.name)
        val host = RecordingHost()
        val controller: AndroidPermissionController = controller(host, protectionOf = { Protection.Other })

        assertEquals(PermissionStatus.Unavailable, controller.check(secureSettings))
        assertEquals(PermissionStatus.Unavailable, controller.request(secureSettings))
        // Granted from outside the app, as `adb shell pm grant` would.
        shadowOf(application).grantPermissions(secureSettings.name)
        assertEquals(PermissionStatus.Granted, controller.check(secureSettings))
        assertEquals(0, host.launchCount)
        assertNull(storage.getStringOrNull(askedKey("test", secureSettings)))
    }

    @Test
    fun `a raw permission this device does not know is unavailable and shows nothing`() = runTest {
        val unknown = AndroidPermission.Runtime("com.example.permission.NOT_INSTALLED")
        val host = RecordingHost()
        val controller: AndroidPermissionController = controller(host, protectionOf = { null })

        assertEquals(PermissionStatus.Unavailable, controller.check(unknown))
        assertEquals(PermissionStatus.Unavailable, controller.request(unknown))
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `a raw permission name must not be blank`() {
        assertFailsWith<IllegalArgumentException> { AndroidPermission.Runtime(" ") }
    }

    @Test
    fun `the protection level is read from the package manager`() {
        // Robolectric ships no framework permission table: register the two as the platform defines them.
        listOf(
            Manifest.permission.READ_PHONE_STATE to PermissionInfo.PROTECTION_DANGEROUS,
            Manifest.permission.INTERNET to PermissionInfo.PROTECTION_NORMAL,
        ).forEach { (name, level) ->
            shadowOf(application.packageManager).addPermissionInfo(
                PermissionInfo().apply {
                    this.name = name
                    protectionLevel = level
                },
            )
        }

        assertEquals(Protection.Dangerous, application.protectionOf(Manifest.permission.READ_PHONE_STATE))
        assertEquals(Protection.Other, application.protectionOf(Manifest.permission.INTERNET))
        assertNull(application.protectionOf("com.example.permission.NOT_INSTALLED"))
    }
}
