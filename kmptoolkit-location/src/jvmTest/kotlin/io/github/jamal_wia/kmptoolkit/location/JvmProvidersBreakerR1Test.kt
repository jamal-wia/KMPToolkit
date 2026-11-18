package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Breaker round 1 of group loc-jvm-fakes (BREAKER-REVIEW.md, D-23): the desktop JVM factories
 * (PLAN D27, 3.4) under re-entrancy, operator fusion, cancellation storms on real threads and
 * argument boundaries. Targets: a state stream that emits twice or completes (it must emit `false`
 * once and stay open), a location stream that does not complete under a buffering operator, a heading
 * collection that leaks or cannot be cancelled, and validation that happens on collection instead of
 * at the call. Virtual time for the silence probes; real-time bounds are hang bounds only.
 */
class JvmProvidersBreakerR1Test {

    private val unsupported: LocationReading = LocationReading.Unavailable(LocationUnavailability.UNSUPPORTED)

    @Test
    fun `the state stream emits false exactly once and neither completes nor emits again`() = runTest {
        val provider: LocationProvider = createLocationProvider()
        // take(2) can only finish on a second value or completion: neither may happen.
        assertNull(withTimeoutOrNull(24.hours) { provider.observeLocationEnabled().take(2).toList() }, "a second value or a completion arrived")
        assertEquals(false, provider.observeLocationEnabled().first())
    }

    @Test
    fun `the location stream completes after UNSUPPORTED under buffering and conflating operators`() = runTest {
        val provider: LocationProvider = createLocationProvider()
        assertEquals(listOf(unsupported), provider.observeLocation().buffer(0).toList())
        assertEquals(listOf(unsupported), provider.observeLocation().conflate().toList())
        assertEquals(listOf(unsupported), provider.observeLocation(LocationUpdatesOptions(maxSeedAge = kotlin.time.Duration.INFINITE)).toList())
    }

    @Test
    fun `the providers can be called from inside their own collections`() = runTest {
        val location: LocationProvider = createLocationProvider()
        val heading: HeadingProvider = createHeadingProvider()
        val nested: MutableList<Any> = mutableListOf()
        location.observeLocation().collect { reading ->
            nested.add(reading)
            nested.add(location.getCurrentLocation())
            nested.add(location.observeLocation().toList())
            nested.add(location.isLocationEnabled())
            nested.add(location.promptToEnableService())
            location.openLocationSettings()
            nested.add(heading.isAvailable)
        }
        assertEquals(listOf(unsupported, unsupported, listOf(unsupported), false, LocationServicePrompt.UNSUPPORTED, false), nested)
    }

    @Test
    fun `heading validation is at the call for every boundary and every rotation`() {
        val heading: HeadingProvider = createHeadingProvider()
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -Double.MIN_VALUE, -1.0)) {
            for (rotation in ScreenRotation.entries) {
                assertFailsWith<IllegalArgumentException>("minChangeDegrees=$bad rotation=$rotation") {
                    heading.observeHeading(bad, GeoCoordinates(0.0, 0.0), rotation)
                }
            }
        }
        for (good in listOf(-0.0, 0.0, Double.MIN_VALUE, 360.0, Double.MAX_VALUE)) {
            heading.observeHeading(good, null, ScreenRotation.ROTATION_270) // no throw; the flow is never collected
        }
    }

    @Test
    fun `silent heading and state collections started and cancelled on real threads all end`() = runTest(timeout = 120.seconds) {
        val location: LocationProvider = createLocationProvider()
        val heading: HeadingProvider = createHeadingProvider()
        val ended: List<Int> = withContext(Dispatchers.Default) {
            withTimeout(100.seconds) {
                (0 until 8).map { worker ->
                    async {
                        var done = 0
                        repeat(500) { step ->
                            val start: CoroutineStart = if ((worker + step) % 2 == 0) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT
                            val jobs: List<Job> = listOf(
                                launch(start = start) { heading.observeHeading().collect { error("a desktop heading was emitted: $it") } },
                                launch(start = start) { location.observeLocationEnabled().collect { check(!it) } },
                            )
                            jobs.forEach { it.cancelAndJoin() }
                            if (jobs.all { it.isCancelled }) done++
                        }
                        done
                    }
                }.awaitAll()
            }
        }
        assertTrue(ended.all { it == 500 }, "every collection ended by its cancellation: $ended")
    }
}
