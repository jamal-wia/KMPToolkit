package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * `cancel` is documented as ordered against a post of the same id that is still in flight: the
 * cancelled notification does not come back. A post is in flight from the moment `post` is called,
 * including while it is suspended in the permission check, before the coalescer has heard of it.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierCancelBeforePrepareAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "cancel-early", name = "Cancel early")

    private class GatedPermissions(
        private val delegate: PermissionController,
        val entered: CountDownLatch,
        val gate: CompletableDeferred<Unit>,
    ) : PermissionController by delegate {
        override suspend fun check(permission: Permission): PermissionStatus {
            entered.countDown()
            gate.await()
            return delegate.check(permission)
        }
    }

    private class Setup(
        val notifier: Notifier,
        val coalescer: ProgressCoalescer,
        val entered: CountDownLatch,
        val gate: CompletableDeferred<Unit>,
    )

    private fun setup(delegate: PermissionController = FakePermissionController(defaultStatus = PermissionStatus.Granted)): Setup {
        val entered = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val notifier: Notifier = AndroidNotifier(
            context, GatedPermissions(delegate, entered, gate), NotificationRenderer(context, "test.ACTION"), coalescer,
        )
        return Setup(notifier, coalescer, entered, gate)
    }

    private fun notification(percent: Int = 40) =
        LocalNotification(title = "t", body = "b", channel = channel, progress = NotificationProgress.Determinate(percent))

    /** Posts "d" on a thread, runs [whileGated] once the post is suspended in its permission check. */
    private fun postWhileGated(setup: Setup, whileGated: () -> Unit): NotificationResult? {
        var result: NotificationResult? = null
        val poster: Thread = thread(name = "kmptoolkit-adversarial-cancel-early") {
            result = runBlocking { withTimeout(15_000L) { setup.notifier.post("d", notification()) } }
        }
        try {
            assertTrue(setup.entered.await(10, TimeUnit.SECONDS), "the post never reached its permission check")
            whileGated()
        } finally {
            setup.gate.complete(Unit)
            poster.join(TimeUnit.SECONDS.toMillis(15))
        }
        assertTrue(!poster.isAlive, "the poster is stuck")
        return result
    }

    @Test
    fun `a cancel that returns while a post of the id is still in its permission check is not followed by that post`() {
        val setup: Setup = setup()
        val result: NotificationResult? = postWhileGated(setup) { setup.notifier.cancel("d") }

        assertEquals(0, shadowOf(manager).allNotifications.size, "the cancelled notification came back (post answered $result)")
        assertEquals(NotificationResult.Coalesced, result)
        assertEquals(0, setup.coalescer.claimCountForTest())
    }

    @Test
    fun `a cancelAll that returns while a post is still in its permission check is not followed by that post`() {
        val setup: Setup = setup()
        val result: NotificationResult? = postWhileGated(setup) { setup.notifier.cancelAll() }

        assertEquals(0, shadowOf(manager).allNotifications.size, "the cancelled notification came back (post answered $result)")
        assertEquals(NotificationResult.Coalesced, result)
        assertEquals(0, setup.coalescer.claimCountForTest())
    }

    @Test
    fun `a post started after the cancel returned is not affected by it`() {
        val setup: Setup = setup()
        postWhileGated(setup) { setup.notifier.cancel("d") }

        val fresh: NotificationResult = runBlocking { withTimeout(15_000L) { setup.notifier.post("d", notification()) } }

        assertEquals(NotificationResult.Posted, fresh)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals(0, setup.coalescer.claimCountForTest())
    }

    @Test
    fun `a cancel of another id does not drop the post`() {
        val setup: Setup = setup()
        val result: NotificationResult? = postWhileGated(setup) { setup.notifier.cancel("other") }

        assertEquals(NotificationResult.Posted, result)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals(0, setup.coalescer.claimCountForTest())
    }

    @Test
    fun `a permission check that throws releases the claim`() {
        val throwing = object : PermissionController by FakePermissionController(defaultStatus = PermissionStatus.Granted) {
            override suspend fun check(permission: Permission): PermissionStatus = throw IllegalStateException("controller failed")
        }
        val setup: Setup = setup(throwing)
        // The gate wraps the throwing controller, so the check is reached, then fails.
        setup.gate.complete(Unit)
        val result: NotificationResult = runBlocking { withTimeout(15_000L) { setup.notifier.post("d", notification()) } }

        assertTrue(result is NotificationResult.Failed, "answered $result")
        assertEquals(0, setup.coalescer.claimCountForTest())
    }

    @Test
    fun `a post cancelled while it waits in the permission check releases the claim`() {
        val setup: Setup = setup()
        runBlocking {
            val job: Job = CoroutineScope(Dispatchers.Default).launch { setup.notifier.post("d", notification()) }
            try {
                withTimeout(10_000L) { while (setup.coalescer.claimCountForTest() == 0) yield() }
                assertTrue(setup.entered.await(10, TimeUnit.SECONDS), "the post never reached its permission check")
                job.cancel()
                withTimeout(10_000L) { job.join() }
            } finally {
                setup.gate.complete(Unit)
            }
        }

        assertEquals(0, setup.coalescer.claimCountForTest())
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `normal and coalesced posts leave no claim`() {
        val setup: Setup = setup()
        setup.gate.complete(Unit)
        assertEquals(NotificationResult.Posted, runBlocking { withTimeout(15_000L) { setup.notifier.post("d", notification(40)) } })
        assertEquals(NotificationResult.Coalesced, runBlocking { withTimeout(15_000L) { setup.notifier.post("d", notification(41)) } })
        assertEquals(0, setup.coalescer.claimCountForTest())
    }
}
