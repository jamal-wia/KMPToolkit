package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * The coalescer protocol of both notifiers, every interleaving of three frames of one id including the
 * moment each is prepared, with a cancel at any point.
 *
 * Android order: prepare, then one finishing step (asked whether still live, notify, complete; or a
 * refusal, rollback), checked against a model of the documented rules. iOS order: prepare, hand-over
 * (asked whether still live, complete without acknowledgement), then a verdict that arrives later
 * (acknowledge or rollback), checked for the one thing a verdict order must never break: a baseline
 * standing for a frame the center does not show.
 */
class ProgressCoalescerProtocolInterleavingAdversarialTest {

    private val progressSets: List<List<NotificationProgress?>> = listOf(
        listOf(NotificationProgress.Determinate(10), NotificationProgress.Determinate(50), NotificationProgress.Determinate(80)),
        listOf(NotificationProgress.Determinate(10), null, NotificationProgress.Determinate(80)),
        listOf(null, NotificationProgress.Determinate(50), NotificationProgress.Determinate(80)),
        listOf(NotificationProgress.Determinate(10), NotificationProgress.Determinate(50), null),
    )

    /** All orders of `steps` events per frame (frame, 1-based event), each frame's events in order. */
    private fun schedules(steps: Int): List<List<Pair<Int, Int>>> {
        val result = mutableListOf<List<Pair<Int, Int>>>()
        fun walk(done: IntArray, acc: List<Pair<Int, Int>>) {
            if (done.all { it == steps }) { result += acc; return }
            for (frame in 0..2) if (done[frame] < steps) {
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

    private fun inFlightCount(coalescer: ProgressCoalescer): Int {
        val field = ProgressCoalescer::class.java.getDeclaredField("inFlight").also { it.isAccessible = true }
        return (field.get(coalescer) as Map<*, *>).values.sumOf { (it as List<*>).size }
    }

    @Test
    fun `android order, every interleaving, matches the model`() {
        var scenarios = 0
        val all: List<List<Pair<Int, Int>>> = schedules(2)
        for (progress in progressSets) for (cancelAt in -1..6) for (mask in 0 until 8) for (schedule in all) {
            scenarios++
            androidOrder(progress, refused = (0..2).filter { mask and (1 shl it) != 0 }.toSet(), schedule, cancelAt)
        }
        assertEquals(4 * 8 * 8 * 90, scenarios)
    }

    private fun androidOrder(progress: List<NotificationProgress?>, refused: Set<Int>, schedule: List<Pair<Int, Int>>, cancelAt: Int) {
        val label = "progress $progress, refused $refused, schedule $schedule, cancel before event $cancelAt"
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val tickets: Array<ProgressCoalescer.PostTicket?> = arrayOfNulls(3)
        // The model.
        val inFlight: MutableList<Int> = mutableListOf()
        var live: Int? = null
        var tray: Int? = null
        val results: MutableMap<Int, String> = mutableMapOf()
        val expected: MutableMap<Int, String> = mutableMapOf()
        fun cancel() {
            coalescer.forget("d")
            inFlight.clear()
            live = null
            tray = null
        }
        schedule.forEachIndexed { position: Int, (frame: Int, event: Int) ->
            if (position == cancelAt) cancel()
            if (event == 1) {
                tickets[frame] = assertNotNull(coalescer.prepare("d", progress[frame], "c$frame"), "$label: frame $frame judged redundant")
                inFlight += frame
                live = frame
            } else {
                val ticket: ProgressCoalescer.PostTicket = tickets[frame]!!
                if (frame in refused) {
                    coalescer.rollback(ticket)
                    results[frame] = "Failed"
                    expected[frame] = "Failed"
                    inFlight.remove(frame)
                    if (live == frame) live = inFlight.lastOrNull()
                } else if (coalescer.isLive(ticket)) {
                    coalescer.complete(ticket, acknowledged = true)
                    results[frame] = "Posted"
                    tray = bucketOf(progress[frame])
                } else {
                    results[frame] = "Coalesced"
                }
                // Model of the same step.
                if (frame !in refused) {
                    if (live == frame) {
                        expected[frame] = "Posted"
                        // Every frame prepared before this one ends with it (the list is in preparation order).
                        inFlight.subList(0, inFlight.indexOf(frame) + 1).clear()
                        if (progress[frame] == null || bucketOf(progress[frame]) == TERMINAL) live = null
                    } else {
                        expected[frame] = "Coalesced"
                        inFlight.remove(frame)
                    }
                }
            }
        }
        if (cancelAt == schedule.size) cancel()
        assertEquals(expected, results, "$label: results")
        assertEquals(0, inFlightCount(coalescer), "$label: frames left in flight")
        val record: ProgressCoalescer.Post? = postedOf(coalescer)
        if (record != null) {
            assertEquals(tray, record.bucket, "$label: the baseline stands for bucket ${record.bucket} (shown=${record.shown}) but the tray shows $tray")
            assertTrue(record.shown, "$label: an unshown baseline with nothing in flight")
        }
    }

    @Test
    fun `ios order, every interleaving, never leaves a baseline for a frame the center does not show`() {
        var scenarios = 0
        val all: List<List<Pair<Int, Int>>> = schedules(3)
        for (progress in progressSets.take(2)) for (cancelAt in listOf(-1, 4, 8)) for (mask in 0 until 8) for (schedule in all) {
            scenarios++
            iosOrder(progress, accepted = (0..2).filter { mask and (1 shl it) != 0 }.toSet(), schedule, cancelAt)
        }
        assertEquals(2 * 3 * 8 * 1680, scenarios)
    }

    private fun iosOrder(progress: List<NotificationProgress?>, accepted: Set<Int>, schedule: List<Pair<Int, Int>>, cancelAt: Int) {
        val label = "progress $progress, accepted $accepted, schedule $schedule, cancel before event $cancelAt"
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val tickets: Array<ProgressCoalescer.PostTicket?> = arrayOfNulls(3)
        val handedOver = BooleanArray(3)
        var tray: Int? = null
        fun cancel() {
            coalescer.forget("d")
            tray = null
        }
        schedule.forEachIndexed { position: Int, (frame: Int, event: Int) ->
            if (position == cancelAt) cancel()
            when (event) {
                1 -> tickets[frame] = assertNotNull(coalescer.prepare("d", progress[frame], "c$frame"), "$label: frame $frame judged redundant")
                2 -> if (coalescer.isLive(tickets[frame]!!)) {
                    handedOver[frame] = true
                    if (frame in accepted) tray = bucketOf(progress[frame])
                    coalescer.complete(tickets[frame]!!)
                }
                else -> if (handedOver[frame]) {
                    if (frame in accepted) coalescer.acknowledge(tickets[frame]!!) else coalescer.rollback(tickets[frame]!!)
                }
            }
        }
        if (cancelAt >= schedule.size) cancel()
        val record: ProgressCoalescer.Post? = postedOf(coalescer)
        if (record != null) {
            assertEquals(tray, record.bucket, "$label: the baseline stands for bucket ${record.bucket} (shown=${record.shown}, refused=${record.refused}) but the center shows $tray")
        }
    }

    private companion object {
        const val TERMINAL: Int = -1
    }
}
