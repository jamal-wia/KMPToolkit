package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The common entries added in 3.0 — photos, saving photos, contacts, calendar, motion activity and
 * Bluetooth advertising — on each side of every API level where their Android mapping changes, as
 * each entry's KDoc on [CommonPermission] states it.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCommonEntriesTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val storage: KeyValueStorage = InMemoryKeyValueStorage()

    private fun controller(
        host: RecordingHost = RecordingHost(answers = { false }),
        sdkInt: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        declared: Set<String>? = null,
    ): AndroidPermissionController = testController(
        context = application,
        storage = storage,
        host = host,
        sdkInt = sdkInt,
        declaredPermissions = { declared },
    )

    /** A host whose dialog grants exactly [granted] — the platform agreeing from then on. */
    private fun granting(vararg granted: String): RecordingHost = object : RecordingHost(answers = { it in granted }) {
        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            shadowOf(application).grantPermissions(*granted)
            return super.launch(androidPermissions, onResult)
        }
    }

    private fun deny(vararg permissions: String) = shadowOf(application).denyPermissions(*permissions)

    // --- Photos ---------------------------------------------------------------------------------

    private val photoStrings34: List<String> = listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )

    @Test
    fun `photos on API 34 ask for images, video and the user-selected subset in one dialog`() = runTest {
        deny(*photoStrings34.toTypedArray())
        val host: RecordingHost = granting(*photoStrings34.toTypedArray())

        assertEquals(PermissionStatus.Granted, controller(host).request(CommonPermission.PhotoLibrary))
        assertEquals(listOf(photoStrings34), host.groups)
    }

    @Test
    fun `photos the user picked items from are limited`() = runTest {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        val host: RecordingHost = granting(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        assertEquals(PermissionStatus.Limited, controller(host).request(CommonPermission.PhotoLibrary))
        assertEquals(PermissionStatus.Limited, controller().check(CommonPermission.PhotoLibrary))
    }

    @Test
    fun `a request from limited photos shows the dialog again so the user can change the selection`() = runTest {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val host = RecordingHost(answers = { it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED })

        assertEquals(PermissionStatus.Limited, controller(host).request(CommonPermission.PhotoLibrary))
        assertEquals(1, host.launchCount)
    }

    @Test
    fun `photos with only images declared ask for images and are granted by them alone`() = runTest {
        deny(*photoStrings34.toTypedArray())
        val host: RecordingHost = granting(Manifest.permission.READ_MEDIA_IMAGES)
        val declared: Set<String> = setOf(Manifest.permission.READ_MEDIA_IMAGES)

        assertEquals(PermissionStatus.Granted, controller(host, declared = declared).request(CommonPermission.PhotoLibrary))
        assertEquals(listOf(listOf(Manifest.permission.READ_MEDIA_IMAGES)), host.groups)
    }

    @Test
    fun `photos refused once are denied and refused again are permanently denied`() = runTest {
        deny(*photoStrings34.toTypedArray())
        // Android asks for a rationale after the first refusal, and stops asking after the second.
        var rationale = false
        val host = object : RecordingHost(answers = { false }) {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                rationale = launchCount == 0
                return super.launch(androidPermissions, onResult)
            }
        }
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = storage,
            host = host,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            shouldShowRationale = { rationale },
        )

        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.request(CommonPermission.PhotoLibrary))
        assertEquals(PermissionStatus.PermanentlyDenied, controller.request(CommonPermission.PhotoLibrary))
        assertEquals(2, host.launchCount)
    }

    @Test
    fun `photos on API 33 have no partial access`() = runTest {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        val host = RecordingHost(answers = { false })

        controller(host, sdkInt = Build.VERSION_CODES.TIRAMISU).request(CommonPermission.PhotoLibrary)

        assertEquals(
            listOf(listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)),
            host.groups,
        )
    }

    @Test
    fun `photos below API 33 are external storage`() = runTest {
        deny(Manifest.permission.READ_EXTERNAL_STORAGE)
        val host: RecordingHost = granting(Manifest.permission.READ_EXTERNAL_STORAGE)

        assertEquals(
            PermissionStatus.Granted,
            controller(host, sdkInt = Build.VERSION_CODES.S_V2).request(CommonPermission.PhotoLibrary),
        )
        assertEquals(listOf(listOf(Manifest.permission.READ_EXTERNAL_STORAGE)), host.groups)
    }

    @Test
    fun `saving photos needs external storage up to API 28`() = runTest {
        deny(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val host: RecordingHost = granting(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertEquals(
            PermissionStatus.Granted,
            controller(host, sdkInt = Build.VERSION_CODES.P).request(CommonPermission.PhotoLibraryAdd),
        )
        assertEquals(listOf(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)), host.groups)
    }

    @Test
    fun `saving photos from API 29 needs no grant and shows nothing`() = runTest {
        deny(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val host = RecordingHost()

        val controller: AndroidPermissionController = controller(host, sdkInt = Build.VERSION_CODES.Q)

        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.PhotoLibraryAdd))
        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.PhotoLibraryAdd))
        assertEquals(0, host.launchCount)
    }

    // --- Contacts -------------------------------------------------------------------------------

    @Test
    fun `contacts ask for reading and writing when both are declared`() = runTest {
        deny(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val host: RecordingHost = granting(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val declared: Set<String> = setOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

        assertEquals(PermissionStatus.Granted, controller(host, declared = declared).request(CommonPermission.Contacts))
        assertEquals(listOf(listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)), host.groups)
    }

    @Test
    fun `contacts ask only for reading when writing is not declared`() = runTest {
        deny(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val host: RecordingHost = granting(Manifest.permission.READ_CONTACTS)
        val declared: Set<String> = setOf(Manifest.permission.READ_CONTACTS)

        assertEquals(PermissionStatus.Granted, controller(host, declared = declared).request(CommonPermission.Contacts))
        assertEquals(listOf(listOf(Manifest.permission.READ_CONTACTS)), host.groups)
    }

    @Test
    fun `contacts with one declared string refused are not granted`() = runTest {
        deny(Manifest.permission.WRITE_CONTACTS)
        shadowOf(application).grantPermissions(Manifest.permission.READ_CONTACTS)

        assertEquals(PermissionStatus.NotDetermined, controller().check(CommonPermission.Contacts))
    }

    @Test
    fun `contacts declared nowhere are unavailable`() = runTest {
        val host = RecordingHost()

        val controller: AndroidPermissionController = controller(host, declared = emptySet())

        assertEquals(PermissionStatus.Unavailable, controller.check(CommonPermission.Contacts))
        assertEquals(PermissionStatus.Unavailable, controller.request(CommonPermission.Contacts))
        assertEquals(0, host.launchCount)
    }

    // --- Calendar -------------------------------------------------------------------------------

    @Test
    fun `the calendar asks for reading and writing and write-only asks for writing`() = runTest {
        deny(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val host = RecordingHost(answers = { false })
        val controller: AndroidPermissionController = controller(host)

        controller.request(CommonPermission.Calendar)
        controller.request(CommonPermission.CalendarWriteOnly)

        assertEquals(
            listOf(
                listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                listOf(Manifest.permission.WRITE_CALENDAR),
            ),
            host.groups,
        )
    }

    @Test
    fun `writing calendar events alone grants write-only and not the calendar`() = runTest {
        deny(Manifest.permission.READ_CALENDAR)
        shadowOf(application).grantPermissions(Manifest.permission.WRITE_CALENDAR)
        val controller: AndroidPermissionController = controller()

        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.CalendarWriteOnly))
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Calendar))
    }

    @Test
    fun `both calendar entries in one batch share one dialog that asks for writing once`() = runTest {
        deny(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val host: RecordingHost = granting(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        val statuses: Map<Permission, PermissionStatus> =
            controller(host).request(listOf(CommonPermission.Calendar, CommonPermission.CalendarWriteOnly))

        assertEquals(
            mapOf<Permission, PermissionStatus>(
                CommonPermission.Calendar to PermissionStatus.Granted,
                CommonPermission.CalendarWriteOnly to PermissionStatus.Granted,
            ),
            statuses,
        )
        assertEquals(listOf(listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)), host.groups)
    }

    // --- Motion activity ------------------------------------------------------------------------

    @Test
    fun `motion activity is a runtime permission from API 29`() = runTest {
        deny(Manifest.permission.ACTIVITY_RECOGNITION)
        val host: RecordingHost = granting(Manifest.permission.ACTIVITY_RECOGNITION)

        assertEquals(
            PermissionStatus.Granted,
            controller(host, sdkInt = Build.VERSION_CODES.Q).request(CommonPermission.MotionActivity),
        )
        assertEquals(listOf(listOf(Manifest.permission.ACTIVITY_RECOGNITION)), host.groups)
    }

    @Test
    fun `motion activity below API 29 needs no grant and shows nothing`() = runTest {
        deny(Manifest.permission.ACTIVITY_RECOGNITION)
        val host = RecordingHost()

        assertEquals(
            PermissionStatus.Granted,
            controller(host, sdkInt = Build.VERSION_CODES.P).request(CommonPermission.MotionActivity),
        )
        assertEquals(0, host.launchCount)
    }

    // --- Bluetooth advertising ------------------------------------------------------------------

    @Test
    fun `bluetooth advertising is a runtime permission from API 31`() = runTest {
        deny(Manifest.permission.BLUETOOTH_ADVERTISE)
        val host: RecordingHost = granting(Manifest.permission.BLUETOOTH_ADVERTISE)

        assertEquals(
            PermissionStatus.Granted,
            controller(host, sdkInt = Build.VERSION_CODES.S).request(CommonPermission.BluetoothAdvertise),
        )
        assertEquals(listOf(listOf(Manifest.permission.BLUETOOTH_ADVERTISE)), host.groups)
    }

    @Test
    fun `bluetooth advertising below API 31 needs no grant and shows nothing`() = runTest {
        deny(Manifest.permission.BLUETOOTH_ADVERTISE)
        val host = RecordingHost()

        assertEquals(
            PermissionStatus.Granted,
            controller(host, sdkInt = Build.VERSION_CODES.R).request(CommonPermission.BluetoothAdvertise),
        )
        assertEquals(0, host.launchCount)
    }
}
