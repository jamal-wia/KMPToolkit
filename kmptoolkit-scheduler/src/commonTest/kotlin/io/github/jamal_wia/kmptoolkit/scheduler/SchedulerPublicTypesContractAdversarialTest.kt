package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * equals, hashCode and toString of every public value type of the module and the exhaustiveness of its
 * sealed result types, on both platforms. The `when` expressions have no `else` on purpose: a new case
 * is a compile error here (docs 04 list the complete sets a consumer may match on).
 */
class SchedulerPublicTypesContractAdversarialTest {

    private fun describe(result: AlarmScheduleResult): String = when (result) {
        AlarmScheduleResult.Exact -> "exact"
        is AlarmScheduleResult.Inexact -> "inexact:" + when (result.reason) {
            InexactReason.EXACT_ALARM_PERMISSION_MISSING -> "missing"
            InexactReason.EXACT_ALARM_PERMISSION_REVOKED -> "revoked"
        }
        is AlarmScheduleResult.Failed -> "failed:" + when (val reason: AlarmFailure = result.reason) {
            AlarmFailure.NotificationPermissionDenied -> "denied"
            AlarmFailure.SchedulerUnavailable -> "unavailable"
            is AlarmFailure.PlatformError -> "platform:${reason.message}"
        }
    }

    @Test
    fun `every case of the result types is matched exhaustively and answers`() {
        assertEquals(
            listOf(
                "exact", "inexact:missing", "inexact:revoked", "failed:denied", "failed:unavailable",
                "failed:platform:m", "failed:platform:null",
            ),
            listOf(
                AlarmScheduleResult.Exact,
                AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING),
                AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_REVOKED),
                AlarmScheduleResult.Failed(AlarmFailure.NotificationPermissionDenied),
                AlarmScheduleResult.Failed(AlarmFailure.SchedulerUnavailable),
                AlarmScheduleResult.Failed(AlarmFailure.PlatformError("m")),
                AlarmScheduleResult.Failed(AlarmFailure.PlatformError(null)),
            ).map { describe(it) },
        )
        assertEquals(2, InexactReason.entries.size)
    }

    @Test
    fun `data objects print their simple name`() {
        assertEquals("Exact", AlarmScheduleResult.Exact.toString())
        assertEquals("NotificationPermissionDenied", AlarmFailure.NotificationPermissionDenied.toString())
        assertEquals("SchedulerUnavailable", AlarmFailure.SchedulerUnavailable.toString())
        assertNotEquals<Any>(AlarmFailure.NotificationPermissionDenied, AlarmFailure.SchedulerUnavailable)
    }

    @Test
    fun `data classes are equal by value with a matching hash`() {
        val notification = AlarmNotification("t", "b", "c")
        val pairs: List<Pair<Any, Any>> = listOf(
            AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING) to
                AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING),
            AlarmScheduleResult.Failed(AlarmFailure.PlatformError("x")) to AlarmScheduleResult.Failed(AlarmFailure.PlatformError("x")),
            notification to AlarmNotification("t", "b", "c"),
            ScheduledAlarm("i", "T", 5L, notification, mapOf("k" to "v")) to
                ScheduledAlarm("i", "T", 5L, AlarmNotification("t", "b", "c"), linkedMapOf("k" to "v")),
            AlarmSchedulerConfig(alarmIntentScheme = "my.app") to AlarmSchedulerConfig(alarmIntentScheme = "my.app"),
        )
        pairs.forEach { (left: Any, right: Any) ->
            assertEquals(left, right, "$left")
            assertEquals(right, left)
            assertEquals(left.hashCode(), right.hashCode(), "$left (hash)")
            assertEquals(left.toString(), right.toString())
        }
        val alarm = ScheduledAlarm("i", "T", 5L, notification)
        listOf(
            alarm.copy(id = "j"), alarm.copy(type = "U"), alarm.copy(fireAtEpochMillis = 6L),
            alarm.copy(notification = notification.copy(channelId = "d")), alarm.copy(payload = mapOf("k" to "v")),
        ).forEach { assertNotEquals(alarm, it, "$it") }
        assertNotEquals(AlarmSchedulerConfig(), AlarmSchedulerConfig(alarmIdKey = "other_id"))
    }

    @Test
    fun `the string of an alarm names its type and a huge payload does not break it`() {
        val alarm = ScheduledAlarm(
            "i", "T", Long.MAX_VALUE, AlarmNotification("t".repeat(100_000), "b", "c"),
            (0 until 200).associate { "k$it" to "v".repeat(100) },
        )
        assertTrue(alarm.toString().startsWith("ScheduledAlarm("))
        assertEquals(alarm.hashCode(), alarm.copy().hashCode())
    }

    @Test
    fun `the handler lookup takes the first of equal types and answers null for none`() {
        val first = object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) = Unit
        }
        val second = object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) = Unit
        }
        assertEquals(first, listOf(first, second).handlerFor("T"))
        assertEquals(second, listOf(second, first).handlerFor("T"))
        assertEquals(null, listOf(first, second).handlerFor("U"))
        assertEquals(null, emptyList<AlarmHandler>().handlerFor(""))
        assertEquals(null, listOf(first).handlerFor("t"))
    }
}
