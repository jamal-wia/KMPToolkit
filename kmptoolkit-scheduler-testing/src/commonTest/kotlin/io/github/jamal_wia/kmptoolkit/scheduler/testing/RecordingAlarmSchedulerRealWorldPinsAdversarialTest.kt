package io.github.jamal_wia.kmptoolkit.scheduler.testing

import io.github.jamal_wia.kmptoolkit.scheduler.AlarmFailure
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmNotification
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmScheduleResult
import io.github.jamal_wia.kmptoolkit.scheduler.ScheduledAlarm
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Equivalence audit, double against the real schedulers, for the scripts a test of app code runs:
 * what a real scheduler does when a replacement is refused, when ids repeat in `cancelAll`, and in
 * which order a re-armed id appears. Both real schedulers leave the previously armed alarm of an id
 * armed when the replacement comes back `Failed` (Android saturation, iOS cap or denial).
 */
class RecordingAlarmSchedulerRealWorldPinsAdversarialTest {

    private fun alarm(id: String, title: String): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "REMINDER",
        fireAtEpochMillis = 1L,
        notification = AlarmNotification(title = title, body = "b", channelId = "c"),
    )

    @Test
    fun `a refused replacement leaves the previously armed alarm of the id armed`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        scheduler.schedule(alarm("a", "old"))
        scheduler.resultFor = { AlarmScheduleResult.Failed(AlarmFailure.PlatformError("cap")) }

        scheduler.schedule(alarm("a", "new"))

        assertEquals(listOf("old"), scheduler.armed.map { it.notification.title })
        assertEquals(listOf("old", "new"), scheduler.scheduleCalls.map { it.notification.title })
    }

    @Test
    fun `cancelAll with a repeated id records it as often as it was passed and disarms it once`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        scheduler.schedule(alarm("a", "t"))

        scheduler.cancelAll(listOf("a", "x", "a"))

        assertEquals(listOf("a", "x", "a"), scheduler.cancelledIds)
        assertEquals(emptyList(), scheduler.armed)
    }

    @Test
    fun `an id cancelled and scheduled again moves to the end while a replaced one keeps its place`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        scheduler.schedule(alarm("a", "1"))
        scheduler.schedule(alarm("b", "1"))
        scheduler.schedule(alarm("a", "2"))
        assertEquals(listOf("a", "b"), scheduler.armed.map { it.id })

        scheduler.cancel("a")
        scheduler.schedule(alarm("a", "3"))

        assertEquals(listOf("b", "a"), scheduler.armed.map { it.id })
    }

    @Test
    fun `the lists handed out are not live views of the recording`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        scheduler.schedule(alarm("a", "t"))
        val armedBefore: List<ScheduledAlarm> = scheduler.armed
        val callsBefore: List<ScheduledAlarm> = scheduler.scheduleCalls
        val cancelledBefore: List<String> = scheduler.cancelledIds

        scheduler.schedule(alarm("b", "t"))
        scheduler.cancel("a")

        assertEquals(listOf("a"), armedBefore.map { it.id })
        assertEquals(listOf("a"), callsBefore.map { it.id })
        assertEquals(emptyList(), cancelledBefore)
    }
}
