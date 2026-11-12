package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.app.Application
import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.getOrNull
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Upgrade from 4.0.0 (and 2.x/3.x): flags an older release wrote must be read by the current code through the PUBLIC
 * factory with its defaults. The ids and the key shape are written out here from the docs, as literals, so a renamed
 * id or a changed prefix fails the test: `<application id>.kmptoolkit.permission.asked.<ID>` and `.rationale.<ID>`,
 * `dialog-shown` or the pre-1.5.0 `true` for asked, `true` for rationale.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpgradeFrom400FlagsRound10Test {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<out Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        controllers.forEach { it.close() }
    }

    /** Entry to its 2.x id as the API reference lists it, for every entry that has a dialog on API 34. */
    private val dialogEntries: Map<Permission, String> = mapOf(
        CommonPermission.Notifications to "NOTIFICATIONS",
        CommonPermission.Microphone to "MICROPHONE",
        CommonPermission.Camera to "CAMERA",
        CommonPermission.Location to "LOCATION",
        CommonPermission.LocationBackground to "LOCATION_BACKGROUND",
        CommonPermission.AudioLibrary to "MEDIA_AUDIO",
        CommonPermission.BluetoothConnect to "BLUETOOTH_CONNECT",
        CommonPermission.BluetoothAdvertise to "BLUETOOTH_ADVERTISE",
        CommonPermission.PhotoLibrary to "PHOTO_LIBRARY",
        CommonPermission.Contacts to "CONTACTS",
        CommonPermission.Calendar to "CALENDAR",
        CommonPermission.CalendarWriteOnly to "CALENDAR_WRITE_ONLY",
        CommonPermission.MotionActivity to "MOTION_ACTIVITY",
        AndroidPermission.NearbyWifiDevices to "NEARBY_WIFI_DEVICES",
        AndroidPermission.BodySensors to "BODY_SENSORS",
        AndroidPermission.BodySensorsBackground to "BODY_SENSORS_BACKGROUND",
    )

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun declareAll() {
        app.declareInManifest(
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BODY_SENSORS, Manifest.permission.BODY_SENSORS_BACKGROUND,
        )
    }

    private fun controllerOver(storage: InMemoryKeyValueStorage): PermissionController {
        val controller: PermissionController = createPermissionController(app, storage)
        controllers += Robolectric.buildActivity(Activity::class.java).setup()
        return controller
    }

    private fun prefix(): String = "${app.packageName}.kmptoolkit.permission"

    @Test
    fun `flags written under the 2x ids are read by the default factory for every dialog entry`() {
        declareAll()
        for ((entry, id) in dialogEntries) {
            val storage = InMemoryKeyValueStorage()
            bounded {
                storage.put("${prefix()}.asked.$id", "dialog-shown")
                storage.put("${prefix()}.rationale.$id", "true")
            }
            val controller: PermissionController = controllerOver(storage)

            assertEquals(PermissionStatus.PermanentlyDenied, bounded { controller.check(entry) }, "$entry with flags under $id")
        }
    }

    @Test
    fun `an asked flag of 1_4 and earlier reads as permanently denied and an asked flag alone is a dismissal`() {
        declareAll()
        for ((entry, id) in dialogEntries) {
            val legacy = InMemoryKeyValueStorage()
            bounded { legacy.put("${prefix()}.asked.$id", "true") }
            assertEquals(PermissionStatus.PermanentlyDenied, bounded { controllerOver(legacy).check(entry) }, "$entry legacy asked flag")

            val dismissed = InMemoryKeyValueStorage()
            bounded { dismissed.put("${prefix()}.asked.$id", "dialog-shown") }
            assertEquals(PermissionStatus.NotDetermined, bounded { controllerOver(dismissed).check(entry) }, "$entry dismissed dialog")

            assertEquals(PermissionStatus.NotDetermined, bounded { controllerOver(InMemoryKeyValueStorage()).check(entry) }, "$entry fresh install")
        }
    }

    @Test
    fun `flags under another application id are not read and a granted permission clears the flags`() {
        declareAll()
        val storage = InMemoryKeyValueStorage()
        bounded {
            storage.put("com.someone.else.kmptoolkit.permission.asked.CAMERA", "true")
            storage.put("${prefix()}.asked.CAMERA", "true")
            storage.put("${prefix()}.rationale.CAMERA", "true")
        }
        val controller: PermissionController = controllerOver(storage)
        assertEquals(PermissionStatus.PermanentlyDenied, bounded { controller.check(CommonPermission.Camera) })

        org.robolectric.Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)

        assertEquals(PermissionStatus.Granted, bounded { controller.check(CommonPermission.Camera) })
        assertEquals(null, bounded { storage.get("${prefix()}.asked.CAMERA") }.getOrNull())
        assertEquals(null, bounded { storage.get("${prefix()}.rationale.CAMERA") }.getOrNull())
        assertEquals("true", bounded { storage.get("com.someone.else.kmptoolkit.permission.asked.CAMERA") }.getOrNull())
    }
}
