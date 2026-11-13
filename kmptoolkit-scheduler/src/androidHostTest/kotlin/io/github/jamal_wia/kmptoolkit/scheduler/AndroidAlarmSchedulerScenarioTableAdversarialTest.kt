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
 * Round 23: the hand-written scenario table ([SchedulerScenarioTable]) through the real Android scheduler and
 * `RecordingAlarmScheduler`, below and above the level at which exact alarms need a permission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class AndroidAlarmSchedulerScenarioTableAdversarialTest {

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
            runBlocking { AndroidAlarmScheduler(context, keys).cancelAll(SchedulerScenarioTable.IDS) }
            shadowOf(alarmManager).scheduledAlarms.toList().forEach { alarmManager.cancel(requireNotNull(it.operation)) }
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
    fun `every row of the scenario table gives the documented answer and the same armed set in the real scheduler and in the double`() {
        runBlocking { withTimeout(120_000) { SchedulerScenarioTable.run(host) } }
    }
}
