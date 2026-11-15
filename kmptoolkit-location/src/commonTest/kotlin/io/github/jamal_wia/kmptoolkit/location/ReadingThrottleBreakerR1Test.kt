@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Breaker round 1 of group loc-core (LOC-1, LOC-2, LOC-3, LOC-5, LOC-6; decision D-23): tests that try
 * to break the common throttle `throttledBy` (PLAN D16, M10, D-21 seed rule, KDoc of `ReadingThrottle`)
 * and its composition with `completingAfterTerminalReading`. They attack what the executor's own suite
 * does not: a throwing consumer and a throwing seed predicate, backpressure under a fast upstream
 * (unbounded inbox growth), concurrent and nested collections of one throttled flow, a collector that
 * cancels itself from inside `emit`, huge and sub-millisecond intervals (deadline arithmetic), reseeds
 * after a reason (several seeds per stream), and a virtual-time storm with reseeds and exact deadline
 * collisions against a reference model written from the KDoc rules.
 *
 * Virtual time only (`runTest`, `testScheduler.timeSource`); `runTest(timeout)` is a hang bound, never
 * a timing assertion; every random choice comes from `Random(seed)` and the seed is in each message.
 */
class ReadingThrottleBreakerR1Test {

    private fun at(latitude: Double): LocationReading =
        LocationReading.Available(LocationFix(GeoCoordinates(latitude, 0.0), timestampEpochMillis = 0L))

    private fun unavailable(reason: LocationUnavailability): LocationReading = LocationReading.Unavailable(reason)

    private fun options(distanceMeters: Double = 0.0, interval: Duration = Duration.ZERO): LocationUpdatesOptions =
        LocationUpdatesOptions(minUpdateDistanceMeters = distanceMeters, minUpdateInterval = interval)

    private data class Timed(val atMillis: Long, val reading: LocationReading)

    /** An upstream that plays [steps] at absolute virtual millis, counts registrations and releases, then suspends or completes. */
    private class CountingUpstream(
        private val scheduler: TestCoroutineScheduler,
        private val steps: List<Pair<Long, LocationReading>>,
        private val thenSuspend: Boolean = true,
    ) {
        var registered: Int = 0
            private set
        var released: Int = 0
            private set

        val flow: Flow<LocationReading> = flow {
            registered++
            try {
                for ((millis: Long, reading: LocationReading) in steps) {
                    val wait: Long = millis - scheduler.currentTime
                    if (wait > 0) delay(wait)
                    emit(reading)
                }
                if (thenSuspend) awaitCancellation()
            } finally {
                released++
            }
        }
    }

    private fun TestScope.assertNoTimerLeft(message: String) {
        val before: Long = currentTime
        testScheduler.advanceUntilIdle()
        assertEquals(before, currentTime, "$message: a surviving timer would have moved the virtual clock")
    }

    // region exceptions from the consumer and from the seed predicate

    @Test
    fun `a consumer that throws on the fix emitted at the deadline gets its exception and the upstream is released`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = CountingUpstream(scheduler, listOf(0L to at(1.0), 100L to at(2.0)))
        val received: MutableList<Timed> = mutableListOf()
        var failure: Throwable? = null

        try {
            upstream.flow.throttledBy(options(interval = 10.seconds), scheduler.timeSource).collect { reading: LocationReading ->
                received += Timed(currentTime, reading)
                if (reading == at(2.0)) throw IllegalStateException("consumer failure")
            }
        } catch (expected: IllegalStateException) {
            failure = expected
        }

        assertEquals("consumer failure", failure?.message, "the consumer's own exception reaches the caller unchanged")
        assertEquals(listOf(Timed(0, at(1.0)), Timed(10_000, at(2.0))), received)
        assertEquals(1, upstream.registered)
        assertEquals(1, upstream.released, "a throwing consumer must not leave the upstream registration behind")
        assertNoTimerLeft("after a consumer failure")
    }

    @Test
    fun `a seed predicate that throws fails the collection and releases the upstream`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = CountingUpstream(scheduler, listOf(0L to at(1.0)))
        var failure: Throwable? = null

        try {
            upstream.flow.throttledBy(options(interval = 10.seconds), scheduler.timeSource, isSeed = { throw IllegalArgumentException("predicate") })
                .collect { }
        } catch (expected: IllegalArgumentException) {
            failure = expected
        }

        assertEquals("predicate", failure?.message)
        assertEquals(1, upstream.released, "the upstream is released when the predicate throws")
        assertNoTimerLeft("after a predicate failure")
    }

    // endregion

    // region backpressure: the inbox must not grow without bound

    @Test
    fun `a fast upstream behind a blocked consumer is suspended instead of filling an unbounded inbox`() = runTest(timeout = 60.seconds) {
        val total = 10_000
        var produced = 0
        val upstream: Flow<LocationReading> = flow {
            for (index: Int in 0 until total) {
                produced++
                emit(at(index * 0.001))
            }
        }
        val gate = CompletableDeferred<Unit>()
        val received: MutableList<LocationReading> = mutableListOf()

        val job: Job = launch {
            upstream.throttledBy(options(), testScheduler.timeSource).collect { reading: LocationReading ->
                received += reading
                if (received.size == 1) gate.await()
            }
        }
        runCurrent()

        // Control: the consumer holds the first reading and the upstream has run as far as it is allowed to.
        assertEquals(1, received.size)
        assertTrue(produced > 1, "control: the upstream ran ahead of the blocked consumer (produced=$produced)")
        assertTrue(
            produced <= 200,
            "the upstream ran $produced of $total readings ahead of a consumer that took one: the inbox is not bounded",
        )

        gate.complete(Unit)
        job.join()
        assertEquals(total, received.size, "nothing is lost once the consumer resumes (no interval, no distance)")
        assertEquals(List(total) { index: Int -> at(index * 0.001) }, received, "order is kept")
    }

    // endregion

    // region concurrent, nested and self-cancelling collections

    @Test
    fun `two concurrent collections of one throttled flow are independent`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = CountingUpstream(scheduler, listOf(0L to at(1.0), 100L to at(2.0)))
        val throttled: Flow<LocationReading> = upstream.flow.throttledBy(options(interval = 10.seconds), scheduler.timeSource)
        val first: MutableList<Timed> = mutableListOf()
        val second: MutableList<Timed> = mutableListOf()

        val firstJob: Job = launch { throttled.collect { first += Timed(currentTime, it) } }
        advanceTimeBy(5_000)
        runCurrent()
        // The second collection starts while the first one holds a fix: it must see its own first fix at once.
        val secondStart: Long = currentTime
        val secondJob: Job = launch { throttled.collect { second += Timed(currentTime, it) } }
        advanceTimeBy(30_000)
        runCurrent()
        firstJob.cancelAndJoin()
        secondJob.cancelAndJoin()

        assertEquals(listOf(Timed(0, at(1.0)), Timed(10_000, at(2.0))), first)
        // The second upstream replays the absolute script: both steps are already past, so they arrive at once;
        // the first passes, the second is held for the second collection's own interval.
        assertEquals(listOf(Timed(secondStart, at(1.0)), Timed(secondStart + 10_000, at(2.0))), second)
        assertEquals(2, upstream.registered)
        assertEquals(2, upstream.released)
        assertNoTimerLeft("after both concurrent collections were cancelled")
    }

    @Test
    fun `a consumer that collects the same throttled flow from inside its collect does not deadlock`() = runTest(timeout = 60.seconds) {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = CountingUpstream(scheduler, listOf(0L to at(1.0), 100L to at(2.0)))
        val throttled: Flow<LocationReading> = upstream.flow.throttledBy(options(interval = 1.hours), scheduler.timeSource)
        val nested: MutableList<LocationReading> = mutableListOf()

        val job: Job = launch {
            throttled.collect { reading: LocationReading ->
                // Re-entrant use from the consumer: a second, nested collection of the very same flow.
                nested += throttled.first()
                if (reading == at(1.0)) currentCoroutineContext().job.cancel()
            }
        }
        job.join()

        assertEquals(listOf(at(1.0)), nested)
        assertEquals(2, upstream.registered)
        assertEquals(2, upstream.released, "both the nested and the outer registration are released")
        assertNoTimerLeft("after a nested collection")
    }

    @Test
    fun `a collector that cancels itself from inside emit while a fix is held releases everything`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = CountingUpstream(scheduler, listOf(0L to at(1.0), 100L to at(2.0), 200L to at(3.0), 300L to unavailable(LocationUnavailability.NO_FIX)))
        val received: MutableList<Timed> = mutableListOf()

        val job: Job = launch {
            upstream.flow.throttledBy(options(interval = 1.hours), scheduler.timeSource).collect { reading: LocationReading ->
                received += Timed(currentTime, reading)
                // Cancel on the reason: at(3.0) was held and then discarded, the stream is still live.
                if (reading is LocationReading.Unavailable) currentCoroutineContext().job.cancel()
            }
        }
        job.join()

        assertEquals(listOf(Timed(0, at(1.0)), Timed(300, unavailable(LocationUnavailability.NO_FIX))), received)
        assertEquals(1, upstream.released)
        assertNoTimerLeft("after the collector cancelled itself")
        assertEquals(2, received.size, "no held fix is delivered after the self-cancellation")
    }

    // endregion

    // region extreme intervals

    @Test
    fun `a huge finite interval holds the fix to its far deadline without overflowing the arithmetic`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val interval: Duration = (Long.MAX_VALUE / 4).milliseconds
        assertTrue(interval.isFinite(), "control: the interval is finite and therefore valid")
        val result: List<Timed> = flow {
            emit(at(1.0))
            delay(100)
            emit(at(2.0))
        }.throttledBy(options(interval = interval), scheduler.timeSource)
            .map { reading: LocationReading -> Timed(scheduler.currentTime, reading) }
            .toList()

        assertEquals(
            listOf(Timed(0, at(1.0)), Timed(interval.inWholeMilliseconds, at(2.0))),
            result,
            "the held fix comes out exactly at the end of the huge interval, and the stream then completes",
        )
    }

    @Test
    fun `a huge interval never delays a reason and the fix after it passes at once`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = CountingUpstream(
            scheduler,
            listOf(
                0L to at(1.0),
                100L to at(2.0),
                10.days.inWholeMilliseconds to unavailable(LocationUnavailability.SERVICE_DISABLED),
                10.days.inWholeMilliseconds + 1 to at(3.0),
            ),
        )
        val received: MutableList<Timed> = mutableListOf()
        val job: Job = launch {
            upstream.flow.throttledBy(options(interval = (365 * 100_000).days), scheduler.timeSource).collect { received += Timed(currentTime, it) }
        }
        advanceTimeBy(11.days.inWholeMilliseconds)
        runCurrent()
        job.cancelAndJoin()

        assertEquals(
            listOf(
                Timed(0, at(1.0)),
                Timed(10.days.inWholeMilliseconds, unavailable(LocationUnavailability.SERVICE_DISABLED)),
                Timed(10.days.inWholeMilliseconds + 1, at(3.0)),
            ),
            received,
        )
        assertEquals(1, upstream.released)
        assertNoTimerLeft("after a huge interval was cancelled")
    }

    @Test
    fun `a one-nanosecond interval delays a same-instant fix by at most the timer resolution and never loses it`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val result: List<Timed> = flow {
            emit(at(1.0))
            emit(at(2.0))
            emit(at(3.0))
        }.throttledBy(options(interval = 1.nanoseconds), scheduler.timeSource)
            .map { reading: LocationReading -> Timed(scheduler.currentTime, reading) }
            .toList()

        assertEquals(at(1.0), result.first().reading)
        assertEquals(at(3.0), result.last().reading, "the newest fix is never lost behind a sub-millisecond interval")
        assertTrue(result.size in 2..3, "result=$result")
        assertTrue(result.all { timed: Timed -> timed.atMillis <= 1 }, "nothing waits longer than one timer tick: $result")
    }

    // endregion

    // region several seeds per stream (reseed after a reason)

    @Test
    fun `a reseed after the service returns lets the next far live fix pass at once again`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val firstSeed: LocationReading = at(1.0)
        val secondSeed: LocationReading = at(5.0)
        val result: List<Timed> = flow {
            emit(firstSeed)
            delay(1_000); emit(at(2.0))
            delay(1_000); emit(unavailable(LocationUnavailability.SERVICE_DISABLED))
            delay(1_000); emit(secondSeed)
            delay(1_000); emit(at(6.0))
            delay(1_000); emit(at(7.0))
        }.throttledBy(options(interval = 60.seconds), scheduler.timeSource, isSeed = { it === firstSeed || it === secondSeed })
            .map { reading: LocationReading -> Timed(scheduler.currentTime, reading) }
            .toList()

        assertEquals(
            listOf(
                Timed(0, firstSeed),
                Timed(1_000, at(2.0)),
                Timed(2_000, unavailable(LocationUnavailability.SERVICE_DISABLED)),
                Timed(3_000, secondSeed),
                Timed(4_000, at(6.0)),
                // The interval starts with the first live fix after the reseed, at 4 000.
                Timed(64_000, at(7.0)),
            ),
            result,
        )
    }

    // endregion

    // region composition with completingAfterTerminalReading

    @Test
    fun `a terminal reason after a held fix completes the throttled stream and releases the upstream and the timer`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = CountingUpstream(
            scheduler,
            listOf(0L to at(1.0), 100L to at(2.0), 200L to unavailable(LocationUnavailability.PERMISSION_MISSING), 300L to at(3.0)),
        )

        val result: List<Timed> = upstream.flow.throttledBy(options(interval = 1.hours), scheduler.timeSource)
            .completingAfterTerminalReading()
            .map { reading: LocationReading -> Timed(scheduler.currentTime, reading) }
            .toList()

        assertEquals(listOf(Timed(0, at(1.0)), Timed(200, unavailable(LocationUnavailability.PERMISSION_MISSING))), result)
        assertEquals(1, upstream.released, "the terminal reading releases the platform registration at once")
        assertNoTimerLeft("after a terminal reading")
    }

    // endregion

    // region storm with reseeds and exact deadline collisions

    private class Step(val atMillis: Long, val reading: LocationReading, val seed: Boolean)

    /**
     * The rules of the `ReadingThrottle` KDoc on absolute time, written for this class without reading the
     * operator: a due deadline is honoured before a step at or after it; a reason passes, discards the held fix
     * and the reference; a first fix passes and starts the interval unless it is a seed; a too-close fix is
     * dropped and discards the held one; a far fix passes when the interval is over (or never started), else it
     * becomes the held fix; when the script ends a held fix comes out at its deadline.
     */
    private fun model(steps: List<Step>, distanceMeters: Double, intervalMillis: Long): List<Timed> {
        val out: MutableList<Timed> = mutableListOf()
        var reference: LocationFix? = null
        var referenceAt: Long? = null
        var held: LocationFix? = null

        fun flushDue(now: Long) {
            val fix: LocationFix = held ?: return
            val start: Long = referenceAt ?: return
            val deadline: Long = start + intervalMillis
            if (deadline <= now) {
                out += Timed(deadline, LocationReading.Available(fix))
                reference = fix
                referenceAt = deadline
                held = null
            }
        }

        for (step: Step in steps) {
            flushDue(step.atMillis)
            when (val reading: LocationReading = step.reading) {
                is LocationReading.Unavailable -> {
                    out += Timed(step.atMillis, reading)
                    reference = null
                    referenceAt = null
                    held = null
                }
                is LocationReading.Available -> {
                    val current: LocationFix? = reference
                    val start: Long? = referenceAt
                    when {
                        current == null -> {
                            out += Timed(step.atMillis, reading)
                            reference = reading.fix
                            referenceAt = if (step.seed) null else step.atMillis
                        }
                        current.coordinates.distanceInMetersTo(reading.fix.coordinates) < distanceMeters -> held = null
                        start == null || step.atMillis - start >= intervalMillis -> {
                            out += Timed(step.atMillis, reading)
                            reference = reading.fix
                            referenceAt = if (step.seed) null else step.atMillis
                            held = null
                        }
                        else -> held = reading.fix
                    }
                }
            }
        }
        flushDue(Long.MAX_VALUE)
        return out
    }

    /**
     * A script whose seeds are where a platform puts them: the first reading of the stream and the first
     * reading after each reason (a reseed when the service returns). About a third of the gaps land exactly
     * on the deadline of the last emitted fix, to force the reading-versus-tick collision.
     */
    private fun script(rng: Random, length: Int, intervalMillis: Long): List<Step> {
        var now = 0L
        var lastEmittedGuess = 0L
        var seedSlot = true
        return List(length) {
            now += when (rng.nextInt(3)) {
                0 -> 0L
                1 -> maxOf(0L, lastEmittedGuess + intervalMillis - now)
                else -> rng.nextLong(1, 7_000)
            }
            val reading: LocationReading = if (rng.nextInt(8) == 0) {
                unavailable(LocationUnavailability.entries[rng.nextInt(LocationUnavailability.entries.size)])
            } else {
                at(rng.nextInt(0, 6) * 0.001)
            }
            val seed: Boolean = reading is LocationReading.Available && seedSlot && rng.nextBoolean()
            seedSlot = reading is LocationReading.Unavailable
            if (reading is LocationReading.Available && rng.nextInt(3) == 0) lastEmittedGuess = now
            Step(now, reading, seed)
        }
    }

    @Test
    fun `a virtual-time storm with reseeds and deadline collisions matches the reference model`() = runTest(timeout = 120.seconds) {
        val scheduler: TestCoroutineScheduler = testScheduler
        val distances: List<Double> = listOf(0.0, 100.0, 150.0, 250.0)
        val intervals: List<Long> = listOf(0L, 1_000L, 5_000L, 30_000L)
        for (round: Int in 0 until 300) {
            val seed: Int = GEO_TEST_SEED + 50_000 + round
            val rng = Random(seed)
            val distance: Double = distances[rng.nextInt(distances.size)]
            val intervalMillis: Long = intervals[rng.nextInt(intervals.size)]
            val steps: List<Step> = script(rng, rng.nextInt(1, 40), intervalMillis)
            val seeds: List<LocationReading> = steps.filter { it.seed }.map { it.reading }
            val base: Long = currentTime

            val actual: List<Timed> = flow {
                for (step: Step in steps) {
                    val wait: Long = base + step.atMillis - scheduler.currentTime
                    if (wait > 0) delay(wait)
                    emit(step.reading)
                }
            }.throttledBy(
                options(distanceMeters = distance, interval = intervalMillis.milliseconds),
                scheduler.timeSource,
                isSeed = { reading: LocationReading -> seeds.any { it === reading } },
            ).map { reading: LocationReading -> Timed(scheduler.currentTime - base, reading) }
                .toList()

            assertEquals(
                model(steps, distance, intervalMillis),
                actual,
                "seed=$seed distance=$distance interval=${intervalMillis}ms steps=${steps.map { "${it.atMillis}:${it.reading}${if (it.seed) "(seed)" else ""}" }}",
            )
        }
    }

    // endregion

    // region real threads: concurrent collections of one flow, each cancelled mid-interval

    @Test
    fun `collections started and cancelled on real threads mid-interval each release their upstream`() = runTest(timeout = 120.seconds) {
        // The real-thread variant of the concurrent-collection attack: the throttle runs on Dispatchers.Default,
        // each collection holds a fix behind a one-hour interval of the real clock and is cancelled while it waits.
        val workers = 32
        val registered = AtomicInt(0)
        val released = AtomicInt(0)
        val upstream: Flow<LocationReading> = flow {
            registered.incrementAndFetch()
            try {
                emit(at(1.0))
                emit(at(2.0))
                awaitCancellation()
            } finally {
                released.incrementAndFetch()
            }
        }
        val throttled: Flow<LocationReading> = upstream.throttledBy(options(interval = 1.hours))
        withContext(Dispatchers.Default) {
            val jobs = (0 until workers).map {
                val firstSeen = CompletableDeferred<Unit>()
                val job: Job = launch(start = CoroutineStart.DEFAULT) {
                    throttled.collect { reading: LocationReading -> if (reading == at(1.0)) firstSeen.complete(Unit) }
                }
                async { firstSeen.await(); job.cancelAndJoin() }
            }
            jobs.forEach { it.await() }
        }
        assertEquals(workers, registered.load(), "control: every collection registered")
        assertEquals(workers, released.load(), "every collection released its upstream on cancellation")
    }

    // endregion
}
