package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

/**
 * A caller tears alarms down with the set of ids it tracks, and that set changes while the first
 * platform cancel is still running (a screen adding an id, a sync removing one). The iOS scheduler
 * copies the ids before it starts; the Android one must not die half way through them either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerCancelAllSnapshotAdversarialTest.PausingShadowAlarmManager::class])
class AndroidAlarmSchedulerCancelAllSnapshotAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun grantExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        PausingShadowAlarmManager.pause.set(null)
        AlarmDispatch.reset()
    }

    private fun alarm(id: String): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = id, body = "b", channelId = "chan"),
    )

    @Test
    fun `cancelAll over a list that grows while the first cancel runs still cancels every id and does not throw`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        runBlocking { listOf("a", "b", "c").forEach { scheduler.schedule(alarm(it)) } }
        val tracked: MutableList<String> = mutableListOf("a", "b", "c")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        PausingShadowAlarmManager.pause.set(entered to release)
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        val worker: Thread = thread(name = "kmptoolkit-adversarial-cancelall") {
            runCatching { runBlocking { scheduler.cancelAll(tracked) } }.onFailure { failure.set(it) }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the first cancel never reached the platform")

        synchronized(tracked) { tracked += "d" }
        release.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(5))

        assertTrue(!worker.isAlive, "cancelAll is stuck")
        assertEquals(null, failure.get(), "cancelAll died half way: ${failure.get()}")
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size, "ids after the failure point stayed armed")
    }

    @Implements(AlarmManager::class)
    class PausingShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            pause.getAndSet(null)?.let { (entered, release) ->
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            super.cancel(operation)
        }

        companion object {
            val pause: AtomicReference<Pair<CountDownLatch, CountDownLatch>?> = AtomicReference(null)
        }
    }
}
