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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * PLAN D6, D16, M10: an invalid platform fix (NaN or out-of-range coordinates) is dropped, never thrown
 * from a platform callback and never emitted: on a live stream, as a one-shot answer and as a cached
 * fix. A throw from the listener would kill the main thread, and an emitted NaN would fail open in an
 * app's "outside the fence" check. Every drop is checked against a control: the next valid fix passes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class InvalidPlatformFixTest {

    private val backend = FakeLocationBackend()
    private val provider: LocationProvider = newProvider(backend)
    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    private val invalidCoordinates: List<Pair<Double, Double>> = listOf(
        Double.NaN to 2.0,
        1.0 to Double.NaN,
        91.0 to 2.0,
        -90.000001 to 2.0,
        1.0 to 180.000001,
        1.0 to -181.0,
        Double.POSITIVE_INFINITY to 2.0,
    )

    @Test
    fun `a live stream drops every invalid fix and delivers the next valid one`() = runTest(timeout = 60.seconds) {
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { seen += it }
        }
        runCurrent()
        assertEquals(1, seen.size, "control: only the seed so far")

        for ((latitude: Double, longitude: Double) in invalidCoordinates) {
            backend.deliver(gps, backend.locationAt(gps, latitude, longitude))
            runCurrent()
        }
        assertEquals(1, seen.size, "an invalid fix was emitted: $seen")

        backend.deliver(gps, backend.locationAt(gps, 5.0, 6.0))
        runCurrent()
        assertEquals(GeoCoordinates(5.0, 6.0), seen.last().fixOrNull()?.coordinates, "the next valid fix must pass")
        job.cancelAndJoin()
    }

    @Test
    fun `an invalid fix does not answer the one-shot call and the source keeps waiting`() = runTest(timeout = 60.seconds) {
        val call = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.ZERO)) }
        runCurrent()
        backend.nowNanos += 1_000_000L

        for ((latitude: Double, longitude: Double) in invalidCoordinates) {
            backend.deliver(gps, backend.locationAt(gps, latitude, longitude))
            runCurrent()
        }
        assertEquals(true, call.isActive, "an invalid fix answered the call")
        assertEquals(2, backend.registeredCount, "control: both sources are still listening")

        backend.deliver(network, backend.locationAt(network, 41.0))
        assertEquals(GeoCoordinates(41.0, 2.0), call.await().fixOrNull()?.coordinates)
    }

    @Test
    fun `an invalid cached fix is dropped from the stream seed too`() = runTest(timeout = 60.seconds) {
        backend.setCached(gps, backend.locationAt(gps, Double.NaN, 2.0, ageMillis = 100))
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { seen += it }
        }
        runCurrent()

        assertEquals(listOf<LocationReading>(LocationReading.Unavailable(LocationUnavailability.NO_FIX)), seen)
        job.cancelAndJoin()
    }

    @Test
    fun `a valid fix with a NaN accuracy is kept and its accuracy is null`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 41.0).apply { accuracy = Float.NaN })

        val fix: LocationFix? = provider.getCurrentLocation().fixOrNull()

        assertEquals(GeoCoordinates(41.0, 2.0), fix?.coordinates)
        assertEquals(null, fix?.horizontalAccuracyMeters, "a NaN accuracy is not a measurement")
    }
}
