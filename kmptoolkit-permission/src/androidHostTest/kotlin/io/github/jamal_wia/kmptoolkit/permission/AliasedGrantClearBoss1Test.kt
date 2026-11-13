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
 * Boss round 1 of E1. 05-platform-notes makes two promises about the refusal flags: entries that ask
 * Android for the same string "share what was learned" (a refusal for good through `PhotoLibrary` reads as
 * one through `AudioLibrary` below API 33), and the flags are "cleared the moment the permission is
 * granted", so a later system reset (auto-reset of an unused app) reads as never refused. A refusal is
 * shared by mirroring the write; the clear is not mirrored, so the entry that was not checked while the
 * permission was granted keeps the refusal learned through the other one.
 */
@RunWith(AndroidJUnit4::class)
class AliasedGrantClearBoss1Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private var rationale: Boolean? = false
    private val host = RecordingHost(answers = { false })

    private fun controller(sdkInt: Int): AndroidPermissionController = testController(
        context = application,
        storage = InMemoryKeyValueStorage(),
        host = host,
        sdkInt = sdkInt,
        shouldShowRationale = { rationale },
    )

    private suspend fun refuseForGood(controller: AndroidPermissionController, entry: Permission) {
        controller.request(entry)
        rationale = true
        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.check(entry))
        controller.request(entry)
        rationale = false
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(entry))
    }

    @Test
    fun `a grant seen through photos clears the refusal audio learned from photos below API 33`() = runTest {
        val strings: Array<String> = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        shadowOf(application).denyPermissions(*strings)
        val controller: AndroidPermissionController = controller(Build.VERSION_CODES.R)
        refuseForGood(controller, CommonPermission.PhotoLibrary)
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.AudioLibrary), "precondition: mirrored")

        // The user grants storage in Settings; the app checks photos while it is granted.
        shadowOf(application).grantPermissions(*strings)
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.PhotoLibrary))

        // Android auto-resets the unused app: not granted, no rationale, the dialog shows again.
        shadowOf(application).denyPermissions(*strings)
        rationale = false
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.PhotoLibrary))
        assertEquals(
            PermissionStatus.NotDetermined,
            controller.check(CommonPermission.AudioLibrary),
            "READ_EXTERNAL_STORAGE was granted after the refusal and then reset, yet audio still reads the refusal it learned from photos",
        )
    }

    @Test
    fun `a grant seen through the full calendar clears the refusal write-only learned from it`() = runTest {
        val strings: Array<String> = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        shadowOf(application).denyPermissions(*strings)
        val controller: AndroidPermissionController = controller(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        refuseForGood(controller, CommonPermission.Calendar)
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.CalendarWriteOnly), "precondition: mirrored")

        shadowOf(application).grantPermissions(*strings)
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.Calendar))

        shadowOf(application).denyPermissions(*strings)
        rationale = false
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Calendar))
        assertEquals(
            PermissionStatus.NotDetermined,
            controller.check(CommonPermission.CalendarWriteOnly),
            "WRITE_CALENDAR was granted after the refusal and then reset, yet write-only still reads the refusal it learned from the full calendar",
        )
    }

    @Test
    fun `after the reset a request for audio shows the dialog the platform would show`() = runTest {
        val strings: Array<String> = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        shadowOf(application).denyPermissions(*strings)
        val controller: AndroidPermissionController = controller(Build.VERSION_CODES.R)
        refuseForGood(controller, CommonPermission.PhotoLibrary)
        shadowOf(application).grantPermissions(*strings)
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.PhotoLibrary))
        shadowOf(application).denyPermissions(*strings)
        rationale = false
        val before: Int = host.launchCount
        controller.request(CommonPermission.AudioLibrary)
        assertEquals(before + 1, host.launchCount, "no dialog: the request answered from the stale mirrored refusal")
    }

    @Test
    fun `the common journey - one refusal then a grant through the photos dialog then a reset leaves audio askable`() = runTest {
        val strings: Array<String> = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        shadowOf(application).denyPermissions(*strings)
        val grantingHost = RecordingHost(answers = { false })
        var grantNext = false
        val host = object : PermissionRequestHost by grantingHost {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                if (grantNext) shadowOf(application).grantPermissions(*strings)
                onResult(androidPermissions.associateWith { grantNext })
                return true
            }
        }
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = host,
            sdkInt = Build.VERSION_CODES.R,
            shouldShowRationale = { rationale },
        )
        controller.request(CommonPermission.PhotoLibrary) // refused once
        rationale = true
        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.check(CommonPermission.PhotoLibrary))
        grantNext = true
        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.PhotoLibrary)) // granted on the second ask
        // Months later Android auto-resets the unused app (API 30+): not granted, no rationale.
        shadowOf(application).denyPermissions(*strings)
        rationale = false
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.PhotoLibrary))
        assertEquals(
            PermissionStatus.NotDetermined,
            controller.check(CommonPermission.AudioLibrary),
            "one refusal and a grant through photos, then a reset, and audio reads a refusal for good",
        )
    }

    @Test
    fun `control - audio checked while granted heals and reads askable after the reset`() = runTest {
        val strings: Array<String> = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        shadowOf(application).denyPermissions(*strings)
        val controller: AndroidPermissionController = controller(Build.VERSION_CODES.R)
        refuseForGood(controller, CommonPermission.PhotoLibrary)
        shadowOf(application).grantPermissions(*strings)
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.PhotoLibrary))
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.AudioLibrary))
        shadowOf(application).denyPermissions(*strings)
        rationale = false
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.AudioLibrary))
    }
}
