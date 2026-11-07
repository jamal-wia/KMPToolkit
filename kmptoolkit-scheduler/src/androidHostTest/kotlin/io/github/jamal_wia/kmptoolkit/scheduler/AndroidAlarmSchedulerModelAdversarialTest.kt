package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * Random sequences of schedule, replace, cancel, cancelAll, scheduler re-creation and process death,
 * checked against a model of what the OS must hold: one alarm per scheduled id, carrying the content
 * of the last schedule of that id, and none for an id that was cancelled since.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerModelAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grantExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    @Test
    fun `any sequence of schedules cancels and re-creations leaves exactly the alarms the model expects`() {
        val ids: List<String> = listOf("a", "b", "c", "d/e", "d%2Fe", "Ab", "aB")
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            shadowAlarmManager.scheduledAlarms.toList().forEach { armed -> alarmManager.cancel(requireNotNull(armed.operation)) }
            AlarmDispatch.reset()
            var scheduler: AlarmScheduler = createAlarmScheduler(context)
            val model: MutableMap<String, ScheduledAlarm> = mutableMapOf()
            val log = StringBuilder()
            repeat(STEPS) { step: Int ->
                val id: String = ids[random.nextInt(ids.size)]
                when (random.nextInt(8)) {
                    0, 1, 2, 3 -> {
                        val alarm = ScheduledAlarm(
                            id = id,
                            type = "T${random.nextInt(3)}",
                            fireAtEpochMillis = 1_000L + step,
                            notification = AlarmNotification(title = "t$seed-$step", body = "b", channelId = "c"),
                            payload = mapOf("k" to "v$step"),
                        )
                        runBlocking { scheduler.schedule(alarm) }
                        model[id] = alarm
                        log.append("schedule($id,$step); ")
                    }

                    4, 5 -> {
                        runBlocking { scheduler.cancel(id) }
                        model.remove(id)
                        log.append("cancel($id); ")
                    }

                    6 -> {
                        val some: List<String> = ids.filter { random.nextBoolean() }
                        runBlocking { scheduler.cancelAll(some) }
                        some.forEach { model.remove(it) }
                        log.append("cancelAll($some); ")
                    }

                    else -> {
                        if (random.nextBoolean()) AlarmDispatch.reset()
                        scheduler = createAlarmScheduler(context)
                        log.append("recreate; ")
                    }
                }
            }
            val armed: Map<String, ScheduledAlarm> = shadowAlarmManager.scheduledAlarms.associate { alarm: ShadowAlarmManager.ScheduledAlarm ->
                val rebuilt: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(alarm.operation).savedIntent, keys), "seed $seed: unreadable alarm")
                rebuilt.id to rebuilt
            }
            assertEquals(model.keys, armed.keys, "seed $seed: armed ids differ. Steps: $log")
            model.forEach { (id: String, expected: ScheduledAlarm) ->
                assertEquals(expected, armed.getValue(id), "seed $seed: content of '$id' differs. Steps: $log")
            }
        }
    }

    private companion object {
        const val SEEDS: Int = 60
        const val STEPS: Int = 40
    }
}
