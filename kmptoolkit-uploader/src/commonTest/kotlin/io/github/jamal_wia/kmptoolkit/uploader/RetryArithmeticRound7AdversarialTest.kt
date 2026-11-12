package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 7 (time and limits): the backoff stays within `0..maxDelayMillis` for every attempt count of the Int range and every
 * base, ceiling and jitter, including a ceiling of `Long.MAX_VALUE`; without jitter it is exactly `min(max, base * 2^(n-1))`
 * and never decreases.
 */
class RetryArithmeticRound7AdversarialTest {

    private val bases: List<Long> = listOf(1L, 2L, 3L, 1_000L, 1L shl 40, Long.MAX_VALUE / 2, Long.MAX_VALUE)
    private val attempts: List<Int> = listOf(-1, 0, 1, 2, 3, 20, 21, 22, 40, 62, 63, 64, 65, 1_000, Int.MAX_VALUE)

    private fun exact(base: Long, max: Long, n: Int): Long {
        val exponent: Int = (n - 1).coerceAtLeast(0)
        var value: Long = base
        repeat(exponent) { value = if (value > max / 2) return max else value * 2 }
        return minOf(value, max)
    }

    @Test
    fun `without jitter the delay is the exact doubling capped at the ceiling and never decreases`() {
        for (base in bases) for (max in bases.filter { it >= base } + Long.MAX_VALUE) {
            val policy = ExponentialBackoffRetryPolicy(baseDelayMillis = base, maxDelayMillis = max, jitterRatio = 0.0)
            var previous = 0L
            for (n in attempts.sorted()) {
                val delay: Long = policy.backoffMillis(n)
                val want: Long = exact(base, max, n)
                // the curve goes through a Double, so past 2^53 only the relative error is promised
                assertTrue(kotlin.math.abs(delay - want) <= want / 1_000_000_000_000L, "base=$base max=$max attempts=$n: $delay instead of $want")
                assertTrue(delay >= previous, "the delay decreased at attempts=$n (base=$base max=$max)")
                previous = delay
            }
        }
    }

    @Test
    fun `with jitter the delay stays within zero and the ceiling for any random source`() {
        val extremes: List<Double> = listOf(0.0, 1.0)
        for (base in bases) for (max in bases.filter { it >= base }) for (jitter in listOf(0.0, 0.2, 1.0)) {
            for (draw in extremes + (0 until 6).map { Random(it).nextDouble() }) {
                val source = object : Random() {
                    override fun nextBits(bitCount: Int): Int = if (draw >= 1.0) -1 ushr (32 - bitCount) else 0
                    override fun nextDouble(): Double = draw
                }
                val policy = ExponentialBackoffRetryPolicy(base, max, jitter, random = source)
                for (n in attempts) {
                    val delay: Long = policy.backoffMillis(n)
                    assertTrue(delay in 0L..max, "delay $delay outside 0..$max (base=$base jitter=$jitter draw=$draw attempts=$n)")
                }
            }
        }
    }
}
