package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * After a refusal the controller waits for an activity to be resumed again so it can read the rationale
 * ("Suspends, briefly and at most once per call, until an activity can be asked"). A backgrounded app is
 * the case where that wait runs to its one-second bound; a batch must not pay it once per permission.
 */
@RunWith(AndroidJUnit4::class)
class BatchResumeWaitTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a batch of five refused permissions waits for an activity at most once`() = runTest {
        val strings: List<String> = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        shadowOf(application).denyPermissions(*strings.toTypedArray())
        var waits = 0
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = RecordingHost(answers = { false }),
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            shouldShowRationale = { null }, // the app went to the background: no activity to ask
            awaitActivity = { waits++ },
        )

        controller.request(
            listOf(
                CommonPermission.Camera,
                CommonPermission.Microphone,
                CommonPermission.Contacts,
                CommonPermission.Calendar,
                CommonPermission.MotionActivity,
            ),
        )

        assertTrue(waits <= 1, "the batch waited for an activity $waits times; up to one second each in a backgrounded app")
    }

    @Test
    fun `a single refused request waits for an activity once`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        var waits = 0
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = RecordingHost(answers = { false }),
            shouldShowRationale = { null },
            awaitActivity = { waits++ },
        )

        controller.request(CommonPermission.Camera)

        assertEquals(1, waits)
    }
}
