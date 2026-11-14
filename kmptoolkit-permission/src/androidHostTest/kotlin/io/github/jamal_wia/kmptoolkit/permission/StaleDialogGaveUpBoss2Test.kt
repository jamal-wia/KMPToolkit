package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Boss round 2 of E1: the give-up bookkeeping of the stale-dialog wait, as documented in 03-guide and
 * 05-platform-notes ("a request arriving after the wait has timed out ... fails at once"):
 * - after one request gave up, the next one fails WITHOUT waiting again (not just without a launch call);
 * - the give-up belongs to one stale dialog: a NEW cancelled caller's dialog is waited for again, and the
 *   request queued behind it gets its own dialog once it is answered.
 *
 * The test thread is the main thread under Robolectric, so launches run inline; every wait is bounded in
 * real time, and no assertion reads elapsed time (a request that does not wait completes before the
 * UNDISPATCHED start returns).
 */
@RunWith(AndroidJUnit4::class)
class StaleDialogGaveUpBoss2Test {

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

        fun answerLast(granted: Boolean) {
            val callback = assertNotNull(slot, "no dialog is up")
            slot = null
            callback(launches.last().associateWith { granted })
        }
    }

    private fun declare() {
        listOf(camera, microphone).forEach { shadowOf(application).denyPermissions(it) }
        application.declareInManifest(camera, microphone)
    }

    @Test
    fun `after a request gave up the next one fails without waiting the bound again`(): Unit = runBlocking {
        declare()
        val host = OneSlotHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host, staleDialogTimeout = 150.milliseconds)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
        withTimeout(5_000) { first.cancelAndJoin() }
        assertEquals(PermissionStatus.NotDetermined, withTimeout(5_000) { controller.request(CommonPermission.Microphone) })

        val third: Deferred<PermissionStatus> =
            async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Microphone) }
        try {
            assertTrue(third.isCompleted, "the request after the give-up is waiting on the stale dialog again")
            assertEquals(PermissionStatus.NotDetermined, third.await())
            assertEquals(1, host.launches.size)
        } finally {
            withTimeout(5_000) { third.cancelAndJoin() }
        }
    }

    @Test
    fun `a new cancelled dialog after a give-up is waited for again and the queued caller gets its own`(): Unit = runBlocking {
        declare()
        val host = OneSlotHost()
        // Long enough that the queued caller below never times out between two lines of this thread.
        val controller = testController(application, InMemoryKeyValueStorage(), host, staleDialogTimeout = 2.seconds)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
        withTimeout(5_000) { first.cancelAndJoin() }
        assertEquals(PermissionStatus.NotDetermined, withTimeout(10_000) { controller.request(CommonPermission.Microphone) })
        host.answerLast(granted = false) // the camera dialog of the cancelled caller is finally answered

        // A second cancelled caller, with its dialog up: a new stale dialog.
        val again = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
        assertEquals(2, host.launches.size, "the request after the answered stale dialog got no dialog")
        withTimeout(5_000) { again.cancelAndJoin() }

        val queued: Deferred<PermissionStatus> =
            async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Microphone) }
        try {
            assertFalse(queued.isCompleted, "the give-up of the first stale dialog was carried over to the second")
            assertEquals(2, host.launches.size, "launched into the second stale dialog")
            host.answerLast(granted = false)
            withTimeout(5_000) { while (host.launches.size < 3) yield() }
            assertEquals(listOf(microphone), host.launches[2])
            host.answerLast(granted = true)
            shadowOf(application).grantPermissions(microphone)
            assertEquals(PermissionStatus.Granted, withTimeout(5_000) { queued.await() })
        } finally {
            withTimeout(5_000) { queued.cancelAndJoin() }
        }
    }
}
