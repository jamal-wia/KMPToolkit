package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

/**
 * The notifier decides and renders outside its post lock and only the final "still wanted? notify, complete"
 * step runs inside it, so a newer frame can be prepared after an older one has passed its liveness check and
 * before that older frame is completed. When the newer one is then refused by the platform, the baseline it
 * restores must be the frame that reached the tray (the older one), not the frame that happened to be shown
 * when the newer one was prepared.
 */
class ProgressCoalescerStaleFallbackAdversarialTest {

    private fun coalescer() = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)

    @Test
    fun `a refused frame restores the baseline of the frame that reached the tray meanwhile`() {
        val coalescer: ProgressCoalescer = coalescer()
        val first: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("d", NotificationProgress.Determinate(5), "c"))
        assertEquals(true, coalescer.isLive(first))
        coalescer.complete(first, acknowledged = true)

        // B has passed its liveness check and is about to notify; C is prepared in that window.
        val second: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("d", NotificationProgress.Determinate(64), "c"))
        assertEquals(true, coalescer.isLive(second))
        val third: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("d", NotificationProgress.Determinate(35), "c"))
        coalescer.complete(second, acknowledged = true) // the platform accepted B: the tray shows 64
        coalescer.rollback(third)                       // C is refused by the platform

        // The tray shows bucket 60. A frame of the old bucket 0 is not redundant against it.
        assertNotNull(coalescer.prepare("d", NotificationProgress.Determinate(3), "c"), "judged redundant against a baseline the tray does not show")
    }

    @Test
    fun `the same window on the iOS order, handed over then refused`() {
        val coalescer: ProgressCoalescer = coalescer()
        val first: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("d", NotificationProgress.Determinate(5), "c"))
        coalescer.complete(first)
        coalescer.acknowledge(first)

        val second: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("d", NotificationProgress.Determinate(64), "c"))
        assertEquals(true, coalescer.isLive(second))
        val third: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("d", NotificationProgress.Determinate(35), "c"))
        coalescer.complete(second)       // handed over; the center will accept it
        coalescer.rollback(third)        // refused by the center
        coalescer.acknowledge(second)

        assertNotNull(coalescer.prepare("d", NotificationProgress.Determinate(3), "c"), "judged redundant against a baseline the center does not show")
    }

    @Test
    fun `without the overlap a refused frame restores the shown baseline`() {
        val coalescer: ProgressCoalescer = coalescer()
        val first: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("d", NotificationProgress.Determinate(64), "c"))
        coalescer.complete(first, acknowledged = true)
        val second: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("d", NotificationProgress.Determinate(35), "c"))
        coalescer.rollback(second)
        assertNull(coalescer.prepare("d", NotificationProgress.Determinate(65), "c"), "the shown 64 is the baseline again")
    }
}
