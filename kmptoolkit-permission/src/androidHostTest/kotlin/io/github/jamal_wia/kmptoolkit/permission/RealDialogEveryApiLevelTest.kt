package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity

/**
 * 05-platform-notes, entry by entry, on API 25, 28, 31 and 35, levels other test classes already load (a sweep of 24..32 passed in isolation, 27 and 32 included, but the full-suite JVM ran out of memory once this class added images no other class uses, so it is kept lean) through the real factory, the real
 * dialog host and a real `ComponentActivity` (the earlier matrices cover six levels and mostly read
 * statuses). The expected strings are written out here from the notes, not taken from the rules: which
 * strings one dialog asks for, which entries show no dialog, what a grant, a refusal with and without a
 * rationale, a dialog that returned nothing and a partial answer read as.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [25, 28, 31, 35])
class RealDialogEveryApiLevelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val sdk: Int = Build.VERSION.SDK_INT
    private val access: ActivityAccess = createActivityAccess(application)
    private val screens: MutableList<ActivityController<ComponentActivity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        access.release()
        screens.forEach { it.close() }
    }

    private class Row(val entry: Permission, val strings: Set<String>?)

    /** The notes' table for this level; `null` strings: this level shows no dialog for the entry. */
    private fun table(): List<Row> {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        return listOf(
            Row(CommonPermission.Notifications, if (sdk >= 33) setOf(Manifest.permission.POST_NOTIFICATIONS) else null),
            Row(CommonPermission.Microphone, setOf(Manifest.permission.RECORD_AUDIO)),
            Row(CommonPermission.Camera, setOf(Manifest.permission.CAMERA)),
            Row(CommonPermission.Location, setOf(fine, coarse)),
            Row(
                CommonPermission.LocationBackground,
                if (sdk >= 29) setOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else setOf(fine, coarse),
            ),
            Row(
                CommonPermission.AudioLibrary,
                setOf(if (sdk >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE),
            ),
            Row(
                CommonPermission.PhotoLibrary,
                when {
                    sdk >= 34 -> setOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
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
    }

    private val locationStrings: Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun declareAllAndDeny() {
        val every: Set<String> = table().flatMap { it.strings.orEmpty() }.toSet() + Manifest.permission.ACCESS_BACKGROUND_LOCATION +
            locationStrings
        application.declareInManifest(*every.toTypedArray())
        shadowOf(application).denyPermissions(*every.toTypedArray())
    }

    private fun screen(): ComponentActivity {
        val controller: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { screens += it }.setup()
        return controller.get()
    }

    private fun controller(): PermissionController = createPermissionController(
        context = application,
        storage = InMemoryKeyValueStorage(),
        activityAccess = access,
        host = ActivityResultPermissionRequestHost(access),
    )

    private fun endPlatformRequest(activity: Activity) {
        val flag = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        flag.isAccessible = true
        flag.setBoolean(activity, false)
    }

    /** `null` granted: the platform answered with no entries at all (a dialog that returned nothing). */
    private fun answer(activity: ComponentActivity, granted: Set<String>?, rationale: Boolean = false) {
        val up: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(activity).lastRequestedPermission)
        val strings: Array<String> = up.requestedPermissions
        for (string in strings) shadowOf(application.packageManager).setShouldShowRequestPermissionRationale(string, rationale)
        if (granted != null) shadowOf(application).grantPermissions(*strings.filter { it in granted }.toTypedArray())
        endPlatformRequest(activity)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            up.requestCode,
            if (granted == null) emptyArray() else strings,
            if (granted == null) IntArray(0) else IntArray(strings.size) {
                if (strings[it] in granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            },
        )
        endPlatformRequest(activity)
    }

    private fun grantForeground() {
        shadowOf(application).grantPermissions(*locationStrings)
    }

    private fun resetEntry(row: Row) {
        shadowOf(application).denyPermissions(*row.strings.orEmpty().toTypedArray(), *locationStrings)
    }

    private fun dialogRows(): List<Row> = table().filter { it.strings != null }

    private fun TestScope.ask(controller: PermissionController, row: Row): Deferred<PermissionStatus> =
        async { controller.request(row.entry) }

    @Test
    fun `every entry asks the strings the notes list in one dialog or shows none on this level`() = runTest(UnconfinedTestDispatcher()) {
        declareAllAndDeny()
        val activity: ComponentActivity = screen()
        for (row in table()) {
            val controller: PermissionController = controller()
            val tag = "${row.entry} on API $sdk"
            if (row.entry == CommonPermission.LocationBackground && sdk >= 29) grantForeground()
            if (row.strings == null) {
                val before: ShadowActivity.PermissionsRequest? = shadowOf(activity).lastRequestedPermission
                val call: Deferred<PermissionStatus> = ask(controller, row)
                assertTrue(call.isCompleted, "$tag: a request waited for a dialog that does not exist")
                assertEquals(PermissionStatus.Granted, call.await(), tag)
                assertTrue(shadowOf(activity).lastRequestedPermission === before, "$tag: a dialog was shown")
                continue
            }
            val call: Deferred<PermissionStatus> = ask(controller, row)
            assertFalse(call.isCompleted, "$tag: no dialog")
            val up: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(activity).lastRequestedPermission, tag)
            assertEquals(row.strings, up.requestedPermissions.toSet(), tag)
            assertEquals(row.strings.size, up.requestedPermissions.size, "$tag: a string is asked twice")
            answer(activity, granted = row.strings)
            assertEquals(PermissionStatus.Granted, call.await(), tag)
            assertEquals(PermissionStatus.Granted, controller.check(row.entry), "$tag: check after the grant")
            resetEntry(row)
        }
    }

    @Test
    fun `a refusal reads Denied with a rationale and PermanentlyDenied without and shows no dialog afterwards`() =
        runTest(UnconfinedTestDispatcher()) {
            declareAllAndDeny()
            val activity: ComponentActivity = screen()
            for (row in dialogRows()) {
                val tag = "${row.entry} on API $sdk"
                val controller: PermissionController = controller()
                if (row.entry == CommonPermission.LocationBackground && sdk >= 29) grantForeground()

                val first: Deferred<PermissionStatus> = ask(controller, row)
                assertFalse(first.isCompleted, "$tag: no first dialog")
                answer(activity, granted = emptySet(), rationale = true)
                assertEquals(PermissionStatus.Denied(explainFirst = true), first.await(), "$tag: first refusal")

                val second: Deferred<PermissionStatus> = ask(controller, row)
                assertFalse(second.isCompleted, "$tag: the Denied(explainFirst) entry was not offered again")
                answer(activity, granted = emptySet(), rationale = false)
                assertEquals(PermissionStatus.PermanentlyDenied, second.await(), "$tag: second refusal")

                val third: Deferred<PermissionStatus> = ask(controller, row)
                assertTrue(third.isCompleted, "$tag: a dialog for a permanently denied entry")
                assertEquals(PermissionStatus.PermanentlyDenied, third.await(), tag)
                resetEntry(row)
            }
        }

    @Test
    fun `a dialog that returned nothing or was dismissed leaves no trace and is offered again`() = runTest(UnconfinedTestDispatcher()) {
        declareAllAndDeny()
        val activity: ComponentActivity = screen()
        for (row in dialogRows()) {
            val tag = "${row.entry} on API $sdk"
            val controller: PermissionController = controller()
            if (row.entry == CommonPermission.LocationBackground && sdk >= 29) grantForeground()

            // No answer entries at all (an OEM that swallows the dialog, or an interrupted request).
            val nothing: Deferred<PermissionStatus> = ask(controller, row)
            assertFalse(nothing.isCompleted, "$tag: no dialog")
            answer(activity, granted = null)
            val afterNothing: PermissionStatus = nothing.await()
            assertEquals(controller.check(row.entry), afterNothing, "$tag: status changed by a dialog that returned nothing")
            assertTrue(afterNothing is PermissionStatus.NotDetermined || afterNothing == PermissionStatus.Granted, "$tag: $afterNothing")

            // Dismissed: every string refused, no rationale: Android cannot tell it from "never asked", and nor can the
            // controller, so it reads NotDetermined and the dialog is offered once more.
            val dismissedCall: Deferred<PermissionStatus> = ask(controller, row)
            assertFalse(dismissedCall.isCompleted, "$tag: not offered again after nothing")
            answer(activity, granted = emptySet(), rationale = false)
            assertEquals(PermissionStatus.NotDetermined, dismissedCall.await(), "$tag: a dismissal changed the status")
            val again: Deferred<PermissionStatus> = ask(controller, row)
            assertFalse(again.isCompleted, "$tag: not offered again after a dismissal")
            answer(activity, granted = row.strings)
            assertEquals(PermissionStatus.Granted, again.await(), tag)
            resetEntry(row)
        }
    }

    @Test
    fun `location approximate only is Limited and a request from Limited shows the dialog again`() = runTest(UnconfinedTestDispatcher()) {
        declareAllAndDeny()
        val activity: ComponentActivity = screen()
        val controller: PermissionController = controller()
        val coarse: String = Manifest.permission.ACCESS_COARSE_LOCATION

        val first: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Location) }
        answer(activity, granted = setOf(coarse))
        assertEquals(PermissionStatus.Limited, first.await(), "API $sdk")
        assertEquals(PermissionStatus.Limited, controller.check(CommonPermission.Location))

        val upgrade: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Location) }
        assertFalse(upgrade.isCompleted, "API $sdk: no second dialog from Limited")
        answer(activity, granted = setOf(coarse, Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(PermissionStatus.Granted, upgrade.await(), "API $sdk")
    }

    @Test
    fun `photos selected only is Limited from API 34 and a partial answer below is not a grant`() = runTest(UnconfinedTestDispatcher()) {
        declareAllAndDeny()
        val activity: ComponentActivity = screen()
        val controller: PermissionController = controller()
        val selected: String = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        val images: String = Manifest.permission.READ_MEDIA_IMAGES

        val call: Deferred<PermissionStatus> = async { controller.request(CommonPermission.PhotoLibrary) }
        answer(activity, granted = if (sdk >= 34) setOf(selected) else setOf(images))
        val status: PermissionStatus = call.await()
        when {
            sdk >= 34 -> {
                assertEquals(PermissionStatus.Limited, status, "API $sdk")
                val again: Deferred<PermissionStatus> = async { controller.request(CommonPermission.PhotoLibrary) }
                assertFalse(again.isCompleted, "API $sdk: no dialog to change the selection")
                again.cancel()
            }
            sdk == 33 -> assertFalse(status.isUsable, "API $sdk: images without videos is not a grant, was $status")
            else -> assertFalse(status.isUsable, "API $sdk: the storage permission was not granted, was $status")
        }
    }

    @Test
    fun `write alone granted reads CalendarWriteOnly Granted and Calendar not`() = runTest(UnconfinedTestDispatcher()) {
        declareAllAndDeny()
        val activity: ComponentActivity = screen()
        val controller: PermissionController = controller()
        val write: String = Manifest.permission.WRITE_CALENDAR

        val call: Deferred<PermissionStatus> = async { controller.request(CommonPermission.CalendarWriteOnly) }
        answer(activity, granted = setOf(write))

        assertEquals(PermissionStatus.Granted, call.await(), "API $sdk")
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.CalendarWriteOnly))
        assertFalse(controller.check(CommonPermission.Calendar).isUsable, "API $sdk: Calendar read Granted with only write")
    }

    @Test
    fun `an entry whose second string is not declared asks for the first alone and is granted by it`() = runTest(UnconfinedTestDispatcher()) {
        val read: String = Manifest.permission.READ_CONTACTS
        application.declareInManifest(read)
        shadowOf(application).denyPermissions(read, Manifest.permission.WRITE_CONTACTS)
        val activity: ComponentActivity = screen()
        val controller: PermissionController = controller()

        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Contacts))
        val call: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Contacts) }
        assertFalse(call.isCompleted)
        assertEquals(setOf(read), shadowOf(activity).lastRequestedPermission!!.requestedPermissions.toSet(), "API $sdk")
        answer(activity, granted = setOf(read))

        assertEquals(PermissionStatus.Granted, call.await(), "API $sdk")
    }

    @Test
    fun `a missing declaration reads Unavailable and shows no dialog on every level`() = runTest(UnconfinedTestDispatcher()) {
        application.declareInManifest()
        val activity: ComponentActivity = screen()
        val controller: PermissionController = controller()
        val before: ShadowActivity.PermissionsRequest? = shadowOf(activity).lastRequestedPermission
        for (row in dialogRows()) {
            if (row.entry == CommonPermission.LocationBackground) continue
            val tag = "${row.entry} on API $sdk"
            assertEquals(PermissionStatus.Unavailable, controller.check(row.entry), tag)
            val call: Deferred<PermissionStatus> = ask(controller, row)
            assertTrue(call.isCompleted, "$tag: waited for a dialog that cannot show")
            assertEquals(PermissionStatus.Unavailable, call.await(), tag)
        }
        assertTrue(shadowOf(activity).lastRequestedPermission === before, "API $sdk: a dialog for an undeclared permission")
    }

    @Test
    fun `an unknown raw name and a blank-looking id never touch the flags of a catalog entry`() = runTest(UnconfinedTestDispatcher()) {
        declareAllAndDeny()
        screen()
        val controller: PermissionController = controller()
        // The id of Camera is "CAMERA": a raw entry that happens to carry the same id has no protection level
        // on the device, so it reads Unavailable and writes nothing under that id.
        val lookalike = AndroidPermission.Runtime("CAMERA")
        assertEquals(PermissionStatus.Unavailable, controller.check(lookalike), "API $sdk")
        assertEquals(PermissionStatus.Unavailable, controller.request(lookalike), "API $sdk")
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera), "API $sdk")
    }
}
