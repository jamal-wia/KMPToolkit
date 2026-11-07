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
 * Overlap and failure: an older frame of an id is still being built when a newer frame of the same id
 * fails. A frame that never reached the tray must neither displace a frame that could still be shown
 * nor stand as the baseline a retry is judged against.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierOverlapFailureAdversarialTest {

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

    @Test
    fun `a newer frame that fails does not cost the older frame still in flight its place in the tray`() {
        val renderer = ScriptedRenderer(context)
        val notifier: Notifier = notifier(renderer)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        renderer.behaviours["old"] = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "never released" }
        }
        renderer.behaviours["new"] = { throw IllegalStateException("the framework refused the newer frame") }
        val worker: Thread = thread(name = "kmptoolkit-adversarial-old") { runBlocking { notifier.post("d", frame("old", 10)) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the old frame never reached its build")

        runBlocking { notifier.post("d", frame("new", 50)) }
        release.countDown()
        worker.join(5_000)

        assertTrue(!worker.isAlive, "the old post is stuck")
        assertEquals(listOf("old"), titles(), "nothing was shown although the older frame was still wanted")
    }

    @Test
    fun `two overlapping frames that both fail leave a retry of the older progress postable`() {
        val renderer = ScriptedRenderer(context)
        val notifier: Notifier = notifier(renderer)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        renderer.behaviours["old"] = {
            renderer.behaviours.remove("old")
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "never released" }
            throw IllegalStateException("the framework refused the older frame")
        }
        renderer.behaviours["new"] = { throw IllegalStateException("the framework refused the newer frame") }
        val worker: Thread = thread(name = "kmptoolkit-adversarial-old") { runBlocking { notifier.post("d", frame("old", 10)) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the old frame never reached its build")
        runBlocking { notifier.post("d", frame("new", 50)) }
        release.countDown()
        worker.join(5_000)
        assertTrue(!worker.isAlive, "the old post is stuck")
        assertEquals(emptyList(), titles())

        val retry: NotificationResult = runBlocking { notifier.post("d", frame("old", 10)) }

        assertEquals(NotificationResult.Posted, retry, "the retry was judged redundant against a frame that never reached the tray")
        assertEquals(listOf("old"), titles())
    }

    @Test
    fun `two overlapping frames that both fail, the older failing first, leave a retry of the older progress postable`() {
        val renderer = ScriptedRenderer(context)
        val notifier: Notifier = notifier(renderer)
        val oldEntered = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val newEntered = CountDownLatch(1)
        val newRelease = CountDownLatch(1)
        renderer.behaviours["old"] = {
            renderer.behaviours.remove("old")
            oldEntered.countDown()
            check(oldRelease.await(5, TimeUnit.SECONDS)) { "never released" }
            throw IllegalStateException("the framework refused the older frame")
        }
        renderer.behaviours["new"] = {
            newEntered.countDown()
            check(newRelease.await(5, TimeUnit.SECONDS)) { "never released" }
            throw IllegalStateException("the framework refused the newer frame")
        }
        val older: Thread = thread(name = "kmptoolkit-adversarial-old") { runBlocking { notifier.post("d", frame("old", 10)) } }
        assertTrue(oldEntered.await(5, TimeUnit.SECONDS), "the old frame never reached its build")
        val newer: Thread = thread(name = "kmptoolkit-adversarial-new") { runBlocking { notifier.post("d", frame("new", 50)) } }
        assertTrue(newEntered.await(5, TimeUnit.SECONDS), "the new frame never reached its build")

        oldRelease.countDown()
        older.join(5_000)
        newRelease.countDown()
        newer.join(5_000)
        assertTrue(!older.isAlive && !newer.isAlive, "a post is stuck")
        assertEquals(emptyList(), titles())

        val retry: NotificationResult = runBlocking { notifier.post("d", frame("old", 10)) }

        assertEquals(NotificationResult.Posted, retry, "the retry was judged redundant against a frame that never reached the tray")
    }
}
