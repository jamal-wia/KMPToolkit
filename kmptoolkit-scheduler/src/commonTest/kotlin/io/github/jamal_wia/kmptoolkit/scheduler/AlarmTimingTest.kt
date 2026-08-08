package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [triggerIntervalSeconds], the iOS trigger arithmetic. iOS rejects a non-positive interval,
 * so every one of these cases is a request the OS would have thrown out if the clamp were wrong.
 */
class AlarmTimingTest {

    @Test
    fun `a future fire time becomes the exact remaining interval`() {
        assertEquals(90.0, triggerIntervalSeconds(fireAtEpochMillis = 100_000L, nowEpochMillis = 10_000L))
    }

    @Test
    fun `sub-second precision is preserved`() {
        assertEquals(2.5, triggerIntervalSeconds(fireAtEpochMillis = 12_500L, nowEpochMillis = 10_000L))
    }

    @Test
    fun `a fire time in the past is clamped to the minimum interval`() {
        assertEquals(
            MIN_TRIGGER_INTERVAL_SECONDS,
            triggerIntervalSeconds(fireAtEpochMillis = 1_000L, nowEpochMillis = 10_000L),
        )
    }

    @Test
    fun `a fire time equal to now is clamped to the minimum interval`() {
        assertEquals(
            MIN_TRIGGER_INTERVAL_SECONDS,
            triggerIntervalSeconds(fireAtEpochMillis = 10_000L, nowEpochMillis = 10_000L),
        )
    }

    @Test
    fun `an interval just under the minimum is raised to it`() {
        assertEquals(
            MIN_TRIGGER_INTERVAL_SECONDS,
            triggerIntervalSeconds(fireAtEpochMillis = 10_999L, nowEpochMillis = 10_000L),
        )
    }

    @Test
    fun `an epoch fire time of zero is clamped rather than turned negative`() {
        assertEquals(
            MIN_TRIGGER_INTERVAL_SECONDS,
            triggerIntervalSeconds(fireAtEpochMillis = 0L, nowEpochMillis = 1_800_000_000_000L),
        )
    }

    @Test
    fun `a far future fire time does not overflow into the past`() {
        val interval: Double = triggerIntervalSeconds(
            fireAtEpochMillis = Long.MAX_VALUE,
            nowEpochMillis = Long.MIN_VALUE + 1,
        )

        assertTrue(interval > 0.0, "expected a positive interval but was $interval")
    }
}
