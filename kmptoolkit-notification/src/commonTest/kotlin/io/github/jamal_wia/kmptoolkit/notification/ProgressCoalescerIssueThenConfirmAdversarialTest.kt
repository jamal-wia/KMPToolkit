package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.TestTimeSource

/**
 * The iOS notifier marks a frame shown when it hands the request to the notification center and only
 * learns afterwards whether the center accepted it, rolling the ticket back on an error. Two frames
 * of one id issued back to back that are both refused must leave nothing behind, whichever refusal
 * arrives first.
 */
class ProgressCoalescerIssueThenConfirmAdversarialTest {

    private fun coalescer(): ProgressCoalescer =
        ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO, timeSource = TestTimeSource())

    private fun ProgressCoalescer.issue(percent: Int): ProgressCoalescer.PostTicket {
        val ticket: ProgressCoalescer.PostTicket = requireNotNull(prepare("a", NotificationProgress.Determinate(percent)))
        check(isLive(ticket))
        complete(ticket)
        return ticket
    }

    @Test
    fun `two issued frames that the center refuses leave no baseline whichever refusal arrives first`() {
        listOf(true, false).forEach { olderRefusedFirst: Boolean ->
            val coalescer: ProgressCoalescer = coalescer()
            val older: ProgressCoalescer.PostTicket = coalescer.issue(10)
            val newer: ProgressCoalescer.PostTicket = coalescer.issue(50)

            if (olderRefusedFirst) {
                coalescer.rollback(older)
                coalescer.rollback(newer)
            } else {
                coalescer.rollback(newer)
                coalescer.rollback(older)
            }

            assertFalse(
                coalescer.wouldSuppress("a", NotificationProgress.Determinate(10)),
                "both requests were refused, yet a retry of 10% is redundant (olderRefusedFirst=$olderRefusedFirst)",
            )
            assertEquals(0, coalescer.postedCountForTest(), "baseline left behind (olderRefusedFirst=$olderRefusedFirst)")
        }
    }
}
