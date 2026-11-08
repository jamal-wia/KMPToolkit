package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * One id, a platform that keeps refusing the newest of two overlapping frames: every cycle strands
 * the older frame's ticket (it answered "overtaken" and never settled). Over days of uptime the
 * per-id list of in-flight tickets must not grow without bound.
 */
class ProgressCoalescerInFlightGrowthAdversarialTest {

    @Test
    fun `stranded tickets of a single id are released over time`() {
        val clock = TestTimeSource()
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO, timeSource = clock)
        repeat(CYCLES) { cycle: Int ->
            val older: ProgressCoalescer.PostTicket = requireNotNull(coalescer.prepare("only", NotificationProgress.Determinate(10 + cycle % 2 * 20)))
            val newer: ProgressCoalescer.PostTicket = requireNotNull(coalescer.prepare("only", NotificationProgress.Determinate(50 + cycle % 2 * 20)))
            check(!coalescer.isLive(older))
            coalescer.rollback(newer)
            clock += 1.minutes
        }

        val field = ProgressCoalescer::class.java.getDeclaredField("inFlight").also { it.isAccessible = true }
        val inFlight: Map<*, *> = field.get(coalescer) as Map<*, *>
        val tickets: Int = inFlight.values.sumOf { (it as List<*>).size }
        assertTrue(tickets < 100, "after $CYCLES cycles over ${CYCLES} minutes one id still holds $tickets in-flight tickets")
    }

    private companion object {
        const val CYCLES: Int = 3_000
    }
}
