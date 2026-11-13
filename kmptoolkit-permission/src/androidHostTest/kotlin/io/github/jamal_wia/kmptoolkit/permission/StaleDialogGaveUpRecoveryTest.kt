package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Once a request has given up on a cancelled caller's stale dialog, the controller keeps that dialog on
 * record and fails the requests after it at once; the moment the host reports the dialog done, the next
 * request launches its own again. The test thread is the main thread under Robolectric, so launches run
 * inline; every wait is bounded in real time.
 */
@RunWith(AndroidJUnit4::class)
class StaleDialogGaveUpRecoveryTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO

    /** One callback slot, no refusal of a second launch; counts every launch call. */
    private class OneSlotHost : PermissionRequestHost {
        val launches: MutableList<List<String>> = mutableListOf()
        var slot: ((Map<String, Boolean>) -> Unit)? = null

        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            launches += androidPermissions
            slot = onResult
            return true
        }

        override fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean = false
    }

    @Test
    fun `after the stale dialog is answered the next request launches its own`(): Unit = runBlocking {
        listOf(camera, microphone).forEach { shadowOf(application).denyPermissions(it) }
        application.declareInManifest(camera, microphone)
        val host = OneSlotHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host, staleDialogTimeout = 150.milliseconds)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
        withTimeout(5_000) { first.cancelAndJoin() }
        assertEquals(PermissionStatus.NotDetermined, withTimeout(5_000) { controller.request(CommonPermission.Microphone) })
        assertEquals(PermissionStatus.NotDetermined, withTimeout(5_000) { controller.request(CommonPermission.Microphone) })
        assertEquals(1, host.launches.size, "the stale dialog is still up: nothing may be launched")

        // The user answers the camera dialog of the cancelled request: the host is free again.
        val answer = assertNotNull(host.slot, "no dialog is up")
        answer(mapOf(camera to false))

        val next = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Microphone) }
        try {
            assertEquals(listOf(listOf(camera), listOf(microphone)), host.launches, "the request after the answer got no dialog of its own")
        } finally {
            withTimeout(5_000) { next.cancelAndJoin() }
        }
    }
}
