package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * F1 (PLAN D11, section 4.2 "One-shot fresh fix", section 6.3): a cached fix answers the call only
 * while it is within `maxAge`, judged on the boot clock. `Duration.ZERO` forces a fresh fix and
 * `Duration.INFINITE` accepts any cache. On API 30 and later the framework `getCurrentLocation` may hand
 * back a fix up to 30 s old: that answer is judged by the delivery rule, so a 20 s-old one is not
 * returned under `maxAge = ZERO` and a fresh one is. Virtual time and a scripted backend: no sleeps, no
 * wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GetCurrentLocationCachedFixTest {

    private val backend = FakeLocationBackend()
    private val provider: LocationProvider = newProvider(backend)
    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    private fun coordinates(reading: LocationReading): GeoCoordinates =
        assertNotNull(reading.fixOrNull(), "expected a fix, got $reading").coordinates

    @Test
    fun `a cached fix within maxAge answers the call and registers nothing`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 10_000))

        val reading: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(maxAge = 30.seconds))

        assertEquals(GeoCoordinates(41.0, 2.0), coordinates(reading))
        assertEquals(0, backend.registrationAttemptCount, "a cache hit must not ask the platform for a fresh fix")
    }

    @Test
    fun `a cached fix older than maxAge is not returned and a fresh fix is awaited`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 40_000))

        val call = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = 30.seconds)) }
        runCurrent()
        assertEquals(2, backend.registeredCount, "control: the stale cache must lead to a fresh request on gps and network")
        backend.deliver(gps, backend.locationAt(gps, 10.0))

        assertEquals(GeoCoordinates(10.0, 2.0), coordinates(call.await()))
        assertEquals(0, backend.registeredCount, "the race must remove every listener after the first fix")
    }

    @Test
    fun `an age equal to maxAge is still acceptable`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 30_000))

        assertEquals(
            GeoCoordinates(41.0, 2.0),
            coordinates(provider.getCurrentLocation(CurrentLocationOptions(maxAge = 30.seconds))),
        )
    }

    @Test
    fun `Duration ZERO forces a fresh fix even when a brand new cache exists`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 0))

        val call = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.ZERO)) }
        runCurrent()
        assertEquals(2, backend.registeredCount, "control: ZERO must register for a fresh fix, not use the cache")
        backend.nowNanos += 50_000_000L
        backend.deliver(gps, backend.locationAt(gps, 10.0, ageMillis = 10))

        assertEquals(GeoCoordinates(10.0, 2.0), coordinates(call.await()))
    }

    @Test
    fun `Duration INFINITE accepts a cache of any age`() = runTest(timeout = 60.seconds) {
        backend.setCached(gps, backend.locationAt(gps, 41.0, ageMillis = 10.hours.inWholeMilliseconds))

        val reading: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.INFINITE))

        assertEquals(GeoCoordinates(41.0, 2.0), coordinates(reading))
        assertEquals(0, backend.registrationAttemptCount)
    }

    @Test
    fun `with both providers holding a cache the younger one by the boot clock answers`() = runTest(timeout = 60.seconds) {
        backend.setCached(gps, backend.locationAt(gps, 10.0, ageMillis = 20_000))
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 1_000))
        assertEquals(GeoCoordinates(41.0, 2.0), coordinates(provider.getCurrentLocation()), "network is younger")

        backend.setCached(gps, backend.locationAt(gps, 10.0, ageMillis = 100))
        assertEquals(GeoCoordinates(10.0, 2.0), coordinates(provider.getCurrentLocation()), "gps is younger")
    }

    @Test
    fun `a cached fix of a disabled provider is not used`() = runTest(timeout = 60.seconds) {
        backend.setProviderEnabled(network, false)
        backend.setCached(network, backend.locationAt(network, 41.0))

        val call = async { provider.getCurrentLocation(CurrentLocationOptions(timeout = 5.seconds)) }
        runCurrent()
        assertEquals(1, backend.registeredCount, "control: only the enabled gps provider is asked, the network cache is stale by rule")
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(LocationReading.Unavailable(LocationUnavailability.NO_FIX), call.await())
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `an invalid cached fix is dropped and does not hide a valid one`() = runTest(timeout = 60.seconds) {
        backend.setCached(gps, backend.locationAt(gps, Double.NaN))
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 5_000))

        assertEquals(GeoCoordinates(41.0, 2.0), coordinates(provider.getCurrentLocation()))
    }

    @Test
    fun `API 30 framework answer of 20 seconds old is not returned under maxAge ZERO and a fresh fix is`() = runTest(timeout = 60.seconds) {
        val api30 = FakeLocationBackend(supportsCurrentLocation = true)
        val api30Provider: LocationProvider = newProvider(api30)

        val call = async { api30Provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.ZERO, timeout = 30.seconds)) }
        runCurrent()
        assertEquals(2, api30.currentCalls().size, "control: the framework one-shot call is made for gps and network")
        api30.nowNanos += 5_000_000_000L
        api30.answerCurrentCall(0, api30.locationAt(gps, 10.0, ageMillis = 20_000))
        runCurrent()
        assertTrue(call.isActive, "a 20 s old framework answer must not complete a maxAge ZERO call")
        assertEquals(1, api30.registeredCount, "control: the source must continue with a one-update registration")

        api30.deliver(gps, api30.locationAt(gps, 11.0, ageMillis = 5))

        assertEquals(GeoCoordinates(11.0, 2.0), coordinates(call.await()))
        assertEquals(0, api30.registeredCount)
        assertTrue(api30.currentCalls()[1].cancelled, "the losing network framework call must be cancelled")
    }

    @Test
    fun `API 30 framework answer taken after the call started is returned under maxAge ZERO`() = runTest(timeout = 60.seconds) {
        val api30 = FakeLocationBackend(supportsCurrentLocation = true)
        val api30Provider: LocationProvider = newProvider(api30)

        val call = async { api30Provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.ZERO)) }
        runCurrent()
        api30.nowNanos += 2_000_000_000L
        api30.answerCurrentCall(1, api30.locationAt(network, 12.0, ageMillis = 100))

        assertEquals(GeoCoordinates(12.0, 2.0), coordinates(call.await()))
        assertEquals(0, api30.registeredCount)
    }
}
