package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * `request(Collection)`: "every runtime permission that can still prompt shares one system dialog". Two
 * entries that stand for the same Android permission on this API level (a scan below API 31 is location)
 * are one prompt, not two.
 */
@RunWith(AndroidJUnit4::class)
class BatchEquivalentEntriesTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun controller(host: RecordingHost, sdkInt: Int): AndroidPermissionController =
        testController(context = application, storage = InMemoryKeyValueStorage(), host = host, sdkInt = sdkInt)

    @Test
    fun `location and a bluetooth scan below API 31 are one dialog even when the user refuses`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val host = RecordingHost(answers = { false })

        val result: Map<Permission, PermissionStatus> =
            controller(host, Build.VERSION_CODES.R).request(listOf(CommonPermission.Location, CommonPermission.BluetoothScan))

        assertEquals(1, host.launchCount, "the same Android permission was put to the user twice in one request: ${host.groups}")
        assertEquals(setOf<Permission>(CommonPermission.Location, CommonPermission.BluetoothScan), result.keys)
        assertEquals(result.getValue(CommonPermission.Location), result.getValue(CommonPermission.BluetoothScan))
    }

    @Test
    fun `body sensors and the background entry below API 33 are one dialog even when the user refuses`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.BODY_SENSORS)
        val host = RecordingHost(answers = { false })

        controller(host, Build.VERSION_CODES.R).request(listOf(AndroidPermission.BodySensors, AndroidPermission.BodySensorsBackground))

        assertEquals(1, host.launchCount, "the same Android permission was put to the user twice in one request: ${host.groups}")
    }

    @Test
    fun `location and nearby wifi below API 33 are one dialog even when the user refuses`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val host = RecordingHost(answers = { false })

        controller(host, Build.VERSION_CODES.S).request(listOf(CommonPermission.Location, AndroidPermission.NearbyWifiDevices))

        assertEquals(1, host.launchCount, "the same Android permission was put to the user twice in one request: ${host.groups}")
    }
}
