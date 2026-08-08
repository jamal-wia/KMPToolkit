package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.test.runTest
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers the Android scheduler against a real `AlarmManager`: what gets armed, what gets cancelled,
 * and — the part a consumer's own reminder logic hinges on — which [AlarmScheduleResult] each
 * permission state produces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(alarmManager)

    private fun scheduler(config: AlarmSchedulerConfig = AlarmSchedulerConfig()): AlarmScheduler =
        createAlarmScheduler(context = context, config = config)

    private fun alarm(id: String, fireAt: Long): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = fireAt,
        notification = AlarmNotification(title = "t", body = "b", channelId = "chan"),
    )

    @Before
    fun grantExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun clearRegistration() {
        AlarmDispatch.reset()
    }

    @Test
    fun `schedule arms an alarm at the given time and reports it as exact`() = runTest {
        val result: AlarmScheduleResult = scheduler().schedule(alarm("a", 10_000L))

        assertEquals(AlarmScheduleResult.Exact, result)
        val next: ShadowAlarmManager.ScheduledAlarm? = shadowAlarmManager.peekNextScheduledAlarm()
        assertNotNull(next)
        assertEquals(10_000L, next.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, next.type)
    }

    @Test
    fun `a fire time already in the past is still armed`() = runTest {
        val result: AlarmScheduleResult = scheduler().schedule(alarm("a", -5_000L))

        assertEquals(AlarmScheduleResult.Exact, result)
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(-5_000L, shadowAlarmManager.peekNextScheduledAlarm()?.triggerAtTime)
    }

    @Test
    fun `cancel removes the armed alarm`() = runTest {
        val scheduler: AlarmScheduler = scheduler()
        scheduler.schedule(alarm("a", 10_000L))

        scheduler.cancel("a")

        assertNull(shadowAlarmManager.peekNextScheduledAlarm())
    }

    @Test
    fun `cancel is a no-op when nothing is armed for that id`() = runTest {
        // FLAG_NO_CREATE resolves nothing here — this must not throw.
        scheduler().cancel("never-scheduled")

        assertNull(shadowAlarmManager.peekNextScheduledAlarm())
    }

    @Test
    fun `rescheduling the same id replaces the alarm rather than stacking`() = runTest {
        val scheduler: AlarmScheduler = scheduler()

        scheduler.schedule(alarm("a", 10_000L))
        scheduler.schedule(alarm("a", 20_000L))

        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(20_000L, shadowAlarmManager.peekNextScheduledAlarm()?.triggerAtTime)
    }

    @Test
    fun `distinct ids with colliding hash codes stay independent`() = runTest {
        // "Aa" and "BB" both hash to 2112, so the request code alone would collapse them into one
        // PendingIntent; the per-id data URI is what keeps the two alarms apart.
        val scheduler: AlarmScheduler = scheduler()
        scheduler.schedule(alarm("Aa", 10_000L))
        scheduler.schedule(alarm("BB", 20_000L))
        assertEquals(2, shadowAlarmManager.scheduledAlarms.size)

        scheduler.cancel("Aa")

        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(20_000L, shadowAlarmManager.peekNextScheduledAlarm()?.triggerAtTime)
    }

    @Test
    fun `cancelAll cancels every id passed and leaves the others armed`() = runTest {
        val scheduler: AlarmScheduler = scheduler()
        scheduler.schedule(alarm("a", 10_000L))
        scheduler.schedule(alarm("b", 20_000L))
        scheduler.schedule(alarm("c", 30_000L))

        scheduler.cancelAll(listOf("a", "b"))

        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(30_000L, shadowAlarmManager.peekNextScheduledAlarm()?.triggerAtTime)
    }

    @Test
    fun `cancelAll on an empty collection is a no-op`() = runTest {
        val scheduler: AlarmScheduler = scheduler()
        scheduler.schedule(alarm("a", 10_000L))

        scheduler.cancelAll(emptyList())

        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun `cancelAll tolerates ids that were never armed`() = runTest {
        val scheduler: AlarmScheduler = scheduler()
        scheduler.schedule(alarm("a", 10_000L))

        scheduler.cancelAll(listOf("ghost", "a", "ghost-2"))

        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Config(sdk = [30])
    @Test
    fun `below Android S an alarm is always armed exactly`() = runTest {
        // canScheduleExactAlarms() does not exist before API 31 — the scheduler must not gate on it.
        val result: AlarmScheduleResult = scheduler().schedule(alarm("a", 10_000L))

        assertEquals(AlarmScheduleResult.Exact, result)
        assertEquals(WINDOW_EXACT, shadowAlarmManager.peekNextScheduledAlarm()?.windowLengthMs)
    }

    @Config(sdk = [33])
    @Test
    fun `on Android S and later an alarm is armed exactly when the permission is granted`() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        val result: AlarmScheduleResult = scheduler().schedule(alarm("a", 10_000L))

        assertEquals(AlarmScheduleResult.Exact, result)
        assertEquals(WINDOW_EXACT, shadowAlarmManager.peekNextScheduledAlarm()?.windowLengthMs)
    }

    @Config(sdk = [33])
    @Test
    fun `a missing exact-alarm permission degrades to an inexact alarm and is reported`() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result: AlarmScheduleResult = scheduler().schedule(alarm("a", 10_000L))

        assertEquals(AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING), result)
        // Still armed — the caller-visible contract — but through setAndAllowWhileIdle, which
        // AlarmManager reports with a nonzero heuristic window rather than the exact sentinel.
        val next: ShadowAlarmManager.ScheduledAlarm? = shadowAlarmManager.peekNextScheduledAlarm()
        assertNotNull(next)
        assertEquals(10_000L, next.triggerAtTime)
        assertNotEquals(WINDOW_EXACT, next.windowLengthMs)
    }

    @Config(sdk = [33], shadows = [ExactAlarmRevokedShadowAlarmManager::class])
    @Test
    fun `an exact-alarm permission revoked between the check and the call degrades to inexact`() = runTest {
        // The shadow reports the permission as granted and then throws from the exact call — the
        // real Android 12+ race, where the user revokes "Alarms & reminders" mid-flight.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        val result: AlarmScheduleResult = scheduler().schedule(alarm("a", 10_000L))

        assertEquals(AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_REVOKED), result)
        val next: ShadowAlarmManager.ScheduledAlarm? = shadowAlarmManager.peekNextScheduledAlarm()
        assertNotNull(next)
        assertEquals(10_000L, next.triggerAtTime)
    }

    @Test
    fun `the alarm intent scheme defaults to the application id`() = runTest {
        scheduler().schedule(alarm("a", 10_000L))

        // The unit-test application id is this module's namespace plus ".test", and it contains an
        // underscore, which a URI scheme may not — so the derived scheme is the sanitized
        // application id, not a verbatim copy of it.
        assertEquals("io.github.jamal_wia.kmptoolkit.scheduler.test", context.packageName)
        assertEquals("io.github.jamal-wia.kmptoolkit.scheduler.test.alarm", armedIntentScheme())
    }

    @Test
    fun `a configured alarm intent scheme is used instead of the derived one`() = runTest {
        scheduler(AlarmSchedulerConfig(alarmIntentScheme = "custom-scheme")).schedule(alarm("a", 10_000L))

        assertEquals("custom-scheme", armedIntentScheme())
    }

    private fun armedIntentScheme(): String? {
        val operation: PendingIntent? = shadowAlarmManager.peekNextScheduledAlarm()?.operation
        assertNotNull(operation)
        return shadowOf(operation).savedIntent.data?.scheme
    }

    /** Reports exact alarms as permitted but rejects the exact call, as a mid-flight revoke does. */
    @Implements(AlarmManager::class)
    class ExactAlarmRevokedShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            throw SecurityException("Caller needs SCHEDULE_EXACT_ALARM permission")
        }
    }

    private companion object {
        const val WINDOW_EXACT = 0L
    }
}
