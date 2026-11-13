package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * E2 boss round 1, design-level mutant D2 (ProgressCoalescer.acknowledge made a no-op) survived every coalescer
 * and notifier class: functionally it is invisible, because the walk in `unrefused` never follows the fallback
 * of a record that was not refused. What it costs is memory. In the iOS order every frame is completed before
 * its outcome is known and keeps the record that was showing as its fallback; only `acknowledge` cuts that link.
 * Without the cut, a live run keeps every frame it ever showed reachable from the id's last shown record, a chain
 * that grows by one record per posted frame for as long as the run lasts (a two-hour download at the default
 * 500 ms interval and 10 percent buckets with a changing body is thousands of records, each holding its whole
 * LocalNotification). The tests pin the cut: after the platform confirms a frame nothing older is reachable from it.
 */
class ProgressCoalescerFallbackChainBossAdversarialTest {

    private val time = TestTimeSource()

    private fun coalescer(): ProgressCoalescer = ProgressCoalescer(timeSource = time)

    /** Length of the fallback chain behind [from], [from] excluded. */
    private fun chainBehind(from: ProgressCoalescer.Post?): Int {
        var length = 0
        var node: ProgressCoalescer.Post? = from?.fallback
        while (node != null) {
            length++
            node = node.fallback
        }
        return length
    }

    @Test
    fun `an acknowledged frame in the ios order keeps nothing older reachable`() {
        val coalescer: ProgressCoalescer = coalescer()
        val first: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("run", NotificationProgress.Determinate(10), "body 10"))
        coalescer.complete(first)
        coalescer.acknowledge(first)
        time += 1.seconds
        val second: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("run", NotificationProgress.Determinate(20), "body 20"))
        coalescer.complete(second)
        val secondRecord: ProgressCoalescer.Post = assertNotNull(second.recorded)
        assertSame(first.recorded, secondRecord.fallback, "while the outcome is unknown the frame that was showing is its fallback")
        coalescer.acknowledge(second)
        assertNull(secondRecord.fallback, "once confirmed the record must not keep the earlier frame reachable")
    }

    @Test
    fun `a long confirmed ios run never grows a chain behind its last record`() {
        val coalescer: ProgressCoalescer = coalescer()
        var last: ProgressCoalescer.Post? = null
        var posted = 0
        for (step in 0 until 400) {
            time += 1.seconds
            // A body that carries a running figure, so every frame is new content and posts.
            val ticket: ProgressCoalescer.PostTicket =
                coalescer.prepare("run", NotificationProgress.Determinate(step % 99), "body $step") ?: continue
            coalescer.complete(ticket)
            coalescer.acknowledge(ticket)
            last = ticket.recorded
            posted++
        }
        assertEquals(400, posted, "every frame carried new content one second apart, so every one posted")
        assertEquals(0, chainBehind(last), "the chain behind the last confirmed record after $posted frames")
    }

    @Test
    fun `overlapping ios frames confirmed out of order leave no chain once all are confirmed`() {
        val coalescer: ProgressCoalescer = coalescer()
        val tickets: MutableList<ProgressCoalescer.PostTicket> = mutableListOf()
        for (step in 0 until 6) {
            time += 1.seconds
            val ticket: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("run", NotificationProgress.Determinate(step * 10), "b$step"))
            coalescer.complete(ticket)
            tickets += ticket
        }
        // Outcomes arrive in reverse order, as completion handlers on other threads may deliver them.
        tickets.asReversed().forEach { coalescer.acknowledge(it) }
        tickets.forEach { ticket: ProgressCoalescer.PostTicket ->
            assertNull(assertNotNull(ticket.recorded).fallback, "serial ${ticket.serial} still links to an older frame")
        }
    }
}
