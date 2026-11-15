package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * The documented mapping of the options onto the platform request (PLAN section 4.2, 4.3): accuracy
 * chooses the source(s) (fused when the device has it, else by the table), `minUpdateDistanceMeters`
 * and `minUpdateInterval` are handed to the request next to the common throttle, `maxUpdateDelay` is
 * handed on, and the one-shot request behind `getCurrentLocation` asks for no throttling at all.
 * Swapping the two values or sending the defaults is invisible to a collector that sees fixes arrive,
 * so the arguments themselves are recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ObserveLocationOptionsMappingTest {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val fused: String = "fused"

    private fun TestScopeJob(backend: FakeLocationBackend, options: LocationUpdatesOptions, scope: kotlinx.coroutines.CoroutineScope): Job =
        scope.launch { newProvider(backend).observeLocation(options).collect { } }

    private fun providersOf(backend: FakeLocationBackend): Set<String> =
        backend.registered().map { registration: FakeLocationBackend.Registration -> registration.provider }.toSet()

    @Test
    fun `every registered source gets the configured distance and interval and not the other way round`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        val options = LocationUpdatesOptions(
            accuracy = LocationAccuracy.HIGH_ACCURACY,
            minUpdateDistanceMeters = 7.5,
            minUpdateInterval = 1234.milliseconds,
        )

        val job: Job = TestScopeJob(backend, options, backgroundScope)
        runCurrent()

        assertEquals(setOf(gps, network), providersOf(backend), "control: both sources registered")
        for (registration: FakeLocationBackend.Registration in backend.registered()) {
            assertEquals(1234L, registration.request.minTimeMillis, "minTime of ${registration.provider}")
            assertEquals(7.5f, registration.request.minDistanceMeters, "minDistance of ${registration.provider}")
            assertEquals(LocationAccuracy.HIGH_ACCURACY, registration.request.accuracy)
        }
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount, "the cancelled collection left a listener")
    }

    @Test
    fun `the default options ask the platform for every update`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()

        val job: Job = TestScopeJob(backend, LocationUpdatesOptions(), backgroundScope)
        runCurrent()

        assertEquals(1, backend.registeredCount, "control: a balanced stream registers one source")
        for (registration: FakeLocationBackend.Registration in backend.registered()) {
            assertEquals(0L, registration.request.minTimeMillis)
            assertEquals(0f, registration.request.minDistanceMeters)
            assertEquals(0L, registration.request.maxUpdateDelayMillis)
        }
        job.cancelAndJoin()
    }

    @Test
    fun `maxUpdateDelay is handed to the request in milliseconds`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        val options = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(maxUpdateDelay = 2.seconds))

        val job: Job = TestScopeJob(backend, options, backgroundScope)
        runCurrent()

        assertEquals(2_000L, backend.registered().single().request.maxUpdateDelayMillis)
        job.cancelAndJoin()
    }

    @Test
    fun `accuracy chooses the sources without fused as the table says`() = runTest(timeout = 60.seconds) {
        val table: Map<LocationAccuracy, Set<String>> = mapOf(
            LocationAccuracy.HIGH_ACCURACY to setOf(gps, network),
            LocationAccuracy.BALANCED to setOf(network),
            LocationAccuracy.LOW_POWER to setOf(network),
        )
        for ((accuracy: LocationAccuracy, expected: Set<String>) in table) {
            val backend = FakeLocationBackend()
            val job: Job = TestScopeJob(backend, LocationUpdatesOptions(accuracy = accuracy), backgroundScope)
            runCurrent()
            assertEquals(expected, providersOf(backend), "stream, $accuracy")
            job.cancelAndJoin()
        }
    }

    @Test
    fun `balanced stream falls back to gps when there is no network provider and low power to passive`() = runTest(timeout = 60.seconds) {
        val noNetwork = FakeLocationBackend(providers = setOf(gps, passive))
        val balanced: Job = TestScopeJob(noNetwork, LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED), backgroundScope)
        runCurrent()
        assertEquals(setOf(gps), providersOf(noNetwork), "balanced without network: gps")
        balanced.cancelAndJoin()

        val low: Job = TestScopeJob(noNetwork, LocationUpdatesOptions(accuracy = LocationAccuracy.LOW_POWER), backgroundScope)
        runCurrent()
        assertEquals(setOf(passive), providersOf(noNetwork), "low power without network: passive")
        low.cancelAndJoin()
    }

    @Test
    fun `with the fused provider every accuracy uses it alone and passes the quality hint`() = runTest(timeout = 60.seconds) {
        for (accuracy: LocationAccuracy in LocationAccuracy.entries) {
            val backend = FakeLocationBackend(providers = setOf(gps, network, passive, fused))
            val job: Job = TestScopeJob(backend, LocationUpdatesOptions(accuracy = accuracy), backgroundScope)
            runCurrent()
            assertEquals(setOf(fused), providersOf(backend), "fused only, $accuracy")
            assertEquals(accuracy, backend.registered().single().request.accuracy, "quality hint, $accuracy")
            job.cancelAndJoin()
        }
    }

    @Test
    fun `the one-shot request is never throttled and carries the accuracy`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        val call = async {
            newProvider(backend).getCurrentLocation(CurrentLocationOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, timeout = 50.milliseconds))
        }
        runCurrent()

        assertEquals(2, backend.registeredCount, "control: a request was made on gps and network")
        for (registration: FakeLocationBackend.Registration in backend.registered()) {
            assertEquals(0L, registration.request.minTimeMillis, "a single fix must not wait for an interval")
            assertEquals(0f, registration.request.minDistanceMeters, "a single fix must not wait for a distance")
            assertEquals(0L, registration.request.maxUpdateDelayMillis)
            assertEquals(LocationAccuracy.HIGH_ACCURACY, registration.request.accuracy)
        }
        advanceTimeBy(50.milliseconds)
        runCurrent()
        assertEquals(LocationReading.Unavailable(LocationUnavailability.NO_FIX), call.await())
        assertEquals(0, backend.registeredCount, "the timed-out request left a listener")
    }

    @Test
    fun `invalid Android options are rejected where they are built`() {
        assertFailsWith<IllegalArgumentException> { AndroidLocationUpdatesOptions(maxUpdateDelay = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { AndroidLocationUpdatesOptions(maxUpdateDelay = Duration.INFINITE) }
        assertEquals(Duration.ZERO, AndroidLocationUpdatesOptions().maxUpdateDelay)
        assertEquals(AndroidLocationSource.AUTO, AndroidCurrentLocationOptions().source)
    }
}
