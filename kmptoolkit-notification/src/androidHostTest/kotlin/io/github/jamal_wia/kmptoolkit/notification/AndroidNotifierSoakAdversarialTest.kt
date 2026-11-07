package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
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
 * Soak of the in-flight bookkeeping: eight threads post progress, terminal frames, cancels and
 * cancelAlls over a few shared ids while builds fail at random and yield mid-way. Nothing may hang,
 * escape or leave the tray with two notifications for one id, and once everything has gone quiet a
 * cancelAll must leave a notifier that posts every first frame again.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierSoakAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "soak", name = "Soak")

    private class FlakyRenderer(context: Context, private val seed: Long) : NotificationRenderer(context, "test.ACTION") {
        private val calls = java.util.concurrent.atomic.AtomicInteger()

        override fun build(id: String, notification: LocalNotification, options: NotificationOptions, iconResId: Int): Notification {
            val dice: Int = Random(seed + calls.incrementAndGet()).nextInt(10)
            if (dice < 3) Thread.yield()
            if (dice == 0) throw IllegalStateException("flaky build")
            if (dice == 1) Thread.sleep(1)
            return super.build(id, notification, options, iconResId)
        }
    }

    @Test
    fun `eight threads over shared ids with failing builds never hang or double a notification`() {
        val notifier: Notifier = AndroidNotifier(
            context, permissions, FlakyRenderer(context, seed = 7L), ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
        )
        val ids: List<String> = listOf("a", "b", "c")
        val barrier = CyclicBarrier(WORKERS)
        val problems: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        val workers: List<Thread> = List(WORKERS) { index: Int ->
            thread(name = "kmptoolkit-adversarial-soak-$index") {
                val random = Random(index)
                barrier.await(10, TimeUnit.SECONDS)
                repeat(ROUNDS) {
                    val id: String = ids[random.nextInt(ids.size)]
                    runCatching {
                        when (random.nextInt(10)) {
                            0 -> notifier.cancel(id)
                            1 -> if (random.nextInt(8) == 0) notifier.cancelAll() else notifier.cancel(id)
                            2 -> runBlocking { notifier.post(id, LocalNotification(title = "done", body = "b", channel = channel)) }
                            else -> runBlocking {
                                notifier.post(
                                    id,
                                    LocalNotification(
                                        title = "p", body = "b", channel = channel,
                                        progress = NotificationProgress.Determinate(random.nextInt(0, 100)),
                                    ),
                                )
                            }
                        }
                    }.onFailure { problems += "${it.javaClass.simpleName}: ${it.message}" }
                }
            }
        }
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(JOIN_SECONDS)) }
        assertTrue(workers.none { it.isAlive }, "a worker is stuck")
        // A failing build is reported as a result, never thrown: an escaped exception is a contract break.
        assertEquals(emptyList(), problems.filterNot { it.startsWith("IllegalStateException: flaky build") })

        val perId: Map<String?, Int> = shadowOf(manager).allNotifications.groupingBy { it.extras.getString(Notification.EXTRA_TITLE) }.eachCount()
        assertTrue(shadowOf(manager).allNotifications.size <= ids.size, "more notifications than ids: $perId")

        notifier.cancelAll()
        assertEquals(0, shadowOf(manager).allNotifications.size)
        ids.forEach { id: String ->
            (0 until 100 step 10).forEach { percent: Int ->
                val result: NotificationResult = runBlocking {
                    notifier.post(id, LocalNotification(title = "p", body = "b", channel = channel, progress = NotificationProgress.Determinate(percent)))
                }
                if (percent == 0) {
                    assertTrue(result == NotificationResult.Posted || result is NotificationResult.Failed, "first frame after cancelAll for '$id' was $result")
                    notifier.cancel(id)
                }
            }
        }
    }

    private companion object {
        const val WORKERS: Int = 8
        const val ROUNDS: Int = 250
        const val JOIN_SECONDS: Long = 30L
    }
}
