package io.github.jamal_wia.kmptoolkit.scheduler.testing

import io.github.jamal_wia.kmptoolkit.scheduler.AlarmNotification
import io.github.jamal_wia.kmptoolkit.scheduler.ScheduledAlarm
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `scheduleCalls` is documented as "every `schedule` call in order": what the code under test handed
 * in at each call. A caller that reuses one mutable payload map for several alarms (a common way to
 * build reminders in a loop) must not rewrite the recording of the earlier calls afterwards. The
 * real Android scheduler writes the payload into an intent at the call and the real notifier
 * snapshots its arguments, so a fixture that stays linked to the caller's map shows a history that
 * no real scheduler would have seen.
 */
class RecordingAlarmSchedulerSnapshotAdversarialTest {

    private fun alarm(id: String, payload: Map<String, String>): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "REMINDER",
        fireAtEpochMillis = 1L,
        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
        payload = payload,
    )

    @Test
    fun `a payload map edited after the call does not rewrite the recorded call`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        val shared: MutableMap<String, String> = mutableMapOf("lesson" to "1")

        scheduler.schedule(alarm("first", shared))
        shared["lesson"] = "2"
        scheduler.schedule(alarm("second", shared))

        assertEquals(listOf("1", "2"), scheduler.scheduleCalls.map { it.payload["lesson"] })
        assertEquals(listOf("1", "2"), scheduler.armed.map { it.payload["lesson"] })
    }

    @Test
    fun `a recorded alarm is equal to the one passed in`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        val alarm: ScheduledAlarm = alarm("only", mapOf("lesson" to "1"))

        scheduler.schedule(alarm)

        assertEquals(alarm, scheduler.scheduleCalls.single())
        assertEquals(alarm, scheduler.armed.single())
    }
}
