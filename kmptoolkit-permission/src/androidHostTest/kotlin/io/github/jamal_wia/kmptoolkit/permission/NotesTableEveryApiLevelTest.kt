package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The dialog table of 05-platform-notes on every API level from 24 to 35, written out here from the notes (not
 * taken from the rules): which strings one dialog asks for, which entries show none, what a grant, a refusal with
 * and without a rationale, an approximate-only or selected-only answer read as. The controller takes its level as
 * a parameter, so the whole range runs on the module's one default image (a Robolectric image per level ran the
 * suite JVM out of memory).
 */
@RunWith(AndroidJUnit4::class)
class NotesTableEveryApiLevelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val fine: String = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarse: String = Manifest.permission.ACCESS_COARSE_LOCATION

    private class Row(val entry: Permission, val strings: Set<String>?)

    private fun table(sdk: Int): List<Row> = listOf(
        Row(CommonPermission.Notifications, if (sdk >= 33) setOf(Manifest.permission.POST_NOTIFICATIONS) else null),
        Row(CommonPermission.Microphone, setOf(Manifest.permission.RECORD_AUDIO)),
        Row(CommonPermission.Camera, setOf(Manifest.permission.CAMERA)),
        Row(CommonPermission.Location, setOf(fine, coarse)),
        Row(CommonPermission.LocationBackground, if (sdk >= 29) setOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else setOf(fine, coarse)),
        Row(CommonPermission.AudioLibrary, setOf(if (sdk >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)),
        Row(
            CommonPermission.PhotoLibrary,
            when {
                sdk >= 34 -> setOf(
                    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
                sdk == 33 -> setOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                else -> setOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
        ),
        Row(CommonPermission.PhotoLibraryAdd, if (sdk >= 29) null else setOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)),
        Row(CommonPermission.Contacts, setOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)),
        Row(CommonPermission.Calendar, setOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
        Row(CommonPermission.CalendarWriteOnly, setOf(Manifest.permission.WRITE_CALENDAR)),
        Row(CommonPermission.MotionActivity, if (sdk >= 29) setOf(Manifest.permission.ACTIVITY_RECOGNITION) else null),
        Row(CommonPermission.BluetoothConnect, if (sdk >= 31) setOf(Manifest.permission.BLUETOOTH_CONNECT) else null),
        Row(CommonPermission.BluetoothAdvertise, if (sdk >= 31) setOf(Manifest.permission.BLUETOOTH_ADVERTISE) else null),
    )

    /** A host whose answer is [granted]: those strings are granted in the platform too, the rest refused. */
    private inner class Host(var granted: (Set<String>) -> Set<String>) : RecordingHost() {
        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            val yes: Set<String> = granted(androidPermissions.toSet())
            shadowOf(application).grantPermissions(*yes.toTypedArray())
            groups += androidPermissions
            onResult(androidPermissions.associateWith { it in yes })
            return true
        }
    }

    private fun every(sdk: Int): Array<String> =
        (table(sdk).flatMap { it.strings.orEmpty() } + fine + coarse + Manifest.permission.ACCESS_BACKGROUND_LOCATION).distinct().toTypedArray()

    private fun resetPlatform(sdk: Int) {
        shadowOf(application).denyPermissions(*every(sdk))
    }

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `every entry asks the strings the notes list in one dialog or shows none on every level`() {
        for (sdk in 24..35) {
            for (row in table(sdk)) {
                resetPlatform(sdk)
                val tag = "${row.entry} on API $sdk"
                val host = Host { it }
                val controller = testController(application, InMemoryKeyValueStorage(), host, sdkInt = sdk)
                if (row.entry == CommonPermission.LocationBackground && sdk >= 29) shadowOf(application).grantPermissions(fine, coarse)

                val status: PermissionStatus = bounded { controller.request(row.entry) }

                assertEquals(PermissionStatus.Granted, status, tag)
                if (row.strings == null) {
                    assertEquals(0, host.launchCount, "$tag: a dialog was shown")
                } else {
                    assertEquals(listOf(row.strings), host.groups.map { it.toSet() }, tag)
                    assertEquals(row.strings.size, host.groups.single().size, "$tag: a string is asked twice")
                }
            }
        }
    }

    @Test
    fun `a refusal reads Denied with a rationale then PermanentlyDenied without and shows no dialog afterwards on every level`() {
        for (sdk in 24..35) {
            for (row in table(sdk).filter { it.strings != null }) {
                resetPlatform(sdk)
                val tag = "${row.entry} on API $sdk"
                val rationale = booleanArrayOf(false)
                val host = Host { emptySet() }
                val controller = testController(
                    application, InMemoryKeyValueStorage(), host, sdkInt = sdk, shouldShowRationale = { rationale[0] },
                )
                if (row.entry == CommonPermission.LocationBackground && sdk >= 29) shadowOf(application).grantPermissions(fine, coarse)

                rationale[0] = true
                assertEquals(PermissionStatus.Denied(explainFirst = true), bounded { controller.request(row.entry) }, "$tag: first refusal")
                rationale[0] = false
                assertEquals(PermissionStatus.PermanentlyDenied, bounded { controller.request(row.entry) }, "$tag: second refusal")
                val shown: Int = host.launchCount
                assertEquals(PermissionStatus.PermanentlyDenied, bounded { controller.request(row.entry) }, tag)
                assertEquals(shown, host.launchCount, "$tag: a dialog for a permanently denied entry")
            }
        }
    }

    @Test
    fun `a dismissed dialog reads NotDetermined and is offered again on every level`() {
        for (sdk in 24..35) {
            for (row in table(sdk).filter { it.strings != null }) {
                resetPlatform(sdk)
                val tag = "${row.entry} on API $sdk"
                val host = Host { emptySet() }
                val controller = testController(application, InMemoryKeyValueStorage(), host, sdkInt = sdk)
                if (row.entry == CommonPermission.LocationBackground && sdk >= 29) shadowOf(application).grantPermissions(fine, coarse)

                assertEquals(PermissionStatus.NotDetermined, bounded { controller.request(row.entry) }, tag)
                host.granted = { it }
                assertEquals(PermissionStatus.Granted, bounded { controller.request(row.entry) }, "$tag: not offered again")
                assertEquals(2, host.launchCount, tag)
            }
        }
    }

    @Test
    fun `approximate location and selected photos only read Limited and write alone reads CalendarWriteOnly on every level`() {
        for (sdk in 24..35) {
            resetPlatform(sdk)
            val location = testController(application, InMemoryKeyValueStorage(), Host { setOf(coarse) }, sdkInt = sdk)
            assertEquals(PermissionStatus.Limited, bounded { location.request(CommonPermission.Location) }, "Location on API $sdk")

            resetPlatform(sdk)
            val selected: String = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            val photos = testController(
                application, InMemoryKeyValueStorage(), Host { asked -> if (selected in asked) setOf(selected) else emptySet() }, sdkInt = sdk,
            )
            val photoStatus: PermissionStatus = bounded { photos.request(CommonPermission.PhotoLibrary) }
            if (sdk >= 34) {
                assertEquals(PermissionStatus.Limited, photoStatus, "PhotoLibrary on API $sdk")
            } else {
                assertFalse(photoStatus.isUsable, "PhotoLibrary on API $sdk: $photoStatus")
            }

            resetPlatform(sdk)
            val calendar = testController(
                application, InMemoryKeyValueStorage(), Host { setOf(Manifest.permission.WRITE_CALENDAR) }, sdkInt = sdk,
            )
            assertEquals(PermissionStatus.Granted, bounded { calendar.request(CommonPermission.CalendarWriteOnly) }, "CalendarWriteOnly on API $sdk")
            assertFalse(bounded { calendar.check(CommonPermission.Calendar) }.isUsable, "Calendar on API $sdk with only write")
            assertTrue(true)
        }
    }
}
