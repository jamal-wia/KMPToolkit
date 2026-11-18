package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * The approximate-only grant (docs 05-platform-notes: an approximate-only grant still delivers fixes at
 * reduced accuracy; PLAN section 4.1 `isApproximate`): GPS refuses a coarse-only app with a
 * `SecurityException`, the network provider accepts either permission. Fixes served under it read
 * `isApproximate == true`, fixes under a fine grant read `false`. The fixes the network provider holds
 * are served, a refused GPS does not end the stream, and a refusal of everything ends it as
 * `PERMISSION_MISSING`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ApproximateOnlyGrantTest {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val both = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)

    /** A device as a coarse-only app sees it: every GPS call is a SecurityException, the network works. */
    private fun coarseOnly(): FakeLocationBackend = FakeLocationBackend().apply {
        fine = false
        coarse = true
        refuse(gps)
    }

    @Test
    fun `observeLocation on a coarse-only grant is seeded from the network cache and the fix reads approximate`() = runTest(timeout = 60.seconds) {
        val backend: FakeLocationBackend = coarseOnly()
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 2_000).apply { accuracy = 2000f })

        val first: LocationReading = newProvider(backend).observeLocation(both).first()

        assertEquals(GeoCoordinates(41.0, 2.0), first.fixOrNull()?.coordinates, "an approximate-only grant still delivers fixes")
        assertEquals(true, first.fixOrNull()?.isApproximate)
    }

    @Test
    fun `getCurrentLocation on a coarse-only grant answers the cached network fix as approximate`() = runTest(timeout = 60.seconds) {
        val backend: FakeLocationBackend = coarseOnly()
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 2_000))

        val reading: LocationReading = newProvider(backend).getCurrentLocation()

        assertEquals(GeoCoordinates(41.0, 2.0), reading.fixOrNull()?.coordinates)
        assertEquals(true, reading.fixOrNull()?.isApproximate)
    }

    @Test
    fun `a fine grant reads precise`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 2_000))

        assertEquals(false, newProvider(backend).getCurrentLocation().fixOrNull()?.isApproximate)
    }

    @Test
    fun `with no cache the one-shot call registers on the network after gps refuses and returns its fix`() = runTest(timeout = 60.seconds) {
        val backend: FakeLocationBackend = coarseOnly()
        val call = async { newProvider(backend).getCurrentLocation(CurrentLocationOptions(maxAge = Duration.ZERO)) }
        runCurrent()
        assertEquals(1, backend.registeredCount, "control: only the network registration is live")
        backend.nowNanos += 1_000_000L
        backend.deliver(network, backend.locationAt(network, 41.0))

        val reading: LocationReading = call.await()

        assertEquals(GeoCoordinates(41.0, 2.0), reading.fixOrNull()?.coordinates)
        assertEquals(true, reading.fixOrNull()?.isApproximate)
        assertEquals(0, backend.registeredCount, "the single-shot listener was left registered")
    }

    @Test
    fun `when every provider refuses both calls answer PERMISSION_MISSING and the stream completes`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        backend.refuse(gps)
        backend.refuse(network)
        val provider: LocationProvider = newProvider(backend)
        val missing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)

        assertEquals(missing, provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.ZERO)))
        assertEquals(missing, provider.observeLocation(both).toList().last(), "the stream ends on the terminal reason")
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `a collection reports SERVICE_DISABLED when the only provider that accepted is switched off`() = runTest(timeout = 60.seconds) {
        val backend: FakeLocationBackend = coarseOnly()
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { newProvider(backend).observeLocation(both).collect { seen += it } }
        runCurrent()
        assertEquals(1, backend.registeredCount, "control: only the network provider accepted")

        backend.setProviderEnabled(network, false)
        runCurrent()

        // gps is still "on" but refused: it can never deliver, so only the accepted network flag counts.
        assertEquals("none:SERVICE_DISABLED", seen.last().let { reading: LocationReading -> (reading as? LocationReading.Unavailable)?.reason?.let { "none:$it" } })
        assertEquals(true, job.isActive, "SERVICE_DISABLED does not end the stream")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
    }
}
