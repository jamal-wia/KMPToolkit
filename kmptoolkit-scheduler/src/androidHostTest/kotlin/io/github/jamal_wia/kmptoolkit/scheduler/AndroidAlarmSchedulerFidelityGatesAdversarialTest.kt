package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import io.github.jamal_wia.kmptoolkit.scheduler.testing.RecordingAlarmScheduler
import kotlin.random.Random
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
 * Round 18 (fake fidelity): the three answers a real Android scheduler gives (exact, inexact because the
 * permission is missing, failed because the service is gone), each scripted into RecordingAlarmScheduler
 * the way a consumer would, over seeded sequences of replacements. Contract (docs 04): an inexact alarm
 * IS armed; a failed one is not, and it does not disturb an alarm already armed under the same id; the
 * double's own doc says the same ("an alarm whose result is Failed is not armed at all").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerFidelityGatesAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    private class NoAlarmServiceContext(base: Context) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any? = if (name == Context.ALARM_SERVICE) null else super.getSystemService(name)
        override fun getApplicationContext(): Context = this
    }

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private val ids: List<String> = listOf("a", "b c", "é", "x/y")

    private fun systemView(): Map<String, ArmedView> =
        shadowOf(alarmManager).scheduledAlarms.associate { armed: ShadowAlarmManager.ScheduledAlarm ->
            val alarm: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys))
            alarm.id to ArmedView(alarm.type, alarm.notification.title, alarm.notification.body, alarm.payload)
        }

    @Test
    fun `exact then inexact then failed answers leave the double and the system with the same armed alarms`() {
        runBlocking {
            withTimeout(120_000) {
                repeat(40) { seed: Int ->
                    val random = Random(seed + 991)
                    shadowOf(alarmManager).scheduledAlarms.toList().forEach { alarmManager.cancel(requireNotNull(it.operation)) }
                    AndroidAlarmScheduler(context, keys).cancelAll(ids)
                    AlarmDispatch.reset()
                    val fake = RecordingAlarmScheduler()
                    val seen: MutableSet<String> = mutableSetOf()
                    val log = StringBuilder()
                    repeat(40) { step: Int ->
                        val where = "seed $seed step $step"
                        val mode: Int = random.nextInt(4)
                        val id: String = ids.random(random)
                        if (random.nextInt(6) == 0) {
                            AndroidAlarmScheduler(context, keys).cancel(id)
                            fake.cancel(id)
                            log.append("cancel($id); ")
                        } else {
                            val alarm = ScheduledAlarm(
                                id = id,
                                type = "T",
                                fireAtEpochMillis = 1_900_000_000_000L + step,
                                notification = AlarmNotification("t$step", "b$mode", "c"),
                                payload = if (mode % 2 == 0) emptyMap() else mapOf("m" to "$mode"),
                            )
                            ShadowAlarmManager.setCanScheduleExactAlarms(mode != 1)
                            val scheduler: AlarmScheduler =
                                if (mode == 2) AndroidAlarmScheduler(NoAlarmServiceContext(context), keys) else AndroidAlarmScheduler(context, keys)
                            val answered: AlarmScheduleResult = scheduler.schedule(alarm)
                            val expected: AlarmScheduleResult = when (mode) {
                                1 -> AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING)
                                2 -> AlarmScheduleResult.Failed(AlarmFailure.SchedulerUnavailable)
                                else -> AlarmScheduleResult.Exact
                            }
                            log.append("schedule($id,mode=$mode)=${answered::class.simpleName}; ")
                            assertEquals(expected, answered, "$where: real answer. Steps: $log")
                            fake.resultFor = { answered }
                            assertEquals(answered, fake.schedule(alarm), "$where: the double answers what it was told. Steps: $log")
                            if (answered !is AlarmScheduleResult.Failed) seen += id
                        }
                        val double: Map<String, ArmedView> = fake.armed.associate { alarm: ScheduledAlarm ->
                            alarm.id to ArmedView(alarm.type, alarm.notification.title, alarm.notification.body, alarm.payload)
                        }
                        assertEquals(systemView(), double, "$where: armed alarms differ between the system and the double. Steps: $log")
                    }
                }
            }
        }
    }
}
