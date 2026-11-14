package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals

/** The documented `min(max, base * 2^(n-1))` right up to the top of the Long range. */
class BackoffTopOfRangeRound8Test {

    @Test
    fun `base 1 under a Long max ceiling reaches the ceiling at attempt 64 and stays there`() {
        val policy = ExponentialBackoffRetryPolicy(baseDelayMillis = 1L, maxDelayMillis = Long.MAX_VALUE, jitterRatio = 0.0)

        assertEquals(1L shl 62, policy.backoffMillis(63))
        assertEquals(Long.MAX_VALUE, policy.backoffMillis(64))
        assertEquals(Long.MAX_VALUE, policy.backoffMillis(65))
        assertEquals(Long.MAX_VALUE, policy.backoffMillis(Int.MAX_VALUE))
    }
}
