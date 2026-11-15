package io.github.jamal_wia.kmptoolkit.location

import android.location.Location
import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Defect class: judging the age of a cached fix by the wall clock. `Location.getTime` is a UTC time
 * that can jump forward or backwards (NTP after boot, the user changing the time), so the age of a fix,
 * and therefore which of two caches wins, must be read from `elapsedRealtimeNanos` on the boot clock. The
 * fixtures give the two cached fixes wall-clock times that disagree with the boot-clock order, for
 * both `getCurrentLocation` and the seed of `observeLocation`, in both provider orders, and add a fix
 * whose wall time says "just now" but whose boot stamp is old (or absent). Virtual time and a scripted
 * backend: no wall clock is read anywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FreshestFixClockJumpTest {

    private val backend = FakeLocationBackend()
    private val provider: LocationProvider = newProvider(backend)
    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    /** A cached fix [ageMillis] old on the boot clock and stamped [wallMillis] on the (possibly jumped) wall clock. */
    private fun cachedFix(source: String, latitude: Double, wallMillis: Long, ageMillis: Long): Location =
        backend.locationAt(source, latitude, ageMillis = ageMillis).apply { time = wallMillis }

    private fun tag(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix:${reading.fix.coordinates.latitude}"
        is LocationReading.Unavailable -> "none:${reading.reason}"
    }

    private fun TestScope.collectInto(options: LocationUpdatesOptions, into: MutableList<LocationReading>): Job =
        backgroundScope.launch { provider.observeLocation(options).collect { reading: LocationReading -> into += reading } }

    private val streamOptions = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, maxSeedAge = 1.hours)

    /** Gps: taken 10 minutes ago, wall-stamped an hour ahead. Network: taken a second ago, wall-stamped earlier. */
    private fun networkYoungerWallBehind() {
        backend.setCached(gps, cachedFix(gps, 10.0, wallMillis = WALL_BASE + 1.hours.inWholeMilliseconds, ageMillis = 600_000))
        backend.setCached(network, cachedFix(network, 41.0, wallMillis = WALL_BASE, ageMillis = 1_000))
    }

    /** The mirror image: gps is the young one and carries the smaller wall time. */
    private fun gpsYoungerWallBehind() {
        backend.setCached(network, cachedFix(network, 41.0, wallMillis = WALL_BASE + 1.hours.inWholeMilliseconds, ageMillis = 600_000))
        backend.setCached(gps, cachedFix(gps, 10.0, wallMillis = WALL_BASE, ageMillis = 1_000))
    }

    @Test
    fun `getCurrentLocation answers the fix that is younger by the boot clock even with the smaller wall time`() = runTest(timeout = 60.seconds) {
        networkYoungerWallBehind()

        val reading: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(maxAge = 1.hours))

        val fix: LocationFix = assertNotNull(reading.fixOrNull(), "expected a fix, got $reading")
        assertEquals(GeoCoordinates(41.0, 2.0), fix.coordinates, "the network fix is younger by the boot clock")
        assertEquals(WALL_BASE, fix.timestampEpochMillis, "the answer is the network fix itself, with its own wall stamp")
        assertEquals(0, backend.registrationAttemptCount, "a cache hit must not ask the platform for a fresh fix")
    }

    @Test
    fun `getCurrentLocation picks the younger fix whichever provider holds it`() = runTest(timeout = 60.seconds) {
        gpsYoungerWallBehind()

        val reading: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(maxAge = 1.hours))

        assertEquals(GeoCoordinates(10.0, 2.0), assertNotNull(reading.fixOrNull(), "expected a fix, got $reading").coordinates)
    }

    @Test
    fun `observeLocation seeds with the fix that is younger by the boot clock even with the smaller wall time`() = runTest(timeout = 60.seconds) {
        networkYoungerWallBehind()
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(streamOptions, values)
        runCurrent()

        assertEquals("fix:41.0", tag(values.first()), "the seed must be the network fix, younger by the boot clock")
        assertEquals(WALL_BASE, values.first().fixOrNull()?.timestampEpochMillis, "the seed is the network fix itself")
        assertEquals(2, backend.registeredCount, "control: the stream registered both sources")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `observeLocation seeds with the younger fix whichever provider holds it`() = runTest(timeout = 60.seconds) {
        gpsYoungerWallBehind()
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(streamOptions, values)
        runCurrent()

        assertEquals("fix:10.0", tag(values.first()), "the seed must be the gps fix, younger by the boot clock")
        job.cancelAndJoin()
    }

    @Test
    fun `a wall clock far in the past does not make a boot-fresh cached fix too old`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, cachedFix(network, 41.0, wallMillis = 1L, ageMillis = 1_000))

        val reading: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(maxAge = 30.seconds))

        assertEquals(GeoCoordinates(41.0, 2.0), assertNotNull(reading.fixOrNull(), "expected a fix, got $reading").coordinates)
        val values: MutableList<LocationReading> = mutableListOf()
        val job: Job = collectInto(LocationUpdatesOptions(maxSeedAge = 30.seconds), values)
        runCurrent()
        assertEquals("fix:41.0", tag(values.first()), "the seed must ignore the wall stamp as well")
        job.cancelAndJoin()
    }

    @Test
    fun `a wall stamp that says just now does not make a boot-stale cached fix fresh`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, cachedFix(network, 41.0, wallMillis = WALL_BASE, ageMillis = 10 * 60_000L))
        backend.setCached(gps, cachedFix(gps, 10.0, wallMillis = WALL_BASE, ageMillis = 10 * 60_000L))

        val call: Deferred<LocationReading> = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = 30.seconds)) }
        runCurrent()
        assertEquals(2, backend.registeredCount, "control: a boot-stale cache must lead to a fresh request, not be answered")
        call.cancelAndJoin()
        assertEquals(0, backend.registeredCount)

        val values: MutableList<LocationReading> = mutableListOf()
        val job: Job = collectInto(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, maxSeedAge = 30.seconds), values)
        runCurrent()
        assertEquals("none:NO_FIX", tag(values.first()), "a boot-stale cache must not seed the stream")
        job.cancelAndJoin()
    }

    @Test
    fun `a fix without a boot stamp has no known age so no finite limit accepts it`() = runTest(timeout = 60.seconds) {
        val unstamped: Location = backend.locationAt(network, 41.0).apply {
            time = WALL_BASE
            elapsedRealtimeNanos = 0L
        }
        backend.setCached(network, unstamped)

        val call: Deferred<LocationReading> = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = 1.hours)) }
        runCurrent()
        assertEquals(2, backend.registeredCount, "control: an unstamped cache must not answer a finite maxAge, whatever its wall time says")
        call.cancelAndJoin()

        val values: MutableList<LocationReading> = mutableListOf()
        val job: Job = collectInto(LocationUpdatesOptions(maxSeedAge = 1.hours), values)
        runCurrent()
        assertEquals("none:NO_FIX", tag(values.first()), "an unstamped cache must not seed the stream")
        job.cancelAndJoin()

        val reading: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.INFINITE))
        assertEquals(GeoCoordinates(41.0, 2.0), assertNotNull(reading.fixOrNull(), "expected a fix, got $reading").coordinates, "INFINITE accepts any cache")
    }

    private companion object {
        /** An arbitrary wall-clock reading; a fixed number, never read from a clock. */
        const val WALL_BASE: Long = 1_700_000_000_000L
    }
}
