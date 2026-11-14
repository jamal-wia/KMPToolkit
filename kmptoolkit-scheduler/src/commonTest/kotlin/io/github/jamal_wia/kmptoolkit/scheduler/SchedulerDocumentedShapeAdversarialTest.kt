package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Round 15 (contract lawyer): the declarations printed in docs/kmptoolkit-scheduler/04-api-reference.md,
 * constructed positionally in the printed order with the printed defaults (runs on the JVM and on iOS).
 */
class SchedulerDocumentedShapeAdversarialTest {

    private val notification = AlarmNotification("title", "body", "channel")

    @Test
    fun `ScheduledAlarm positional order and the empty payload default are the printed ones`() {
        val payload: Map<String, String> = mapOf("k" to "v")
        val alarm = ScheduledAlarm("id", "TYPE", 123L, notification, payload)
        assertEquals("id", alarm.id)
        assertEquals("TYPE", alarm.type)
        assertEquals(123L, alarm.fireAtEpochMillis)
        assertEquals(notification, alarm.notification)
        assertEquals(payload, alarm.payload)
        assertEquals(emptyMap(), ScheduledAlarm("id", "TYPE", 0L, notification).payload)
        assertEquals(AlarmNotification("title", "body", "channel"), notification)
        assertEquals(Triple("title", "body", "channel"), Triple(notification.title, notification.body, notification.channelId))
    }

    @Test
    fun `only the id and the type are validated - an alarm may have empty text a negative or extreme time and a blank channel`() {
        assertFailsWith<IllegalArgumentException> { ScheduledAlarm(" ", "T", 0L, notification) }
        assertFailsWith<IllegalArgumentException> { ScheduledAlarm("i", "", 0L, notification) }
        for (at in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
            assertEquals(at, ScheduledAlarm("i", "T", at, AlarmNotification("", "", "")).fireAtEpochMillis)
        }
    }

    @Test
    fun `AlarmSchedulerConfig positional order and the printed key defaults`() {
        val defaults = AlarmSchedulerConfig()
        assertNull(defaults.alarmIntentScheme)
        assertEquals("alarm_id", defaults.alarmIdKey)
        assertEquals("alarm_type", defaults.alarmTypeKey)
        assertEquals("alarm_id", AlarmSchedulerConfig.DEFAULT_ALARM_ID_KEY)
        assertEquals("alarm_type", AlarmSchedulerConfig.DEFAULT_ALARM_TYPE_KEY)
        val positional = AlarmSchedulerConfig("myscheme", "idk", "typek")
        assertEquals("myscheme", positional.alarmIntentScheme)
        assertEquals("idk", positional.alarmIdKey)
        assertEquals("typek", positional.alarmTypeKey)
    }

    @Test
    fun `the result and failure cases are exactly the printed ones`() {
        assertEquals(listOf("EXACT_ALARM_PERMISSION_MISSING", "EXACT_ALARM_PERMISSION_REVOKED"), InexactReason.entries.map { it.name })
        val failures: List<AlarmFailure> = listOf(
            AlarmFailure.NotificationPermissionDenied, AlarmFailure.SchedulerUnavailable, AlarmFailure.PlatformError(null),
        )
        val names: List<String> = failures.map { failure: AlarmFailure ->
            when (failure) {
                AlarmFailure.NotificationPermissionDenied -> "denied"
                AlarmFailure.SchedulerUnavailable -> "unavailable"
                is AlarmFailure.PlatformError -> "platform"
            }
        }
        assertEquals(3, names.toSet().size)
        val results: List<AlarmScheduleResult> = listOf(
            AlarmScheduleResult.Exact,
            AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING),
            AlarmScheduleResult.Failed(AlarmFailure.PlatformError("x")),
        )
        assertEquals(
            listOf("exact", "inexact", "failed"),
            results.map { result: AlarmScheduleResult ->
                when (result) {
                    AlarmScheduleResult.Exact -> "exact"
                    is AlarmScheduleResult.Inexact -> "inexact"
                    is AlarmScheduleResult.Failed -> "failed"
                }
            },
        )
    }
}
