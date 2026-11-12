package io.github.jamal_wia.kmptoolkit.scheduler.testing

import io.github.jamal_wia.kmptoolkit.scheduler.AlarmFailure
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmNotification
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmScheduleResult
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmScheduler
import io.github.jamal_wia.kmptoolkit.scheduler.InexactReason
import io.github.jamal_wia.kmptoolkit.scheduler.ScheduledAlarm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * Round 20 (consumer setups): how a consumer's own tests use [RecordingAlarmScheduler] under `runTest`
 * with virtual time, a result that changes between calls, a cancelled collaborator and a decorator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingAlarmSchedulerConsumerVirtualTimeAdversarialTest {

    private fun alarm(id: String, at: Long, payload: Map<String, String> = emptyMap()): ScheduledAlarm =
        ScheduledAlarm(id, "T", at, AlarmNotification("t", "b", "c"), payload)

    private class Reminders(private val scheduler: AlarmScheduler) {
        suspend fun dailyFor(days: Int, startAt: Long) {
            for (day in 0 until days) {
                scheduler.schedule(ScheduledAlarm("day-$day", "T", startAt + day * 86_400_000L, AlarmNotification("t", "b", "c")))
                delay(10.minutes)
            }
        }
    }

    @Test
    fun `a consumer that waits between schedules runs in virtual time and the double adds no time`() = runTest {
        val scheduler = RecordingAlarmScheduler()

        Reminders(scheduler).dailyFor(days = 30, startAt = 1_000L)

        assertEquals(30, scheduler.armed.size)
        assertEquals((0 until 30).map { "day-$it" }, scheduler.armed.map { it.id })
        assertEquals(30 * 10 * 60_000L, currentTime)
    }

    @Test
    fun `a collaborator cancelled halfway leaves exactly the alarms it had scheduled`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        val job: Job = launch(StandardTestDispatcher(testScheduler)) { Reminders(scheduler).dailyFor(30, 1_000L) }

        advanceTimeBy(10 * 60_000L * 12 + 1)
        job.cancel()
        job.join()

        assertEquals(13, scheduler.scheduleCalls.size, "day 0 at t=0 then one every ten minutes up to t=120")
        assertEquals(13, scheduler.armed.size)
    }

    @Test
    fun `a refused replacement keeps the alarm that was armed and a later success replaces it`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        scheduler.schedule(alarm("a", 100L, mapOf("v" to "1")))

        scheduler.resultFor = { AlarmScheduleResult.Failed(AlarmFailure.PlatformError("no")) }
        val refused: AlarmScheduleResult = scheduler.schedule(alarm("a", 200L, mapOf("v" to "2")))
        assertTrue(refused is AlarmScheduleResult.Failed)
        assertEquals(100L, scheduler.armed.single().fireAtEpochMillis, "the real schedulers leave a refused replacement's predecessor as it was")
        assertEquals(2, scheduler.scheduleCalls.size)

        scheduler.resultFor = { AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING) }
        scheduler.schedule(alarm("a", 300L, mapOf("v" to "3")))
        assertEquals(mapOf("v" to "3"), scheduler.armed.single().payload)
    }

    @Test
    fun `cancelAll with the ids of the armed list is safe and records every id once`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        listOf("a", "b", "c").forEach { scheduler.schedule(alarm(it, 1L)) }

        scheduler.cancelAll(scheduler.armed.map { it.id })

        assertTrue(scheduler.armed.isEmpty())
        assertEquals(listOf("a", "b", "c"), scheduler.cancelledIds)
    }

    @Test
    fun `a decorator over the double sees every call and a cancelled call does not reach it`() = runTest {
        val inner = RecordingAlarmScheduler()
        val calls: MutableList<String> = mutableListOf()
        val logging: AlarmScheduler = object : AlarmScheduler {
            override suspend fun schedule(alarm: ScheduledAlarm): AlarmScheduleResult {
                calls += "schedule ${alarm.id}"
                delay(1.minutes)
                return inner.schedule(alarm)
            }
            override suspend fun cancel(id: String) { calls += "cancel $id"; inner.cancel(id) }
            override suspend fun cancelAll(ids: Collection<String>) { calls += "cancelAll ${ids.size}"; inner.cancelAll(ids) }
        }

        val job: Job = launch(StandardTestDispatcher(testScheduler)) { logging.schedule(alarm("slow", 1L)) }
        advanceTimeBy(30_000)
        job.cancel()
        job.join()

        assertEquals(listOf("schedule slow"), calls)
        assertTrue(inner.scheduleCalls.isEmpty(), "cancelled before the inner call: nothing recorded")
        assertTrue(job.isCancelled)
        try {
            logging.cancelAll(listOf("x", "y"))
        } catch (e: CancellationException) {
            throw AssertionError("a plain cancelAll must not be cancelled", e)
        }
        assertEquals(listOf("x", "y"), inner.cancelledIds)
    }
}
