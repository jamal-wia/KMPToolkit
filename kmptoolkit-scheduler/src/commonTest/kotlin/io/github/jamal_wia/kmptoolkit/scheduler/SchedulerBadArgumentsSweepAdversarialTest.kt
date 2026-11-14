package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round 12: every public constructor of the scheduler model with every bad argument of the grid. A bad
 * value fails at construction (and through copy) with the documented IllegalArgumentException; a value the
 * contract accepts constructs however large; the iOS trigger arithmetic never produces a value a request
 * cannot carry for any pair of Long extremes.
 */
class SchedulerBadArgumentsSweepAdversarialTest {

    private val blanks: List<String> = listOf(
        "", " ", "  ", "\t", "\n", "\r\n", "\u000B", "\u000C", " ", " ", " ", " ", "　", " \t\n ",
    )
    private val notification = AlarmNotification("t", "b", "c")

    @Test
    fun `every blank id and every blank type is rejected at construction and through copy`() {
        val valid = ScheduledAlarm("i", "T", 0L, notification)
        for (blank in blanks) {
            assertFailsWith<IllegalArgumentException>("id <$blank>") { ScheduledAlarm(blank, "T", 0L, notification) }
            assertFailsWith<IllegalArgumentException>("type <$blank>") { ScheduledAlarm("i", blank, 0L, notification) }
            assertFailsWith<IllegalArgumentException>("copy id <$blank>") { valid.copy(id = blank) }
            assertFailsWith<IllegalArgumentException>("copy type <$blank>") { valid.copy(type = blank) }
        }
    }

    @Test
    fun `fire times of every extreme blank notification text and a huge payload construct and compare`() {
        val times: List<Long> = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val payload: Map<String, String> = (0 until 100_000).associate { "k$it" to "v$it" }
        for (time in times) {
            val a = ScheduledAlarm("i", "T", time, AlarmNotification("", "", ""), payload)
            val b = a.copy(payload = payload.toMap())
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
            assertEquals(time, a.fireAtEpochMillis)
        }
        assertTrue(ScheduledAlarm("x".repeat(1_000_000), "T", 0L, notification).toString().isNotEmpty())
    }

    @Test
    fun `config keys - blank equal and the modules own extras are rejected anything else is kept verbatim`() {
        for (blank in blanks) {
            assertFailsWith<IllegalArgumentException>("id key <$blank>") { AlarmSchedulerConfig(alarmIdKey = blank) }
            assertFailsWith<IllegalArgumentException>("type key <$blank>") { AlarmSchedulerConfig(alarmTypeKey = blank) }
            assertFailsWith<IllegalArgumentException>("copy id key <$blank>") { AlarmSchedulerConfig().copy(alarmIdKey = blank) }
        }
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmIdKey = "same", alarmTypeKey = "same") }
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmIdKey = "alarm_type") }
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmTypeKey = "alarm_id") }
        val huge = "k".repeat(1_000_000)
        val config = AlarmSchedulerConfig(alarmIdKey = huge, alarmTypeKey = "$huge-t")
        assertEquals(huge, config.alarmIdKey)
        assertEquals(" padded ", AlarmSchedulerConfig(alarmIdKey = " padded ", alarmTypeKey = "other").alarmIdKey)
    }

    @Test
    fun `the intent scheme accepts exactly the RFC 3986 shape and rejects everything else`() {
        val valid: List<String> = listOf("a", "A", "a1", "a+b", "a.b", "a-b", "a1+2.3-4", "x".repeat(10_000), "My-App.Alarms")
        val invalid: List<String> = listOf(
            "", " ", "1a", "+a", ".a", "-a", "a b", "a_b", "a:b", "a/b", "a\n", "\na", "a\u0000", "é", "aé", "日本", "a ", "a%41", "a#b",
        )
        for (scheme in valid) assertEquals(scheme, AlarmSchedulerConfig(alarmIntentScheme = scheme).alarmIntentScheme)
        for (scheme in invalid) {
            assertFailsWith<IllegalArgumentException>("scheme <$scheme>") { AlarmSchedulerConfig(alarmIntentScheme = scheme) }
            assertFailsWith<IllegalArgumentException>("copy scheme <$scheme>") { AlarmSchedulerConfig().copy(alarmIntentScheme = scheme) }
        }
        assertEquals(null, AlarmSchedulerConfig().alarmIntentScheme)
    }

    @Test
    fun `the trigger interval is finite and at least one second for every pair of Long extremes and never wraps`() {
        val values: List<Long> = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1_000L, -1L, 0L, 1L, 1_000L, 999_999L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        for (now in values) {
            var previous: Double = Double.NEGATIVE_INFINITY
            for (fireAt in values) {
                val interval: Double = triggerIntervalSeconds(fireAt, now)
                assertTrue(interval.isFinite(), "fire $fireAt now $now gave $interval")
                assertTrue(interval >= 1.0, "fire $fireAt now $now gave $interval")
                assertTrue(interval >= previous, "not monotonic in the fire time at now $now: $previous then $interval for $fireAt")
                previous = interval
            }
        }
        assertTrue(triggerIntervalSeconds(Long.MAX_VALUE, Long.MIN_VALUE) > 1.0e15, "a far future from a far past must stay far, not wrap")
    }
}
