package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Round 19 (failure injection, starvation): a platform call that stalls (a binder transaction that takes seconds while the
 * system server is busy) while the notifier's post lock is held. Nothing may deadlock: every other post, cancel and
 * cancelAll queued behind the stalled `notify` finishes once the call returns, in an order the lock decides, and the
 * notifier is fully usable afterwards (a stall is not a failure and leaves no state behind). A cancel queued behind
 * the stalled frame of its own id still removes that frame afterwards ("`cancel` ... ordered against a post of the same
 * id still in flight"), and the next post of the id posts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], shadows = [AndroidNotifierStalledPlatformCallAdversarialTest.StallingShadowNotificationManager::class])
class AndroidNotifierStalledPlatformCallAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "stall", name = "Stall")

    @After
    fun heal() {
        StallingShadowNotificationManager.stall.set(null)
    }

    private fun notifier(): Notifier = AndroidNotifier(
        context, permissions, NotificationRenderer(context, "test.ACTION"),
        ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
    )

    private fun frame(title: String, percent: Int? = null): LocalNotification = LocalNotification(
        title = title,
        body = "b",
        channel = channel,
        progress = percent?.let { NotificationProgress.Determinate(it) },
    )

    class Stall(val tag: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private fun titleOf(id: String): String? = shadowOf(manager).getNotification(id, 1)?.extras?.getString(Notification.EXTRA_TITLE)

    @Test
    fun `posts and cancels queued behind a stalled notify all finish after it returns and the notifier stays usable`() {
        val notifier: Notifier = notifier()
        val stall = Stall("a")
        StallingShadowNotificationManager.stall.set(stall)
        val results: MutableMap<String, Any?> = java.util.concurrent.ConcurrentHashMap()
        val stalled: Thread = thread(name = "kmptoolkit-adversarial-stalled") {
            results["a"] = runBlocking { notifier.post("a", frame("a-first", 10)) }
        }
        assertTrue(stall.entered.await(10, TimeUnit.SECONDS), "the first post never reached notify")

        val queued: List<Thread> = listOf(
            thread(name = "kmptoolkit-adversarial-post-b") { results["b"] = runBlocking { notifier.post("b", frame("b", 10)) } },
            thread(name = "kmptoolkit-adversarial-cancel-c") { notifier.cancel("c"); results["c"] = "cancelled" },
            thread(name = "kmptoolkit-adversarial-post-a2") { results["a2"] = runBlocking { notifier.post("a", frame("a-second", 55)) } },
        )
        Thread.sleep(300)
        assertTrue(queued.all { it.isAlive }, "a queued call finished while the platform call was still stalled and holding the lock")
        stall.release.countDown()
        (queued + stalled).forEach { it.join(TimeUnit.SECONDS.toMillis(15)) }
        assertTrue((queued + stalled).none { it.isAlive }, "a thread is stuck after the stalled call returned")

        assertEquals(NotificationResult.Posted, results["a"])
        assertEquals(NotificationResult.Posted, results["b"])
        assertEquals("cancelled", results["c"])
        assertEquals(NotificationResult.Posted, results["a2"], "the newer frame of the stalled id is not overtaken by the older one: it was queued after it")
        assertEquals("a-second", titleOf("a"), "the newest frame of the id is what the tray shows")
        assertEquals("b", titleOf("b"))

        // Fully usable afterwards, nothing left locked.
        notifier.cancelAll()
        assertEquals(0, shadowOf(manager).allNotifications.size)
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame("again", 55)) })
    }

    @Test
    fun `a cancel queued behind the stalled frame of its own id removes it afterwards and the id posts again`() {
        val notifier: Notifier = notifier()
        val stall = Stall("a")
        StallingShadowNotificationManager.stall.set(stall)
        var first: NotificationResult? = null
        val stalled: Thread = thread(name = "kmptoolkit-adversarial-stalled-a") {
            first = runBlocking { notifier.post("a", frame("a-first", 30)) }
        }
        assertTrue(stall.entered.await(10, TimeUnit.SECONDS))
        val canceller: Thread = thread(name = "kmptoolkit-adversarial-cancel-a") { notifier.cancel("a") }
        Thread.sleep(200)
        assertTrue(canceller.isAlive, "the cancel must queue behind the stalled frame of its own id")
        stall.release.countDown()
        canceller.join(TimeUnit.SECONDS.toMillis(15))
        stalled.join(TimeUnit.SECONDS.toMillis(15))
        assertTrue(!canceller.isAlive && !stalled.isAlive)

        assertEquals(NotificationResult.Posted, first)
        assertNull(titleOf("a"), "the cancel was issued while the frame was in flight: the frame must not stay on the tray")
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame("a-after", 30)) }, "cancel forgot the baseline")
        assertNotNull(titleOf("a"))
    }

    /** Holds the calling thread inside `notify` of the stalled tag until the test releases it. */
    @Implements(NotificationManager::class)
    class StallingShadowNotificationManager : ShadowNotificationManager() {

        @Implementation
        public override fun notify(tag: String?, id: Int, notification: Notification?) {
            val stalled: Stall? = stall.get()
            if (stalled != null && stalled.tag == tag) {
                stall.set(null)
                stalled.entered.countDown()
                check(stalled.release.await(20, TimeUnit.SECONDS)) { "the test never released the stall" }
            }
            super.notify(tag, id, notification)
        }

        companion object {
            val stall: AtomicReference<Stall?> = AtomicReference(null)
        }
    }
}
