package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import kotlin.test.Test
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
 * RecordingAlarmScheduler against the real Android scheduler: one seeded script through both, the
 * double told what the real scheduler answered, and both must agree on what is armed after every step
 * ([SchedulerFidelityDriver]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class AndroidAlarmSchedulerFidelityDifferentialAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private val host = object : SchedulerFuzzHost {
        override fun reset() {
            shadowOf(alarmManager).scheduledAlarms.toList().forEach { alarmManager.cancel(requireNotNull(it.operation)) }
            (SchedulerProtocolFuzz.IDS + listOf("\uD800", "\uDC00", "x\uD83D", "?")).forEach { id: String -> runBlocking { AndroidAlarmScheduler(context, keys).cancel(id) } }
            AlarmDispatch.reset()
        }

        override fun newScheduler(): AlarmScheduler = createAlarmScheduler(context)

        override fun armed(ids: List<String>): Map<String, ArmedView> =
            shadowOf(alarmManager).scheduledAlarms.associate { armed: ShadowAlarmManager.ScheduledAlarm ->
                val alarm: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys))
                alarm.id to ArmedView(alarm.type, alarm.notification.title, alarm.notification.body, alarm.payload)
            }
    }

    @Test
    fun `the double and the real scheduler agree on the armed alarms over seeded scripts`() {
        runBlocking { withTimeout(120_000) { SchedulerFidelityDriver.run(host, seeds = 30, steps = 50) } }
    }
}
