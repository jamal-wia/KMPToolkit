package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * F3 and the registration-failure leak (bug class 1): a listener the platform holds must not outlive a
 * collection that failed to finish registering. A refusal of every source is not a crash: the stream
 * emits `Unavailable(PERMISSION_MISSING)` and completes, the same on every platform. A refusal of one
 * source is skipped. Any other failure removes what was registered and reaches the collector, and a
 * failing undo never replaces the failure that made it necessary. Counters, not timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ObserveLocationRegistrationFailureTest {

    private val backend = FakeLocationBackend()
    private val provider: LocationProvider = newProvider(backend)
    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    /** Registers both gps and network. */
    private val both = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)

    @Test
    fun `a source that refuses its permission is skipped and the stream stays live on the other`() = runTest(timeout = 60.seconds) {
        backend.refuse(network)
        val seen: MutableList<LocationReading> = mutableListOf()

        val job: Job = backgroundScope.launch { provider.observeLocation(both).collect { reading: LocationReading -> seen += reading } }
        runCurrent()

        assertEquals(listOf<LocationReading>(LocationReading.Unavailable(LocationUnavailability.NO_FIX)), seen, "the seed comes first")
        assertEquals(1, backend.registeredCount, "control: the gps registration is live although the network one was refused")
        backend.deliver(gps, backend.locationAt(gps, 5.0, longitude = 6.0))
        runCurrent()
        assertEquals(GeoCoordinates(5.0, 6.0), seen.last().fixOrNull()?.coordinates)
        assertTrue(job.isActive, "a partial refusal must not end the stream")

        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount, "listener still registered after the cancel")
        assertEquals(0, backend.subscriberCount, "service receiver still registered after the cancel")
    }

    @Test
    fun `when every source refuses the stream emits PERMISSION_MISSING and completes with nothing registered`() = runTest(timeout = 60.seconds) {
        backend.refuse(gps)
        backend.refuse(network)

        val values: List<LocationReading> = provider.observeLocation(both).toList()

        assertEquals(
            listOf<LocationReading>(
                LocationReading.Unavailable(LocationUnavailability.NO_FIX),
                LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING),
            ),
            values,
            "the seed, then the terminal reason, then completion (toList returned)",
        )
        assertEquals(2, backend.registrationAttemptCount, "control: both registrations were attempted")
        assertEquals(0, backend.registeredCount, "a refused collection left a listener")
        assertEquals(0, backend.subscriberCount, "a refused collection left a receiver")
    }

    @Test
    fun `a failing removal on the permission path still ends with PERMISSION_MISSING instead of throwing`() = runTest(timeout = 60.seconds) {
        backend.refuse(gps)
        backend.refuse(network)
        backend.removalFailure = IllegalStateException("dead system server")

        val values: List<LocationReading> = provider.observeLocation(both).toList()

        assertEquals(LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING), values.last())
        assertTrue(backend.removalCount > 0, "control: the undo was attempted")
    }

    @Test
    fun `any other failure on the second source removes the first listener and reaches the collector`() = runTest(timeout = 60.seconds) {
        backend.failRegistration(network, IllegalStateException("dead system server"))

        assertFailsWith<IllegalStateException> { provider.observeLocation(both).first { false } }

        assertEquals(1, backend.registrationAttemptCount - 1, "control: the gps registration happened before the network one failed")
        assertEquals(0, backend.registeredCount, "the first source's listener must not survive the failure")
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `a failing removal on the other path keeps the original failure and suppresses the removal's`() = runTest(timeout = 60.seconds) {
        val original = IllegalStateException("original")
        val removal = IllegalArgumentException("dead listener")
        backend.failRegistration(network, original)
        backend.removalFailure = removal

        val thrown: IllegalStateException = assertFailsWith<IllegalStateException> { provider.observeLocation(both).first { false } }

        assertEquals("original", thrown.message)
        // Coroutines may rethrow a stack-trace-recovered copy, so look at the original itself.
        assertTrue(original.suppressed.any { suppressed: Throwable -> suppressed === removal }, "the removal failure must be suppressed, not lost")
    }

    @Test
    fun `a collection after a failed one starts clean (re-initialisation)`() = runTest(timeout = 60.seconds) {
        backend.refuse(gps)
        backend.refuse(network)
        provider.observeLocation(both).toList()
        assertEquals(0, backend.registeredCount, "control: the failed collection left nothing")
        // The user grants the permission: the same provider instance serves the next collection.
        backend.allow(gps)
        backend.allow(network)

        val job: Job = backgroundScope.launch { provider.observeLocation(both).collect { } }
        runCurrent()

        assertEquals(2, backend.registeredCount, "the next collection must register normally")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }
}
