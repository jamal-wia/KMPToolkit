package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * An older frame that is promoted back to "live" because the newer one was refused must still lose to
 * a cancel, a cancelAll and a terminal frame that succeeds, and may win against a terminal frame that
 * is refused.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierPromotionAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val shown: List<Notification> get() = shadowOf(manager).allNotifications
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "overlap", name = "Overlap")

    private fun frame(title: String, percent: Int): LocalNotification =
        LocalNotification(title = title, body = "body", channel = channel, progress = NotificationProgress.Determinate(percent))

    private fun titles(): List<String> =
        shown.map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }.sorted()

    private class ScriptedRenderer(context: Context) : NotificationRenderer(context, "test.ACTION") {
        val behaviours: MutableMap<String, () -> Unit> = ConcurrentHashMap()

        override fun build(id: String, notification: LocalNotification, options: NotificationOptions, iconResId: Int): Notification {
            behaviours[notification.title]?.invoke()
            return super.build(id, notification, options, iconResId)
        }
    }

    private fun notifier(renderer: NotificationRenderer): Notifier =
        AndroidNotifier(context, permissions, renderer, ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO))

    private fun frame(title: String, percent: Int?): LocalNotification = LocalNotification(
        title = title,
        body = "body",
        channel = channel,
        progress = percent?.let { NotificationProgress.Determinate(it) },
    )

    /** Parks "old" (10%) in its build, runs [middle] while it is parked, then releases it. */
    private fun withParkedOld(middle: (Notifier) -> Unit) {
        val renderer = ScriptedRenderer(context)
        val notifier: Notifier = notifier(renderer)
        renderer.behaviours["new"] = { throw IllegalStateException("the framework refused the newer frame") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        renderer.behaviours["old"] = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "never released" }
        }
        val worker: Thread = thread(name = "kmptoolkit-adversarial-old") { runBlocking { notifier.post("d", frame("old", 10)) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the old frame never reached its build")
        middle(notifier)
        release.countDown()
        worker.join(5_000)
        assertTrue(!worker.isAlive, "the old post is stuck")
    }

    @Test
    fun `an older frame promoted by a refused newer one does not come back after a cancel`() {
        withParkedOld { notifier: Notifier ->
            runBlocking { notifier.post("d", frame("new", 50)) }
            notifier.cancel("d")
        }
        assertEquals(emptyList(), titles())
    }

    @Test
    fun `an older frame promoted by a refused newer one does not come back after a cancelAll`() {
        withParkedOld { notifier: Notifier ->
            runBlocking { notifier.post("d", frame("new", 50)) }
            notifier.cancelAll()
        }
        assertEquals(emptyList(), titles())
    }

    @Test
    fun `a terminal frame that succeeds drops the older frame still being built`() {
        withParkedOld { notifier: Notifier ->
            assertEquals(NotificationResult.Posted, runBlocking { notifier.post("d", frame("done", null)) })
        }
        assertEquals(listOf("done"), titles())
    }
}
