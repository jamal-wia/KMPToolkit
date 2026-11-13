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
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * A caller queued behind a request that was cancelled with its dialog still up: it waits, bounded, for
 * the host to be free, then gets its own dialog; past the bound it fails like a refused launch.
 *
 * The test thread is the main thread under Robolectric, so launches run inline; every wait is bounded
 * in real time.
 */
@RunWith(AndroidJUnit4::class)
class StaleDialogQueueTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO

    /** One dialog at a time, like the real host: a launch while one is up is refused. */
    private class HoldingHost : PermissionRequestHost {
        val launches: MutableList<List<String>> = mutableListOf()
        var refused: Int = 0
        private var pending: ((Map<String, Boolean>) -> Unit)? = null

        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            if (pending != null) {
                refused++
                return false
            }
            launches += androidPermissions
            pending = onResult
            return true
        }

        override fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean = false

        fun answer(granted: Boolean) {
            val callback = assertNotNull(pending, "no dialog is up")
            pending = null
            callback(launches.last().associateWith { granted })
        }
    }

    private fun declare() {
        listOf(camera, microphone).forEach { org.robolectric.Shadows.shadowOf(application).denyPermissions(it) }
        application.declareInManifest(camera, microphone)
    }

    @Test
    fun `the queued caller waits for the stale dialog then gets its own`(): Unit = runBlocking {
        declare()
        val host = HoldingHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
        assertEquals(1, host.launches.size)
        val second: Deferred<PermissionStatus> =
            async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Microphone) }
        first.cancelAndJoin()
        assertFalse(second.isCompleted)
        assertEquals(0, host.refused, "the queued caller launched into the stale dialog")

        host.answer(granted = false) // the user answers the dialog of the cancelled request
        withTimeout(5_000) { while (host.launches.size < 2) kotlinx.coroutines.yield() }
        assertEquals(listOf(microphone), host.launches[1])
        host.answer(granted = true)
        shadowGrant()
        assertEquals(PermissionStatus.Granted, withTimeout(5_000) { second.await() })
    }

    private fun shadowGrant() = org.robolectric.Shadows.shadowOf(application).grantPermissions(microphone)

    @Test
    fun `past the bound the queued caller fails like a refused launch and later callers are not held again`(): Unit = runBlocking {
        declare()
        val host = HoldingHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host, staleDialogTimeout = 150.milliseconds)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
        first.cancelAndJoin()

        val second: PermissionStatus = withTimeout(5_000) { controller.request(CommonPermission.Microphone) }
        assertEquals(PermissionStatus.NotDetermined, second)
        assertEquals(1, host.launches.size, "a dialog was launched for the timed-out caller")

        // Not held for another full wait: the next caller fails at once, and makes no launch call (a host
        // is promised one request in flight, so it is not asked while the stale dialog is up).
        val third: PermissionStatus = withTimeout(5_000) { controller.request(CommonPermission.Microphone) }
        assertEquals(PermissionStatus.NotDetermined, third)
        assertEquals(0, host.refused)
        assertEquals(1, host.launches.size)
    }

    @Test
    fun `cancelling the waiting caller ends its wait at once and launches nothing`(): Unit = runBlocking {
        declare()
        val host = HoldingHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
        first.cancelAndJoin()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Microphone) }
        withTimeout(5_000) { second.cancelAndJoin() }
        assertEquals(1, host.launches.size)
        assertEquals(0, host.refused)

        // The mutex is free again and the stale dialog is still honoured for the next caller.
        val third: Deferred<PermissionStatus> =
            async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Microphone) }
        assertFalse(third.isCompleted)
        host.answer(granted = false)
        withTimeout(5_000) { while (host.launches.size < 2) kotlinx.coroutines.yield() }
        host.answer(granted = false)
        withTimeout(5_000) { third.await() }
    }
}
