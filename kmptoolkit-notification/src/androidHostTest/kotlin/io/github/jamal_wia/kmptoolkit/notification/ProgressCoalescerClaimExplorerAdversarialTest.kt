package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/**
 * Claims against cancels, exhaustively: three posts of one id, each (claim, prepare with the claim, finish and
 * release), every interleaving, with up to two cancels of any kind (forget of the id, forget of another id,
 * clear) at any positions. A post whose claim was taken before a cancel and prepared after it is dropped; a post
 * whose claim came after the cancel, or whose prepare came before it, is not; a prepared frame is shown only if
 * nothing cancelled or overtook it; and every claim is gone at the end.
 */
class ProgressCoalescerClaimExplorerAdversarialTest {

    private enum class Cancel { FORGET_ID, FORGET_OTHER, CLEAR }

    private fun schedules(): List<List<Pair<Int, Int>>> {
        val result = mutableListOf<List<Pair<Int, Int>>>()
        fun walk(done: IntArray, acc: List<Pair<Int, Int>>) {
            if (done.all { it == 3 }) { result += acc; return }
            for (frame in 0..2) if (done[frame] < 3) {
                done[frame]++
                walk(done, acc + (frame to done[frame]))
                done[frame]--
            }
        }
        walk(IntArray(3), emptyList())
        return result
    }

    @Test
    fun `every interleaving of three claimed posts and two cancels matches the model`() {
        var scenarios = 0
        val all: List<List<Pair<Int, Int>>> = schedules()
        val kinds: List<Cancel> = Cancel.values().toList()
        for (schedule in all) for (p1 in -1..8) for (p2 in p1..8) {
            val cancelKinds: List<Pair<Cancel?, Cancel?>> = when {
                p1 == -1 && p2 == -1 -> listOf(null to null)
                p1 == -1 -> kinds.map { null to it }
                p1 == p2 -> kinds.map { it to null }
                else -> kinds.flatMap { a -> kinds.map { b -> a to b } }
            }
            for ((k1, k2) in cancelKinds) {
                scenarios++
                run(schedule, if (p1 < 0 && p2 >= 0) p2 else p1, k1 ?: k2, if (p1 < 0 || p1 == p2) -1 else p2, if (p1 < 0 || p1 == p2) null else k2)
            }
        }
        assertEquals(true, scenarios > 100_000, "scenarios: $scenarios")
    }

    private fun run(schedule: List<Pair<Int, Int>>, at1: Int, kind1: Cancel?, at2: Int, kind2: Cancel?) {
        val label = "schedule $schedule, cancel1 $kind1 before $at1, cancel2 $kind2 before $at2"
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val claims: Array<ProgressCoalescer.PostClaim?> = arrayOfNulls(3)
        val tickets: Array<ProgressCoalescer.PostTicket?> = arrayOfNulls(3)
        // Model: the "epoch" counts the cancels that hit the id; a frame is dropped when its claim's epoch differs at prepare
        // and is overtaken or cancelled when the epoch moved or a newer ticket was issued since its prepare.
        var epoch = 0
        val claimEpoch = IntArray(3)
        val prepareEpoch = IntArray(3)
        var issued = 0
        val serial = IntArray(3)
        val expectedPrepared = BooleanArray(3)
        fun cancel(kind: Cancel) {
            when (kind) {
                Cancel.FORGET_ID -> { coalescer.forget("d"); epoch++ }
                Cancel.CLEAR -> { coalescer.clear(); epoch++ }
                Cancel.FORGET_OTHER -> coalescer.forget("other")
            }
        }
        schedule.forEachIndexed { position: Int, (frame: Int, event: Int) ->
            if (position == at1 && kind1 != null) cancel(kind1)
            if (position == at2 && kind2 != null) cancel(kind2)
            when (event) {
                1 -> { claims[frame] = coalescer.claim("d"); claimEpoch[frame] = epoch }
                2 -> {
                    val ticket: ProgressCoalescer.PostTicket? = coalescer.prepare("d", NotificationProgress.Determinate(10 + frame * 30), "c$frame", claims[frame])
                    val shouldPrepare: Boolean = claimEpoch[frame] == epoch
                    assertEquals(shouldPrepare, ticket != null, "$label: frame $frame prepare")
                    expectedPrepared[frame] = shouldPrepare
                    if (ticket != null) { tickets[frame] = ticket; prepareEpoch[frame] = epoch; serial[frame] = ++issued }
                }
                else -> {
                    val ticket: ProgressCoalescer.PostTicket? = tickets[frame]
                    if (ticket != null) {
                        val overtaken: Boolean = (0..2).any { other -> other != frame && expectedPrepared[other] && serial[other] > serial[frame] }
                        val shouldBeLive: Boolean = prepareEpoch[frame] == epoch && !overtaken
                        val live: Boolean = coalescer.isLive(ticket)
                        assertEquals(shouldBeLive, live, "$label: frame $frame isLive")
                        if (live) coalescer.complete(ticket, acknowledged = true)
                    }
                    coalescer.release(claims[frame]!!)
                }
            }
        }
        if (at1 >= schedule.size && kind1 != null) cancel(kind1)
        assertEquals(0, coalescer.claimCountForTest(), "$label: claims left behind")
        val inFlight = ProgressCoalescer::class.java.getDeclaredField("inFlight").also { it.isAccessible = true }
        assertEquals(0, (inFlight.get(coalescer) as Map<*, *>).values.sumOf { (it as List<*>).size }, "$label: frames left in flight")
    }
}
