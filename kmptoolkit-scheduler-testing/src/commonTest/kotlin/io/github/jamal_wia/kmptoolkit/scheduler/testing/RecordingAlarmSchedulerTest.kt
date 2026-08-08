package io.github.jamal_wia.kmptoolkit.scheduler.testing

import io.github.jamal_wia.kmptoolkit.scheduler.AlarmFailure
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmNotification
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmScheduleResult
import io.github.jamal_wia.kmptoolkit.scheduler.InexactReason
import io.github.jamal_wia.kmptoolkit.scheduler.ScheduledAlarm
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fixture is only useful if it behaves like the thing it stands in for, so these assert the two
 * places [RecordingAlarmScheduler] could quietly lie about the real schedulers: replacement by id
 * and "a failed schedule armed nothing".
 */
class RecordingAlarmSchedulerTest {

    private fun alarm(id: String, fireAt: Long = 1L): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "REMINDER",
        fireAtEpochMillis = fireAt,
        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
    )

    @Test
    fun `scheduling records the alarm and reports exact by default`() = runTest {
        val scheduler = RecordingAlarmScheduler()

        val result: AlarmScheduleResult = scheduler.schedule(alarm("a"))

        assertEquals(AlarmScheduleResult.Exact, result)
        assertEquals(listOf("a"), scheduler.armed.map { it.id })
    }

    @Test
    fun `scheduling the same id twice replaces rather than stacks`() = runTest {
        val scheduler = RecordingAlarmScheduler()

        scheduler.schedule(alarm("a", fireAt = 1L))
        scheduler.schedule(alarm("a", fireAt = 2L))

        assertEquals(1, scheduler.armed.size)
        assertEquals(2L, scheduler.armed.single().fireAtEpochMillis)
        assertEquals(2, scheduler.scheduleCalls.size)
    }

    @Test
    fun `an alarm that failed to schedule is not armed`() = runTest {
        val scheduler = RecordingAlarmScheduler(
            resultFor = { AlarmScheduleResult.Failed(AlarmFailure.NotificationPermissionDenied) },
        )

        scheduler.schedule(alarm("a"))

        assertTrue(scheduler.armed.isEmpty())
        assertEquals(listOf("a"), scheduler.scheduleCalls.map { it.id })
    }

    @Test
    fun `an inexactly scheduled alarm is still armed`() = runTest {
        val scheduler = RecordingAlarmScheduler(
            resultFor = { AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING) },
        )

        scheduler.schedule(alarm("a"))

        assertEquals(listOf("a"), scheduler.armed.map { it.id })
    }

    @Test
    fun `the result can be decided per alarm`() = runTest {
        val scheduler = RecordingAlarmScheduler(
            resultFor = { candidate ->
                if (candidate.id == "bad") {
                    AlarmScheduleResult.Failed(AlarmFailure.SchedulerUnavailable)
                } else {
                    AlarmScheduleResult.Exact
                }
            },
        )

        assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm("good")))
        assertEquals(
            AlarmScheduleResult.Failed(AlarmFailure.SchedulerUnavailable),
            scheduler.schedule(alarm("bad")),
        )
        assertEquals(listOf("good"), scheduler.armed.map { it.id })
    }

    @Test
    fun `cancelling removes the alarm and records the id`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        scheduler.schedule(alarm("a"))
        scheduler.schedule(alarm("b"))

        scheduler.cancel("a")

        assertEquals(listOf("b"), scheduler.armed.map { it.id })
        assertEquals(listOf("a"), scheduler.cancelledIds)
    }

    @Test
    fun `cancelling an id that was never armed is recorded and harmless`() = runTest {
        val scheduler = RecordingAlarmScheduler()

        scheduler.cancel("ghost")

        assertTrue(scheduler.armed.isEmpty())
        assertEquals(listOf("ghost"), scheduler.cancelledIds)
    }

    @Test
    fun `cancelAll cancels exactly the ids passed`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        scheduler.schedule(alarm("a"))
        scheduler.schedule(alarm("b"))
        scheduler.schedule(alarm("c"))

        scheduler.cancelAll(listOf("a", "c"))

        assertEquals(listOf("b"), scheduler.armed.map { it.id })
        assertEquals(listOf("a", "c"), scheduler.cancelledIds)
    }

    @Test
    fun `cancelAll on an empty collection changes nothing`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        scheduler.schedule(alarm("a"))

        scheduler.cancelAll(emptyList())

        assertEquals(listOf("a"), scheduler.armed.map { it.id })
        assertTrue(scheduler.cancelledIds.isEmpty())
    }

    @Test
    fun `clear drops recordings and armed alarms`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        scheduler.schedule(alarm("a"))
        scheduler.cancel("a")

        scheduler.clear()

        assertTrue(scheduler.armed.isEmpty())
        assertTrue(scheduler.scheduleCalls.isEmpty())
        assertTrue(scheduler.cancelledIds.isEmpty())
    }

    @Test
    fun `armed keeps the order ids were first scheduled in`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        scheduler.schedule(alarm("first"))
        scheduler.schedule(alarm("second"))
        scheduler.schedule(alarm("first", fireAt = 99L))

        assertEquals(listOf("first", "second"), scheduler.armed.map { it.id })
    }
}
