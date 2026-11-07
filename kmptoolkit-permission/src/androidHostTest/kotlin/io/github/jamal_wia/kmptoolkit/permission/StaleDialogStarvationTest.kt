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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Callers piling up behind a stale dialog: the wait is bounded once for the whole queue, a long chain of
 * cancelled callers never wedges the mutex, and nothing is left running afterwards. Every wait is
 * bounded in real time; the test thread is the main thread under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class StaleDialogStarvationTest {

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
        listOf(camera, microphone).forEach { shadowOf(application).denyPermissions(it) }
        application.declareInManifest(camera, microphone)
    }

    @Test
    fun `thirty callers queued behind a stale dialog that is never answered wait one bound in total, not one each`(): Unit = runBlocking {
        declare()
        val host = HoldingHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host, staleDialogTimeout = 200.milliseconds)
        val first: Job = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
        val queued: List<Deferred<PermissionStatus>> = List(30) {
            async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Microphone) }
        }
        first.cancelAndJoin()

        val started: Long = System.nanoTime()
        val statuses: List<PermissionStatus> = withTimeout(10_000) { queued.map { it.await() } }
        val elapsedMs: Long = (System.nanoTime() - started) / 1_000_000

        assertTrue(statuses.all { it == PermissionStatus.NotDetermined })
        assertEquals(1, host.launches.size, "a dialog was launched behind the stale one")
        assertTrue(elapsedMs < 3_000, "thirty queued callers took $elapsedMs ms: the 200 ms bound was applied per caller")
    }

    @Test
    fun `forty callers each cancelled with their own dialog up never wedge the mutex or lose the next dialog`(): Unit = runBlocking {
        declare()
        val host = HoldingHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host)
        var current: Job = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
        assertEquals(1, host.launches.size)

        repeat(40) { round ->
            val next: Job = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Microphone) }
            current.cancelAndJoin() // its dialog is now stale; `next` is queued behind it
            assertEquals(round + 1, host.launches.size, "round $round: a dialog was launched on top of the stale one")
            host.answer(granted = false)
            withTimeout(5_000) { while (host.launches.size < round + 2) yield() }
            assertEquals(0, host.refused, "round $round: a launch was refused")
            current = next
        }

        current.cancelAndJoin()
        host.answer(granted = false)
        val last: Deferred<PermissionStatus> = async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
        assertFalse(last.isCompleted, "the last caller was answered without a dialog")
        assertEquals(42, host.launches.size)
        host.answer(granted = false)
        withTimeout(5_000) { last.await() }
        assertEquals(0, host.refused)
    }
}
