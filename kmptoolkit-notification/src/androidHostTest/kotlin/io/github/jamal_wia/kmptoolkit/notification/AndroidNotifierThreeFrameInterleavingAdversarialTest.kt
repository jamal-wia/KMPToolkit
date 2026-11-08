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
 * Every interleaving of three overlapping frames of one id (ninety orders of the three builds and the
 * three finishing steps, eight combinations of accepted and refused), for three mixes of progress and
 * terminal frames. The expected result of every frame and the frame left on the tray come from a
 * four-line model of the documented rules: the newest frame that has not failed is the live one, a
 * failed live frame hands the role to the newest frame still in flight, a frame that finishes while
 * not live is dropped for good, and a frame that completes ends every older frame.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierThreeFrameInterleavingAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "interleave3", name = "Interleave3")

    private class Gates {
        val beforeArrived = CountDownLatch(1)
        val beforeRelease = CountDownLatch(1)
        val afterArrived = CountDownLatch(1)
        val afterRelease = CountDownLatch(1)
    }

    private class GatedRenderer(context: Context, val gates: Map<String, Gates>, val refused: Set<String>) :
        NotificationRenderer(context, "test.ACTION") {
        override fun build(id: String, notification: LocalNotification, options: NotificationOptions, iconResId: Int): Notification {
            val g: Gates = gates.getValue(notification.title)
            g.beforeArrived.countDown()
            check(g.beforeRelease.await(10, TimeUnit.SECONDS)) { "before gate of ${notification.title}" }
            val built: Notification = super.build(id, notification, options, iconResId)
            g.afterArrived.countDown()
            check(g.afterRelease.await(10, TimeUnit.SECONDS)) { "after gate of ${notification.title}" }
            if (notification.title in refused) throw IllegalStateException("refused ${notification.title}")
            return built
        }
    }

    private val schedules: List<List<Pair<Int, Int>>> = run {
        val result = mutableListOf<List<Pair<Int, Int>>>()
        fun walk(done: IntArray, acc: List<Pair<Int, Int>>) {
            if (done.all { it == 2 }) { result += acc; return }
            for (frame in 0..2) if (done[frame] < 2) {
                done[frame]++
                walk(done, acc + (frame to done[frame]))
                done[frame]--
            }
        }
        walk(IntArray(3), emptyList())
        result
    }

    private val progress10 = NotificationProgress.Determinate(10)
    private val progress50 = NotificationProgress.Determinate(50)
    private val progress80 = NotificationProgress.Determinate(80)

    @Test
    fun `three progress frames in every interleaving`() = explore(listOf(progress10, progress50, progress80))

    @Test
    fun `a terminal frame in the middle in every interleaving`() = explore(listOf(progress10, null, progress80))

    @Test
    fun `an older terminal frame under two progress frames in every interleaving`() = explore(listOf(null, progress50, progress80))

    private fun explore(progress: List<NotificationProgress?>) {
        var scenarios = 0
        // cancelAt: -1 for no cancel, otherwise the cancel runs just before that event of the schedule
        // (6 = after the last one).
        for (cancelAt in -1..6) for (mask in 0 until 8) for (schedule in schedules) {
            scenarios++
            run(progress, refused = (0..2).filter { mask and (1 shl it) != 0 }.toSet(), schedule, cancelAt)
        }
        assertEquals(8 * 8 * 90, scenarios)
    }

    private fun run(progress: List<NotificationProgress?>, refused: Set<Int>, schedule: List<Pair<Int, Int>>, cancelAt: Int) {
        val label = "progress $progress, refused $refused, schedule $schedule, cancel before event $cancelAt"
        manager.cancelAll()
        val titles: List<String> = listOf("f0", "f1", "f2")
        val gates: Map<String, Gates> = titles.associateWith { Gates() }
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val notifier: Notifier = AndroidNotifier(
            context, permissions, GatedRenderer(context, gates, refused.map { titles[it] }.toSet()), coalescer,
        )
        val results = ConcurrentHashMap<String, NotificationResult>()
        val threads: MutableList<Thread> = mutableListOf()
        try {
            titles.forEachIndexed { index: Int, title: String ->
                threads += thread(name = "kmptoolkit-adversarial-interleave3-$title") {
                    results[title] = runBlocking {
                        notifier.post("d", LocalNotification(title = title, body = "b", channel = channel, progress = progress[index]))
                    }
                }
                assertTrue(gates.getValue(title).beforeArrived.await(10, TimeUnit.SECONDS), "$label: $title never reached its build")
            }
            schedule.forEachIndexed { position: Int, (frame: Int, event: Int) ->
                if (position == cancelAt) notifier.cancel("d")
                val g: Gates = gates.getValue(titles[frame])
                if (event == 1) {
                    g.beforeRelease.countDown()
                    assertTrue(g.afterArrived.await(10, TimeUnit.SECONDS), "$label: ${titles[frame]} never finished its build")
                } else {
                    g.afterRelease.countDown()
                    threads[frame].join(TimeUnit.SECONDS.toMillis(10))
                    assertTrue(!threads[frame].isAlive, "$label: ${titles[frame]} is stuck")
                }
            }
            if (cancelAt == schedule.size) notifier.cancel("d")
        } finally {
            gates.values.forEach { it.beforeRelease.countDown(); it.afterRelease.countDown() }
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
        }

        // The model: finishing steps in schedule order.
        val inFlight: MutableList<Int> = mutableListOf(0, 1, 2)
        var live: Int? = 2
        var tray: Int? = null
        val expected: MutableMap<Int, Class<*>> = mutableMapOf()
        val steps: List<Int> = (0..schedule.size).flatMap { position: Int ->
            // -1 marks the cancel, any other value a finishing step of that frame
            listOfNotNull(if (position == cancelAt) -1 else null, schedule.getOrNull(position)?.takeIf { it.second == 2 }?.first)
        }
        steps.forEach { frame: Int ->
            if (frame == -1) {
                inFlight.clear()
                live = null
                tray = null
            } else if (frame in refused) {
                expected[frame] = NotificationResult.Failed::class.java
                inFlight.remove(frame)
                if (live == frame) live = inFlight.maxOrNull()
            } else if (live == frame) {
                expected[frame] = NotificationResult.Posted::class.java
                tray = frame
                inFlight.removeAll { it <= frame }
            } else {
                expected[frame] = NotificationResult.Coalesced::class.java
                inFlight.remove(frame)
            }
        }
        titles.forEachIndexed { index: Int, title: String ->
            assertEquals(expected.getValue(index), results[title]!!::class.java, "$label: result of $title")
        }
        val shown: List<String> = shadowOf(manager).allNotifications.map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }
        assertEquals(listOfNotNull(tray?.let { titles[it] }), shown, "$label: what the tray shows")

        val inFlightField = ProgressCoalescer::class.java.getDeclaredField("inFlight").also { it.isAccessible = true }
        assertEquals(0, (inFlightField.get(coalescer) as Map<*, *>).values.sumOf { (it as List<*>).size }, "$label: frames left in flight")
        val postedField = ProgressCoalescer::class.java.getDeclaredField("posted").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val posted = (postedField.get(coalescer) as Map<String, ProgressCoalescer.Post>)["d"]
        if (posted != null) {
            val onTray: Notification? = shadowOf(manager).getNotification("d", 1)
            val bucket: Int? = onTray?.extras?.takeIf { it.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 }
                ?.getInt(Notification.EXTRA_PROGRESS)?.let { (it / 10) * 10 }
            assertEquals(posted.bucket, bucket, "$label: the coalescer remembers bucket ${posted.bucket} (shown=${posted.shown}) but the tray shows $bucket")
            assertTrue(posted.shown, "$label: an unshown baseline with nothing in flight")
        }
    }
}
