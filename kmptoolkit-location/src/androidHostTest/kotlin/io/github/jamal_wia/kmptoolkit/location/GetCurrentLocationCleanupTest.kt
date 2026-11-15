package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationListener
import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Defect class: a leaked platform listener. `getCurrentLocation` races the enabled sources with
 * single-shot registrations; whether the call takes a fix, times out, is cancelled by its caller (a
 * screen leaving), fails on one source or is answered at once, no listener (and no framework one-shot
 * call) may stay registered with the platform, because the manager holds it, and through it the dead
 * continuation, for the whole process. Every release assertion is preceded by a control assertion that
 * the thing existed, so a call that never registered cannot pass the test by accident. Virtual time and
 * a scripted backend: no sleeps, no wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GetCurrentLocationCleanupTest {

    private val backend = FakeLocationBackend()
    private val provider: LocationProvider = newProvider(backend)
    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    private fun coordinates(reading: LocationReading): GeoCoordinates =
        assertNotNull(reading.fixOrNull(), "expected a fix, got $reading").coordinates

    @Test
    fun `a fix removes the listener of the winner and of the loser`() = runTest(timeout = 60.seconds) {
        val call: Deferred<LocationReading> = async { provider.getCurrentLocation() }
        runCurrent()
        assertEquals(2, backend.registeredCount, "control: both sources must be registered while the call is pending")

        backend.deliver(gps, backend.locationAt(gps, 10.0))

        assertEquals(GeoCoordinates(10.0, 2.0), coordinates(call.await()))
        assertEquals(0, backend.registeredCount, "the winner's and the loser's listeners must both be removed")
        assertTrue(backend.removalCount > 0, "the listeners must have been removed through the platform, not forgotten")
    }

    @Test
    fun `every timeout removes the listeners and the next call registers afresh`() = runTest(timeout = 60.seconds) {
        repeat(5) { round: Int ->
            val call: Deferred<LocationReading> =
                async { provider.getCurrentLocation(CurrentLocationOptions(timeout = 5.seconds)) }
            runCurrent()
            assertEquals(2, backend.registeredCount, "control (round $round): the pending call must be registered")

            advanceTimeBy(5.seconds)
            runCurrent()

            assertEquals(LocationReading.Unavailable(LocationUnavailability.NO_FIX), call.await(), "round $round")
            assertEquals(0, backend.registeredCount, "round $round: a timed-out call left its listeners registered")
        }
        assertEquals(10, backend.registrationAttemptCount, "re-initialisation: each of the five calls registered both sources again")
    }

    @Test
    fun `every cancellation removes the listeners and a late callback after the removal is harmless`() = runTest(timeout = 60.seconds) {
        repeat(5) { round: Int ->
            val call: Deferred<LocationReading> = async { provider.getCurrentLocation() }
            runCurrent()
            val listeners: List<LocationListener> = backend.registered().map { registration: FakeLocationBackend.Registration -> registration.listener }
            assertEquals(2, listeners.size, "control (round $round): the pending call must be registered")

            call.cancelAndJoin()

            assertTrue(call.isCancelled, "round $round: the call must end cancelled")
            assertEquals(0, backend.registeredCount, "round $round: a cancelled call left its listeners registered")
            // The platform may still run a callback that was already queued when the listener was removed:
            // it must neither throw (a second resume of the continuation) nor register anything again.
            val late = backend.locationAt(gps, 10.0)
            for (listener: LocationListener in listeners) listener.onLocationChanged(late)
            assertEquals(0, backend.registeredCount, "round $round: a late callback must not bring a registration back")
        }
    }

    @Test
    fun `cancelling one of two concurrent calls keeps the other call's listeners`() = runTest(timeout = 60.seconds) {
        val first: Deferred<LocationReading> = async { provider.getCurrentLocation() }
        val second: Deferred<LocationReading> = async { provider.getCurrentLocation() }
        runCurrent()
        assertEquals(4, backend.registeredCount, "control: two calls, two sources each")

        first.cancelAndJoin()

        assertEquals(2, backend.registeredCount, "cancelling one call must remove exactly its own listeners")
        backend.deliver(network, backend.locationAt(network, 41.0))
        assertEquals(GeoCoordinates(41.0, 2.0), coordinates(second.await()))
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `a source that fails to register leaves nothing behind and the others still answer`() = runTest(timeout = 60.seconds) {
        backend.failRegistration(gps, IllegalArgumentException("OEM failure"))

        val call: Deferred<LocationReading> = async { provider.getCurrentLocation() }
        runCurrent()
        assertEquals(2, backend.registrationAttemptCount, "control: both sources were attempted")
        assertEquals(1, backend.registeredCount, "control: only the healthy source stays registered")
        backend.deliver(network, backend.locationAt(network, 41.0))

        assertEquals(GeoCoordinates(41.0, 2.0), coordinates(call.await()))
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `every source refusing the permission answers PERMISSION_MISSING and registers nothing`() = runTest(timeout = 60.seconds) {
        backend.refuse(gps)
        backend.refuse(network)

        val reading: LocationReading = provider.getCurrentLocation()

        assertEquals(LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING), reading)
        assertEquals(2, backend.registrationAttemptCount, "control: both sources were attempted before the refusal ended the call")
        assertEquals(0, backend.registeredCount, "a refused registration registers nothing")
    }

    @Test
    fun `answers given at once register nothing`() = runTest(timeout = 60.seconds) {
        backend.setProviderEnabled(gps, false)
        backend.setProviderEnabled(network, false)
        assertEquals(
            LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED),
            provider.getCurrentLocation(),
            "every source off is answered at once",
        )
        backend.setProviderEnabled(gps, true)
        backend.setProviderEnabled(network, true)
        backend.fine = false
        backend.coarse = false
        assertEquals(
            LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING),
            provider.getCurrentLocation(),
            "no permission is answered at once",
        )

        assertEquals(0, backend.registrationAttemptCount, "an answer given at once must not touch the platform")
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `a framework one-shot call is cancelled when the caller cancels`() = runTest(timeout = 60.seconds) {
        val api30 = FakeLocationBackend(supportsCurrentLocation = true)
        val api30Provider: LocationProvider = newProvider(api30)

        val call: Deferred<LocationReading> = async { api30Provider.getCurrentLocation() }
        runCurrent()
        val frameworkCalls: List<FakeLocationBackend.CurrentCall> = api30.currentCalls()
        assertEquals(2, frameworkCalls.size, "control: the framework one-shot call is made for gps and network")
        assertTrue(frameworkCalls.none { it.cancelled }, "control: no framework call is cancelled while the caller waits")

        call.cancelAndJoin()

        assertTrue(frameworkCalls.all { it.cancelled }, "every framework call must be cancelled with the caller")
        assertEquals(0, api30.registeredCount)
    }

    @Test
    fun `a framework one-shot call is cancelled at the timeout`() = runTest(timeout = 60.seconds) {
        val api30 = FakeLocationBackend(supportsCurrentLocation = true)
        val api30Provider: LocationProvider = newProvider(api30)

        val call: Deferred<LocationReading> = async { api30Provider.getCurrentLocation(CurrentLocationOptions(timeout = 5.seconds)) }
        runCurrent()
        val frameworkCalls: List<FakeLocationBackend.CurrentCall> = api30.currentCalls()
        assertEquals(2, frameworkCalls.size, "control: the framework one-shot call is made for gps and network")
        assertTrue(frameworkCalls.none { it.cancelled }, "control: no framework call is cancelled before the timeout")
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(LocationReading.Unavailable(LocationUnavailability.NO_FIX), call.await())
        assertTrue(frameworkCalls.all { it.cancelled }, "a timed-out call must cancel its framework calls")
        assertEquals(0, api30.registeredCount)
    }

    @Test
    fun `a call cancelled after the framework answered empty removes the one-update registrations`() = runTest(timeout = 60.seconds) {
        val api30 = FakeLocationBackend(supportsCurrentLocation = true)
        val api30Provider: LocationProvider = newProvider(api30)

        val call: Deferred<LocationReading> = async { api30Provider.getCurrentLocation() }
        runCurrent()
        api30.answerCurrentCall(0, null)
        api30.answerCurrentCall(1, null)
        runCurrent()
        assertEquals(2, api30.registeredCount, "control: an empty framework answer continues with one-update registrations")

        call.cancelAndJoin()

        assertEquals(0, api30.registeredCount, "a cancelled call must remove the registrations that followed the framework answer")
    }
}
