package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The strict edge of the in-flight ledger: a frame handed out and never settled is dropped from the ledger only once it
 * is older than the stale horizon, not when it is exactly that old. Read through the ledger field, as the other
 * leak tests do, because the ledger has no other observable effect.
 */
class ProgressCoalescerInFlightBoundaryAdversarialTest {

    private fun inFlightTickets(coalescer: ProgressCoalescer): Int {
        val field = ProgressCoalescer::class.java.getDeclaredField("inFlight").also { it.isAccessible = true }
        return (field.get(coalescer) as Map<*, *>).values.sumOf { (it as List<*>).size }
    }

    @Test
    fun `an unsettled frame exactly as old as the horizon stays in the ledger and an older one leaves it`() {
        val clock = TestTimeSource()
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO, timeSource = clock)
        assertNotNull(coalescer.prepare("x", NotificationProgress.Determinate(10)))
        clock += 10.minutes
        assertNotNull(coalescer.prepare("y", NotificationProgress.Determinate(10)))
        assertEquals(2, inFlightTickets(coalescer), "a frame exactly as old as the horizon left the ledger")
        clock += 1.seconds
        clock += 1.minutes
        assertNotNull(coalescer.prepare("w", NotificationProgress.Determinate(10)))
        assertEquals(2, inFlightTickets(coalescer), "a frame older than the horizon stayed in the ledger")
    }
}
