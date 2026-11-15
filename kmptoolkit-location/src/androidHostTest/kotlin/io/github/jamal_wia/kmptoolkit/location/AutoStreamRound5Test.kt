package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Group loc-android (ticket 5.1.0), correction round 5 (breaker round 4 findings M1, m1, m2). Derived from
 * PLAN D8 (one mapper for the stream and the one-shot), D16 (a refusal is stable until the grant changes), D19 and
 * the 4.2 rows `PERMISSION_MISSING` / `SERVICE_DISABLED` ("denied with services off is SERVICE_DISABLED"), beside
 * the breaker tests: a listener callback for a provider the session does not hold, the order of the grant read, a
 * move that keeps the candidates that accepted, and the stream whose every candidate refuses while it is off.
 * Virtual time, no sleeps; every case ends with the counters at zero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AutoStreamRound5Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val fused: String = "fused"
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val high = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
        timeSource = TestTimeSource(),
    )

    private fun providersOf(fake: FakeLocationBackend): List<String> =
        fake.registered().map { registration: FakeLocationBackend.Registration -> registration.provider }.sorted()

    /** The fake plus per-provider non-security registration failures that can be lifted, and an ordered call log. */
    private class LoggingBackend(val fake: FakeLocationBackend) : LocationBackend by fake {
        private val failures: MutableMap<String, RuntimeException> = ConcurrentHashMap()
        val log: MutableList<String> = CopyOnWriteArrayList()

        fun fail(provider: String) {
            failures[provider] = IllegalArgumentException("oem: $provider registration failed")
        }

        fun lift(provider: String) {
            failures.remove(provider)
        }

        override fun hasFinePermission(): Boolean {
            log += "fine"
            return fake.hasFinePermission()
        }

        override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
            log += "request:$provider"
            failures[provider]?.let { failure: RuntimeException -> throw failure }
            fake.requestUpdates(provider, request, listener)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // M1: a listener callback for a provider the session does not hold
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `listener callbacks with nothing registered never re-run the move but the next service broadcast does`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network, fused))
            val backend = LoggingBackend(fake)
            fake.setProviderEnabled(fused, false)
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(high).collect { } }
            runCurrent()
            val listener: LocationListener = fake.registered().first().listener
            fake.setProviderEnabled(gps, false)
            runCurrent()
            backend.fail(fused)
            backend.fail(network)
            fake.setProviderEnabled(fused, true)
            runCurrent()
            assertEquals(0, fake.registeredCount, "control: the move and its restore failed, nothing is registered")

            val attempts: Int = fake.registrationAttemptCount
            repeat(30) {
                // The late callbacks of registrations that were removed (API 24-28 posts them after the removal).
                listener.onProviderDisabled(gps)
                listener.onProviderEnabled(gps)
                listener.onProviderDisabled(fused)
                runCurrent()
            }
            assertEquals(attempts, fake.registrationAttemptCount, "a callback of a provider the session does not hold re-ran the move")

            backend.lift(fused)
            backend.lift(network)
            fake.setDeviceLocationEnabled(true)
            runCurrent()
            assertEquals(listOf(fused), providersOf(fake), "the service broadcast retries the nothing-registered state")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    // ---------------------------------------------------------------------------------------------
    // m1: the grant is read before the platform is asked
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the grant a refusal is recorded under is read before the platform is asked`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
            fine = false
            coarse = true
            refuse(gps)
        }
        val backend = LoggingBackend(fake)
        // One candidate: nothing else reads the grant after the refusal except the refusal bookkeeping itself.
        val job: Job = backgroundScope.launch {
            providerOver(backend).observeLocation(
                LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS)),
            ).collect { }
        }
        runCurrent()
        val log: List<String> = backend.log.toList()
        val gpsRequest: Int = log.indexOf("request:$gps")
        assertTrue(gpsRequest > 0, "control: gps was asked: $log")
        assertEquals("fine", log[gpsRequest - 1], "the fine grant must be read right before gps is asked: $log")
        assertNotEquals("fine", log.getOrNull(gpsRequest + 1), "the grant was read again after the refusal: $log")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // m2: a move keeps the candidates that accepted
    // ---------------------------------------------------------------------------------------------

    /** HIGH stream on fused; fused goes off while the network fails on the platform. */
    private suspend fun TestScope.partialMove(backend: LoggingBackend): Job {
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(high).collect { } }
        runCurrent()
        assertEquals(listOf(fused), providersOf(backend.fake), "control: starts on the enabled fused provider")
        backend.fail(network)
        backend.fake.setProviderEnabled(fused, false)
        runCurrent()
        assertEquals(listOf(gps), providersOf(backend.fake), "gps accepted the move and is kept, the failing network is skipped")
        return job
    }

    @Test
    fun `a partial move is not retried on its own events and is tried again once the decision changed`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network, fused))
            val backend = LoggingBackend(fake)
            val job: Job = partialMove(backend)
            val attempts: Int = fake.registrationAttemptCount

            repeat(10) {
                fake.setDeviceLocationEnabled(true)
                fake.setProviderEnabled(gps, true)
                runCurrent()
            }
            assertEquals(attempts, fake.registrationAttemptCount, "the kept gps was re-registered on events of the same decision")

            // The decision changes (fused returns, then goes off again) and the failure is gone: both sources serve.
            backend.lift(network)
            fake.setProviderEnabled(fused, true)
            runCurrent()
            assertEquals(listOf(fused), providersOf(fake), "control: the decision moved to fused")
            fake.setProviderEnabled(fused, false)
            runCurrent()
            assertEquals(listOf(gps, network), providersOf(fake), "a new decision gets its new chance")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `a partial move does not read SERVICE_DISABLED while the kept source is on`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network, fused))
        val backend = LoggingBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(high).collect { seen += it } }
        runCurrent()
        backend.fail(network)
        fake.setProviderEnabled(fused, false)
        runCurrent()
        assertNotEquals(serviceDisabled, seen.last(), "gps is on and registered: $seen")
        fake.setProviderEnabled(gps, false)
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "nothing usable is on any more: $seen")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }
}
