package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The common throttle (PLAN D16, M10, section 6.1 "Throttle helper"): an `Unavailable` is never
 * delayed or dropped, the distance is measured from the last emitted fix, and the interval runs on
 * an injected clock, here a `TestTimeSource` that the scripted upstream advances itself, so no test
 * waits on real time. The storm compares 16 concurrent collections with a straight-line reference
 * model, each with its own seed and clock, and prints the seed on a mismatch.
 */
class ReadingThrottleTest {

    /** About 111.195 m per 0.001 degree of latitude on the sphere. */
    private fun at(latitude: Double): LocationReading =
        LocationReading.Available(LocationFix(GeoCoordinates(latitude, 0.0), timestampEpochMillis = 0L))

    private fun unavailable(reason: LocationUnavailability): LocationReading = LocationReading.Unavailable(reason)

    private fun options(distanceMeters: Double = 0.0, interval: Duration = Duration.ZERO): LocationUpdatesOptions =
        LocationUpdatesOptions(minUpdateDistanceMeters = distanceMeters, minUpdateInterval = interval)

    /** Runs [script] as the upstream and returns what passes the throttle; the script advances [clock] between emissions. */
    private suspend fun run(
        options: LocationUpdatesOptions,
        clock: TestTimeSource,
        script: suspend FlowCollector<LocationReading>.() -> Unit,
    ): List<LocationReading> = flow(script).throttledBy(options, clock).toList()

    // region interval

    @Test
    fun `with no interval and no distance every reading passes`() = runTest {
        val clock = TestTimeSource()
        val result: List<LocationReading> = run(options(), clock) {
            emit(at(0.0)); emit(at(0.0)); emit(at(0.0001))
        }

        assertEquals(listOf(at(0.0), at(0.0), at(0.0001)), result)
    }

    @Test
    fun `the interval drops a fix that comes too soon and passes one at exactly the interval`() = runTest {
        val clock = TestTimeSource()
        val result: List<LocationReading> = run(options(interval = 5.seconds), clock) {
            emit(at(1.0))
            clock += 4_999.milliseconds; emit(at(2.0)) // dropped
            clock += 1.milliseconds; emit(at(3.0)) // 5 s after the first: passes
            clock += 5.seconds; emit(at(4.0)) // 5 s after the third: passes
        }

        assertEquals(listOf(at(1.0), at(3.0), at(4.0)), result)
    }

    @Test
    fun `the interval is measured from the last emitted fix not from the last received one`() = runTest {
        val clock = TestTimeSource()
        val result: List<LocationReading> = run(options(interval = 5.seconds), clock) {
            emit(at(1.0))
            clock += 3.seconds; emit(at(2.0)) // dropped
            clock += 3.seconds; emit(at(3.0)) // 6 s after the emitted first: passes (3 s after the dropped one)
        }

        assertEquals(listOf(at(1.0), at(3.0)), result)
    }

    // endregion

    // region distance

    @Test
    fun `the distance is measured from the last emitted fix not from the last received one`() = runTest {
        val clock = TestTimeSource()
        val result: List<LocationReading> = run(options(distanceMeters = 100.0), clock) {
            emit(at(0.0))
            emit(at(0.00054)) // about 60 m from the first: dropped
            emit(at(0.00108)) // about 120 m from the first, 60 m from the dropped one: passes
            emit(at(0.00110)) // about 2 m from the emitted one: dropped
        }

        assertEquals(listOf(at(0.0), at(0.00108)), result)
    }

    @Test
    fun `a move of exactly the minimum distance passes`() = runTest {
        val clock = TestTimeSource()
        val first: GeoCoordinates = GeoCoordinates(0.0, 0.0)
        val second: GeoCoordinates = GeoCoordinates(0.001, 0.0)
        val exact: Double = first.distanceInMetersTo(second)

        val result: List<LocationReading> = run(options(distanceMeters = exact), clock) {
            emit(LocationReading.Available(LocationFix(first, 0L)))
            emit(LocationReading.Available(LocationFix(second, 0L)))
        }

        assertEquals(2, result.size)
    }

    @Test
    fun `a fix must satisfy both the interval and the distance`() = runTest {
        val clock = TestTimeSource()
        val result: List<LocationReading> = run(options(distanceMeters = 100.0, interval = 5.seconds), clock) {
            emit(at(0.0))
            clock += 10.seconds; emit(at(0.0001)) // old enough, 11 m: dropped
            emit(at(0.002)) // 222 m but still 10 s after the first: passes (both met)
            clock += 1.seconds; emit(at(0.004)) // far enough, only 1 s after the last emitted: dropped
            clock += 4.seconds; emit(at(0.004)) // old enough (5 s), 0 m from the last received but 222 m from the emitted: passes
        }

        assertEquals(listOf(at(0.0), at(0.002), at(0.004)), result)
    }

    // endregion

    // region Unavailable is never hidden (M10)

    @Test
    fun `an Unavailable is never dropped whatever the interval and the distance`() = runTest {
        val clock = TestTimeSource()
        val hostile: LocationUpdatesOptions = options(distanceMeters = 1.0e9, interval = 1.hours)
        val reasons: List<LocationReading> = LocationUnavailability.entries.map { reason: LocationUnavailability -> unavailable(reason) }

        val result: List<LocationReading> = run(hostile, clock) {
            emit(at(0.0))
            for (reason: LocationReading in reasons) emit(reason) // immediately after a fix, all inside the interval
            for (reason: LocationReading in reasons) emit(reason) // repeated reasons are not deduplicated either
        }

        assertEquals(listOf(at(0.0)) + reasons + reasons, result)
    }

    @Test
    fun `a fix after an emitted Unavailable passes even inside the interval and the distance`() = runTest {
        val clock = TestTimeSource()
        val result: List<LocationReading> = run(options(distanceMeters = 1.0e9, interval = 1.hours), clock) {
            emit(at(1.0))
            emit(unavailable(LocationUnavailability.SERVICE_DISABLED))
            emit(at(1.0)) // the consumer's latest value is a reason: this fix must reach it
            emit(at(1.0)) // and the throttle applies again from here: dropped
        }

        assertEquals(listOf(at(1.0), unavailable(LocationUnavailability.SERVICE_DISABLED), at(1.0)), result)
    }

    @Test
    fun `a fix between two reasons is throttled like any other and the reasons still pass`() = runTest {
        val clock = TestTimeSource()
        val result: List<LocationReading> = run(options(interval = 10.seconds), clock) {
            emit(unavailable(LocationUnavailability.NO_FIX))
            emit(at(1.0)) // first after a reason: passes
            emit(at(2.0)) // inside the interval: dropped
            emit(unavailable(LocationUnavailability.NO_FIX))
        }

        assertEquals(listOf(unavailable(LocationUnavailability.NO_FIX), at(1.0), unavailable(LocationUnavailability.NO_FIX)), result)
    }

    // endregion

    // region cancellation, release, re-initialisation

    /** An upstream that counts registrations and releases and then suspends, like a platform listener. */
    private class RegisteredUpstream(private val first: LocationReading) {
        var registered: Int = 0
            private set
        var released: Int = 0
            private set

        val flow: Flow<LocationReading> = flow {
            registered++
            try {
                emit(first)
                awaitCancellation()
            } finally {
                released++
            }
        }
    }

    @Test
    fun `cancelling a collection mid-stream releases the upstream and leaves no state behind`() = runTest {
        val upstream = RegisteredUpstream(at(1.0))
        val received: MutableList<LocationReading> = mutableListOf()
        val arrived = CompletableDeferred<Unit>()
        val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            upstream.flow.throttledBy(options(interval = 1.hours), TestTimeSource()).collect { item: LocationReading ->
                received += item
                arrived.complete(Unit)
            }
        }

        arrived.await()
        // Control: the registration existed and was live before the cancellation.
        assertEquals(1, upstream.registered)
        assertEquals(0, upstream.released)
        assertTrue(job.isActive)

        job.cancelAndJoin()

        assertEquals(1, upstream.released, "the upstream registration is released on cancellation")
        assertEquals(listOf(at(1.0)), received)
    }

    @Test
    fun `a new collection after a cancelled one starts fresh and emits its first fix at once`() = runTest {
        val clock = TestTimeSource()
        val upstream = RegisteredUpstream(at(1.0))
        val throttled: Flow<LocationReading> = upstream.flow.throttledBy(options(interval = 1.hours), clock)

        for (round: Int in 1..3) {
            val arrived = CompletableDeferred<LocationReading>()
            val job: Job = launch(start = CoroutineStart.UNDISPATCHED) { throttled.collect { item: LocationReading -> arrived.complete(item) } }
            assertEquals(at(1.0), arrived.await(), "round $round: the first fix of a collection is never throttled")
            job.cancelAndJoin()
            assertEquals(round, upstream.released, "round $round")
        }

        assertEquals(3, upstream.registered, "every collection registers anew")
    }

    @Test
    fun `an upstream failure reaches the collector and releases the upstream`() = runTest {
        var released = 0
        val failing: Flow<LocationReading> = flow {
            try {
                emit(at(1.0))
                throw IllegalStateException("platform failure")
            } finally {
                released++
            }
        }
        val received: MutableList<LocationReading> = mutableListOf()
        var failure: Throwable? = null

        try {
            failing.throttledBy(options(), TestTimeSource()).collect { item: LocationReading -> received += item }
        } catch (expected: IllegalStateException) {
            failure = expected
        }

        assertEquals("platform failure", failure?.message)
        assertEquals(listOf(at(1.0)), received)
        assertEquals(1, released)
    }

    // endregion

    // region concurrent storm against a reference model

    private class Step(val advance: Duration, val reading: LocationReading)

    /** The straight-line statement of the rules, written independently of the operator: what a list of steps must produce. */
    private fun expected(steps: List<Step>, distanceMeters: Double, interval: Duration): List<LocationReading> {
        val out: MutableList<LocationReading> = mutableListOf()
        var now: Duration = Duration.ZERO
        var lastFix: LocationFix? = null
        var lastAt: Duration = Duration.ZERO
        for (step: Step in steps) {
            now += step.advance
            val reading: LocationReading = step.reading
            if (reading is LocationReading.Unavailable) {
                out += reading
                lastFix = null
                continue
            }
            val fix: LocationFix = (reading as LocationReading.Available).fix
            val reference: LocationFix? = lastFix
            val passes: Boolean = reference == null ||
                (now - lastAt >= interval && reference.coordinates.distanceInMetersTo(fix.coordinates) >= distanceMeters)
            if (passes) {
                out += reading
                lastFix = fix
                lastAt = now
            }
        }
        return out
    }

    private fun script(rng: Random, length: Int): List<Step> = List(length) {
        val advance: Duration = rng.nextInt(0, 3_000).milliseconds
        val reading: LocationReading = if (rng.nextInt(10) == 0) {
            unavailable(LocationUnavailability.entries[rng.nextInt(LocationUnavailability.entries.size)])
        } else {
            LocationReading.Available(
                LocationFix(GeoCoordinates(rng.nextDouble(0.0, 0.01), rng.nextDouble(0.0, 0.01)), timestampEpochMillis = 0L),
            )
        }
        Step(advance, reading)
    }

    @Test
    fun `sixteen concurrent collections each match the reference model`() = runTest(timeout = 120.seconds) {
        val workers = 16
        val distances: List<Double> = listOf(0.0, 50.0, 500.0)
        val intervals: List<Duration> = listOf(Duration.ZERO, 1.seconds, 5.seconds)

        val mismatches: List<String?> = withContext(Dispatchers.Default) {
            (0 until workers).map { worker: Int ->
                async {
                    val seed: Int = GEO_TEST_SEED + 1_000 + worker
                    val rng = Random(seed)
                    val distance: Double = distances[rng.nextInt(distances.size)]
                    val interval: Duration = intervals[rng.nextInt(intervals.size)]
                    val steps: List<Step> = script(rng, 2_000)
                    val clock = TestTimeSource()
                    val actual: List<LocationReading> = flow {
                        for (step: Step in steps) {
                            clock += step.advance
                            emit(step.reading)
                        }
                    }.throttledBy(options(distance, interval), clock).toList()
                    val model: List<LocationReading> = expected(steps, distance, interval)
                    if (actual == model) null else "seed=$seed distance=$distance interval=$interval: ${actual.size} readings against ${model.size} expected"
                }
            }.awaitAll()
        }

        assertTrue(mismatches.all { it == null }, mismatches.filterNotNull().joinToString("; "))
    }

    // endregion
}
