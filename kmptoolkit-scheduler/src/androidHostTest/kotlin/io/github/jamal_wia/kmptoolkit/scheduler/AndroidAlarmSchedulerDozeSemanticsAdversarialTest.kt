package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import android.os.Build
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
import org.robolectric.shadows.ShadowAlarmManager

/**
 * What the platform is actually asked for, at the levels where the call set differs: the scheduler's
 * KDoc promises an alarm that still fires in Doze (`setExactAndAllowWhileIdle` and, when exactness is
 * unavailable, `setAndAllowWhileIdle` rather than plain `set`, which "can land hours late"), measured
 * on the wall clock (`RTC_WAKEUP`, so a clock change moves nothing the user asked for), as one-shot
 * alarms. Every earlier suite checks which alarms exist; none looks at how they were requested, so a
 * plain `set` or an `ELAPSED_REALTIME` clock would pass all of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 31, 34])
class AndroidAlarmSchedulerDozeSemanticsAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, at: Long): ScheduledAlarm = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = at, notification = AlarmNotification("t", "b", "c"),
    )

    @Suppress("DEPRECATION")
    private fun assertOneWallClockDozeProofAlarm(expectedAt: Long, label: String) {
        val scheduled: ShadowAlarmManager.ScheduledAlarm = shadowAlarmManager.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, scheduled.type, "$label: the clock")
        assertEquals(expectedAt, scheduled.triggerAtTime, "$label: the time")
        assertEquals(0L, scheduled.interval, "$label: one-shot")
        assertTrue(scheduled.allowWhileIdle, "$label: must still fire in Doze")
    }

    @Test
    fun `an exact alarm is a one-shot wall-clock alarm that fires in Doze`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)

        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("a", 123_456L)) })

        assertOneWallClockDozeProofAlarm(123_456L, "exact on ${Build.VERSION.SDK_INT}")
    }

    @Test
    fun `an inexact alarm is a one-shot wall-clock alarm that fires in Doze too`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return // no exact-alarm permission to lack below 31
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)

        assertEquals(
            AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING),
            runBlocking { scheduler.schedule(alarm("a", 654_321L)) },
        )

        assertOneWallClockDozeProofAlarm(654_321L, "inexact on ${Build.VERSION.SDK_INT}")
    }

    @Test
    fun `moving a reminder earlier and later keeps one alarm of the same kind at the new time`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        for (at: Long in listOf(5_000L, 1_000L, 9_000L, 9_000L, 0L, Long.MAX_VALUE)) {
            assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("a", at)) })
            assertOneWallClockDozeProofAlarm(at, "moved to $at on ${Build.VERSION.SDK_INT}")
        }
    }

    @Test
    fun `losing the exact right between two schedules of one id replaces the exact alarm by one inexact alarm`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("a", 1_000L)) })

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(
            AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING),
            runBlocking { scheduler.schedule(alarm("a", 2_000L)) },
        )
        assertOneWallClockDozeProofAlarm(2_000L, "downgrade on ${Build.VERSION.SDK_INT}")

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("a", 3_000L)) })
        assertOneWallClockDozeProofAlarm(3_000L, "upgrade on ${Build.VERSION.SDK_INT}")
    }
}
