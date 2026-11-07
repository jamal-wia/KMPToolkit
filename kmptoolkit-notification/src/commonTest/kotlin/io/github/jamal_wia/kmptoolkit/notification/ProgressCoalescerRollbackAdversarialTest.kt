package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.TestTimeSource

/**
 * Overlapping posts of one id that both fail: a rollback restores "what the id held before", and
 * what it held before may itself be a frame whose post is still in flight or has failed. The tray
 * must never be judged to show a frame that was never shown, whatever order the failures arrive in.
 */
class ProgressCoalescerRollbackAdversarialTest {

    private fun coalescer(): ProgressCoalescer =
        ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO, timeSource = TestTimeSource())

    private fun ProgressCoalescer.prepareProgress(percent: Int): ProgressCoalescer.PostTicket =
        requireNotNull(prepare("a", NotificationProgress.Determinate(percent))) { "$percent% was suppressed" }

    @Test
    fun `two overlapping frames that both fail leave no baseline whichever fails first`() {
        listOf(true, false).forEach { olderFailsFirst: Boolean ->
            val coalescer: ProgressCoalescer = coalescer()
            val older: ProgressCoalescer.PostTicket = coalescer.prepareProgress(10)
            val newer: ProgressCoalescer.PostTicket = coalescer.prepareProgress(50)

            if (olderFailsFirst) {
                coalescer.rollback(older)
                coalescer.rollback(newer)
            } else {
                coalescer.rollback(newer)
                coalescer.rollback(older)
            }

            assertFalse(
                coalescer.wouldSuppress("a", NotificationProgress.Determinate(10)),
                "nothing was ever shown, yet a retry of 10% is redundant (olderFailsFirst=$olderFailsFirst)",
            )
            assertEquals(0, coalescer.postedCountForTest(), "baseline left behind (olderFailsFirst=$olderFailsFirst)")
            assertEquals(0, coalescer.latestCountForTest(), "newest serial left behind (olderFailsFirst=$olderFailsFirst)")
        }
    }

    @Test
    fun `two overlapping frames that fail after a shown one leave the shown one as the baseline`() {
        val coalescer: ProgressCoalescer = coalescer()
        val shown: ProgressCoalescer.PostTicket = coalescer.prepareProgress(10)
        coalescer.complete(shown)
        val second: ProgressCoalescer.PostTicket = coalescer.prepareProgress(50)
        val third: ProgressCoalescer.PostTicket = coalescer.prepareProgress(80)

        coalescer.rollback(second)
        coalescer.rollback(third)

        assertFalse(
            coalescer.wouldSuppress("a", NotificationProgress.Determinate(50)),
            "50% never reached the tray, yet a retry of it is redundant",
        )
        assertEquals(
            true,
            coalescer.wouldSuppress("a", NotificationProgress.Determinate(10)),
            "10% is what the tray shows, so a repeat of it is redundant",
        )
    }
}
