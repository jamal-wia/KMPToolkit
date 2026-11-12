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
