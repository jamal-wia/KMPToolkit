package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers [ScheduledAlarm]'s own invariants. A blank id or type is not a harmless typo: the id is
 * the identity replacement and cancellation are keyed on, and the type is the only thing that gets
 * a fired alarm to a handler.
 */
class ScheduledAlarmTest {

    private val notification = AlarmNotification(title = "t", body = "b", channelId = "c")

    @Test
    fun `payload defaults to empty`() {
        val alarm = ScheduledAlarm(
            id = "a",
            type = "REMINDER",
            fireAtEpochMillis = 1L,
            notification = notification,
        )

        assertEquals(emptyMap(), alarm.payload)
    }

    @Test
    fun `a blank id is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ScheduledAlarm(id = " ", type = "REMINDER", fireAtEpochMillis = 1L, notification = notification)
        }
    }

    @Test
    fun `a blank type is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ScheduledAlarm(id = "a", type = "", fireAtEpochMillis = 1L, notification = notification)
        }
    }

    @Test
    fun `a fire time in the past is accepted`() {
        val alarm = ScheduledAlarm(
            id = "a",
            type = "REMINDER",
            fireAtEpochMillis = -1L,
            notification = notification,
        )

        assertEquals(-1L, alarm.fireAtEpochMillis)
    }

    @Test
    fun `two alarms with the same content are equal`() {
        val first = ScheduledAlarm("a", "T", 1L, notification, mapOf("k" to "v"))
        val second = ScheduledAlarm("a", "T", 1L, notification, mapOf("k" to "v"))

        assertEquals(first, second)
    }
}
