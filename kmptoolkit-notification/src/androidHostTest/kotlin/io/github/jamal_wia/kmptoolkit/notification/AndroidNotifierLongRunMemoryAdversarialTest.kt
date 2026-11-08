package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Long runs of fresh ids: whatever finishes with a terminal frame or a cancel leaves no state behind (coalescer maps,
 * claims, the 2.x sweep cache), and ids that simply went quiet are aged out on the clock, not kept for the notifier's life.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierLongRunMemoryAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "long-run", name = "Long run")

    /** Frames still in flight, over all ids (reflection: the coalescer has no seam for it). */
    private fun inFlightCount(coalescer: ProgressCoalescer): Int {
        val field = ProgressCoalescer::class.java.getDeclaredField("inFlight").also { it.isAccessible = true }
        return (field.get(coalescer) as Map<*, *>).values.sumOf { (it as List<*>).size }
    }

    @Test
    fun `thousands of finished and cancelled runs leave no state behind`() = runTest {
        val clock = TestTimeSource()
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO, timeSource = clock)
        val notifier = AndroidNotifier(
            context, FakePermissionController(defaultStatus = PermissionStatus.Granted), NotificationRenderer(context, "test.ACTION"), coalescer,
        )
        repeat(RUNS) { i: Int ->
            val id = "run-$i"
            notifier.post(id, LocalNotification("t", "b", channel, progress = NotificationProgress.Determinate(30)))
            notifier.post(id, LocalNotification("t", "b", channel, progress = NotificationProgress.Determinate(60)))
            if (i % 2 == 0) notifier.post(id, LocalNotification("done", "b", channel)) else Unit
            notifier.cancel(id)
            clock += 1.milliseconds
        }
        assertEquals(0, shadowOf(manager).allNotifications.size)
        assertEquals(0, coalescer.postedCountForTest())
        assertEquals(0, coalescer.latestCountForTest())
        assertEquals(0, coalescer.claimCountForTest())
        assertEquals(0, inFlightCount(coalescer))
        assertTrue(notifier.sweptLegacyCountForTest() <= 256, "sweep cache: ${notifier.sweptLegacyCountForTest()}")
    }

    @Test
    fun `thousands of runs that just went quiet are aged out by the clock`() = runTest {
        val clock = TestTimeSource()
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO, timeSource = clock)
        val notifier = AndroidNotifier(
            context, FakePermissionController(defaultStatus = PermissionStatus.Granted), NotificationRenderer(context, "test.ACTION"), coalescer,
        )
        repeat(RUNS) { i: Int ->
            notifier.post("quiet-$i", LocalNotification("t", "b", channel, progress = NotificationProgress.Determinate(30)))
            clock += 1.milliseconds
        }
        assertEquals(RUNS, coalescer.postedCountForTest(), "nothing is old enough yet")
        clock += 30.minutes
        notifier.post("fresh", LocalNotification("t", "b", channel, progress = NotificationProgress.Determinate(30)))
        assertTrue(coalescer.postedCountForTest() <= 2, "still tracking ${coalescer.postedCountForTest()} quiet ids")
        assertTrue(coalescer.latestCountForTest() <= 2, "still tracking ${coalescer.latestCountForTest()} newest tickets")
        assertEquals(0, inFlightCount(coalescer))
        assertEquals(0, coalescer.claimCountForTest())
    }

    private companion object {
        const val RUNS: Int = 3_000
    }
}
