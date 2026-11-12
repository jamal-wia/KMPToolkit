package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals

/** Round 7 regression: `backoffMillis` documents "values below 1 are treated as 1"; `attempts - 1` wraps for Int.MIN_VALUE. */
class RetryAttemptsMinValueRound7RegressionTest {
    @Test
    fun `an attempts count below one is treated as one even at Int MIN_VALUE`() {
        val policy = ExponentialBackoffRetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 300_000L, jitterRatio = 0.0)
        assertEquals(1_000L, policy.backoffMillis(1))
        assertEquals(1_000L, policy.backoffMillis(0))
        assertEquals(1_000L, policy.backoffMillis(Int.MIN_VALUE), "Int.MIN_VALUE - 1 wrapped to Int.MAX_VALUE and the delay jumped to the ceiling")
    }
}
