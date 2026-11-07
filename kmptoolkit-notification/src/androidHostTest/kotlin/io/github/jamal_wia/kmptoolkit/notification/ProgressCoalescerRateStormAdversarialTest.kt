package io.github.jamal_wia.kmptoolkit.notification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The rate limit under contention, on a clock that only moves between rounds: eight threads race to
 * post a changed frame for each of two ids at one instant, and exactly one of them per id and round
 * may pass. A second pass in the same round is the over-eager progress bar the limit exists to stop,
 * and no round may pass none.
 */
class ProgressCoalescerRateStormAdversarialTest {

    private class StepClock : TimeSource {
        val nanos = AtomicLong(0L)
        override fun markNow(): TimeMark = object : TimeMark {
            private val start: Long = nanos.get()
            override fun elapsedNow(): Duration = (nanos.get() - start).nanoseconds
        }
    }

    @Test
    fun `exactly one racing frame per id passes each interval and a completed frame never lets a second one through`() {
        val clock = StepClock()
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = 1.seconds, timeSource = clock)
        val ids: List<String> = listOf("a", "b")
        val passed = ConcurrentHashMap<String, AtomicInteger>()
        val start = CyclicBarrier(WORKERS + 1)
        val end = CyclicBarrier(WORKERS + 1)
        val workers: List<Thread> = List(WORKERS) { index: Int ->
            thread(name = "kmptoolkit-adversarial-rate-$index") {
                repeat(ROUNDS) { round: Int ->
                    start.await(10, TimeUnit.SECONDS)
                    ids.forEach { id: String ->
                        // Every round's bucket differs from the previous round's, so only the rate can suppress.
                        val ticket: ProgressCoalescer.PostTicket? =
                            coalescer.prepare(id, NotificationProgress.Determinate((round % 9) * 10 + index % 10), "c")
                        if (ticket != null) {
                            passed.getOrPut("$id-$round") { AtomicInteger() }.incrementAndGet()
                            if (coalescer.isLive(ticket)) coalescer.complete(ticket, acknowledged = true)
                        }
                    }
                    end.await(10, TimeUnit.SECONDS)
                }
            }
        }
        repeat(ROUNDS) {
            start.await(10, TimeUnit.SECONDS)
            end.await(10, TimeUnit.SECONDS)
            clock.nanos.addAndGet(1.seconds.inWholeNanoseconds)
        }
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
        assertTrue(workers.none { it.isAlive }, "a worker is stuck")
        repeat(ROUNDS) { round: Int ->
            ids.forEach { id: String ->
                assertEquals(1, passed["$id-$round"]?.get() ?: 0, "round $round, id '$id': frames that passed the limit at one instant")
            }
        }
    }

    private companion object {
        const val WORKERS: Int = 8
        const val ROUNDS: Int = 60
    }
}
