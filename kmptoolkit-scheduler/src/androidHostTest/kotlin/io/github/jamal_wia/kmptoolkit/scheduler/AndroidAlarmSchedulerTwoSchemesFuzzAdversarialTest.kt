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
 * Two modules of one app (two configured schemes) use the same ids: an alarm is identified by scheme
 * and id together, so schedules, replacements, cancels and cancelAll of one scheme never touch the other
 * scheme's alarm of the same id, whichever instances are created in between. A seeded sequence checked
 * against a model with one alarm map per scheme. The seed is in every failure message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerTwoSchemesFuzzAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(alarmManager)

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private fun keysOf(scheme: String): AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(alarmIntentScheme = scheme), context.packageName)

    @Test
    fun `alarms of two schemes with the same ids never see each other`() {
        val schemes: List<String> = listOf("modulea", "moduleb")
        val keys: Map<String, AlarmIntentKeys> = schemes.associateWith { keysOf(it) }
        val ids: List<String> = listOf("x", "y", "x/y")
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed)
            shadowAlarmManager.scheduledAlarms.toList().forEach { alarmManager.cancel(requireNotNull(it.operation)) }
            for (scheme: String in schemes) for (id: String in ids) runBlocking { AndroidAlarmScheduler(context, keys.getValue(scheme)).cancel(id) }
            val schedulers: MutableMap<String, AlarmScheduler> = schemes.associateWith { AndroidAlarmScheduler(context, keys.getValue(it)) as AlarmScheduler }.toMutableMap()
            val model: Map<String, MutableMap<String, ScheduledAlarm>> = schemes.associateWith { mutableMapOf() }
            val log = StringBuilder()
            repeat(STEPS) { step: Int ->
                val scheme: String = schemes.random(random)
                val id: String = ids.random(random)
                when (random.nextInt(8)) {
                    0, 1, 2, 3 -> {
                        val alarm = ScheduledAlarm(id, "T", 100L + step, AlarmNotification("$scheme-$step", "b", "c"), mapOf("scheme" to scheme))
                        runBlocking { schedulers.getValue(scheme).schedule(alarm) }
                        model.getValue(scheme)[id] = alarm
                        log.append("schedule($scheme,$id); ")
                    }

                    4, 5 -> {
                        runBlocking { schedulers.getValue(scheme).cancel(id) }
                        model.getValue(scheme).remove(id)
                        log.append("cancel($scheme,$id); ")
                    }

                    6 -> {
                        val some: List<String> = ids.filter { random.nextBoolean() }
                        runBlocking { schedulers.getValue(scheme).cancelAll(some) }
                        some.forEach { model.getValue(scheme).remove(it) }
                        log.append("cancelAll($scheme,$some); ")
                    }

                    else -> {
                        schedulers[scheme] = AndroidAlarmScheduler(context, keys.getValue(scheme))
                        log.append("recreate($scheme); ")
                    }
                }
                // The scheme is what tells the two apart: the URI of every armed alarm carries its own, and the
                // payload names the scheme that asked for it, so the two must always agree.
                val armedByScheme: Map<String, Map<String, ScheduledAlarm>> = shadowAlarmManager.scheduledAlarms
                    .map { a: ShadowAlarmManager.ScheduledAlarm ->
                        val intent = shadowOf(a.operation).savedIntent
                        val alarm: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(intent, keys.getValue(schemes.first())))
                        assertEquals(alarm.payload["scheme"], intent.data?.scheme, "seed $seed step $step: an alarm armed under another scheme. Steps: $log")
                        alarm
                    }
                    .groupBy { it.payload.getValue("scheme") }
                    .mapValues { (_, list: List<ScheduledAlarm>) -> list.associateBy { it.id } }
                for (check: String in schemes) {
                    assertEquals(model.getValue(check).toMap(), armedByScheme[check].orEmpty(), "seed $seed step $step: scheme $check differs. Steps: $log")
                }
                assertEquals(
                    model.values.sumOf { it.size }, shadowAlarmManager.scheduledAlarms.size,
                    "seed $seed step $step: the platform holds another number of alarms than the two models together. Steps: $log",
                )
                assertNotNull(shadowAlarmManager.scheduledAlarms)
            }
        }
    }

    private companion object {
        const val SEEDS: Int = 80
        const val STEPS: Int = 40
    }
}
