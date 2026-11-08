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
 * Every interleaving of two overlapping frames of one id, driven by latches on a real notifier: both
 * orders of preparation, every order of the two builds and the two finishing steps, every combination
 * of "the platform accepts" and "refuses" each frame, with the newer frame a progress frame or a
 * terminal one, and an older terminal frame. The result of each frame, what the tray shows and what the
 * coalescer remembers must follow the documented rules in all of them.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierTwoFrameInterleavingAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "interleave", name = "Interleave")

    /** Two gates per frame: the build is held before it starts and again after it ran. */
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

    private data class Kind(val name: String, val older: NotificationProgress?, val newer: NotificationProgress?)

    private val kinds: List<Kind> = listOf(
        Kind("progress then progress", NotificationProgress.Determinate(10), NotificationProgress.Determinate(50)),
        Kind("progress then terminal", NotificationProgress.Determinate(10), null),
        Kind("terminal then progress", null, NotificationProgress.Determinate(50)),
    )

    /** Event order of one scenario: 1 = release a frame's before gate, 2 = release its after gate. */
    private val schedules: List<List<Pair<Int, Int>>> = run {
        // frame 0 = the frame prepared first, frame 1 = the frame prepared second
        val result = mutableListOf<List<Pair<Int, Int>>>()
        fun walk(done0: Int, done1: Int, acc: List<Pair<Int, Int>>) {
            if (done0 == 2 && done1 == 2) { result += acc; return }
            if (done0 < 2) walk(done0 + 1, done1, acc + (0 to done0 + 1))
            if (done1 < 2) walk(done0, done1 + 1, acc + (1 to done1 + 1))
        }
        walk(0, 0, emptyList())
        result
    }

    @Test
    fun `every interleaving of two overlapping frames follows the documented rules`() {
        var scenarios = 0
        kinds.forEach { kind: Kind ->
            for (firstRefused in listOf(false, true)) for (secondRefused in listOf(false, true)) for (schedule in schedules) {
                scenarios++
                run(kind, firstRefused, secondRefused, schedule)
            }
        }
        assertEquals(3 * 4 * 6, scenarios)
    }

    private fun run(kind: Kind, firstRefused: Boolean, secondRefused: Boolean, schedule: List<Pair<Int, Int>>) {
        val label = "${kind.name}, first ${if (firstRefused) "refused" else "accepted"}, second ${if (secondRefused) "refused" else "accepted"}, schedule $schedule"
        manager.cancelAll()
        val titles: List<String> = listOf("first", "second")
        val gates: Map<String, Gates> = titles.associateWith { Gates() }
        val refused: Set<String> = setOfNotNull("first".takeIf { firstRefused }, "second".takeIf { secondRefused })
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val notifier: Notifier = AndroidNotifier(context, permissions, GatedRenderer(context, gates, refused), coalescer)
        val progress: List<NotificationProgress?> = listOf(kind.older, kind.newer)
        val results = ConcurrentHashMap<String, NotificationResult>()
        val threads: MutableList<Thread> = mutableListOf()
        try {
            // Preparation order is the start order: each frame is held in its build, so it has prepared.
            titles.forEachIndexed { index: Int, title: String ->
                threads += thread(name = "kmptoolkit-adversarial-interleave-$title") {
                    results[title] = runBlocking {
                        notifier.post("d", LocalNotification(title = title, body = "b", channel = channel, progress = progress[index]))
                    }
                }
                assertTrue(gates.getValue(title).beforeArrived.await(10, TimeUnit.SECONDS), "$label: $title never reached its build")
            }
            val finishedAt: MutableMap<String, Int> = mutableMapOf()
            schedule.forEachIndexed { step: Int, (frame: Int, event: Int) ->
                val title: String = titles[frame]
                val g: Gates = gates.getValue(title)
                if (event == 1) {
                    g.beforeRelease.countDown()
                    assertTrue(g.afterArrived.await(10, TimeUnit.SECONDS), "$label: $title never finished its build")
                } else {
                    g.afterRelease.countDown()
                    threads[frame].join(TimeUnit.SECONDS.toMillis(10))
                    assertTrue(!threads[frame].isAlive, "$label: $title is stuck")
                    finishedAt[title] = step
                }
            }
        } finally {
            gates.values.forEach { it.beforeRelease.countDown(); it.afterRelease.countDown() }
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
        }

        // What each frame must have been told.
        val expectedSecond: Class<*> = if (secondRefused) NotificationResult.Failed::class.java else NotificationResult.Posted::class.java
        assertEquals(expectedSecond, results["second"]!!::class.java, "$label: the newest frame's result")
        val secondFinishedFirst: Boolean = schedule.indexOf(1 to 2) < schedule.indexOf(0 to 2)
        val expectedFirst: Class<*> = when {
            firstRefused -> NotificationResult.Failed::class.java
            !secondRefused -> NotificationResult.Coalesced::class.java
            secondFinishedFirst -> NotificationResult.Posted::class.java
            else -> NotificationResult.Coalesced::class.java
        }
        assertEquals(expectedFirst, results["first"]!!::class.java, "$label: the older frame's result")

        // What the tray shows: the newest accepted frame that was still wanted.
        val shownTitles: List<String> = shadowOf(manager).allNotifications.map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }
        val expectedShown: List<String> = when {
            !secondRefused -> listOf("second")
            results["first"] is NotificationResult.Posted -> listOf("first")
            else -> emptyList()
        }
        assertEquals(expectedShown, shownTitles, "$label: what the tray shows")

        // What the coalescer remembers must be what the tray shows, and nothing may be left in flight.
        val inFlight = ProgressCoalescer::class.java.getDeclaredField("inFlight").also { it.isAccessible = true }
        assertEquals(0, (inFlight.get(coalescer) as Map<*, *>).values.sumOf { (it as List<*>).size }, "$label: frames left in flight")
        val postedField = ProgressCoalescer::class.java.getDeclaredField("posted").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val posted = (postedField.get(coalescer) as Map<String, ProgressCoalescer.Post>)["d"]
        if (posted != null) {
            val onTray: Notification? = shadowOf(manager).getNotification("d", 1)
            val bucket: Int? = onTray?.extras?.takeIf { it.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 }?.getInt(Notification.EXTRA_PROGRESS)?.let { (it / 10) * 10 }
            assertEquals(posted.bucket, bucket, "$label: the coalescer remembers bucket ${posted.bucket} (shown=${posted.shown}) but the tray shows $bucket")
            assertTrue(posted.shown, "$label: an unshown baseline with nothing in flight")
        }
    }
}
