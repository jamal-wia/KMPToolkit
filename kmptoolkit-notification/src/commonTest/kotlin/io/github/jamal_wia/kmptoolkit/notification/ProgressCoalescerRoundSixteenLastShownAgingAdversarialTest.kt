package io.github.jamal_wia.kmptoolkit.notification

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Round 16 SUSPECT r16-S1 (regression hunt on the round 15 fix of the abandoned-run horizon). 03-guide "Abandoned runs": an id
 * is forgotten only "once the id has been quiet for far longer than" the horizon; a run that keeps sending
 * frames is not quiet, whatever else happens to it. Every test here uses the internal ticket protocol the two
 * notifiers use (prepare, complete or rollback) on a test clock.
 */
class ProgressCoalescerRoundSixteenLastShownAgingAdversarialTest {

    private val time = TestTimeSource()

    private fun coalescer(minInterval: Duration = 500.milliseconds): ProgressCoalescer =
        ProgressCoalescer(bucketPercent = 10, minInterval = minInterval, timeSource = time)

    private fun ProgressCoalescer.post(id: String, percent: Int?, content: Any? = null): Boolean {
        val progress: NotificationProgress? = percent?.let { NotificationProgress.Determinate(it) }
        val ticket: ProgressCoalescer.PostTicket = prepare(id, progress, content) ?: return false
        complete(ticket, acknowledged = true)
        return true
    }

    @Test
    fun `a run that kept sending frames and then had one frame refused still remembers the frame on the tray`() {
        val coalescer: ProgressCoalescer = coalescer()
        assertTrue(coalescer.post("a", 30))
        // A live run for 15 minutes: every in-bucket frame is suppressed, none is quiet.
        repeat(15 * 60) {
            time += 1.seconds
            assertFalse(coalescer.post("a", 31), "an in-bucket frame is redundant")
        }
        // The next tenth is handed to the platform and the platform refuses it.
        val refused: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("a", NotificationProgress.Determinate(45)))
        coalescer.rollback(refused)

        // The tray still shows the 30 percent frame, so another frame of that tenth is redundant.
        assertNull(
            coalescer.prepare("a", NotificationProgress.Determinate(31)),
            "the run was never quiet; after a refused frame the frame still on the tray is the baseline",
        )
    }
}
