package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration

/**
 * The iOS order with the fourth way a hand-over can end: the caller is cancelled while the center is
 * answering, so the outcome is unknown. Every interleaving of three frames of one id, every combination of
 * verdicts per frame (acknowledged, refused, abandoned with the center having accepted, abandoned with the
 * center having refused), a cancel at several points. The tray is modelled as the set of states it may be in; a
 * baseline may only stand when that set is exactly the baseline's bucket.
 */
class ProgressCoalescerAbandonExplorerAdversarialTest {

    private enum class Verdict { ACCEPTED_ACKNOWLEDGED, REFUSED_ROLLBACK, ACCEPTED_ABANDONED, REFUSED_ABANDONED }

    private val progressSets: List<List<NotificationProgress?>> = listOf(
        listOf(NotificationProgress.Determinate(10), NotificationProgress.Determinate(50), NotificationProgress.Determinate(80)),
        listOf(NotificationProgress.Determinate(10), null, NotificationProgress.Determinate(80)),
        listOf(NotificationProgress.Determinate(50), NotificationProgress.Determinate(50), NotificationProgress.Determinate(60)),
    )

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

    private fun bucketOf(progress: NotificationProgress?): Int = when {
        progress is NotificationProgress.Determinate && progress.percent < 100 -> (progress.percent / 10) * 10
        else -> TERMINAL
    }

    private fun postedOf(coalescer: ProgressCoalescer): ProgressCoalescer.Post? {
        val field = ProgressCoalescer::class.java.getDeclaredField("posted").also { it.isAccessible = true }
        return (field.get(coalescer) as Map<*, *>)["d"] as ProgressCoalescer.Post?
    }

    @Test
    fun `no verdict order, abandoned or not, leaves a baseline the tray may not show`() {
        var scenarios = 0
        val all: List<List<Pair<Int, Int>>> = schedules()
        val verdicts: List<Verdict> = Verdict.values().toList()
        for (progress in progressSets) for (cancelAt in listOf(-1, 5)) for (a in verdicts) for (b in verdicts) for (c in verdicts) for (schedule in all) {
            scenarios++
            run(progress, listOf(a, b, c), schedule, cancelAt)
        }
        assertEquals(3 * 2 * 64 * 1680, scenarios)
    }

    private fun run(progress: List<NotificationProgress?>, verdict: List<Verdict>, schedule: List<Pair<Int, Int>>, cancelAt: Int) {
        val label = "progress $progress, verdicts $verdict, schedule $schedule, cancel before event $cancelAt"
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val tickets: Array<ProgressCoalescer.PostTicket?> = arrayOfNulls(3)
        val handedOver = BooleanArray(3)
        // The states the tray may be in; null is "nothing of this id".
        var possible: Set<Int?> = setOf(null)
        fun cancel() {
            coalescer.forget("d")
            possible = setOf(null)
        }
        schedule.forEachIndexed { position: Int, (frame: Int, event: Int) ->
            if (position == cancelAt) cancel()
            when (event) {
                1 -> tickets[frame] = assertNotNull(coalescer.prepare("d", progress[frame], "c$frame"), "$label: frame $frame judged redundant")
                2 -> if (coalescer.isLive(tickets[frame]!!)) {
                    handedOver[frame] = true
                    val accepted: Boolean = verdict[frame] == Verdict.ACCEPTED_ACKNOWLEDGED || verdict[frame] == Verdict.ACCEPTED_ABANDONED
                    val unknown: Boolean = verdict[frame] == Verdict.ACCEPTED_ABANDONED || verdict[frame] == Verdict.REFUSED_ABANDONED
                    if (unknown) possible = possible + bucketOf(progress[frame]) else if (accepted) possible = setOf(bucketOf(progress[frame]))
                    coalescer.complete(tickets[frame]!!)
                }
                else -> if (handedOver[frame]) {
                    when (verdict[frame]) {
                        Verdict.ACCEPTED_ACKNOWLEDGED -> coalescer.acknowledge(tickets[frame]!!)
                        Verdict.REFUSED_ROLLBACK -> coalescer.rollback(tickets[frame]!!)
                        Verdict.ACCEPTED_ABANDONED, Verdict.REFUSED_ABANDONED -> coalescer.abandon(tickets[frame]!!)
                    }
                }
            }
        }
        if (cancelAt >= schedule.size) cancel()
        val record: ProgressCoalescer.Post? = postedOf(coalescer)
        if (record != null) {
            assertEquals(setOf<Int?>(record.bucket), possible, "$label: the baseline stands for bucket ${record.bucket} (shown=${record.shown}, refused=${record.refused}) but the center may show $possible")
        }
    }

    private companion object {
        const val TERMINAL: Int = -1
    }
}
