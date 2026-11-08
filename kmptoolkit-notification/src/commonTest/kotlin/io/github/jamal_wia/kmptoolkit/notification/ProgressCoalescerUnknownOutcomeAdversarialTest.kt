package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration

/**
 * The iOS notifier cannot know the outcome of a frame whose caller was cancelled while the center
 * was answering: the center may have accepted it. Restoring the older baseline then claims the tray
 * still shows a frame it may no longer show, and keeping the newer one claims it shows a frame the
 * center may have refused. The only state that never lies is no baseline: the next frame posts.
 */
class ProgressCoalescerUnknownOutcomeAdversarialTest {

    private fun coalescer(): ProgressCoalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)

    private fun ProgressCoalescer.ticket(id: String, percent: Int): ProgressCoalescer.PostTicket =
        assertNotNull(prepare(id, NotificationProgress.Determinate(percent), "c"))

    @Test
    fun `an abandoned frame does not make the older baseline stand for a tray that may show the newer frame`() {
        val coalescer: ProgressCoalescer = coalescer()
        val first: ProgressCoalescer.PostTicket = coalescer.ticket("x", 10)
        coalescer.complete(first)
        coalescer.acknowledge(first)
        // Handed to the center (which may accept it), then the caller is cancelled.
        val second: ProgressCoalescer.PostTicket = coalescer.ticket("x", 50)
        coalescer.complete(second)
        coalescer.abandon(second)

        assertNotNull(
            coalescer.prepare("x", NotificationProgress.Determinate(10), "c"),
            "a retry of the older bucket is judged redundant although the abandoned frame may be what the tray shows",
        )
    }

    @Test
    fun `an abandoned frame is not a baseline either`() {
        val coalescer: ProgressCoalescer = coalescer()
        val first: ProgressCoalescer.PostTicket = coalescer.ticket("x", 10)
        coalescer.complete(first)
        coalescer.acknowledge(first)
        val second: ProgressCoalescer.PostTicket = coalescer.ticket("x", 50)
        coalescer.complete(second)
        coalescer.abandon(second)

        assertNotNull(
            coalescer.prepare("x", NotificationProgress.Determinate(50), "c"),
            "a retry of the abandoned bucket is judged redundant although the center may have refused it",
        )
    }

    @Test
    fun `a refused frame prepared after an abandoned one does not fall through to the older baseline`() {
        val coalescer: ProgressCoalescer = coalescer()
        val first: ProgressCoalescer.PostTicket = coalescer.ticket("x", 10)
        coalescer.complete(first)
        coalescer.acknowledge(first)
        val second: ProgressCoalescer.PostTicket = coalescer.ticket("x", 50)
        coalescer.complete(second)
        val third: ProgressCoalescer.PostTicket = coalescer.ticket("x", 70)
        coalescer.abandon(second)
        coalescer.complete(third)
        coalescer.rollback(third)

        assertNotNull(coalescer.prepare("x", NotificationProgress.Determinate(10), "c"), "the older baseline came back")
        val again: ProgressCoalescer = coalescer()
        val a: ProgressCoalescer.PostTicket = again.ticket("y", 10)
        again.complete(a)
        again.acknowledge(a)
        val b: ProgressCoalescer.PostTicket = again.ticket("y", 50)
        again.complete(b)
        val c: ProgressCoalescer.PostTicket = again.ticket("y", 70)
        again.abandon(b)
        again.complete(c)
        again.rollback(c)
        assertNotNull(again.prepare("y", NotificationProgress.Determinate(50), "c"), "the abandoned bucket is judged redundant")
    }

    @Test
    fun `abandoning what a cancel already forgot changes nothing`() {
        val coalescer: ProgressCoalescer = coalescer()
        val first: ProgressCoalescer.PostTicket = coalescer.ticket("x", 10)
        coalescer.complete(first)
        coalescer.forget("x")
        coalescer.abandon(first)

        assertEquals(0, coalescer.postedCountForTest())
    }

    @Test
    fun `abandoning a terminal frame does not throw and a later frame posts`() {
        val coalescer: ProgressCoalescer = coalescer()
        val terminal: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("x", null, "c"))
        coalescer.complete(terminal)
        coalescer.abandon(terminal)

        assertNotNull(coalescer.prepare("x", NotificationProgress.Determinate(30), "c"))
    }
}
