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
 * A seeded operation-sequence fuzzer for the recording scheduler against a plain model of its
 * documented contract (KDoc of [RecordingAlarmScheduler]): every schedule call is recorded in order
 * with the answer the test configured, a replacement keeps the position of the id in armed, a Failed
 * answer arms nothing and leaves an alarm already armed under the id as it was, cancel and cancelAll
 * record every id including unknown and repeated ones, and a payload map the caller edits afterwards
 * never reaches a recording. The seed is in every failure message.
 */
class RecordingAlarmSchedulerModelFuzzAdversarialTest {

    private val answers: List<AlarmScheduleResult> = listOf(
        AlarmScheduleResult.Exact,
        AlarmScheduleResult.Exact,
        AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING),
        AlarmScheduleResult.Failed(AlarmFailure.NotificationPermissionDenied),
        AlarmScheduleResult.Failed(AlarmFailure.SchedulerUnavailable),
        AlarmScheduleResult.Failed(AlarmFailure.PlatformError("p")),
    )

    @Test
    fun `no sequence of schedules cancels and clears makes the recordings disagree with the contract`() = runTest {
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed)
            val scheduler = RecordingAlarmScheduler()
            val calls: MutableList<ScheduledAlarm> = mutableListOf()
            val armed: MutableList<ScheduledAlarm> = mutableListOf()
            val cancelled: MutableList<String> = mutableListOf()
            val log = StringBuilder()
            repeat(STEPS) { step: Int ->
                val where = "seed $seed step $step"
                when (random.nextInt(10)) {
                    0, 1, 2, 3, 4 -> {
                        val id: String = IDS.random(random)
                        val answer: AlarmScheduleResult = answers.random(random)
                        scheduler.resultFor = { answer }
                        val payload: MutableMap<String, String> = mutableMapOf("k" to "v$step")
                        val alarm = ScheduledAlarm(id, "T", random.nextLong(), AlarmNotification("t$step", "b", "c"), payload)
                        val result: AlarmScheduleResult = scheduler.schedule(alarm)
                        log.append("schedule($id,$answer); ")
                        assertEquals(answer, result, "$where: answer. Steps: $log")
                        val snapshot: ScheduledAlarm = alarm.copy(payload = payload.toMap())
                        calls += snapshot
                        if (answer !is AlarmScheduleResult.Failed) {
                            val at: Int = armed.indexOfFirst { it.id == id }
                            if (at >= 0) armed[at] = snapshot else armed += snapshot
                        }
                        payload["late"] = "edit"
                    }

                    5, 6 -> {
                        val id: String = (IDS + "never").random(random)
                        scheduler.cancel(id)
                        armed.removeAll { it.id == id }
                        cancelled += id
                        log.append("cancel($id); ")
                    }

                    7 -> {
                        val ids: List<String> = List(random.nextInt(0, 5)) { (IDS + "never").random(random) }
                        scheduler.cancelAll(ids)
                        ids.forEach { id: String -> armed.removeAll { it.id == id } }
                        cancelled += ids
                        log.append("cancelAll($ids); ")
                    }

                    8 -> if (random.nextInt(3) == 0) {
                        scheduler.clear()
                        calls.clear(); armed.clear(); cancelled.clear()
                        log.append("clear; ")
                    }

                    else -> {
                        // A set live view over the recording must not be able to change it either.
                        val view: List<ScheduledAlarm> = scheduler.armed
                        scheduler.cancel("never")
                        cancelled += "never"
                        assertEquals(armed.toList(), view, "$where: a read before a call is a snapshot. Steps: $log")
                    }
                }
                assertEquals(calls.toList(), scheduler.scheduleCalls, "$where: scheduleCalls. Steps: $log")
                assertEquals(armed.toList(), scheduler.armed, "$where: armed. Steps: $log")
                assertEquals(cancelled.toList(), scheduler.cancelledIds, "$where: cancelledIds. Steps: $log")
            }
        }
    }

    private companion object {
        const val SEEDS: Int = 300
        const val STEPS: Int = 60
        val IDS: List<String> = listOf("a", "b", "c", "d e")
    }
}
