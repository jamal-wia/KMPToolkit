package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Round 15 (contract lawyer), 03-guide "Abandoned runs": "The notifier forgets such a position by itself
 * once the id has been quiet for far longer than any sensible rate limit (the larger of 120 times
 * `minProgressInterval` and ten minutes) ... The only visible effect is that a very late frame for a
 * forgotten id is posted, like the first frame of any run."
 */
class ProgressCoalescerAbandonedRunHorizonAdversarialTest {

    private val time = TestTimeSource()

    private fun coalescer(minInterval: Duration): ProgressCoalescer =
        ProgressCoalescer(bucketPercent = 10, minInterval = minInterval, timeSource = time)

    private fun lateFrameIsPostedAfterQuiet(minInterval: Duration, quiet: Duration): Boolean {
        val coalescer: ProgressCoalescer = coalescer(minInterval)
        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(31)), "the first frame posts")
        time += quiet
        // Same bucket as the baseline (31 -> 32) and far past any rate limit: only a forgotten baseline posts it.
        return coalescer.shouldPost("a", NotificationProgress.Determinate(32))
    }

    @Test
    fun `the quiet horizon is the larger of 120 intervals and ten minutes`() {
        // 500 ms: 120 x = 60 s, so the ten minute floor decides.
        assertFalse(lateFrameIsPostedAfterQuiet(500.milliseconds, 9.minutes), "9 min < 10 min floor: still remembered")
        assertTrue(lateFrameIsPostedAfterQuiet(500.milliseconds, 11.minutes), "11 min > 10 min floor: forgotten")
        // 10 s: 120 x = 20 min, which is above the floor.
        assertFalse(lateFrameIsPostedAfterQuiet(10.seconds, 19.minutes), "19 min < 20 min: still remembered")
        assertTrue(lateFrameIsPostedAfterQuiet(10.seconds, 22.minutes), "22 min > 20 min: forgotten")
        // 1 minute: 120 x = 2 h.
        assertFalse(lateFrameIsPostedAfterQuiet(1.minutes, 100.minutes))
        assertTrue(lateFrameIsPostedAfterQuiet(1.minutes, 130.minutes))
    }

    @Test
    fun `an id that keeps posting frames inside its bucket is not quiet and stays suppressed for an hour`() {
        val coalescer: ProgressCoalescer = coalescer(500.milliseconds)
        assertTrue(coalescer.shouldPost("a", NotificationProgress.Determinate(31)))
        val posted: MutableList<Duration> = mutableListOf()

        var elapsed: Duration = Duration.ZERO
        while (elapsed < 60.minutes) {
            time += 1.seconds
            elapsed += 1.seconds
            if (coalescer.shouldPost("a", NotificationProgress.Determinate(32))) posted += elapsed
        }

        assertEquals(
            emptyList(),
            posted,
            "an id that posted a frame every second is not quiet, yet its in-bucket frames were posted at $posted",
        )
    }

    private fun ProgressCoalescer.shouldPost(id: String, progress: NotificationProgress?): Boolean {
        val ticket: ProgressCoalescer.PostTicket = prepare(id, progress) ?: return false
        complete(ticket, acknowledged = true)
        return true
    }
}
