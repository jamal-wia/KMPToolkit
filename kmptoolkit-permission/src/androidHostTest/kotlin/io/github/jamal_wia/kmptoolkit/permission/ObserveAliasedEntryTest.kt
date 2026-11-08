package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * `observe` re-reads "after every request through this controller that covered [permission]". Two entries
 * that ask Android for the same string are one question to the platform (their refusals are shared), so a
 * request through one covers the other: its observer must see the answer without waiting for a resume.
 */
@RunWith(AndroidJUnit4::class)
class ObserveAliasedEntryTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `observing audio below API 33 sees a grant that came through the photo entry's dialog`() = runTest(UnconfinedTestDispatcher()) {
        shadowOf(application).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        val host = object : RecordingHost(answers = { true }) {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                shadowOf(application).grantPermissions(*androidPermissions.toTypedArray())
                return super.launch(androidPermissions, onResult)
            }
        }
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = host,
            sdkInt = Build.VERSION_CODES.R,
        )
        val seen: MutableList<PermissionStatus> = mutableListOf()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.observe(CommonPermission.AudioLibrary).collect { seen += it }
        }

        controller.request(CommonPermission.PhotoLibrary)

        assertEquals(
            listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted),
            seen,
            "the grant of READ_EXTERNAL_STORAGE is not seen by the observer of the other entry that asks for it",
        )
        collection.cancel()
    }

    @Test
    fun `observing calendar write-only sees a refusal for good that came through the calendar entry`() = runTest(UnconfinedTestDispatcher()) {
        shadowOf(application).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        var rationale: Boolean? = false
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = RecordingHost(answers = { false }),
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            shouldShowRationale = { rationale },
        )
        val seen: MutableList<PermissionStatus> = mutableListOf()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.observe(CommonPermission.CalendarWriteOnly).collect { seen += it }
        }

        controller.request(CommonPermission.Calendar)
        rationale = true
        controller.request(CommonPermission.Calendar)
        rationale = false
        controller.request(CommonPermission.Calendar)

        val direct: PermissionStatus = controller.check(CommonPermission.CalendarWriteOnly)
        assertEquals(direct, seen.last(), "the observer of the aliased entry is behind what check says now: $seen")
        collection.cancel()
    }
}
