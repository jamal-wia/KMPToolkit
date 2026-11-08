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

/** Controls for [ObserveAliasedEntryTest]: the intersection re-read reaches an entry that is only partly the same, and no more. */
@RunWith(AndroidJUnit4::class)
class ObserveAliasedControlsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `observing calendar agrees with check after the write-only entry was granted`() = runTest(UnconfinedTestDispatcher()) {
        shadowOf(application).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val host = object : RecordingHost(answers = { it == Manifest.permission.WRITE_CALENDAR }) {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                shadowOf(application).grantPermissions(Manifest.permission.WRITE_CALENDAR)
                return super.launch(androidPermissions, onResult)
            }
        }
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = host,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
        )
        val seen: MutableList<PermissionStatus> = mutableListOf()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.observe(CommonPermission.Calendar).collect { seen += it }
        }

        controller.request(CommonPermission.CalendarWriteOnly)

        // On Android only the read-and-write grant is `Granted`: a write-only grant leaves `Calendar` where it was.
        assertEquals(controller.check(CommonPermission.Calendar), seen.last(), "observer behind check: $seen")
        collection.cancel()
    }

    @Test
    fun `observing camera emits nothing new after a request for the microphone`() = runTest(UnconfinedTestDispatcher()) {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = RecordingHost(answers = { false }),
            sdkInt = Build.VERSION_CODES.TIRAMISU,
        )
        val seen: MutableList<PermissionStatus> = mutableListOf()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.observe(CommonPermission.Camera).collect { seen += it }
        }

        controller.request(CommonPermission.Microphone)

        assertEquals(listOf<PermissionStatus>(PermissionStatus.NotDetermined), seen)
        collection.cancel()
    }
}
