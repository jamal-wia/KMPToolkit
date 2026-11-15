@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Breaker round 2 of group loc-core (LOC-1, LOC-2, LOC-3, LOC-5, LOC-6; decision D-23): tests that try
 * to break the seed marks added in round 4 (`StreamReading.seed` / `StreamReading.live`,
 * `throttledByMarks`, the `pendingIsSeed` state of `ReadingThrottle`) against the KDoc of
 * `ReadingThrottle` and the D-21 rule ("every seed counts ... a seed that has to wait for the end of an
 * interval is still emitted as a seed").
 *
 * Attacks: a storm where a seed may sit ANYWHERE in the script (inside an interval, twice in a row, as an
 * `Unavailable`, on an exact deadline) against a reference model written from the KDoc, with both entries
 * (marks and the identity predicate) on seeds that differ from the executor's storm; a held seed replaced
 * by a live fix and the reverse; a stale seed flag after a held seed was dropped or discarded by a reason;
 * a zero interval never starting a timer; a collector that cancels itself while the held seed is emitted
 * at its deadline (the Tick path); the composition with `completingAfterTerminalReading` while a seed is
 * held; backpressure through the marked entry.
 *
 * Virtual time only (`runTest`, `testScheduler.timeSource`); `runTest(timeout)` is a hang bound, never a
 * timing assertion; every random choice comes from `Random(seed)` and the seed is in each message.
 */
class ReadingThrottleBreakerR2Test {

    /** About 111.195 m per 0.001 degree of latitude on the sphere. */
    private fun at(latitude: Double): LocationReading =
        LocationReading.Available(LocationFix(GeoCoordinates(latitude, 0.0), timestampEpochMillis = 0L))

    private fun unavailable(reason: LocationUnavailability): LocationReading = LocationReading.Unavailable(reason)

    private fun options(distanceMeters: Double = 0.0, interval: Duration = Duration.ZERO): LocationUpdatesOptions =
        LocationUpdatesOptions(minUpdateDistanceMeters = distanceMeters, minUpdateInterval = interval)

    private data class Timed(val atMillis: Long, val reading: LocationReading)

    private class Step(val atMillis: Long, val reading: LocationReading, val seed: Boolean) {
        val item: StreamReading
            get() = if (seed) StreamReading.seed(reading) else StreamReading.live(reading)

        override fun toString(): String = "$atMillis:$reading${if (seed) "(seed)" else ""}"
    }

    private fun seed(atMillis: Long, reading: LocationReading): Step = Step(atMillis, reading, seed = true)

    private fun live(atMillis: Long, reading: LocationReading): Step = Step(atMillis, reading, seed = false)

    /** Plays [steps] at absolute virtual millis (relative to [base]), counts registrations and releases. */
    private class MarkedUpstream(
        private val scheduler: TestCoroutineScheduler,
        private val steps: List<Step>,
        private val thenSuspend: Boolean,
        private val base: Long = 0L,
    ) {
        var registered: Int = 0
            private set
        var released: Int = 0
            private set

        val flow: Flow<StreamReading> = flow {
            registered++
            try {
                for (step: Step in steps) {
                    val wait: Long = base + step.atMillis - scheduler.currentTime
                    if (wait > 0) delay(wait)
                    emit(step.item)
                }
                if (thenSuspend) awaitCancellation()
            } finally {
                released++
            }
        }
    }

    private suspend fun TestScope.runMarked(options: LocationUpdatesOptions, steps: List<Step>): List<Timed> {
        val scheduler: TestCoroutineScheduler = testScheduler
        val base: Long = currentTime
        return MarkedUpstream(scheduler, steps, thenSuspend = false, base = base).flow
            .throttledByMarks(options, scheduler.timeSource)
            .map { reading: LocationReading -> Timed(scheduler.currentTime - base, reading) }
            .toList()
    }

    private fun TestScope.assertNoTimerLeft(message: String) {
        val before: Long = currentTime
        testScheduler.advanceUntilIdle()
        assertEquals(before, currentTime, "$message: a surviving timer would have moved the virtual clock")
    }

    // region reference model and storm

    /**
     * The KDoc rules of `ReadingThrottle` on absolute time, written for this class without reading the operator:
     * a due deadline is honoured before a step at or after it; a reason passes and discards the held fix and the
     * reference; a first fix passes; a too-close fix is dropped and discards the held one; a far fix passes when
     * the interval is over or never started, else it becomes the held fix together with its mark (newest wins);
     * an emitted seed (at once or at its deadline) sets the reference but no emission time; when the script ends a
     * held fix comes out at its deadline.
     */
    private fun model(steps: List<Step>, distanceMeters: Double, intervalMillis: Long): List<Timed> {
        val out: MutableList<Timed> = mutableListOf()
        var reference: LocationFix? = null
        var referenceAt: Long? = null
        var held: LocationFix? = null
        var heldSeed = false

        fun emit(at: Long, fix: LocationFix, asSeed: Boolean) {
            out += Timed(at, LocationReading.Available(fix))
            reference = fix
            referenceAt = if (asSeed) null else at
            held = null
        }

        fun flushDue(now: Long) {
            val fix: LocationFix = held ?: return
            val start: Long = referenceAt ?: error("a held fix always has a running interval")
            val deadline: Long = start + intervalMillis
            if (deadline <= now) emit(deadline, fix, heldSeed)
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
                        current == null -> emit(step.atMillis, reading.fix, step.seed)
                        current.coordinates.distanceInMetersTo(reading.fix.coordinates) < distanceMeters -> held = null
                        start == null || step.atMillis - start >= intervalMillis -> emit(step.atMillis, reading.fix, step.seed)
                        else -> {
                            held = reading.fix
                            heldSeed = step.seed
                        }
                    }
                }
            }
        }
        flushDue(Long.MAX_VALUE)
        return out
    }

    /**
     * A script where a seed can be anywhere: about a third of the fixes are marked seeds wherever they fall
     * (inside an interval, twice in a row, after a reason), an `Unavailable` is sometimes marked too, and the
     * gaps hit the last guessed deadline exactly, one millisecond before and one after it.
     */
    private fun script(rng: Random, length: Int, intervalMillis: Long): List<Step> {
        var now = 0L
        var anchor = 0L
        return List(length) {
            now += when (rng.nextInt(5)) {
                0 -> 0L
                1 -> maxOf(0L, anchor + intervalMillis - now)
                2 -> maxOf(0L, anchor + intervalMillis - now - 1)
                3 -> maxOf(0L, anchor + intervalMillis - now + 1)
                else -> rng.nextLong(1, 9_000)
            }
            val reading: LocationReading = if (rng.nextInt(7) == 0) {
                unavailable(LocationUnavailability.entries[rng.nextInt(LocationUnavailability.entries.size)])
            } else {
                at(rng.nextInt(0, 6) * 0.001)
            }
            if (rng.nextInt(3) == 0) anchor = now
            Step(now, reading, seed = rng.nextInt(3) == 0)
        }
    }

    @Test
    fun `seeds anywhere in a storm match the reference model through both entries`() = runTest(timeout = 300.seconds) {
        val scheduler: TestCoroutineScheduler = testScheduler
        val distances: List<Double> = listOf(0.0, 100.0, 150.0, 250.0)
        val intervals: List<Long> = listOf(0L, 1L, 1_000L, 5_000L, 30_000L)
        var heldSeedsSeen = 0
        for (round: Int in 0 until 400) {
            val seed: Int = GEO_TEST_SEED + 92_000 + round
            val rng = Random(seed)
            val distance: Double = distances[rng.nextInt(distances.size)]
            val intervalMillis: Long = intervals[rng.nextInt(intervals.size)]
            val steps: List<Step> = script(rng, rng.nextInt(1, 45), intervalMillis)
            val expected: List<Timed> = model(steps, distance, intervalMillis)
            val note = "seed=$seed distance=$distance interval=${intervalMillis}ms steps=$steps"

            val marked: List<Timed> = runMarked(options(distance, intervalMillis.milliseconds), steps)
            assertEquals(expected, marked, "throttledByMarks: $note")

            val seedObjects: List<LocationReading> = steps.filter { it.seed }.map { it.reading }
            val base: Long = currentTime
            val predicate: List<Timed> = MarkedUpstream(scheduler, steps.map { live(it.atMillis, it.reading) }, thenSuspend = false, base = base)
                .flow
                .map { item: StreamReading -> item.reading }
                .throttledBy(
                    options(distance, intervalMillis.milliseconds),
                    scheduler.timeSource,
                    isSeed = { reading: LocationReading -> seedObjects.any { it === reading } },
                )
                .map { reading: LocationReading -> Timed(scheduler.currentTime - base, reading) }
                .toList()
            assertEquals(expected, predicate, "throttledBy(isSeed): $note")

            // Count the scripts where a seed was really held and emitted at a deadline (control: the storm reaches
            // the new pendingIsSeed path, not only seeds that pass at once).
            if (heldSeedEmitted(steps, distance, intervalMillis)) heldSeedsSeen++
        }
        assertTrue(heldSeedsSeen > 20, "control: the storm must hold seeds behind an interval; it did in $heldSeedsSeen scripts")
    }

    /** Whether the model emitted at least one seed at a deadline later than the step that carried it. */
    private fun heldSeedEmitted(steps: List<Step>, distanceMeters: Double, intervalMillis: Long): Boolean {
        val seedFixes: Set<LocationReading> = steps.filter { it.seed && it.reading is LocationReading.Available }
            .map { it.reading }.toSet()
        if (seedFixes.isEmpty()) return false
        val arrivals: Map<LocationReading, List<Long>> = steps.groupBy({ it.reading }, { it.atMillis })
        return model(steps, distanceMeters, intervalMillis).any { timed: Timed ->
            timed.reading in seedFixes && arrivals[timed.reading].orEmpty().none { it == timed.atMillis }
        }
    }

    // endregion

    // region held seed versus live replacement, stale flags

    @Test
    fun `a held seed replaced by a live fix is emitted as live and starts the interval`() = runTest {
        val result: List<Timed> = runMarked(
            options(distanceMeters = 100.0, interval = 30.seconds),
            listOf(
                live(0, at(0.0)),
                seed(5_000, at(0.002)), // held as a seed
                live(10_000, at(0.004)), // replaces it: the newest wins, and so does its mark
                live(31_000, at(0.006)), // inside the interval started at 30 s by the live fix
            ),
        )
        assertEquals(
            listOf(Timed(0, at(0.0)), Timed(30_000, at(0.004)), Timed(60_000, at(0.006))),
            result,
            "the live fix that replaced a held seed starts the interval when it comes out",
        )
    }

    @Test
    fun `a held live fix replaced by a seed is emitted as a seed and starts no interval`() = runTest {
        val result: List<Timed> = runMarked(
            options(distanceMeters = 100.0, interval = 30.seconds),
            listOf(
                live(0, at(0.0)),
                live(5_000, at(0.002)), // held as live
                seed(10_000, at(0.004)), // replaces it as a seed
                live(31_000, at(0.006)), // the reference is a seed: passes at once
            ),
        )
        assertEquals(
            listOf(Timed(0, at(0.0)), Timed(30_000, at(0.004)), Timed(31_000, at(0.006))),
            result,
            "a seed that replaced a held live fix is still a seed when it comes out",
        )
    }

    @Test
    fun `a held seed dropped by a too-close fix leaves no seed mark on the next held live fix`() = runTest {
        val result: List<Timed> = runMarked(
            options(distanceMeters = 100.0, interval = 30.seconds),
            listOf(
                live(0, at(0.0)),
                seed(5_000, at(0.002)), // held as a seed
                live(6_000, at(0.0003)), // 33 m from the reference: dropped, discards the held seed
                live(7_000, at(0.004)), // held as live
                live(31_000, at(0.006)), // inside the interval started by the live fix at 30 s
            ),
        )
        assertEquals(
            listOf(Timed(0, at(0.0)), Timed(30_000, at(0.004)), Timed(60_000, at(0.006))),
            result,
            "the discarded seed's mark must not survive into the next held fix",
        )
    }

    @Test
    fun `a held seed discarded by a reason leaves no seed mark behind`() = runTest {
        val result: List<Timed> = runMarked(
            options(distanceMeters = 100.0, interval = 30.seconds),
            listOf(
                live(0, at(0.0)),
                seed(5_000, at(0.002)), // held as a seed
                live(6_000, unavailable(LocationUnavailability.SERVICE_DISABLED)), // discards it
                live(7_000, at(0.004)), // first fix after the reason: passes, starts the interval
                live(8_000, at(0.006)), // held as live
                live(38_000, at(0.008)), // inside the interval started at 37 s by the held live fix
            ),
        )
        assertEquals(
            listOf(
                Timed(0, at(0.0)),
                Timed(6_000, unavailable(LocationUnavailability.SERVICE_DISABLED)),
                Timed(7_000, at(0.004)),
                Timed(37_000, at(0.006)),
                Timed(67_000, at(0.008)),
            ),
            result,
        )
    }

    @Test
    fun `a held seed on the exact deadline of a live step comes out first and the step is judged against it`() = runTest {
        val result: List<Timed> = runMarked(
            options(distanceMeters = 100.0, interval = 30.seconds),
            listOf(
                live(0, at(0.0)),
                seed(10_000, at(0.002)), // held as a seed, deadline 30 s
                live(30_000, at(0.0025)), // same instant as the deadline: 55 m from the seed, dropped after the flush
                live(30_001, at(0.004)), // reference is the seed: passes at once
            ),
        )
        assertEquals(listOf(Timed(0, at(0.0)), Timed(30_000, at(0.002)), Timed(30_001, at(0.004))), result)
    }

    // endregion

    // region zero interval, cancellation on the tick path, composition, backpressure

    @Test
    fun `a zero interval never starts a timer whatever the marks`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        // Control: with a positive interval the same detector does see the timer of a held fix.
        val control = MarkedUpstream(scheduler, listOf(live(0, at(0.0)), live(0, at(0.002))), thenSuspend = true)
        val controlJob: Job = launch { control.flow.throttledByMarks(options(interval = 1.hours), scheduler.timeSource).collect { } }
        runCurrent()
        val before: Long = currentTime
        testScheduler.advanceUntilIdle()
        assertEquals(before + 1.hours.inWholeMilliseconds, currentTime, "control: the held fix's timer moves the clock to its deadline")
        controlJob.cancelAndJoin()

        val steps: List<Step> = List(40) { index: Int ->
            if (index % 3 == 0) seed(0, at(index * 0.001)) else live(0, at(index * 0.001))
        }
        val upstream = MarkedUpstream(scheduler, steps, thenSuspend = true, base = currentTime)
        val received: MutableList<LocationReading> = mutableListOf()
        val job: Job = launch { upstream.flow.throttledByMarks(options(), scheduler.timeSource).collect { received += it } }
        runCurrent()
        assertEquals(steps.map { it.reading }, received, "every fix passes with no interval and no distance")
        assertNoTimerLeft("zero interval")
        job.cancelAndJoin()
        assertEquals(1, upstream.registered)
        assertEquals(1, upstream.released)
    }

    @Test
    fun `a collector that cancels itself while the held seed comes out at its deadline releases everything`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = MarkedUpstream(
            scheduler,
            // at(0.002) is held as live, then the seed at(0.004) replaces it: the seed comes out at the deadline.
            listOf(live(0, at(0.0)), live(100, at(0.002)), seed(200, at(0.004))),
            thenSuspend = true,
        )
        val received: MutableList<Timed> = mutableListOf()
        val job: Job = launch {
            upstream.flow.throttledByMarks(options(interval = 1.hours), scheduler.timeSource).collect { reading: LocationReading ->
                received += Timed(currentTime, reading)
                // Cancel on the fix that comes out at the deadline (the Tick path).
                if (currentTime == 1.hours.inWholeMilliseconds) currentCoroutineContext().job.cancel()
            }
        }
        runCurrent()
        assertEquals(1, upstream.registered, "control: the upstream is registered")
        job.join()

        assertEquals(listOf(Timed(0, at(0.0)), Timed(3_600_000, at(0.004))), received)
        assertEquals(1, upstream.released, "the self-cancellation on the tick path releases the upstream")
        assertNoTimerLeft("after a self-cancellation on the tick path")
    }

    @Test
    fun `a terminal reason while a seed is held completes at once and releases the upstream`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = MarkedUpstream(
            scheduler,
            listOf(
                live(0, at(0.0)),
                seed(100, at(0.002)), // held as a seed behind an hour
                live(200, unavailable(LocationUnavailability.PERMISSION_MISSING)),
                live(300, at(0.004)), // never reached
            ),
            thenSuspend = true,
        )
        val result: List<Timed> = upstream.flow
            .throttledByMarks(options(interval = 1.hours), scheduler.timeSource)
            .completingAfterTerminalReading()
            .map { reading: LocationReading -> Timed(currentTime, reading) }
            .toList()
        assertEquals(listOf(Timed(0, at(0.0)), Timed(200, unavailable(LocationUnavailability.PERMISSION_MISSING))), result)
        assertEquals(1, upstream.registered, "control: registered once")
        assertEquals(1, upstream.released, "the terminal reading releases the platform registration at once")
        assertNoTimerLeft("after a terminal reading with a held seed")
    }

    @Test
    fun `a fast marked upstream behind a blocked consumer is held back by the bounded inbox`() = runTest {
        var produced = 0
        val upstream: Flow<StreamReading> = flow {
            for (index: Int in 0 until 10_000) {
                produced++
                emit(if (index % 2 == 0) StreamReading.seed(at(0.0)) else StreamReading.live(at(0.0)))
            }
        }
        val gate = CompletableDeferred<Unit>()
        val job: Job = launch {
            upstream.throttledByMarks(options(), testScheduler.timeSource).collect { gate.await() }
        }
        runCurrent()
        assertTrue(produced >= 1, "control: the upstream started (produced=$produced)")
        assertTrue(produced < 500, "a blocked consumer must slow the upstream; produced=$produced of 10000")
        job.cancelAndJoin()
    }

    // endregion
}
