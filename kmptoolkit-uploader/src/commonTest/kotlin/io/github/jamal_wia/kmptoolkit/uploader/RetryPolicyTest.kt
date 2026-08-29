package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [ExponentialBackoffRetryPolicy]'s curve, its bounds, and the arguments it refuses. */
class RetryPolicyTest {

    @Test
    fun `the delay doubles with each attempt`() {
        val policy = ExponentialBackoffRetryPolicy(
            baseDelayMillis = 100L,
            maxDelayMillis = 100_000L,
            jitterRatio = 0.0,
        )

        assertEquals(100L, policy.backoffMillis(1))
        assertEquals(200L, policy.backoffMillis(2))
        assertEquals(400L, policy.backoffMillis(3))
        assertEquals(800L, policy.backoffMillis(4))
    }

    @Test
    fun `the curve saturates at the maximum`() {
        val policy = ExponentialBackoffRetryPolicy(
            baseDelayMillis = 100L,
            maxDelayMillis = 500L,
            jitterRatio = 0.0,
        )

        assertEquals(400L, policy.backoffMillis(3))
        assertEquals(500L, policy.backoffMillis(4))
        assertEquals(500L, policy.backoffMillis(50))
    }

    @Test
    fun `an enormous attempt count does not overflow into a negative delay`() {
        val policy = ExponentialBackoffRetryPolicy(
            baseDelayMillis = 1_000L,
            maxDelayMillis = 60_000L,
            jitterRatio = 0.0,
        )

        assertEquals(60_000L, policy.backoffMillis(Int.MAX_VALUE))
    }

    @Test
    fun `an attempt count below one is treated as the first attempt`() {
        val policy = ExponentialBackoffRetryPolicy(
            baseDelayMillis = 100L,
            maxDelayMillis = 100_000L,
            jitterRatio = 0.0,
        )

        assertEquals(100L, policy.backoffMillis(0))
        assertEquals(100L, policy.backoffMillis(-5))
    }

    @Test
    fun `jitter stays within the configured band`() {
        val policy = ExponentialBackoffRetryPolicy(
            baseDelayMillis = 1_000L,
            maxDelayMillis = 1_000_000L,
            jitterRatio = 0.2,
            random = Random(seed = 42),
        )

        repeat(200) {
            val delay: Long = policy.backoffMillis(1)
            assertTrue(delay in 800L..1_200L, "jittered delay out of band: $delay")
        }
    }

    @Test
    fun `jitter never pushes the delay above the maximum`() {
        val policy = ExponentialBackoffRetryPolicy(
            baseDelayMillis = 1_000L,
            maxDelayMillis = 1_000L,
            jitterRatio = 1.0,
            random = Random(seed = 7),
        )

        repeat(200) {
            val delay: Long = policy.backoffMillis(9)
            assertTrue(delay in 0L..1_000L, "delay escaped its ceiling: $delay")
        }
    }

    @Test
    fun `a seeded random makes the policy reproducible`() {
        fun policy(): ExponentialBackoffRetryPolicy = ExponentialBackoffRetryPolicy(
            baseDelayMillis = 1_000L,
            maxDelayMillis = 100_000L,
            random = Random(seed = 1),
        )

        assertEquals(
            List(5) { policy().backoffMillis(it + 1) },
            List(5) { policy().backoffMillis(it + 1) },
        )
    }

    @Test
    fun `zero jitter is exactly reproducible`() {
        val policy = ExponentialBackoffRetryPolicy(
            baseDelayMillis = 250L,
            maxDelayMillis = 10_000L,
            jitterRatio = 0.0,
        )

        assertEquals(List(20) { 250L }, List(20) { policy.backoffMillis(1) })
    }

    @Test
    fun `the default policy retries forever`() {
        assertEquals(GiveUpPolicy.Never, ExponentialBackoffRetryPolicy().giveUp)
    }

    @Test
    fun `the defaults are the documented ones`() {
        val policy = ExponentialBackoffRetryPolicy(jitterRatio = 0.0)

        assertEquals(ExponentialBackoffRetryPolicy.DEFAULT_BASE_DELAY_MILLIS, policy.backoffMillis(1))
        assertEquals(ExponentialBackoffRetryPolicy.DEFAULT_MAX_DELAY_MILLIS, policy.maxDelayMillis)
    }

    @Test
    fun `a non-positive base delay is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExponentialBackoffRetryPolicy(baseDelayMillis = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            ExponentialBackoffRetryPolicy(baseDelayMillis = -1L)
        }
    }

    @Test
    fun `a maximum below the base delay is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExponentialBackoffRetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 999L)
        }
    }

    @Test
    fun `a jitter ratio outside zero to one is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExponentialBackoffRetryPolicy(jitterRatio = -0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            ExponentialBackoffRetryPolicy(jitterRatio = 1.1)
        }
    }

    @Test
    fun `a give-up policy with a non-positive attempt budget is rejected`() {
        assertFailsWith<IllegalArgumentException> { GiveUpPolicy.ParkAfterAttempts(0) }
        assertFailsWith<IllegalArgumentException> { GiveUpPolicy.ParkAfterAttempts(-3) }
        assertFailsWith<IllegalArgumentException> { GiveUpPolicy.DropAfterAttempts(0) }
        assertFailsWith<IllegalArgumentException> { GiveUpPolicy.DropAfterAttempts(-3) }
    }
}
