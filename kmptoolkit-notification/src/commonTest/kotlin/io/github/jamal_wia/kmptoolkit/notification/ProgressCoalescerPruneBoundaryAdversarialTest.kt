package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The exact edges of the stale horizon, in virtual time. A run is abandoned only when it has been quiet for MORE than
 * the horizon (ten minutes with a zero interval), the sweep runs once a tenth of the horizon has passed since the
 * previous one (so exactly a tenth is enough), and an unfinished frame is dropped on the same strict comparison.
 * An implementation that moved any of these edges by one tick is caught here.
 */
class ProgressCoalescerPruneBoundaryAdversarialTest {

    private val horizon: Duration = 10.minutes

    private fun coalescer(clock: TestTimeSource): ProgressCoalescer =
        ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO, timeSource = clock)

    private fun show(coalescer: ProgressCoalescer, id: String) {
        val ticket: ProgressCoalescer.PostTicket? = coalescer.prepare(id, NotificationProgress.Determinate(10))
        assertNotNull(ticket)
        coalescer.complete(ticket)
    }

    @Test
    fun `a run quiet for exactly the horizon is kept and one quiet for a little longer is forgotten by a sweep exactly a tenth after the last`() {
        val clock = TestTimeSource()
        val coalescer: ProgressCoalescer = coalescer(clock)
        show(coalescer, "x")
        clock += horizon
        show(coalescer, "y")
        assertEquals(2, coalescer.postedCountForTest(), "a run quiet for exactly the horizon was forgotten")
        clock += horizon / 10
        show(coalescer, "z")
        assertEquals(2, coalescer.postedCountForTest(), "the sweep exactly a tenth of the horizon after the last did not run")
    }

    @Test
    fun `an unfinished frame as old as the horizon is still counted and one a little older is not`() {
        val clock = TestTimeSource()
        val coalescer: ProgressCoalescer = coalescer(clock)
        assertNotNull(coalescer.prepare("x", NotificationProgress.Determinate(10)))
        clock += horizon
        assertNotNull(coalescer.prepare("y", NotificationProgress.Determinate(10)))
        assertEquals(2, coalescer.latestCountForTest(), "an unfinished frame exactly as old as the horizon was dropped")
        clock += 1.seconds
        clock += horizon / 10
        assertNotNull(coalescer.prepare("w", NotificationProgress.Determinate(10)))
        assertEquals(2, coalescer.latestCountForTest(), "an unfinished frame older than the horizon was kept")
    }
}
