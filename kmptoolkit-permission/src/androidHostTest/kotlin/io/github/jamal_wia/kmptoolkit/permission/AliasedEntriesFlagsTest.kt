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
 * Two entries that ask Android for the same permission string are one question to the platform: a
 * permission refused for good through one is refused for good through the other. `PermanentlyDenied` is
 * "asking again would not show anything", and the platform decides that per string, not per entry.
 */
@RunWith(AndroidJUnit4::class)
class AliasedEntriesFlagsTest {

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

    /** Two refusals, the way the platform presents them: the dialog, a rationale from then on, the dialog again. */
    private suspend fun refuseForGood(controller: AndroidPermissionController, entry: Permission) {
        controller.request(entry)
        rationale = true
        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.check(entry))
        controller.request(entry)
        rationale = false
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(entry))
    }

    @Test
    fun `audio and photos below API 33 both ask for external storage, and a refusal for good through one is one for the other`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        val controller: AndroidPermissionController = controller(Build.VERSION_CODES.R)

        refuseForGood(controller, CommonPermission.PhotoLibrary)

        assertEquals(
            PermissionStatus.PermanentlyDenied,
            controller.check(CommonPermission.AudioLibrary),
            "the platform will not show READ_EXTERNAL_STORAGE again, yet the audio entry reads as askable",
        )
    }

    @Test
    fun `calendar and write-only calendar both ask for WRITE_CALENDAR, and a refusal for good through one is one for the other`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val controller: AndroidPermissionController = controller(Build.VERSION_CODES.TIRAMISU)

        refuseForGood(controller, CommonPermission.Calendar)

        assertEquals(
            PermissionStatus.PermanentlyDenied,
            controller.check(CommonPermission.CalendarWriteOnly),
            "the platform will not show WRITE_CALENDAR again, yet the write-only entry reads as askable",
        )
    }

    @Test
    fun `a request through the aliased entry after a refusal for good does not read as askable again and again`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        val controller: AndroidPermissionController = controller(Build.VERSION_CODES.R)
        refuseForGood(controller, CommonPermission.PhotoLibrary)

        val first: PermissionStatus = controller.request(CommonPermission.AudioLibrary)
        val second: PermissionStatus = controller.request(CommonPermission.AudioLibrary)

        assertEquals(PermissionStatus.PermanentlyDenied, second, "first=$first: the button keeps doing nothing and never offers settings")
    }

    @Test
    fun `control - a refusal for good of the write-only calendar does not read as one for the full calendar`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val controller: AndroidPermissionController = controller(Build.VERSION_CODES.TIRAMISU)

        refuseForGood(controller, CommonPermission.CalendarWriteOnly)

        assertEquals(
            PermissionStatus.NotDetermined,
            controller.check(CommonPermission.Calendar),
            "the full calendar can still show a dialog for READ_CALENDAR",
        )
    }

    @Test
    fun `control - a partial answer of the full calendar records nothing for the write-only calendar`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val partial = RecordingHost(answers = { it == Manifest.permission.WRITE_CALENDAR })
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = partial,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            shouldShowRationale = { false },
        )

        controller.request(CommonPermission.Calendar)

        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.CalendarWriteOnly))
    }
}
