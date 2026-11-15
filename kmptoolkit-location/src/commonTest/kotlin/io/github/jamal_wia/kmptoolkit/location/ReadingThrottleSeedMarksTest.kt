@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The seed marker of the throttle ([StreamReading], [throttledByMarks]; group loc-core, round 4,
 * breaker round 1 hand-off, decisions D-21 and D-23): every position the platform already held when
 * the stream began, and every reseed after the service came back, is marked at the place it is
 * built, so the first live fix after it is never held behind a cached position. The old entry took a
 * predicate over identity, and a platform that named only the first seed lost that guarantee on
 * every later reseed.
 *
 * Time is virtual (the scripted upstreams `delay` on the test scheduler, the throttle measures on its
 * time source); the storm has a fixed seed that is printed on a mismatch.
 */
class ReadingThrottleSeedMarksTest {

    /** About 111.195 m per 0.001 degree of latitude on the sphere. */
    private fun at(latitude: Double): LocationReading =
        LocationReading.Available(LocationFix(GeoCoordinates(latitude, 0.0), timestampEpochMillis = 0L))

    private fun unavailable(reason: LocationUnavailability): LocationReading = LocationReading.Unavailable(reason)

    private fun options(distanceMeters: Double = 0.0, interval: Duration = Duration.ZERO): LocationUpdatesOptions =
        LocationUpdatesOptions(minUpdateDistanceMeters = distanceMeters, minUpdateInterval = interval)

    private data class Timed(val atMillis: Long, val reading: LocationReading)

    /** One scripted step: at [atMillis] emit [item]. */
    private class Step(val atMillis: Long, val item: StreamReading)

    private class ScriptedUpstream(
        private val scheduler: TestCoroutineScheduler,
        private val steps: List<Step>,
        private val thenSuspend: Boolean = false,
    ) {
        var registered: Int = 0
            private set
        var released: Int = 0
            private set

        val flow: Flow<StreamReading> = flow {
            registered++
            try {
                for (step: Step in steps) {
                    val wait: Long = step.atMillis - scheduler.currentTime
                    if (wait > 0) delay(wait)
                    emit(step.item)
                }
                if (thenSuspend) awaitCancellation()
            } finally {
                released++
            }
        }
    }

    private suspend fun TestScope.run(options: LocationUpdatesOptions, steps: List<Step>): List<Timed> {
        val scheduler: TestCoroutineScheduler = testScheduler
        return ScriptedUpstream(scheduler, steps).flow
            .throttledByMarks(options, scheduler.timeSource)
            .map { reading: LocationReading -> Timed(scheduler.currentTime, reading) }
            .toList()
    }

    @Test
    fun `a marked seed does not start the interval so the first live fix passes at once`() = runTest {
        val result: List<Timed> = run(
            options(interval = 30.seconds),
            listOf(Step(0, StreamReading.seed(at(1.0))), Step(1_000, StreamReading.live(at(2.0))), Step(2_000, StreamReading.live(at(3.0)))),
        )
        // The seed at 0, the live fix at once at 1 s, then the interval of the live fix: the next waits until 31 s.
        assertEquals(listOf(Timed(0, at(1.0)), Timed(1_000, at(2.0)), Timed(31_000, at(3.0))), result)
    }

    @Test
    fun `a reseed after the service returns is a seed again and the live fix after it passes at once`() = runTest {
        val steps: List<Step> = listOf(
            Step(0, StreamReading.seed(at(1.0))),
            Step(1_000, StreamReading.live(at(2.0))), // starts the interval, ends at 31 s
            Step(5_000, StreamReading.live(unavailable(LocationUnavailability.SERVICE_DISABLED))),
            Step(40_000, StreamReading.seed(at(3.0))), // the service is back: a cached position again
            Step(41_000, StreamReading.live(at(4.0))), // must not wait 30 s behind the reseed
        )
        val result: List<Timed> = run(options(interval = 30.seconds), steps)
        assertEquals(
            listOf(
                Timed(0, at(1.0)),
                Timed(1_000, at(2.0)),
                Timed(5_000, unavailable(LocationUnavailability.SERVICE_DISABLED)),
                Timed(40_000, at(3.0)),
                Timed(41_000, at(4.0)),
            ),
            result,
        )
    }

    @Test
    fun `control - the same reseed marked live holds the first live fix behind it`() = runTest {
        val steps: List<Step> = listOf(
            Step(0, StreamReading.seed(at(1.0))),
            Step(5_000, StreamReading.live(unavailable(LocationUnavailability.SERVICE_DISABLED))),
            Step(40_000, StreamReading.live(at(3.0))),
            Step(41_000, StreamReading.live(at(4.0))),
        )
        val result: List<Timed> = run(options(interval = 30.seconds), steps)
        assertEquals(Timed(70_000, at(4.0)), result.last(), "an unmarked reseed starts the interval: the live fix waits for its end")
    }

    @Test
    fun `a seed held inside an interval is still emitted as a seed at its deadline`() = runTest {
        // Live fix at 0 starts the interval (ends at 30 s). A reseed without a reason before it arrives
        // inside the interval and is held; at 30 s it comes out as a seed, so the live fix at 31 s passes.
        val steps: List<Step> = listOf(
            Step(0, StreamReading.live(at(1.0))),
            Step(10_000, StreamReading.seed(at(2.0))),
            Step(31_000, StreamReading.live(at(3.0))),
        )
        val result: List<Timed> = run(options(interval = 30.seconds), steps)
        assertEquals(listOf(Timed(0, at(1.0)), Timed(30_000, at(2.0)), Timed(31_000, at(3.0))), result)
    }

    @Test
    fun `a seed that is too close to the reference is dropped like any fix`() = runTest {
        val steps: List<Step> = listOf(
            Step(0, StreamReading.live(at(1.0))),
            Step(1_000, StreamReading.seed(at(1.0001))), // about 11 m, filter is 100 m
            Step(2_000, StreamReading.live(at(2.0))),
        )
        val result: List<Timed> = run(options(distanceMeters = 100.0), steps)
        assertEquals(listOf(Timed(0, at(1.0)), Timed(2_000, at(2.0))), result)
    }

    @Test
    fun `the marks of one collection are not remembered by the next`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = ScriptedUpstream(scheduler, listOf(Step(0, StreamReading.seed(at(1.0))), Step(100, StreamReading.live(at(2.0)))))
        val throttled: Flow<LocationReading> = upstream.flow.throttledByMarks(options(interval = 1.hours), scheduler.timeSource)
        val first: List<LocationReading> = throttled.toList()
        val second: List<LocationReading> = throttled.toList()
        assertEquals(listOf(at(1.0), at(2.0)), first)
        assertEquals(first, second, "a fresh collection starts with no reference and no mark")
        assertEquals(2, upstream.registered)
        assertEquals(2, upstream.released)
    }

    @Test
    fun `cancelling while a reseed and a live fix are held releases the upstream and leaves no timer`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = ScriptedUpstream(
            scheduler,
            listOf(
                Step(0, StreamReading.seed(at(1.0))),
                Step(100, StreamReading.live(at(2.0))), // out at once, starts an hour-long interval
                Step(200, StreamReading.seed(at(3.0))), // held behind it
            ),
            thenSuspend = true,
        )
        val received: MutableList<Timed> = mutableListOf()
        val job: Job = launch {
            upstream.flow.throttledByMarks(options(interval = 1.hours), scheduler.timeSource)
                .collect { reading: LocationReading -> received += Timed(currentTime, reading) }
        }
        advanceTimeBy(300)
        runCurrent()
        // Control: two readings out, the third held, upstream registered and alive.
        assertEquals(listOf(Timed(0, at(1.0)), Timed(100, at(2.0))), received)
        assertEquals(1, upstream.registered)
        assertEquals(0, upstream.released)
        assertTrue(job.isActive)

        job.cancelAndJoin()

        assertEquals(1, upstream.released)
        val before: Long = currentTime
        testScheduler.advanceUntilIdle()
        assertEquals(before, currentTime, "no timer is left")
        assertEquals(2, received.size, "the held seed is not delivered after the cancellation")
    }

    /** The marked entry and the predicate entry (marks by identity) are the same throttle: 120 seeded scripts must agree. */
    @Test
    fun `the marked entry and the identity predicate entry agree on random scripts`() {
        val seed: Int = 20_261_115 + 4
        repeat(120) { script: Int ->
            val rng = Random(seed + script)
            var time = 0L
            val steps: MutableList<Step> = mutableListOf()
            val seeds: MutableList<LocationReading> = mutableListOf()
            repeat(rng.nextInt(2, 12)) {
                time += rng.nextLong(0, 40_000)
                val reading: LocationReading = when (rng.nextInt(6)) {
                    0 -> unavailable(if (rng.nextBoolean()) LocationUnavailability.SERVICE_DISABLED else LocationUnavailability.NO_FIX)
                    else -> at(rng.nextInt(0, 5) * 0.001)
                }
                val isSeed: Boolean = rng.nextInt(3) == 0
                if (isSeed) seeds += reading
                steps += Step(time, if (isSeed) StreamReading.seed(reading) else StreamReading.live(reading))
            }
            val interval: Duration = (rng.nextInt(0, 3) * 20).seconds
            val distance: Double = if (rng.nextBoolean()) 0.0 else 100.0

            var viaMarks: List<Timed> = emptyList()
            runTest { viaMarks = run(options(distance, interval), steps) }
            var viaPredicate: List<Timed> = emptyList()
            runTest {
                val scheduler: TestCoroutineScheduler = testScheduler
                viaPredicate = flow<LocationReading> {
                    for (step: Step in steps) {
                        val wait: Long = step.atMillis - scheduler.currentTime
                        if (wait > 0) delay(wait)
                        emit(step.item.reading)
                    }
                }.throttledBy(options(distance, interval), scheduler.timeSource) { reading: LocationReading -> seeds.any { it === reading } }
                    .map { reading: LocationReading -> Timed(scheduler.currentTime, reading) }
                    .toList()
            }
            assertEquals(viaMarks, viaPredicate, "script $script (seed=$seed, interval=$interval, distance=$distance)")
        }
    }
}
