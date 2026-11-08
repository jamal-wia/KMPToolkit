package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Process death and re-creation mixed with concurrency: several threads, each rebuilding its notifier at random
 * (a fresh instance with empty bookkeeping on the same tray), post, cancel and cancelAll look-alike ids, every
 * frame with a tap target and two buttons. Ordering across instances is not promised, so nothing is asserted about
 * which frame is last; but whatever is on the tray afterwards must be addressed by its own id only: one tray entry per
 * posted id, a tap target that opens with that id's extras, buttons that report that id.
 */
@RunWith(AndroidJUnit4::class)
class NotificationRestartStormLookAlikeAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "storm", name = "Storm")

    private fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    private fun notifier(): Notifier = createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))

    private fun frame(id: String, progress: NotificationProgress?, n: Int) = LocalNotification(
        title = "$n", body = "b", channel = channel, progress = progress,
        actions = listOf(NotificationAction("stop", "Stop"), NotificationAction(id, "Self")),
        contentExtras = mapOf("who" to id, "n" to "$n"),
    )

    @Test
    fun `whatever is left on the tray is addressed by its own id only`() {
        registerLauncher()
        for (round in 0 until ROUNDS) {
            manager.cancelAll()
            val escaped: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
            val barrier = CyclicBarrier(WORKERS)
            val workers = List(WORKERS) { index ->
                thread(name = "kmptoolkit-adversarial-restart-$round-$index") {
                    val random = Random(round * 17 + index)
                    var notifier: Notifier = notifier()
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(OPS) { op ->
                        val id = IDS[random.nextInt(IDS.size)]
                        try {
                            when (random.nextInt(20)) {
                                in 0..11 -> runBlocking { notifier.post(id, frame(id, NotificationProgress.Determinate(random.nextInt(100)), op)) }
                                in 12..13 -> runBlocking { notifier.post(id, frame(id, null, op)) }
                                in 14..16 -> notifier.cancel(id)
                                17 -> notifier.cancelAll()
                                else -> notifier = notifier()
                            }
                        } catch (e: Throwable) {
                            escaped += "${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                }
            }
            workers.forEach { it.join(TimeUnit.SECONDS.toMillis(60)) }
            assertTrue(workers.none { it.isAlive }, "round $round: a worker is stuck")
            assertEquals(emptyList(), escaped.toList(), "round $round: an exception escaped")

            val active = manager.activeNotifications
            assertEquals(active.size, active.map { it.tag }.toSet().size, "round $round: two tray entries share a tag")
            for (entry in active) {
                val id = entry.tag
                assertTrue(id in IDS, "round $round: a tray entry with a tag that is no posted id")
                val shown: Notification = entry.notification
                val tap = requireNotNull(shown.contentIntent) { "round $round: no tap target for ${id.map { it.code }}" }
                assertEquals(id, shadowOf(tap).savedIntent.getStringExtra("who"), "round $round: tap target of ${id.map { it.code }} opens another id's extras")
                for (action in shown.actions) {
                    val intent = shadowOf(action.actionIntent).savedIntent
                    assertEquals(id, NotificationActionIntent.notificationId(intent), "round $round: a button of ${id.map { it.code }} reports another id")
                }
            }
        }
    }

    private companion object {
        const val ROUNDS: Int = 40
        const val WORKERS: Int = 6
        const val OPS: Int = 120
        val IDS: List<String> = listOf("a", "\uD800", "?", "\uD801\uD800", "\uD800\uD81F", "%uD800", "%3F")
    }
}
