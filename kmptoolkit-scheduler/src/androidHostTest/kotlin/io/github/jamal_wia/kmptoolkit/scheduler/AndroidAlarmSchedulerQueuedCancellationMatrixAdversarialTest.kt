package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Equivalence audit, guarantee "cancellation is not a platform failure and leaves nothing behind",
 * for every suspending function of `AlarmScheduler` on Android (the iOS column is
 * `IosAlarmSchedulerQueuedCancellationMatrixAdversarialTest`): a call cancelled while it waits for the
 * process-wide lock ends cancelled, changes nothing once the lock frees, and does not poison the lock.
 * Only `schedule` had such a test on Android before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerQueuedCancellationMatrixAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
    private var returnedNormally: Boolean = false

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private class BlockingPayload(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : AbstractMap<String, String>() {
        override val entries: Set<Map.Entry<String, String>>
            get() {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return emptySet()
            }
    }

    private fun alarm(id: String, payload: Map<String, String> = emptyMap()): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = 5L,
        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
        payload = payload,
    )

    private fun armedIds(): Set<String> = shadowAlarmManager.scheduledAlarms
        .mapNotNull { AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)?.id }.toSet()

    /** Runs [queued] behind a call that holds the lock, cancels it while it waits, then frees the lock. */
    private fun cancelledWhileQueued(scheduler: AlarmScheduler, queued: suspend (AlarmScheduler) -> Unit): Job {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder: Thread = thread(name = "kmptoolkit-adversarial-holder") {
            runBlocking { scheduler.schedule(alarm("holder", BlockingPayload(entered, release))) }
        }
        lateinit var job: Job
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the holder never reached the platform")
            runBlocking {
                withTimeout(10_000L) {
                    job = launch(start = CoroutineStart.UNDISPATCHED) { queued(scheduler); returnedNormally = true }
                    job.cancel()
                    job.join()
                }
            }
        } finally {
            release.countDown()
            holder.join(TimeUnit.SECONDS.toMillis(5))
        }
        return job
    }

    @Test
    fun `a schedule cancelled while queued ends cancelled and arms nothing`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)

        val job: Job = cancelledWhileQueued(scheduler) { it.schedule(alarm("queued")) }

        assertTrue(job.isCancelled)
        assertFalse(returnedNormally, "the cancelled call returned normally instead of ending cancelled")
        assertEquals(setOf("holder"), armedIds())
    }

    @Test
    fun `a cancel cancelled while queued ends cancelled and leaves the alarm armed`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        runBlocking { scheduler.schedule(alarm("victim")) }

        val job: Job = cancelledWhileQueued(scheduler) { it.cancel("victim") }

        assertTrue(job.isCancelled)
        assertFalse(returnedNormally, "the cancelled call returned normally instead of ending cancelled")
        assertEquals(setOf("victim", "holder"), armedIds())
    }

    @Test
    fun `a cancelAll cancelled while queued ends cancelled and leaves every alarm armed`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        runBlocking { listOf("a", "b").forEach { scheduler.schedule(alarm(it)) } }

        val job: Job = cancelledWhileQueued(scheduler) { it.cancelAll(listOf("a", "b")) }

        assertTrue(job.isCancelled)
        assertFalse(returnedNormally, "the cancelled call returned normally instead of ending cancelled")
        assertEquals(setOf("a", "b", "holder"), armedIds())
    }

    @Test
    fun `the lock stays usable after each cancelled call`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        cancelledWhileQueued(scheduler) { it.schedule(alarm("q1")) }
        cancelledWhileQueued(scheduler) { it.cancel("q1") }
        cancelledWhileQueued(scheduler) { it.cancelAll(listOf("q1")) }

        runBlocking {
            withTimeout(10_000L) {
                assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm("after")))
                scheduler.cancel("after")
            }
        }

        assertEquals(setOf("holder"), armedIds())
    }
}
