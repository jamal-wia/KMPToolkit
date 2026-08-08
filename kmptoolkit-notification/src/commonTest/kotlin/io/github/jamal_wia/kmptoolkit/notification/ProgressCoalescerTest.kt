package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * The coalescing contract from `01-overview.md` and `03-guide.md`: a determinate update posts only
 * when it has moved to a new bucket *and* the rate limit has elapsed, while a terminal frame always
 * gets through.
 *
 * Time is virtual throughout — a [TestTimeSource] the test moves by hand. A test that verified a
 * 500 ms throttle by waiting 500 ms would take half a second per assertion and would still be flaky
 * on a loaded machine.
 */
class ProgressCoalescerTest {

    private val time = TestTimeSource()

    private fun coalescer(
        bucketPercent: Int = 10,
        minInterval: Duration = Duration.ZERO,
    ): ProgressCoalescer = ProgressCoalescer(bucketPercent, minInterval, time)

    // --- bucketing -------------------------------------------------------------------------

    @Test
    fun `the first determinate update posts`() {
        assertTrue(coalescer().shouldPost("a", NotificationProgress.Determinate(0)))
    }

    @Test
    fun `an update inside the bucket already posted is suppressed`() {
        val coalescer: ProgressCoalescer = coalescer()

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(40)))
        assertFalse(coalescer.shouldPost("a", NotificationProgress.Determinate(41)))
        assertFalse(coalescer.shouldPost("a", NotificationProgress.Determinate(49)))
    }

    @Test
    fun `crossing into a new bucket posts`() {
        val coalescer: ProgressCoalescer = coalescer()
        coalescer.shouldPost("a", NotificationProgress.Determinate(40))

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(50)))
    }

    @Test
    fun `a jump over several buckets posts once`() {
        val coalescer: ProgressCoalescer = coalescer()
        coalescer.shouldPost("a", NotificationProgress.Determinate(10))

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(70)))
        assertFalse(coalescer.shouldPost("a", NotificationProgress.Determinate(75)))
    }

    @Test
    fun `a bucket width of 1 suppresses only a repeated percentage`() {
        val coalescer: ProgressCoalescer = coalescer(bucketPercent = 1)

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(41)))
        assertFalse(coalescer.shouldPost("a", NotificationProgress.Determinate(41)))
        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(42)))
    }

    @Test
    fun `a full-width bucket collapses a whole run into one post`() {
        val coalescer: ProgressCoalescer = coalescer(bucketPercent = 100)

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(1)))
        assertFalse(coalescer.shouldPost("a", NotificationProgress.Determinate(50)))
        assertFalse(coalescer.shouldPost("a", NotificationProgress.Determinate(99)))
    }

    // --- frames that must never be swallowed -----------------------------------------------

    @Test
    fun `100 percent always posts even inside the last posted bucket`() {
        val coalescer: ProgressCoalescer = coalescer(minInterval = 500.milliseconds)
        coalescer.shouldPost("a", NotificationProgress.Determinate(95))

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(100)))
    }

    @Test
    fun `a null progress always posts and resets the run`() {
        val coalescer: ProgressCoalescer = coalescer(minInterval = 500.milliseconds)
        coalescer.shouldPost("a", NotificationProgress.Determinate(40))

        assertTrue(coalescer.shouldPost("a", null))
        // The run was reset, so the same percentage posts again immediately.
        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(40)))
    }

    @Test
    fun `an indeterminate progress always posts and resets the run`() {
        val coalescer: ProgressCoalescer = coalescer(minInterval = 500.milliseconds)
        coalescer.shouldPost("a", NotificationProgress.Determinate(40))

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Indeterminate))
        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(40)))
    }

    @Test
    fun `100 percent resets the run so a second download starts fresh`() {
        val coalescer: ProgressCoalescer = coalescer()
        coalescer.shouldPost("a", NotificationProgress.Determinate(100))

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(0)))
    }

    // --- out-of-range input ------------------------------------------------------------------

    @Test
    fun `a percentage above 100 is clamped and treated as the terminal frame`() {
        val coalescer: ProgressCoalescer = coalescer()
        coalescer.shouldPost("a", NotificationProgress.Determinate(95))

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(140)))
        // Clamping to 100 also resets, so the next run's first frame posts.
        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(0)))
    }

    @Test
    fun `a negative percentage is clamped into the first bucket`() {
        val coalescer: ProgressCoalescer = coalescer()

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(-40)))
        assertFalse(coalescer.shouldPost("a", NotificationProgress.Determinate(5)))
    }

    // --- the rate limit, on virtual time -----------------------------------------------------

    @Test
    fun `a new bucket within the minimum interval is suppressed`() {
        val coalescer: ProgressCoalescer = coalescer(minInterval = 500.milliseconds)
        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(0)))

        time += 499.milliseconds

        assertFalse(coalescer.shouldPost("a", NotificationProgress.Determinate(10)))
    }

    @Test
    fun `a new bucket posts once the minimum interval has elapsed`() {
        val coalescer: ProgressCoalescer = coalescer(minInterval = 500.milliseconds)
        coalescer.shouldPost("a", NotificationProgress.Determinate(0))

        time += 500.milliseconds

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(10)))
    }

    @Test
    fun `a suppressed update does not restart the interval`() {
        val coalescer: ProgressCoalescer = coalescer(minInterval = 500.milliseconds)
        coalescer.shouldPost("a", NotificationProgress.Determinate(0))

        time += 300.milliseconds
        assertFalse(coalescer.shouldPost("a", NotificationProgress.Determinate(10)))
        time += 200.milliseconds

        // 500 ms after the post that actually happened, not after the suppressed attempt.
        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(20)))
    }

    @Test
    fun `a zero interval leaves bucketing as the only limit`() {
        val coalescer: ProgressCoalescer = coalescer(minInterval = Duration.ZERO)

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(0)))
        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(10)))
        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(20)))
    }

    // --- per-id state ------------------------------------------------------------------------

    @Test
    fun `ids are tracked independently`() {
        val coalescer: ProgressCoalescer = coalescer(minInterval = 500.milliseconds)

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(40)))
        assertTrue(coalescer.shouldPost("b", NotificationProgress.Determinate(40)))
        assertFalse(coalescer.shouldPost("a", NotificationProgress.Determinate(42)))
    }

    @Test
    fun `forget resets one id and leaves the others alone`() {
        val coalescer: ProgressCoalescer = coalescer()
        coalescer.shouldPost("a", NotificationProgress.Determinate(40))
        coalescer.shouldPost("b", NotificationProgress.Determinate(40))

        coalescer.forget("a")

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(40)))
        assertFalse(coalescer.shouldPost("b", NotificationProgress.Determinate(42)))
    }

    @Test
    fun `forget of an id that was never posted is a no-op`() {
        val coalescer: ProgressCoalescer = coalescer()

        coalescer.forget("never-seen")

        assertTrue(coalescer.shouldPost("never-seen", NotificationProgress.Determinate(40)))
    }

    @Test
    fun `clear resets every id`() {
        val coalescer: ProgressCoalescer = coalescer()
        coalescer.shouldPost("a", NotificationProgress.Determinate(40))
        coalescer.shouldPost("b", NotificationProgress.Determinate(40))

        coalescer.clear()

        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(40)))
        assertTrue(coalescer.shouldPost("b", NotificationProgress.Determinate(40)))
    }

    @Test
    fun `a full run at the defaults posts exactly eleven times`() {
        // The point of the class, stated as a number: a per-percent loop must not become 101 posts.
        // Ten buckets across 0..99, plus the terminal 100 that is never suppressed.
        val coalescer: ProgressCoalescer = coalescer(bucketPercent = 10, minInterval = Duration.ZERO)

        val posts: Int = (0..100).count { percent ->
            coalescer.shouldPost("a", NotificationProgress.Determinate(percent))
        }

        assertEquals(11, posts)
    }
}
