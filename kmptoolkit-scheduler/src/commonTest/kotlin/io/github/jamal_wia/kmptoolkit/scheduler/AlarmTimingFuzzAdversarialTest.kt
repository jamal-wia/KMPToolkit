package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `triggerIntervalSeconds` is what the iOS scheduler hands the system for every reminder, computed
 * from the wall clock at schedule time. A seeded property sweep over the whole `Long` range, including
 * both extremes together, a clock that steps backwards between two calls and far-future times: the
 * answer is always a finite number of at least one second (the platform rejects anything smaller),
 * never wraps into the past, never decreases for a later fire time or increases for a later clock, and
 * is the plain difference in seconds wherever the difference is exactly representable.
 */
class AlarmTimingFuzzAdversarialTest {

    private val edges: List<Long> = listOf(
        Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, 999L, 1_000L, 1_001L, 2_000L,
        1_700_000_000_000L, 4_102_444_800_000L, Long.MAX_VALUE / 2, Long.MAX_VALUE - 1, Long.MAX_VALUE,
    )

    private fun draw(random: Random): Long = if (random.nextInt(3) == 0) edges.random(random) else random.nextLong()

    @Test
    fun `the interval is finite and at least one second for any pair of instants`() {
        val random = Random(20261111)
        repeat(20_000) { i: Int ->
            val fire: Long = draw(random)
            val now: Long = draw(random)
            val seconds: Double = triggerIntervalSeconds(fire, now)
            assertTrue(!seconds.isNaN() && !seconds.isInfinite(), "iteration $i fire=$fire now=$now gave $seconds")
            assertTrue(seconds >= MIN_TRIGGER_INTERVAL_SECONDS, "iteration $i fire=$fire now=$now gave $seconds")
        }
    }

    @Test
    fun `a later fire time never gives a shorter interval and a later clock never a longer one`() {
        val random = Random(7)
        repeat(20_000) { i: Int ->
            val a: Long = draw(random)
            val b: Long = draw(random)
            val low: Long = minOf(a, b)
            val high: Long = maxOf(a, b)
            val now: Long = draw(random)
            assertTrue(
                triggerIntervalSeconds(high, now) >= triggerIntervalSeconds(low, now),
                "iteration $i: fire $high gave less than fire $low at now=$now",
            )
            val fire: Long = draw(random)
            assertTrue(
                triggerIntervalSeconds(fire, low) >= triggerIntervalSeconds(fire, high),
                "iteration $i: clock $low gave less than clock $high for fire=$fire",
            )
        }
    }

    @Test
    fun `a far-future alarm is never turned into an immediate one by an extreme clock`() {
        // The case the Double subtraction exists for: Long.MAX_VALUE - Long.MIN_VALUE wraps a Long subtraction to -1.
        val seconds: Double = triggerIntervalSeconds(Long.MAX_VALUE, Long.MIN_VALUE)
        assertTrue(seconds > 1.0e15, "the interval wrapped into the past: $seconds")
        assertTrue(triggerIntervalSeconds(Long.MAX_VALUE, 0L) > 9.0e15)
        assertEquals(MIN_TRIGGER_INTERVAL_SECONDS, triggerIntervalSeconds(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun `a clock that steps backwards between two schedules lengthens the same alarm's interval`() {
        val fire = 1_700_000_100_000L
        var clock = 1_700_000_000_000L
        var previous: Double = triggerIntervalSeconds(fire, clock)
        assertEquals(100.0, previous)
        repeat(50) { step: Int ->
            // Step the clock back by a day (a manual change or a network time correction): the alarm is further away, not nearer.
            clock -= 86_400_000L
            val next: Double = triggerIntervalSeconds(fire, clock)
            assertTrue(next > previous, "step $step: $next is not longer than $previous after the clock went back")
            previous = next
        }
    }

    @Test
    fun `where the difference is exactly representable the interval is that difference in seconds`() {
        val random = Random(99)
        repeat(20_000) { i: Int ->
            val now: Long = random.nextLong(-(1L shl 50), 1L shl 50)
            val fire: Long = now + random.nextLong(-(1L shl 40), 1L shl 40)
            val expected: Double = maxOf(MIN_TRIGGER_INTERVAL_SECONDS, (fire - now).toDouble() / MILLIS_PER_SECOND)
            val seconds: Double = triggerIntervalSeconds(fire, now)
            assertTrue(abs(seconds - expected) <= abs(expected) * 1.0e-12, "iteration $i fire=$fire now=$now: $seconds vs $expected")
        }
    }
}
