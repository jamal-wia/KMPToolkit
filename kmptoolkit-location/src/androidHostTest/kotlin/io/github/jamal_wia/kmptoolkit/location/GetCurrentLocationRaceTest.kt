package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * F2 (PLAN D13): the one-shot call asks the sources concurrently and takes the first valid fix. With the
 * GPS silent (indoors) and the network answering, the call returns the network fix long before the
 * timeout instead of burning the whole timeout on the GPS first. The losers are cancelled, which removes
 * their listeners; a source that fails or is refused must not end the race while another can still answer.
 * Virtual time: the assertion is on the scheduler's clock, not on a wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GetCurrentLocationRaceTest {

    private val backend = FakeLocationBackend()
    private val provider: LocationProvider = newProvider(backend)
    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    @Test
    fun `with gps silent and network answering the call returns the network fix long before the timeout`() = runTest(timeout = 60.seconds) {
        val call = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = kotlin.time.Duration.ZERO, timeout = 30.seconds)) }
        runCurrent()
        assertEquals(2, backend.registeredCount, "control: both sources were asked at once")
        advanceTimeBy(2.seconds)
        backend.nowNanos += 2_000_000_000L
        backend.deliver(network, backend.locationAt(network, 41.0, ageMillis = 50))

        val reading: LocationReading = call.await()

        assertEquals(GeoCoordinates(41.0, 2.0), reading.fixOrNull()?.coordinates)
        assertEquals(2_000L, currentTime, "the call must return when the network answers, not at the 30 s timeout")
        assertEquals(0, backend.registeredCount, "the losing gps request must be cancelled and its listener removed")
    }

    @Test
    fun `the first valid fix wins and a later fix of the loser changes nothing`() = runTest(timeout = 60.seconds) {
        val call = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = kotlin.time.Duration.ZERO)) }
        runCurrent()
        backend.nowNanos += 1_000_000L
        val gpsListener: FakeLocationBackend.Registration = backend.registered().first { it.provider == gps }

        backend.deliver(gps, backend.locationAt(gps, 10.0, ageMillis = 0))
        // A late callback of the loser after its removal (the platform may still deliver one queued fix).
        gpsListener.listener.onLocationChanged(backend.locationAt(gps, 99.0, ageMillis = 0))

        assertEquals(GeoCoordinates(10.0, 2.0), call.await().fixOrNull()?.coordinates)
    }

    @Test
    fun `a stale fix delivered first is ignored and the source keeps waiting for a fresh one`() = runTest(timeout = 60.seconds) {
        val call = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = kotlin.time.Duration.ZERO)) }
        runCurrent()
        backend.nowNanos += 5_000_000_000L
        // Taken 20 s ago, before the call started: not fresh for maxAge ZERO.
        backend.deliver(gps, backend.locationAt(gps, 10.0, ageMillis = 20_000))
        runCurrent()
        assertTrue(call.isActive, "a fix older than the request must not answer a maxAge ZERO call")
        assertEquals(2, backend.registeredCount, "control: both sources are still listening")

        backend.deliver(network, backend.locationAt(network, 41.0, ageMillis = 10))

        assertEquals(GeoCoordinates(41.0, 2.0), call.await().fixOrNull()?.coordinates)
    }

    @Test
    fun `a refused source does not end the race while another can still answer`() = runTest(timeout = 60.seconds) {
        backend.refuse(gps)
        val call = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = kotlin.time.Duration.ZERO)) }
        runCurrent()
        assertEquals(1, backend.registeredCount, "control: the gps registration was refused, the network one is live")
        backend.nowNanos += 1_000_000L
        backend.deliver(network, backend.locationAt(network, 41.0, ageMillis = 0))

        assertEquals(GeoCoordinates(41.0, 2.0), call.await().fixOrNull()?.coordinates)
    }

    @Test
    fun `a source that fails with another exception does not fail the call`() = runTest(timeout = 60.seconds) {
        backend.failRegistration(gps, IllegalStateException("OEM failure"))
        val call = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = kotlin.time.Duration.ZERO)) }
        runCurrent()
        backend.nowNanos += 1_000_000L
        backend.deliver(network, backend.locationAt(network, 41.0, ageMillis = 0))

        assertEquals(GeoCoordinates(41.0, 2.0), call.await().fixOrNull()?.coordinates)
    }

    @Test
    fun `when every source ends without a fix the call answers at once and not at the timeout`() = runTest(timeout = 60.seconds) {
        backend.failRegistration(gps, IllegalStateException("OEM failure"))
        backend.failRegistration(network, IllegalStateException("OEM failure"))

        val reading: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(maxAge = kotlin.time.Duration.ZERO, timeout = 30.seconds))

        assertEquals(LocationReading.Unavailable(LocationUnavailability.NO_FIX), reading)
        assertEquals(0L, currentTime, "nothing can answer: the call must not wait out the timeout")
    }

    @Test
    fun `when every source is refused the call answers PERMISSION_MISSING at once`() = runTest(timeout = 60.seconds) {
        backend.refuse(gps)
        backend.refuse(network)

        val reading: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(maxAge = kotlin.time.Duration.ZERO))

        assertEquals(LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING), reading)
        assertEquals(0L, currentTime)
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `cancelling the caller mid-race removes every listener and a later call works (re-init)`() = runTest(timeout = 60.seconds) {
        val first = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = kotlin.time.Duration.ZERO)) }
        runCurrent()
        assertEquals(2, backend.registeredCount, "control: both sources are listening")

        first.cancel()
        runCurrent()
        assertEquals(0, backend.registeredCount, "a cancelled race left a listener")

        val second = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = kotlin.time.Duration.ZERO)) }
        runCurrent()
        backend.nowNanos += 1_000_000L
        backend.deliver(gps, backend.locationAt(gps, 7.0, ageMillis = 0))
        assertEquals(GeoCoordinates(7.0, 2.0), second.await().fixOrNull()?.coordinates)
        assertEquals(0, backend.registeredCount)
    }
}
