package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * Round 24: the 30-day virtual-clock model of [SchedulerLongHorizon] through the real Android scheduler at API
 * 24 (no exact-alarm right to lose) and 34 (the right flips between granted and refused). The system fires an
 * alarm the way a real one does: the alarm leaves the manager, its `PendingIntent` stays; a reboot drops both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class AndroidAlarmSchedulerLongHorizonClockAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AlarmDispatch.reset()
    }

    private fun alarmIdOf(armed: ShadowAlarmManager.ScheduledAlarm): String =
        assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys)).id

    private val host = object : SchedulerHorizonHost {
        override fun baseEpochMillis(): Long = 1_700_000_000_000L

        override fun realNowMillis(): Long = 0L

        override fun reset() {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            wipe()
            AlarmDispatch.reset()
        }

        override fun newScheduler(): AlarmScheduler = createAlarmScheduler(context)

        override fun setPermission(granted: Boolean) {
            ShadowAlarmManager.setCanScheduleExactAlarms(granted)
        }

        override fun expectedAnswer(granted: Boolean): AlarmScheduleResult =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted) {
                AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING)
            } else {
                AlarmScheduleResult.Exact
            }

        override val wipesOnReboot: Boolean = true

        override fun wipe() {
            shadowOf(alarmManager).scheduledAlarms.toList().forEach { alarm: ShadowAlarmManager.ScheduledAlarm ->
                alarmManager.cancel(requireNotNull(alarm.operation))
                alarm.operation?.cancel()
            }
            // Pending intents of alarms that already fired outlive the alarm: a reboot drops those too.
            runBlocking { SchedulerLongHorizonIds.ALL.forEach { id: String -> AndroidAlarmScheduler(context, keys).cancel(id) } }
        }

        override fun armed(): Map<String, ArmedView> {
            val alarms: List<ShadowAlarmManager.ScheduledAlarm> = shadowOf(alarmManager).scheduledAlarms
            val ids: List<String> = alarms.map { alarmIdOf(it) }
            assertEquals(ids.distinct(), ids, "an id holds more than one alarm in the manager: $ids")
            return alarms.associate { armed: ShadowAlarmManager.ScheduledAlarm ->
                val alarm: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys))
                alarm.id to ArmedView(alarm.type, alarm.notification.title, alarm.notification.body, alarm.payload)
            }
        }

        override fun fire(ids: Collection<String>) {
            shadowOf(alarmManager).scheduledAlarms.toList()
                .filter { alarmIdOf(it) in ids }
                .forEach { alarmManager.cancel(requireNotNull(it.operation)) }
        }

        override fun timeProblem(id: String, fireAt: Long, beforeReal: Long, afterReal: Long): String? {
            val mine: ShadowAlarmManager.ScheduledAlarm =
                shadowOf(alarmManager).scheduledAlarms.single { alarmIdOf(it) == id }
            return if (mine.triggerAtTime == fireAt) null else "triggerAtTime ${mine.triggerAtTime} instead of $fireAt"
        }
    }

    @Test
    fun `seeded 30 day sequences give the answers and the armed alarms of the documented model`() {
        runBlocking {
            withTimeout(120_000) {
                val tally: SchedulerLongHorizon.Tally = SchedulerLongHorizon.run(host, seeds = 6, steps = 400)
                SchedulerLongHorizon.assertExercised(tally, host)
            }
        }
    }
}
