package io.github.jamal_wia.kmptoolkit.notification

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * Round 18 (leaks): whatever legal mix of the coalescer's protocol calls ran (both platforms' orders, tickets
 * that are never settled, claims, cancels, clears, long pauses), once everything has gone quiet for longer than
 * the horizon and one later frame has been prepared, nothing but that frame's id may be left in any of the
 * five maps; and while it runs, no map may name an id outside the pool or an in-flight ticket the caller
 * has already settled. The maps are read by reflection (the round 12 soak does the same for typical flows;
 * this one drives the states typical flows never reach: abandoned hand-overs, orphans, refused-after-complete).
 */
class ProgressCoalescerQuiescenceLeakFuzzAdversarialTest {

    private enum class State { PREPARED, LIVE, COMPLETED_UNACKED, SETTLED, ORPHAN }

    private class Open(val ticket: ProgressCoalescer.PostTicket, val id: String, var state: State)

    private fun map(coalescer: ProgressCoalescer, field: String): Map<*, *> {
        val f = ProgressCoalescer::class.java.getDeclaredField(field).also { it.isAccessible = true }
        return f.get(coalescer) as Map<*, *>
    }

    private val ids: List<String> = listOf("a", "b", "c", "d", "e", "f")

    @Test
    fun `after any legal protocol run the maps drain once the horizon has passed and never name a settled ticket`() {
        repeat(150) { seed: Int ->
            val random = Random(seed * 31 + 5)
            val clock = TestTimeSource()
            val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = 500.milliseconds, timeSource = clock)
            val open: MutableList<Open> = mutableListOf()
            val claims: MutableList<ProgressCoalescer.PostClaim> = mutableListOf()
            val log = StringBuilder()

            fun check(where: String) {
                val posted: Map<*, *> = map(coalescer, "posted")
                val latest: Map<*, *> = map(coalescer, "latest")
                val lastShown: Map<*, *> = map(coalescer, "lastShown")
                val inFlight: Map<*, *> = map(coalescer, "inFlight")
                for (name: String in listOf("posted", "latest", "lastShown", "inFlight")) {
                    val keys: Set<*> = map(coalescer, name).keys
                    assertTrue(keys.all { it in ids }, "$where: map $name names an id outside the pool: $keys. Steps: $log")
                }
                assertTrue(posted.size <= ids.size && latest.size <= ids.size && lastShown.size <= ids.size, "$where: a map outgrew the pool. Steps: $log")
                val unsettled: Set<ProgressCoalescer.PostTicket> = open
                    .filter { it.state != State.SETTLED }
                    .mapTo(mutableSetOf()) { it.ticket }
                for ((_: Any?, list: Any?) in inFlight) {
                    for (ticket: Any? in list as List<*>) {
                        assertTrue(ticket in unsettled, "$where: inFlight holds a ticket the caller already settled. Steps: $log")
                    }
                }
                assertEquals(claims.size >= map(coalescer, "claims").values.sumOf { (it as List<*>).size }, true, "$where: more claims registered than open. Steps: $log")
            }

            repeat(250) { step: Int ->
                val where = "seed $seed step $step"
                when (random.nextInt(12)) {
                    0, 1, 2 -> {
                        val id: String = ids.random(random)
                        val progress: NotificationProgress? = when (random.nextInt(6)) {
                            0 -> null
                            1 -> NotificationProgress.Indeterminate
                            2 -> NotificationProgress.Determinate(100)
                            else -> NotificationProgress.Determinate(random.nextInt(0, 100))
                        }
                        val claim: ProgressCoalescer.PostClaim? = if (random.nextBoolean()) coalescer.claim(id).also { claims += it } else null
                        val ticket: ProgressCoalescer.PostTicket? = coalescer.prepare(id, progress, random.nextInt(2), claim)
                        log.append("prepare($id,$progress,claim=${claim != null})=${ticket != null}; ")
                        if (ticket != null) open += Open(ticket, id, State.PREPARED)
                    }

                    3, 4, 5, 6 -> {
                        val candidates: List<Open> = open.filter { it.state == State.PREPARED || it.state == State.LIVE || it.state == State.COMPLETED_UNACKED }
                        if (candidates.isNotEmpty()) {
                            val target: Open = candidates.random(random)
                            when (target.state) {
                                State.PREPARED -> when (random.nextInt(4)) {
                                    0 -> { coalescer.rollback(target.ticket); target.state = State.SETTLED; log.append("rollbackBeforeLive; ") }
                                    1 -> { target.state = State.ORPHAN; log.append("orphan; ") }
                                    else -> if (coalescer.isLive(target.ticket)) {
                                        target.state = State.LIVE
                                        log.append("live; ")
                                    } else {
                                        target.state = State.SETTLED
                                        log.append("overtaken; ")
                                    }
                                }

                                State.LIVE -> when (random.nextInt(4)) {
                                    0 -> { coalescer.complete(target.ticket, acknowledged = true); target.state = State.SETTLED; log.append("completeAck; ") }
                                    1 -> { coalescer.complete(target.ticket); target.state = State.COMPLETED_UNACKED; log.append("completeUnacked; ") }
                                    2 -> { coalescer.rollback(target.ticket); target.state = State.SETTLED; log.append("rollbackLive; ") }
                                    else -> { target.state = State.ORPHAN; log.append("orphanLive; ") }
                                }

                                else -> {
                                    when (random.nextInt(3)) {
                                        0 -> coalescer.acknowledge(target.ticket)
                                        1 -> coalescer.rollback(target.ticket)
                                        else -> coalescer.abandon(target.ticket)
                                    }
                                    target.state = State.SETTLED
                                    log.append("verdict; ")
                                }
                            }
                        }
                    }

                    7 -> if (claims.isNotEmpty()) {
                        coalescer.release(claims.removeAt(random.nextInt(claims.size)))
                        log.append("release; ")
                    }

                    8 -> {
                        val id: String = ids.random(random)
                        coalescer.forget(id)
                        log.append("forget($id); ")
                    }

                    9 -> if (random.nextInt(4) == 0) {
                        coalescer.clear()
                        log.append("clear; ")
                    }

                    else -> {
                        clock += listOf(0, 100, 499, 600, 4_000, 90_000).random(random).milliseconds
                        log.append("tick; ")
                    }
                }
                check(where)
            }

            // Quiet down: the caller releases every claim (its `finally`), then nothing happens for longer than the
            // horizon, and one new frame of a fresh id makes the coalescer scan.
            claims.forEach { coalescer.release(it) }
            claims.clear()
            clock += 12.minutes
            coalescer.prepare("z", NotificationProgress.Determinate(5), 0, null)
            clock += 12.minutes
            coalescer.prepare("z", NotificationProgress.Determinate(15), 0, null)
            for (name: String in listOf("posted", "latest", "lastShown", "inFlight", "claims")) {
                val keys: Set<*> = map(coalescer, name).keys
                assertTrue(keys.all { it == "z" }, "seed $seed: map $name still holds ${keys - "z"} after the horizon. Steps: $log")
            }
        }
    }
}
