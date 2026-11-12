package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationChannel
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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Round 15 (resource starvation, low-memory style failures): a platform seam throws a JVM `Error`
 * (`OutOfMemoryError`, `StackOverflowError`, `NoSuchMethodError`) once, at each stage of a post.
 *
 * The contract does not say that an Error becomes a result, so the test accepts either a `Failed` or
 * the Error itself. What it does pin is what the contract says about state: a post that did not reach the
 * tray "is rolled back" (a retry of the same progress posts instead of answering `Coalesced`), the
 * notifier "is safe to call from any thread" afterwards (the post lock is not leaked: `cancel` and
 * `cancelAll` return), and an in-flight claim is not left behind to swallow the next frame of the id.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [AndroidNotifierErrorFromPlatformAdversarialTest.ErrorShadowNotificationManager::class])
class AndroidNotifierErrorFromPlatformAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "error", name = "Error")

    @After
    fun heal() {
        ErrorShadowNotificationManager.armed.set(null)
    }

    private fun notifier(): Notifier = AndroidNotifier(
        context, permissions, NotificationRenderer(context, "test.ACTION"), ProgressCoalescer(minInterval = Duration.ZERO),
    )

    private fun frame(percent: Int): LocalNotification = LocalNotification(
        title = "t", body = "b", channel = channel, progress = NotificationProgress.Determinate(percent),
    )

    private fun trayTitles(): List<String> =
        shadowOf(manager).allNotifications.map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }

    private fun withinTimeout(what: String, block: () -> Unit) {
        val done = CountDownLatch(1)
        val worker = thread(name = "r15-$what", isDaemon = true) {
            block()
            done.countDown()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "$what did not return: the post lock or a claim was leaked")
        worker.join(1_000)
    }

    private fun errors(): List<() -> Error> = listOf(
        { OutOfMemoryError("r15") },
        { StackOverflowError("r15") },
        { NoSuchMethodError("r15") },
    )

    @Test
    fun `an Error from any platform stage leaves the notifier usable and the next identical frame posts`() {
        for (stage in listOf("areNotificationsEnabled", "getNotificationChannel", "createNotificationChannel", "notify")) {
            for (make in errors()) {
                val error: Error = make()
                val notifier: Notifier = notifier()
                runBlocking { notifier.cancel("a") }
                ErrorShadowNotificationManager.armed.set(stage to error)

                val outcome: Result<NotificationResult> = runCatching { runBlocking { withTimeout(10_000) { notifier.post("a", frame(50)) } } }

                val thrown: Throwable? = outcome.exceptionOrNull()
                assertTrue(
                    thrown == null || thrown.javaClass == error.javaClass,
                    "stage $stage ${error.javaClass.simpleName}: post threw $thrown instead of the Error itself or a Failed",
                )
                if (thrown == null) {
                    assertTrue(outcome.getOrNull() is NotificationResult.Failed, "stage $stage: expected Failed, was ${outcome.getOrNull()}")
                }
                assertEquals(emptyList(), trayTitles(), "stage $stage ${error.javaClass.simpleName}: nothing was accepted by the platform")
                withinTimeout("cancel after $stage") { notifier.cancel("a") }
                withinTimeout("cancelAll after $stage") { notifier.cancelAll() }
                ErrorShadowNotificationManager.armed.set(null)

                val retry: NotificationResult = runBlocking { withTimeout(10_000) { notifier.post("a", frame(50)) } }

                assertEquals(
                    NotificationResult.Posted,
                    retry,
                    "stage $stage ${error.javaClass.simpleName}: the frame that died with an Error must not be the baseline, nor its claim swallow the retry",
                )
                assertEquals(1, trayTitles().size)
            }
        }
    }

    @Test
    fun `an Error while a post holds the lock does not wedge a concurrent cancel nor the id`() {
        val notifier: Notifier = notifier()
        runBlocking { notifier.post("a", frame(10)) }
        ErrorShadowNotificationManager.armed.set("notify" to OutOfMemoryError("r15"))

        val dead: Throwable? = runCatching { runBlocking { withTimeout(10_000) { notifier.post("a", frame(90)) } } }.exceptionOrNull()
        assertTrue(dead == null || dead is OutOfMemoryError, "was $dead")
        withinTimeout("cancel after the notify Error") { notifier.cancel("a") }

        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame(90)) })
        assertEquals(1, trayTitles().size)
    }

    @Implements(NotificationManager::class)
    class ErrorShadowNotificationManager : ShadowNotificationManager() {

        private fun maybeThrow(stage: String) {
            val pending: Pair<String, Error>? = armed.get()
            if (pending != null && pending.first == stage && armed.compareAndSet(pending, null)) throw pending.second
        }

        @Implementation
        public override fun areNotificationsEnabled(): Boolean {
            maybeThrow("areNotificationsEnabled")
            return super.areNotificationsEnabled()
        }

        @Implementation
        public override fun getNotificationChannel(channelId: String?): NotificationChannel? {
            maybeThrow("getNotificationChannel")
            return super.getNotificationChannel(channelId)
        }

        @Implementation
        public override fun createNotificationChannel(channel: NotificationChannel?) {
            maybeThrow("createNotificationChannel")
            super.createNotificationChannel(channel)
        }

        @Implementation
        public override fun notify(tag: String?, id: Int, notification: Notification?) {
            maybeThrow("notify")
            super.notify(tag, id, notification)
        }

        companion object {
            val armed: AtomicReference<Pair<String, Error>?> = AtomicReference(null)
        }
    }
}
