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
 * The Android half of the cross-platform differential for the scheduler: the seeded sequences and the
 * model of [SchedulerProtocolFuzz] that `IosAlarmSchedulerProtocolFuzzDifferentialAdversarialTest` runs
 * through the iOS scheduler. Kept to two API levels because the scheduler suite has run out of memory
 * with more sandboxes (round 7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class AndroidAlarmSchedulerProtocolFuzzDifferentialAdversarialTest {

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
            SchedulerProtocolFuzz.IDS.forEach { id: String -> runBlocking { AndroidAlarmScheduler(context, keys).cancel(id) } }
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
    fun `seeded sequences give the answers and the armed alarms of the shared model on both api levels`() {
        runBlocking { withTimeout(120_000) { SchedulerProtocolFuzz.run(host, seeds = 40, steps = 40) } }
    }
}
