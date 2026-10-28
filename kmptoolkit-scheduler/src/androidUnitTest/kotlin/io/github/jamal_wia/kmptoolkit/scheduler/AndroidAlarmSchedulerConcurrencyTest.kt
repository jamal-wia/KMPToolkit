package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * [AlarmScheduler]'s "safe to call from any thread" and "calling this twice with one id leaves exactly
 * one alarm armed, at the later call's time", for calls that genuinely overlap.
 *
 * Arming one alarm is two platform steps — resolve the id's `PendingIntent`, which with
 * `FLAG_UPDATE_CURRENT` rewrites its extras, then hand it to `AlarmManager` with a time. Two schedules
 * of one id interleaving those steps would arm one call's time carrying the other call's content. The
 * shadow below parks the first schedule between the two steps, so the second runs entirely inside that
 * window; the two calls go through two scheduler instances, as two `createAlarmScheduler` calls would
 * produce, so per-instance state cannot be what serializes them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerConcurrencyTest.PausingShadowAlarmManager::class])
class AndroidAlarmSchedulerConcurrencyTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grantExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun clearRegistration() {
        ExactCheckGate.reset()
        AlarmDispatch.reset()
    }

    @Test
    fun `two overlapping schedules of one id arm one whole alarm, never one's time with the other's content`() {
        val first: AlarmScheduler = createAlarmScheduler(context)
        val second: AlarmScheduler = createAlarmScheduler(context)
        val gate: ExactCheckGate.Pause = ExactCheckGate.pauseNextCheck()

        val firstCall: Thread = thread { runBlocking { first.schedule(alarm(fireAt = 10_000L, title = "first")) } }
        assertTrue(gate.entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first schedule reached the window")
        val secondCall: Thread = thread { runBlocking { second.schedule(alarm(fireAt = 20_000L, title = "second")) } }
        // Unserialized, the second schedule completes inside the window; serialized, it waits for the
        // first. Either way the window is held open long enough for the second call to use it.
        secondCall.join(TimeUnit.SECONDS.toMillis(1))
        gate.release.countDown()
        firstCall.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        secondCall.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "one id, one alarm")
        val armed: ShadowAlarmManager.ScheduledAlarm = assertNotNull(shadowAlarmManager.peekNextScheduledAlarm())
        val carried: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys))
        assertEquals(
            armed.triggerAtTime,
            carried.fireAtEpochMillis,
            "the armed time (${armed.triggerAtTime}) and the content it fires with ('${carried.notification.title}') " +
                "must come from the same schedule call",
        )
    }

    private fun alarm(fireAt: Long, title: String): ScheduledAlarm = ScheduledAlarm(
        id = "same-id",
        type = "TEST",
        fireAtEpochMillis = fireAt,
        notification = AlarmNotification(title = title, body = "b", channelId = "chan"),
    )

    /** Parks the first exact-permission check — made between resolving the intent and arming it. */
    @Implements(AlarmManager::class)
    class PausingShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun canScheduleExactAlarms(): Boolean {
            ExactCheckGate.awaitIfPaused()
            return super.canScheduleExactAlarms()
        }
    }

    /** Coordination between the test thread and the shadow, which Robolectric instantiates itself. */
    private object ExactCheckGate {

        class Pause {
            val entered: CountDownLatch = CountDownLatch(1)
            val release: CountDownLatch = CountDownLatch(1)
        }

        private val next: AtomicReference<Pause?> = AtomicReference(null)

        fun pauseNextCheck(): Pause = Pause().also { next.set(it) }

        /** Only the first caller after [pauseNextCheck] parks; every later one passes straight through. */
        fun awaitIfPaused() {
            val pause: Pause = next.getAndSet(null) ?: return
            pause.entered.countDown()
            pause.release.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }

        fun reset() {
            next.set(null)
        }
    }

    private companion object {
        const val WAIT_SECONDS: Long = 5L
    }
}
