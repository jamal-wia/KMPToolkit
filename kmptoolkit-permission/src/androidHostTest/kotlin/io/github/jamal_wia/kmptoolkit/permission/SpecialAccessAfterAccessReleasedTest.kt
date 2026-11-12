package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * Documented misuse: the `ActivityAccess` handed to the factory is released while the controller built over it
 * is still in use. Over a released access no resume ever arrives, so a special-access request does not return
 * until its caller is cancelled, and later requests wait behind it (docs/kmptoolkit-permission/03-guide.md,
 * special access). Cancelling it ends the wait at once and frees the queue. Everything runs inside one
 * `runBlocking` on the test thread, so the controller's main-thread hop runs inline; every wait is bounded in
 * real time.
 */
@RunWith(AndroidJUnit4::class)
class SpecialAccessAfterAccessReleasedTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val launches = AtomicInteger()

    private fun controllerOver(access: ActivityAccess): PermissionController {
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
        return createPermissionController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            activityAccess = access,
            systemScreenLauncher = SystemScreenLauncher {
                launches.incrementAndGet()
                true
            },
        )
    }

    private suspend fun awaitLaunches(expected: Int) {
        // Over a released access the controller first waits its one second for a resumed activity, then launches.
        withTimeout(4_000) { while (launches.get() < expected) delay(10) }
    }

    private suspend fun assertStillPending(job: Job) {
        delay(1_000)
        assertTrue(job.isActive, "the request must still be waiting")
    }

    private suspend fun cancelAndAwait(job: Job) {
        job.cancel()
        withTimeout(1_000) { job.join() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a request after the release waits until cancelled and queues the next behind it`() {
        val access: ActivityAccess = createActivityAccess(application)
        val screen = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val controller: PermissionController = controllerOver(access)
            access.release()

            runBlocking {
                val a: Job = async(start = CoroutineStart.UNDISPATCHED) {
                    controller.request(AndroidPermission.InstallPackages)
                }
                awaitLaunches(1)
                assertStillPending(a)
                assertEquals(1, launches.get(), "request A reached the launcher once")

                val b: Job = async(start = CoroutineStart.UNDISPATCHED) {
                    controller.request(AndroidPermission.Overlay)
                }
                delay(1_000)
                assertTrue(b.isActive)
                assertEquals(1, launches.get(), "request B is queued behind A and has not reached the launcher")

                cancelAndAwait(a)
                awaitLaunches(2)
                assertEquals(2, launches.get(), "B reaches the launcher once A is cancelled")

                cancelAndAwait(b)
                assertEquals(
                    PermissionStatus.Denied(explainFirst = true),
                    withTimeout(2_000) { controller.check(AndroidPermission.InstallPackages) },
                )
            }
        } finally {
            access.release()
            screen.close()
        }
    }

    @Test
    fun `a request already waiting in Settings when the access is released waits until cancelled`() {
        val access: ActivityAccess = createActivityAccess(application)
        val screen = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val controller: PermissionController = controllerOver(access)

            runBlocking {
                val a: Job = async(start = CoroutineStart.UNDISPATCHED) {
                    controller.request(AndroidPermission.InstallPackages)
                }
                awaitLaunches(1)
                access.release()
                assertStillPending(a)

                cancelAndAwait(a)

                val c: Job = async(start = CoroutineStart.UNDISPATCHED) {
                    controller.request(AndroidPermission.Overlay)
                }
                awaitLaunches(2)
                assertEquals(2, launches.get(), "a new request for another special access reaches the launcher")
                cancelAndAwait(c)
            }
        } finally {
            access.release()
            screen.close()
        }
    }
}
