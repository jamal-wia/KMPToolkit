package io.github.jamal_wia.kmptoolkit.scheduler.testing

import io.github.jamal_wia.kmptoolkit.scheduler.AlarmFailure
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmNotification
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmScheduleResult
import io.github.jamal_wia.kmptoolkit.scheduler.InexactReason
import io.github.jamal_wia.kmptoolkit.scheduler.ScheduledAlarm
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Round 12 (scale and documentation as a test) for RecordingAlarmScheduler: fifty thousand seeded operations
 * against a reference model (replace keeps the place, a failed result arms nothing and leaves an armed
 * alarm armed, cancel of an unarmed id is recorded), and the two samples of the testing guide.
 */
class RecordingAlarmSchedulerScaleAdversarialTest {

    private fun alarm(id: String, at: Long): ScheduledAlarm = ScheduledAlarm(id, "T", at, AlarmNotification("t", "b", "c"))

    @Test
    fun fiftyThousandSeededOperationsAgreeWithAReferenceModel() = runTest {
        val random = Random(12)
        val scheduler = RecordingAlarmScheduler()
        val armed: MutableMap<String, Long> = linkedMapOf()
        var schedules = 0
        val cancelled: MutableList<String> = mutableListOf()
        repeat(50_000) { step ->
            val id = "id-${random.nextInt(400)}"
            when (random.nextInt(100)) {
                in 0 until 65 -> {
                    val verdict: AlarmScheduleResult = when (random.nextInt(8)) {
                        0 -> AlarmScheduleResult.Failed(AlarmFailure.PlatformError("x"))
                        1 -> AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING)
                        else -> AlarmScheduleResult.Exact
                    }
                    scheduler.resultFor = { verdict }
                    assertEquals(verdict, scheduler.schedule(alarm(id, step.toLong())))
                    schedules++
                    if (verdict !is AlarmScheduleResult.Failed) armed[id] = step.toLong()
                }
                in 65 until 90 -> {
                    scheduler.cancel(id)
                    cancelled += id
                    armed.remove(id)
                }
                else -> {
                    val ids: List<String> = List(random.nextInt(0, 5)) { "id-${random.nextInt(400)}" }
                    scheduler.cancelAll(ids)
                    cancelled += ids
                    ids.forEach { armed.remove(it) }
                }
            }
        }
        assertEquals(schedules, scheduler.scheduleCalls.size)
        assertEquals(cancelled, scheduler.cancelledIds)
        assertEquals(armed.keys.toList(), scheduler.armed.map { it.id }, "armed keeps the order ids were first scheduled in")
        assertEquals(armed.values.toList(), scheduler.armed.map { it.fireAtEpochMillis })
        scheduler.clear()
        assertEquals(0, scheduler.armed.size + scheduler.scheduleCalls.size + scheduler.cancelledIds.size)
    }

    @Test
    fun theTwoSamplesOfTheTestingGuideRunAsWritten() = runTest {
        val scheduler = RecordingAlarmScheduler()
        val reminders: List<String> = listOf("reminder-morning", "reminder-evening")
        reminders.forEach { scheduler.schedule(alarm(it, 1L)) }
        assertEquals(reminders, scheduler.armed.map { it.id })

        val degraded = RecordingAlarmScheduler(
            resultFor = { AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING) },
        )
        val result: AlarmScheduleResult = degraded.schedule(alarm("medication", 1L))
        assertEquals(AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING), result)
        assertEquals(1, degraded.armed.size, "an inexact alarm is armed")
    }
}
