package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.GeoCoordinates
import io.github.jamal_wia.kmptoolkit.location.Heading
import io.github.jamal_wia.kmptoolkit.location.ScreenRotation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 1 of group loc-jvm-fakes (BREAKER-REVIEW.md, D-23): [FakeHeadingProvider] validation
 * boundaries (PLAN 3.1 `observeHeading`: throws when negative or non-finite, nothing else), the
 * recorders keeping the exact values, release proof under cancellation racing registration on real
 * threads, re-entrant use from inside a collector, and a device without a compass under an emit
 * storm. Every storm message starts with `seed=`; real-time bounds are hang bounds only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeHeadingProviderBreakerR1Test {

    @Test
    fun `every negative or non finite minChangeDegrees throws from the call and registers nothing`() {
        val provider = FakeHeadingProvider()
        val rejected: List<Double> = listOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -Double.MIN_VALUE, -1e-300, -1.0, -360.0, -Double.MAX_VALUE,
        )
        for (value in rejected) {
            assertFailsWith<IllegalArgumentException>("minChangeDegrees=$value") { provider.observeHeading(minChangeDegrees = value) }
            assertFailsWith<IllegalArgumentException>("minChangeDegrees=$value without a compass") {
                FakeHeadingProvider(isAvailable = false).observeHeading(minChangeDegrees = value)
            }
        }
        assertEquals(0, provider.activeObservers.value)
        assertTrue(provider.activeFilters.isEmpty())
    }

    @Test
    fun `every non negative finite minChangeDegrees is accepted and recorded bit exact`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider()
        // -0.0 is not negative (IEEE: -0.0 >= 0.0); > 360 is not forbidden by the contract.
        val accepted: List<Double> = listOf(-0.0, 0.0, Double.MIN_VALUE, 1e-300, 359.999999, 360.0, 720.0, Double.MAX_VALUE)
        val rotations: List<ScreenRotation> = ScreenRotation.entries
        val places: List<GeoCoordinates?> = listOf(null, GeoCoordinates(90.0, 180.0), GeoCoordinates(-90.0, -180.0), GeoCoordinates(-0.0, -0.0))
        val jobs: List<Job> = accepted.mapIndexed { index, value ->
            backgroundScope.launch {
                provider.observeHeading(value, places[index % places.size], rotations[index % rotations.size]).collect { }
            }
        }
        assertEquals(accepted.size, provider.activeObservers.value)
        assertEquals(accepted, provider.activeFilters, "the filter values must be recorded exactly (the sign of -0.0 included)")
        assertEquals(accepted.indices.map { places[it % places.size] }, provider.activeDeclinationAt)
        assertEquals(accepted.indices.map { rotations[it % rotations.size] }, provider.activeScreenRotations)
        jobs.forEach { it.cancelAndJoin() }
        assertEquals(0, provider.activeObservers.value)
        assertTrue(provider.activeFilters.isEmpty() && provider.activeDeclinationAt.isEmpty() && provider.activeScreenRotations.isEmpty())
    }

    @Test
    fun `the fake can be driven from inside its own collector`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider()
        val inner: MutableList<String> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeHeading(minChangeDegrees = 2.0).collect { heading ->
                inner += "observers=${provider.activeObservers.value} filters=${provider.activeFilters}"
                assertEquals(heading, provider.observeHeading(minChangeDegrees = 3.0).first(), "a nested collection replays the latest heading")
                if (heading.magneticDegrees < 10.0) provider.emit(heading(heading.magneticDegrees + 1.0))
            }
        }
        provider.emit(heading(5.0))

        assertTrue(job.isActive)
        assertEquals(List(6) { "observers=1 filters=[2.0]" }, inner, "5..10: each delivered once, nested collections released")
        job.cancelAndJoin()
        assertEquals(0, provider.activeObservers.value)
    }

    @Test
    fun `cancel racing registration on real threads leaves the count exact and never negative`() = runTest(timeout = 120.seconds) {
        val seed: Long = FAKE_TEST_SEED + 201
        val provider = FakeHeadingProvider()
        val negative = CompletableDeferred<Int>()
        withContext(Dispatchers.Default) {
            val watcher: Job = launch { provider.activeObservers.collect { if (it < 0) negative.complete(it) } }
            withTimeout(100.seconds) {
                val publisher = async {
                    val rng = Random(seed)
                    repeat(20_000) { provider.emit(heading(rng.nextDouble(0.0, 360.0), needsCalibration = rng.nextBoolean())) }
                }
                (0 until 7).map { worker ->
                    async {
                        val rng = Random(seed * 1000 + worker)
                        repeat(400) {
                            val start: CoroutineStart = if (rng.nextBoolean()) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT
                            val job: Job = launch(start = start) { provider.observeHeading(minChangeDegrees = worker.toDouble()).collect { } }
                            if (rng.nextBoolean()) job.cancelAndJoin() else job.cancel()
                        }
                    }
                }.awaitAll()
                publisher.await()
                provider.activeObservers.first { it == 0 }
            }
            watcher.cancelAndJoin()
        }
        assertTrue(!negative.isCompleted, "seed=$seed the observer count went negative")
        assertEquals(0, provider.activeObservers.value, "seed=$seed")
        assertTrue(provider.activeFilters.isEmpty(), "seed=$seed")
        // Re-init: a fresh collector after the storm gets the last heading at once.
        val last: Heading = heading(123.0)
        provider.emit(last)
        assertEquals(last, provider.observeHeading().first(), "seed=$seed")
    }

    @Test
    fun `a device without a compass delivers nothing under an emit storm and releases every collector`() = runTest(timeout = 120.seconds) {
        val seed: Long = FAKE_TEST_SEED + 202
        val provider = FakeHeadingProvider(isAvailable = false)
        val received = CompletableDeferred<Heading>()
        withContext(Dispatchers.Default) {
            withTimeout(100.seconds) {
                val collectors: List<Job> = List(8) {
                    launch(start = CoroutineStart.UNDISPATCHED) { provider.observeHeading().collect { received.complete(it) } }
                }
                assertEquals(8, provider.activeObservers.value, "seed=$seed control: eight live silent collectors")
                (0 until 4).map { worker ->
                    async {
                        val rng = Random(seed * 1000 + worker)
                        repeat(5_000) { provider.emit(heading(rng.nextDouble(0.0, 360.0))) }
                    }
                }.awaitAll()
                // A collector that starts after the storm does not get a replay either.
                val late: Job = launch(start = CoroutineStart.UNDISPATCHED) { provider.observeHeading().collect { received.complete(it) } }
                assertEquals(9, provider.activeObservers.value, "seed=$seed")
                (collectors + late).forEach { it.cancelAndJoin() }
            }
        }
        assertTrue(!received.isCompleted, "seed=$seed a heading reached a collector of a device without a compass")
        assertEquals(0, provider.activeObservers.value, "seed=$seed")
    }
}
