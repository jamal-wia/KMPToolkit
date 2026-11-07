package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * An app update can ship any rate limit its config validation allows. The coalescer's own stale
 * horizon is derived from it by arithmetic that must not overflow, saturate into nonsense or make an
 * id's state vanish while it is still being rate limited.
 */
class ProgressCoalescerExtremeConfigAdversarialTest {

    private fun settle(coalescer: ProgressCoalescer, ticket: ProgressCoalescer.PostTicket) {
        if (coalescer.isLive(ticket)) coalescer.complete(ticket) else coalescer.rollback(ticket)
    }

    @Test
    fun `an enormous or infinite minimum interval rate limits for as long as it says and never throws`() {
        listOf(Duration.INFINITE, 3650.days, 1_000_000.days, Long.MAX_VALUE.milliseconds / 3).forEach { interval: Duration ->
            val clock = TestTimeSource()
            val coalescer = ProgressCoalescer(bucketPercent = 1, minInterval = interval, timeSource = clock)
            repeat(200) { index: Int ->
                val ticket: ProgressCoalescer.PostTicket? = coalescer.prepare("id-$index", NotificationProgress.Determinate(10))
                assertNotNull(ticket, "first frame of id-$index must post (interval=$interval)")
                settle(coalescer, ticket)
            }
            clock += 30.days
            repeat(200) { index: Int ->
                // Still inside the interval: the second frame of every id is rate limited, pruning or not.
                assertNull(
                    coalescer.prepare("id-$index", NotificationProgress.Determinate(11)),
                    "id-$index was forgotten while its ${interval} rate limit still ran",
                )
            }
        }
    }

    @Test
    fun `a zero interval never suppresses a changed bucket however many ids and however long the gaps`() {
        val clock = TestTimeSource()
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO, timeSource = clock)
        repeat(500) { index: Int ->
            val first: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("id-$index", NotificationProgress.Determinate(10)))
            settle(coalescer, first)
            clock += 7.minutes
            val second: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("id-$index", NotificationProgress.Determinate(50)))
            settle(coalescer, second)
            assertEquals(null, coalescer.prepare("id-$index", NotificationProgress.Determinate(50)))
        }
    }
}
