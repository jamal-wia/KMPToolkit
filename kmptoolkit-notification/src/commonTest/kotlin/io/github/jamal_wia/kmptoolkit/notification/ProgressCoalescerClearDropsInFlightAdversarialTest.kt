package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration

/**
 * Mutation survivor hunt (round 13). Contract (`ProgressCoalescer.forget` / `clear` and the guide, "cancelAll forgets
 * every id's state"): a cancel erases everything known about the id, so the frames that were in flight when it ran
 * are gone for good. When a post made AFTER the cancel then fails, nothing from before the cancel may be promoted back
 * as the id's baseline: the retry of that failed post must be posted, not judged redundant against a cancelled frame.
 */
class ProgressCoalescerClearDropsInFlightAdversarialTest {

    private val determinate = NotificationProgress.Determinate(40)
    private val content: String = "same content"

    private fun coalescer(): ProgressCoalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)

    @Test
    fun `clear drops the frames in flight so a failed later post does not resurrect one`() {
        val coalescer: ProgressCoalescer = coalescer()
        assertNotNull(coalescer.prepare("x", determinate, content), "first frame posts")
        // never completed, never rolled back: the post is still running when the cancel arrives
        coalescer.clear()
        val after: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("x", determinate, content))
        coalescer.rollback(after)
        assertFalse(
            coalescer.wouldSuppress("x", determinate, content),
            "the retry of the failed post was judged redundant against a frame that a cancelAll cancelled",
        )
        assertEquals(0, coalescer.postedCountForTest())
    }

    @Test
    fun `forget drops the frames in flight so a failed later post does not resurrect one`() {
        val coalescer: ProgressCoalescer = coalescer()
        assertNotNull(coalescer.prepare("x", determinate, content))
        coalescer.forget("x")
        val after: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("x", determinate, content))
        coalescer.rollback(after)
        assertFalse(coalescer.wouldSuppress("x", determinate, content))
        assertEquals(0, coalescer.postedCountForTest())
    }
}

/**
 * Mutation survivor hunt (round 13), docs 03-guide "Abandoned runs": a baseline is forgotten only after the id has been
 * quiet for "the larger of 120 times `minProgressInterval` and ten minutes", and the claims that a cancel cancelled are
 * unregistered at once.
 */
class ProgressCoalescerRetentionAndClaimsAdversarialTest {

    private class ManualClock : kotlin.time.TimeSource {
        var nowMillis: Long = 0L
        override fun markNow(): kotlin.time.TimeMark = object : kotlin.time.TimeMark {
            private val start: Long = nowMillis
            override fun elapsedNow(): Duration = (nowMillis - start).let { kotlin.time.Duration.parse("${it}ms") }
        }
    }

    @Test
    fun `a baseline outlives ten minutes when 120 rate intervals are longer and is forgotten after them`() {
        val clock = ManualClock()
        val minute: Long = 60_000L
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = kotlin.time.Duration.parse("1m"), timeSource = clock)
        val progress = NotificationProgress.Determinate(40)
        assertNotNull(coalescer.prepare("x", progress, "c"))
        clock.nowMillis = 30 * minute
        coalescer.prepare("other", progress, "c")
        assertEquals(true, coalescer.wouldSuppress("x", progress, "c"), "the baseline was forgotten after 30 minutes although 120 rate intervals are two hours")
        clock.nowMillis = 130 * minute
        coalescer.prepare("another", progress, "c")
        assertEquals(false, coalescer.wouldSuppress("x", progress, "c"), "after two hours of quiet the baseline is forgotten and the next frame posts")
    }

    @Test
    fun `clear and forget unregister the claims they cancelled at once`() {
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        coalescer.claim("a")
        coalescer.claim("b")
        assertEquals(2, coalescer.claimCountForTest())
        coalescer.forget("a")
        assertEquals(1, coalescer.claimCountForTest())
        coalescer.clear()
        assertEquals(0, coalescer.claimCountForTest())
    }

    @Test
    fun `a refused frame handed over before its predecessor was confirmed falls back to that predecessor`() {
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val shownBar = NotificationProgress.Determinate(40)
        val refusedBar = NotificationProgress.Determinate(70)
        val first: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("x", shownBar, "c"))
        coalescer.complete(first)
        val second: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("x", refusedBar, "c"))
        coalescer.complete(second)
        coalescer.rollback(second)
        assertEquals(true, coalescer.wouldSuppress("x", shownBar, "c"), "the tray still shows the first frame, so the same bar is redundant")
        assertEquals(false, coalescer.wouldSuppress("x", refusedBar, "c"), "the refused bar was never shown and must post again")
    }
}
