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
import java.util.concurrent.atomic.AtomicBoolean
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * The platform itself refuses `notify` (a revoked permission, a dead binder) for every fourth call
 * while eight threads post, cancel and overtake each other: the refusal happens under the post lock
 * after the frame passed the liveness check. Every refusal must come back as `Failed` and roll its
 * frame back, and once everything is quiet the coalescer must believe exactly what the tray shows.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [AndroidNotifierNotifyRefusalStormAdversarialTest.RefusingShadowNotificationManager::class])
class AndroidNotifierNotifyRefusalStormAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "refusal", name = "Refusal")

    private class YieldingRenderer(context: Context) : NotificationRenderer(context, "test.ACTION") {
        override fun build(id: String, notification: LocalNotification, options: NotificationOptions, iconResId: Int): Notification {
            Thread.yield()
            val built: Notification = super.build(id, notification, options, iconResId)
            Thread.yield()
            return built
        }
    }

    @Test
    fun `a platform that refuses every fourth notify leaves the coalescer in step with the tray`() {
        val ids: List<String> = listOf("a", "b")
        repeat(ROUNDS) { round: Int ->
            manager.cancelAll()
            val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
            val notifier: Notifier = AndroidNotifier(context, permissions, YieldingRenderer(context), coalescer)
            val barrier = CyclicBarrier(WORKERS)
            val escaped: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
            val refusedResults = AtomicInteger()
            RefusingShadowNotificationManager.refusing.set(true)
            val workers: List<Thread> = List(WORKERS) { index: Int ->
                thread(name = "kmptoolkit-adversarial-notify-refusal-$round-$index") {
                    val random = Random(round * 100 + index)
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(OPS) {
                        val id: String = ids[random.nextInt(ids.size)]
                        try {
                            when (val dice: Int = random.nextInt(100)) {
                                in 0..5 -> notifier.cancel(id)
                                in 6..7 -> notifier.cancelAll()
                                in 8..15 -> runBlocking { notifier.post(id, frame(id, null)) }
                                else -> {
                                    val result: NotificationResult = runBlocking {
                                        notifier.post(id, frame(id, NotificationProgress.Determinate((dice % 10) * 10 + random.nextInt(10))))
                                    }
                                    if (result is NotificationResult.Failed) refusedResults.incrementAndGet()
                                }
                            }
                        } catch (e: Throwable) {
                            escaped += "${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                }
            }
            workers.forEach { it.join(TimeUnit.SECONDS.toMillis(JOIN_SECONDS)) }
            RefusingShadowNotificationManager.refusing.set(false)
            assertTrue(workers.none { it.isAlive }, "round $round: a worker is stuck")
            assertEquals(emptyList(), escaped.toList(), "round $round: a platform refusal escaped as an exception")
            assertTrue(refusedResults.get() > 0, "round $round: the fault was never exercised")

            val inFlight = ProgressCoalescer::class.java.getDeclaredField("inFlight").also { it.isAccessible = true }
            assertEquals(0, (inFlight.get(coalescer) as Map<*, *>).values.sumOf { (it as List<*>).size }, "round $round: frames still in flight")
            val posted = ProgressCoalescer::class.java.getDeclaredField("posted").also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (posted.get(coalescer) as Map<String, ProgressCoalescer.Post>).toMap().forEach { (id: String, record: ProgressCoalescer.Post) ->
                val onTray: Notification? = shadowOf(manager).getNotification(id, 1)
                val progress: Int? = onTray?.extras?.takeIf { it.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 }?.getInt(Notification.EXTRA_PROGRESS)
                assertTrue(
                    progress != null && (progress / 10) * 10 == record.bucket && record.shown,
                    "round $round: '$id' is remembered at bucket ${record.bucket} (shown=${record.shown}) but the tray holds progress $progress",
                )
            }
        }
    }

    private fun frame(id: String, progress: NotificationProgress?): LocalNotification =
        LocalNotification(title = "t-$id", body = "b", channel = channel, progress = progress)

    /** While [refusing], every fourth `notify` throws. */
    @Implements(NotificationManager::class)
    class RefusingShadowNotificationManager : ShadowNotificationManager() {

        @Implementation
        public override fun notify(tag: String?, id: Int, notification: Notification?) {
            if (refusing.get() && calls.incrementAndGet() % 4 == 0) throw SecurityException("notify refused")
            super.notify(tag, id, notification)
        }

        companion object {
            val refusing: AtomicBoolean = AtomicBoolean(false)
            val calls: AtomicInteger = AtomicInteger(0)
        }
    }

    private companion object {
        const val ROUNDS: Int = 8
        const val WORKERS: Int = 8
        const val OPS: Int = 250
        const val JOIN_SECONDS: Long = 45L
    }
}
