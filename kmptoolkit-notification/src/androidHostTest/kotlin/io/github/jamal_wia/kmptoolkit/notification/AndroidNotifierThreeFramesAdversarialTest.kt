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
 * Three frames of one id in flight at once, the newest ones refused by the platform in each possible
 * order: the newest frame still being built when the failures settle is the one that ends up on the
 * tray, and a frame that was refused is never the one a retry is judged against.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierThreeFramesAdversarialTest {

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

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private fun run(failureOrder: List<String>, expectedTray: List<String>) {
        val renderer = ScriptedRenderer(context)
        val notifier: Notifier = notifier(renderer)
        val gates: Map<String, Gate> = listOf("old", "mid", "new").associateWith { Gate() }
        listOf("old", "mid", "new").forEach { title: String ->
            renderer.behaviours[title] = {
                val gate: Gate = gates.getValue(title)
                gate.entered.countDown()
                check(gate.release.await(5, TimeUnit.SECONDS)) { "never released" }
                if (title in failureOrder) throw IllegalStateException("the framework refused $title")
            }
        }
        val percents: Map<String, Int> = mapOf("old" to 10, "mid" to 50, "new" to 80)
        val workers: Map<String, Thread> = listOf("old", "mid", "new").associateWith { title: String ->
            thread(name = "kmptoolkit-adversarial-$title") { runBlocking { notifier.post("d", frame(title, percents.getValue(title))) } }
                .also { assertTrue(gates.getValue(title).entered.await(5, TimeUnit.SECONDS), "$title never reached its build") }
        }

        // Frames are released one at a time, failures first in the given order, then the rest.
        (failureOrder + listOf("old", "mid", "new").filter { it !in failureOrder }).forEach { title: String ->
            gates.getValue(title).release.countDown()
            workers.getValue(title).join(5_000)
            assertTrue(!workers.getValue(title).isAlive, "$title is stuck")
        }

        assertEquals(expectedTray, titles(), "failures $failureOrder")
    }

    @Test
    fun `refusing the newest of three overlapping frames hands the tray to the newest one still being built`() {
        // new refused while old and mid are still parked: mid takes over, old is overtaken.
        run(failureOrder = listOf("new"), expectedTray = listOf("mid"))
        // new then mid refused while old is still parked: old takes over.
        manager.cancelAll()
        run(failureOrder = listOf("new", "mid"), expectedTray = listOf("old"))
        manager.cancelAll()
        // mid then new refused: old takes over, whichever refusal arrives first.
        run(failureOrder = listOf("mid", "new"), expectedTray = listOf("old"))
    }
}
