package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A batch whose one shared dialog could not be shown: "the controller then reports the status unchanged rather
 * than inventing a refusal", for every entry, and the batch does not go on to ask the same host one entry at a
 * time (a host that cannot show now cannot show a moment later, and each attempt is a request of its own).
 */
@RunWith(AndroidJUnit4::class)
class BatchHostCannotShowTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a batch whose shared dialog cannot be shown asks the host once and records nothing`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val storage = InMemoryKeyValueStorage()
        val host = RecordingHost(canLaunch = false)
        val controller: AndroidPermissionController = testController(context = application, storage = storage, host = host)

        val statuses: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone))

        assertEquals(mapOf<Permission, PermissionStatus>(
            CommonPermission.Camera to PermissionStatus.NotDetermined,
            CommonPermission.Microphone to PermissionStatus.NotDetermined,
        ), statuses)
        assertEquals(1, host.launchCount, "the batch kept asking a host that could not show: ${host.groups}")
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Microphone))
    }

    @Test
    fun `a batch whose host throws while launching asks it once and records nothing`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val host = RecordingHost(throwOnLaunch = true)
        val controller: AndroidPermissionController = testController(context = application, storage = InMemoryKeyValueStorage(), host = host)

        val statuses: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone))

        assertEquals(setOf(PermissionStatus.NotDetermined), statuses.values.toSet())
        assertEquals(1, host.launchCount, "the batch kept asking a host that threw: ${host.groups}")
    }
}
