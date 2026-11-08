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
import java.util.concurrent.atomic.AtomicInteger
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
 * Eight threads post, refuse, overtake, cancel and cancelAll over shared ids with builds that yield
 * and fail at random. Once every call has returned, the coalescer's belief about what an id's tray
 * frame is must match the tray itself (a recorded baseline for a frame that is not on the tray makes
 * a retry of it look redundant), no unfinished-frame bookkeeping may be left behind, and every id
 * must accept a fresh frame again.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierTrayOracleStormAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "oracle", name = "Oracle")

    private class ChaosRenderer(context: Context, private val seed: Long) : NotificationRenderer(context, "test.ACTION") {
        private val calls = AtomicInteger()

        override fun build(id: String, notification: LocalNotification, options: NotificationOptions, iconResId: Int): Notification {
            val random = Random(seed * 31 + calls.incrementAndGet())
            if (random.nextInt(3) == 0) Thread.yield()
            if (random.nextInt(6) == 0) Thread.sleep(random.nextLong(1, 3))
            val built: Notification = super.build(id, notification, options, iconResId)
            if (random.nextInt(3) == 0) Thread.yield()
            if (random.nextInt(6) == 0) Thread.sleep(random.nextLong(1, 3))
            if (random.nextInt(4) == 0) throw IllegalStateException("refused")
            return built
        }
    }

    private fun postedRecords(coalescer: ProgressCoalescer): Map<String, ProgressCoalescer.Post> {
        val field = ProgressCoalescer::class.java.getDeclaredField("posted").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return coalescer.let { c -> (field.get(c) as Map<String, ProgressCoalescer.Post>).toMap() }
    }

    private fun inFlightTickets(coalescer: ProgressCoalescer): Int {
        val field = ProgressCoalescer::class.java.getDeclaredField("inFlight").also { it.isAccessible = true }
        return (field.get(coalescer) as Map<*, *>).values.sumOf { (it as List<*>).size }
    }

    @Test
    fun `after a storm the coalescer believes exactly what the tray shows and holds nothing in flight`() {
        storm(foreground = false)
    }

    @Test
    fun `the same holds when one of the ids is a foreground service notification under an untagged id`() {
        storm(foreground = true)
    }

    private fun storm(foreground: Boolean) {
        val ids: List<String> = if (foreground) listOf("a", "fg") else listOf("a", "b")
        val foregroundIds: Set<ForegroundNotificationId> = if (foreground) setOf(ForegroundNotificationId("fg", 77)) else emptySet()
        repeat(ROUNDS) { round: Int ->
            shadowOf(manager).allNotifications.let { manager.cancelAll() }
            val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
            val notifier: Notifier = AndroidNotifier(context, permissions, ChaosRenderer(context, seed = round.toLong()), coalescer, foregroundIds)
            val barrier = CyclicBarrier(WORKERS)
            val escaped: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
            val workers: List<Thread> = List(WORKERS) { index: Int ->
                thread(name = "kmptoolkit-adversarial-oracle-$round-$index") {
                    val random = Random(round * 100 + index)
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(OPS) {
                        val id: String = ids[random.nextInt(ids.size)]
                        try {
                            when (val dice: Int = random.nextInt(100)) {
                                in 0..5 -> notifier.cancel(id)
                                in 6..7 -> notifier.cancelAll()
                                in 8..15 -> runBlocking { notifier.post(id, frame(id, null)) }
                                in 16..19 -> runBlocking { notifier.post(id, frame(id, NotificationProgress.Determinate(100))) }
                                in 20..22 -> runBlocking { notifier.post(id, frame(id, NotificationProgress.Indeterminate)) }
                                else -> runBlocking { notifier.post(id, frame(id, NotificationProgress.Determinate((dice % 10) * 10 + random.nextInt(10)))) }
                            }
                        } catch (e: Throwable) {
                            escaped += "${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                }
            }
            workers.forEach { it.join(TimeUnit.SECONDS.toMillis(JOIN_SECONDS)) }
            assertTrue(workers.none { it.isAlive }, "round $round: a worker is stuck")
            assertEquals(emptyList(), escaped.toList(), "round $round: an exception escaped post / cancel")

            assertEquals(0, inFlightTickets(coalescer), "round $round: frames still counted as in flight with no caller left")
            postedRecords(coalescer).forEach { (id: String, record: ProgressCoalescer.Post) ->
                val onTray: Notification? =
                    if (id == "fg") shadowOf(manager).getNotification(null, 77) else shadowOf(manager).getNotification(id, 1)
                val trayProgress: Int? = onTray?.extras?.takeIf { it.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 }?.getInt(Notification.EXTRA_PROGRESS)
                assertTrue(
                    onTray != null && trayProgress != null && (trayProgress / 10) * 10 == record.bucket,
                    "round $round: '$id' is remembered as shown at bucket ${record.bucket} (shown=${record.shown}) but the tray holds progress $trayProgress (present=${onTray != null})",
                )
                assertTrue(record.shown, "round $round: '$id' keeps an unshown baseline with nothing in flight")
            }
            // And a fresh frame of every id posts.
            ids.forEach { id: String ->
                notifier.cancel(id)
                val result: NotificationResult = runBlocking { notifier.post(id, frame(id, NotificationProgress.Determinate(40))) }
                assertTrue(result == NotificationResult.Posted || result is NotificationResult.Failed, "round $round: fresh frame of '$id' was $result")
            }
        }
    }

    private fun frame(id: String, progress: NotificationProgress?): LocalNotification =
        LocalNotification(title = "t-$id", body = "b", channel = channel, progress = progress)

    private companion object {
        const val ROUNDS: Int = 10
        const val WORKERS: Int = 8
        const val OPS: Int = 300
        const val JOIN_SECONDS: Long = 45L
    }
}
