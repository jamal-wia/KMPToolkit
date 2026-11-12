package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The "What you must declare" table of 05-platform-notes, every runtime entry on every API level from 24 to 37:
 * "the controller asks only for the ones your manifest declares ... and reads `Unavailable` only when none is
 * declared". Written out here from the notes, not taken from the rules. The level is the controller's own seam
 * (one Robolectric image: a image per level ran the suite out of memory in round 8).
 */
@RunWith(AndroidJUnit4::class)
class ManifestMatrixRound10Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val fine: String = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarse: String = Manifest.permission.ACCESS_COARSE_LOCATION
    private val heartRate: String = "android.permission.health.READ_HEART_RATE"
    private val healthBackground: String = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /** What the notes say one entry asks in its dialog on [sdk]; `null` when it asks nothing there. */
    private fun strings(entry: Permission, sdk: Int, target: Int): List<String>? = when (entry) {
        CommonPermission.Notifications -> if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else null
        CommonPermission.Microphone -> listOf(Manifest.permission.RECORD_AUDIO)
        CommonPermission.Camera -> listOf(Manifest.permission.CAMERA)
        CommonPermission.Location -> listOf(fine, coarse)
        CommonPermission.AudioLibrary ->
            listOf(if (sdk >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        CommonPermission.PhotoLibrary -> when {
            sdk >= 34 -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            sdk == 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        CommonPermission.PhotoLibraryAdd -> if (sdk <= 28) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else null
        CommonPermission.Contacts -> listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        CommonPermission.Calendar -> listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        CommonPermission.CalendarWriteOnly -> listOf(Manifest.permission.WRITE_CALENDAR)
        CommonPermission.MotionActivity -> if (sdk >= 29) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else null
        CommonPermission.BluetoothConnect -> if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else null
        CommonPermission.BluetoothAdvertise -> if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_ADVERTISE) else null
        CommonPermission.BluetoothScan -> if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN) else listOf(fine, coarse)
        AndroidPermission.NearbyWifiDevices ->
            if (sdk >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else listOf(fine, coarse)
        AndroidPermission.LocalNetwork ->
            if (sdk >= 37 && target >= 37) listOf(Manifest.permission.ACCESS_LOCAL_NETWORK) else null
        AndroidPermission.BodySensors -> listOf(if (sdk >= 36) heartRate else Manifest.permission.BODY_SENSORS)
        else -> error("not in the table: $entry")
    }

    /** Entries whose dialog on [sdk] is another entry's: their own undeclared answer is the other's. */
    private val entries: List<Permission> = listOf(
        CommonPermission.Notifications, CommonPermission.Microphone, CommonPermission.Camera, CommonPermission.Location,
        CommonPermission.AudioLibrary, CommonPermission.PhotoLibrary, CommonPermission.PhotoLibraryAdd,
        CommonPermission.Contacts, CommonPermission.Calendar, CommonPermission.CalendarWriteOnly,
        CommonPermission.MotionActivity, CommonPermission.BluetoothConnect, CommonPermission.BluetoothAdvertise,
        CommonPermission.BluetoothScan, AndroidPermission.NearbyWifiDevices, AndroidPermission.LocalNetwork,
        AndroidPermission.BodySensors,
    )

    private class Answering(val app: Application, var grants: (String) -> Boolean) : RecordingHost() {
        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            val yes: List<String> = androidPermissions.filter(grants)
            shadowOf(app).grantPermissions(*yes.toTypedArray())
            groups += androidPermissions
            onResult(androidPermissions.associateWith { it in yes })
            return true
        }
    }

    private val everyString: Array<String> = (
        entries.flatMap { e -> (24..37).flatMap { strings(e, it, 37).orEmpty() } } +
            Manifest.permission.ACCESS_BACKGROUND_LOCATION + Manifest.permission.BODY_SENSORS_BACKGROUND + healthBackground
        ).distinct().toTypedArray()

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun controller(
        sdk: Int,
        target: Int,
        declared: Set<String>?,
        host: PermissionRequestHost,
    ): AndroidPermissionController = testController(
        application, InMemoryKeyValueStorage(), host, sdkInt = sdk, targetSdk = target,
        declaredPermissions = { declared },
    )

    @Test
    fun `an entry whose dialog has no declared string reads Unavailable and shows nothing on every level`() {
        for (sdk in 24..37) for (entry in entries) {
            val asked: List<String> = strings(entry, sdk, sdk) ?: continue
            if (entry == CommonPermission.Notifications && sdk < 33) continue
            shadowOf(application).denyPermissions(*everyString)
            val host = Answering(application) { true }
            val others: Set<String> = everyString.toSet() - asked.toSet()
            val controller = controller(sdk, sdk, others, host)

            assertEquals(PermissionStatus.Unavailable, bounded { controller.check(entry) }, "$entry check on API $sdk")
            assertEquals(PermissionStatus.Unavailable, bounded { controller.request(entry) }, "$entry request on API $sdk")
            assertEquals(0, host.launchCount, "$entry on API $sdk: a dialog for an undeclared permission: ${host.groups}")
        }
    }

    @Test
    fun `an entry declaring any one of its strings asks exactly the declared one on every level`() {
        for (sdk in 24..37) for (entry in entries) {
            val asked: List<String> = strings(entry, sdk, sdk) ?: continue
            if (entry == CommonPermission.Notifications && sdk < 33) continue
            for (only in asked) {
                shadowOf(application).denyPermissions(*everyString)
                val host = Answering(application) { true }
                val controller = controller(sdk, sdk, setOf(only), host)

                val status: PermissionStatus = bounded { controller.request(entry) }

                // Approximate alone and the user-selected string alone are the narrower grants the notes name.
                val expected: PermissionStatus =
                    if (only == coarse || only == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) {
                        PermissionStatus.Limited
                    } else {
                        PermissionStatus.Granted
                    }
                assertEquals(expected, status, "$entry declaring only $only on API $sdk")
                assertEquals(listOf(listOf(only)), host.groups, "$entry declaring only $only on API $sdk")
            }
        }
    }

    @Test
    fun `an entry declaring every string asks each once in one dialog on every level`() {
        for (sdk in 24..37) for (entry in entries) {
            val asked: List<String> = strings(entry, sdk, sdk) ?: continue
            if (entry == CommonPermission.Notifications && sdk < 33) continue
            shadowOf(application).denyPermissions(*everyString)
            val host = Answering(application) { true }
            val controller = controller(sdk, sdk, everyString.toSet(), host)

            bounded { controller.request(entry) }

            assertEquals(1, host.launchCount, "$entry on API $sdk: ${host.groups}")
            assertEquals(asked, host.groups.single(), "$entry on API $sdk")
        }
    }

    @Test
    fun `an entry with no runtime grant on the level reads Granted whatever the manifest says`() {
        for (sdk in 24..37) for (entry in entries) {
            if (strings(entry, sdk, sdk) != null) continue
            if (entry == CommonPermission.Notifications) continue
            shadowOf(application).denyPermissions(*everyString)
            val host = Answering(application) { true }
            val controller = controller(sdk, sdk, emptySet(), host)

            assertEquals(PermissionStatus.Granted, bounded { controller.check(entry) }, "$entry check on API $sdk")
            assertEquals(PermissionStatus.Granted, bounded { controller.request(entry) }, "$entry request on API $sdk")
            assertEquals(0, host.launchCount, "$entry on API $sdk")
        }
    }

    @Test
    fun `LocalNetwork is a dialog only on API 37 for an app targeting 37 and Granted for any lower target`() {
        for (target in listOf(24, 33, 34, 35, 36, 37)) {
            shadowOf(application).denyPermissions(*everyString)
            val host = Answering(application) { false }
            val controller = controller(37, target, setOf(Manifest.permission.ACCESS_LOCAL_NETWORK), host)

            val status: PermissionStatus = bounded { controller.request(AndroidPermission.LocalNetwork) }

            if (target >= 37) {
                assertEquals(PermissionStatus.NotDetermined, status, "target $target")
                assertEquals(1, host.launchCount, "target $target")
            } else {
                assertEquals(PermissionStatus.Granted, status, "target $target")
                assertEquals(0, host.launchCount, "target $target")
            }
        }
    }

    @Test
    fun `Contacts and Calendar are granted only when every declared string is`() {
        for (sdk in listOf(24, 29, 33, 35)) {
            shadowOf(application).denyPermissions(*everyString)
            shadowOf(application).grantPermissions(Manifest.permission.READ_CONTACTS)
            val both = controller(sdk, sdk, setOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), Answering(application) { false })
            val readOnly = controller(sdk, sdk, setOf(Manifest.permission.READ_CONTACTS), Answering(application) { false })

            assertTrue(bounded { both.check(CommonPermission.Contacts) } != PermissionStatus.Granted, "write declared but not granted, API $sdk")
            assertEquals(PermissionStatus.Granted, bounded { readOnly.check(CommonPermission.Contacts) }, "write not declared, API $sdk")

            shadowOf(application).denyPermissions(*everyString)
            shadowOf(application).grantPermissions(Manifest.permission.WRITE_CALENDAR)
            val declaredAll = setOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            val calendar = controller(sdk, sdk, declaredAll, Answering(application) { false })
            assertTrue(bounded { calendar.check(CommonPermission.Calendar) } != PermissionStatus.Granted, "Calendar with write only, API $sdk")
            assertEquals(PermissionStatus.Granted, bounded { calendar.check(CommonPermission.CalendarWriteOnly) }, "CalendarWriteOnly with write only, API $sdk")
        }
    }

    @Test
    fun `the background entries read Unavailable when their own string is not declared`() {
        for (sdk in listOf(29, 30, 33, 35, 36)) {
            shadowOf(application).denyPermissions(*everyString)
            val declared: Set<String> = everyString.toSet() - Manifest.permission.ACCESS_BACKGROUND_LOCATION
            val location = controller(sdk, sdk, declared, Answering(application) { true })
            assertEquals(PermissionStatus.Unavailable, bounded { location.check(CommonPermission.LocationBackground) }, "LocationBackground API $sdk")
        }
        for ((sdk, own) in listOf(33 to Manifest.permission.BODY_SENSORS_BACKGROUND, 35 to Manifest.permission.BODY_SENSORS_BACKGROUND, 36 to healthBackground)) {
            shadowOf(application).denyPermissions(*everyString)
            val declared: Set<String> = everyString.toSet() - own
            val sensors = controller(sdk, sdk, declared, Answering(application) { true })
            assertEquals(PermissionStatus.Unavailable, bounded { sensors.check(AndroidPermission.BodySensorsBackground) }, "BodySensorsBackground API $sdk")
        }
    }
}
