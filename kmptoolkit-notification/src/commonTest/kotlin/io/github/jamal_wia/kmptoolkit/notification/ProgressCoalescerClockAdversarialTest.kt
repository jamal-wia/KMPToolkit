package io.github.jamal_wia.kmptoolkit.notification

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Many callers while the clock jumps past the stale horizon: the prune that drops abandoned ids runs
 * inside concurrent prepares, and must neither throw, nor suppress a terminal or first frame, nor
 * leave the maps larger than the live id set.
 */
@OptIn(ExperimentalAtomicApi::class)
class ProgressCoalescerClockAdversarialTest {

    /** A clock whose reading is an atomic count of milliseconds, safe to advance from any thread. */
    private class AtomicClock : TimeSource {
        private val millis = AtomicInt(0)

        fun advance(by: Duration) {
            millis.addAndFetch(by.inWholeMilliseconds.toInt())
        }

        override fun markNow(): TimeMark = object : TimeMark {
            private val start: Int = millis.load()
            override fun elapsedNow(): Duration = (millis.load() - start).milliseconds
        }
    }

    @Test
    fun `eight workers preparing fresh ids while the clock jumps past the horizon never fail nor suppress a first or terminal frame`() =
        runTest(timeout = 60.seconds) {
            val clock = AtomicClock()
            val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = 500.milliseconds, timeSource = clock)
            val start = CompletableDeferred<Unit>()

            val problems: List<String> = withContext(Dispatchers.Default) {
                withTimeout(40.seconds) {
                    val workers = List(8) { worker: Int ->
                        async {
                            start.await()
                            val random = Random(SEED + worker)
                            val problems: MutableList<String> = mutableListOf()
                            repeat(1_500) { iteration: Int ->
                                val id = "w$worker-$iteration"
                                if (coalescer.prepare(id, NotificationProgress.Determinate(random.nextInt(0, 100))) == null) {
                                    problems += "first frame of $id suppressed (seed=${SEED + worker})"
                                }
                                if (coalescer.prepare(id, null) == null) problems += "terminal frame of $id suppressed (seed=${SEED + worker})"
                                if (iteration % 100 == 99) clock.advance(2.minutes)
                            }
                            problems
                        }
                    }
                    start.complete(Unit)
                    workers.awaitAll().flatten()
                }
            }

            assertTrue(problems.isEmpty(), "first problems of ${problems.size}: ${problems.take(5)}")
            // Terminal frames reset every id, and a never-completed ticket's serial is dropped with it
            // only if the caller says so: nothing here completes, so the serial map is the thing to bound.
            clock.advance(11.minutes)
            coalescer.prepare("probe", NotificationProgress.Determinate(10))
            assertEquals(0, coalescer.postedCountForTest() - 1, "stale baselines survived a prune")
        }

    private companion object {
        const val SEED: Int = 20_261_107
    }
}
