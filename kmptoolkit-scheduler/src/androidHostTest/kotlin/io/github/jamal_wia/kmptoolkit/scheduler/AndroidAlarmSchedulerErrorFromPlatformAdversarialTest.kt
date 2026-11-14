package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Round 15 (resource starvation, low-memory style failures): `AlarmManager` throws an `OutOfMemoryError`
 * while it marshals the request (a huge payload is exactly what makes a real one do that). The contract
 * only promises results for refusals, so the test accepts a `Failed` or the Error itself - but "a refused
 * replacement leaves the alarm that was armed exactly as it was" and "Failed: nothing was armed" must hold
 * for it, and the id must not be left unusable: the four-slot bookkeeping ("when all four slots are
 * occupied ... `schedule` answers Failed(PlatformError)") is about a *cancel the platform refused*, not about
 * a set that never happened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerErrorFromPlatformAdversarialTest.ErrorShadowAlarmManager::class])
class AndroidAlarmSchedulerErrorFromPlatformAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ErrorShadowAlarmManager.failures.set(0)
    }

    @After
    fun tearDown() {
        ErrorShadowAlarmManager.failures.set(0)
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, title: String, at: Long = 1_900_000_000_000L): ScheduledAlarm =
        ScheduledAlarm(id, "T", at, AlarmNotification(title, "b", "c"))

    private fun attempt(scheduler: AlarmScheduler, alarm: ScheduledAlarm): Result<AlarmScheduleResult> =
        runCatching { runBlocking { withTimeout(20_000) { scheduler.schedule(alarm) } } }

    @Test
    fun `Errors from the platform at the set call never make the id unschedulable and never change the armed alarm`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("a", "original")) })

        for (round in 1..6) {
            ErrorShadowAlarmManager.failures.set(1)
            val outcome: Result<AlarmScheduleResult> = attempt(scheduler, alarm("a", "attempt-$round", at = 1_900_000_000_000L + round))
            val thrown: Throwable? = outcome.exceptionOrNull()
            assertTrue(thrown == null || thrown is OutOfMemoryError, "round $round: $thrown")
            if (thrown == null) assertTrue(outcome.getOrNull() is AlarmScheduleResult.Failed, "round $round: ${outcome.getOrNull()}")
            assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "round $round: the armed alarm must still be the only one")
            assertEquals(
                1_900_000_000_000L,
                shadowAlarmManager.scheduledAlarms.single().triggerAtMs,
                "round $round: a failed replacement must leave the armed alarm exactly as it was",
            )
        }

        val healthy: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm("a", "healthy", at = 1_900_000_000_999L)) }

        assertEquals(AlarmScheduleResult.Exact, healthy, "six failed sets of the platform must not saturate the id")
        assertEquals(listOf(1_900_000_000_999L), shadowAlarmManager.scheduledAlarms.map { it.triggerAtMs })
    }

    @Test
    fun `Errors on the first schedule of an id leave nothing behind and the id schedules normally afterwards`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)

        repeat(6) {
            ErrorShadowAlarmManager.failures.set(1)
            attempt(scheduler, alarm("fresh", "t"))
        }

        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("fresh", "ok")) })
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
        runBlocking { scheduler.cancel("fresh") }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Implements(AlarmManager::class)
    class ErrorShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent?) {
            if (failures.getAndUpdate { if (it > 0) it - 1 else it } > 0) throw OutOfMemoryError("r15")
            super.setExactAndAllowWhileIdle(type, triggerAtMillis, operation)
        }

        companion object {
            val failures: AtomicInteger = AtomicInteger(0)
        }
    }
}
