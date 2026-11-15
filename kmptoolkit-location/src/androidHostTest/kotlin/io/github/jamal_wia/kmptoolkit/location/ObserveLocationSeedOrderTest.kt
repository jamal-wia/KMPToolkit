package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * A [FakeLocationBackend] that can run code at the exact point of a registration (right after the
 * platform has recorded it, where the real main looper may deliver a fix) and notes how many
 * registrations had been attempted whenever the cache was read.
 */
private class SeedProbeBackend(private val inner: FakeLocationBackend) : LocationBackend by inner {

    /** For each cache read, the number of `requestUpdates` calls made before it. */
    val attemptsAtCacheRead: MutableList<Int> = CopyOnWriteArrayList()

    /** Runs after a registration of the given provider has been recorded. */
    @Volatile var onRegistered: (String) -> Unit = {}

    /** Thrown by every `requestUpdates` before it reaches the platform, when set. */
    @Volatile var registrationFailure: Throwable? = null

    override fun lastKnownLocation(provider: String): Location? {
        attemptsAtCacheRead.add(inner.registrationAttemptCount)
        return inner.lastKnownLocation(provider)
    }

    override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
        registrationFailure?.let { failure: Throwable -> throw failure }
        inner.requestUpdates(provider, request, listener)
        onRegistered(provider)
    }
}

/**
 * Defect class: the stale seed that overtakes a live fix. A live fix is delivered on the main looper
 * while the stream's block may run on any dispatcher, so a seed sent after the registration can land
 * behind a newer fix and leave the collector on an older position until the next update. The contract:
 * the seed (the freshest cached fix within `maxSeedAge`, else `Unavailable(NO_FIX)`) is read and sent
 * BEFORE any registration exists, so every live fix follows it by construction. The test asserts the
 * order three ways: the cache is read with zero registrations attempted, a live fix delivered from
 * inside the registration is never followed by the seed, and live fixes after the seed keep their
 * order. The stream is conflated, so a collector may legitimately miss the seed when a live fix is
 * already waiting; it must never see the seed after the fix. A seed older than `maxSeedAge` is not
 * used. Virtual time and a scripted backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ObserveLocationSeedOrderTest {

    private val backend = FakeLocationBackend()
    private val probe = SeedProbeBackend(backend)
    private val provider: LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = probe,
    )
    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    /** Both sources: the gps and the network provider. */
    private val bothSources = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)

    private fun tag(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix:${reading.fix.coordinates.latitude}"
        is LocationReading.Unavailable -> "none:${reading.reason}"
    }

    private fun tags(readings: List<LocationReading>): List<String> = readings.map { reading: LocationReading -> tag(reading) }

    private fun TestScope.collectInto(options: LocationUpdatesOptions, into: MutableList<LocationReading>): Job =
        backgroundScope.launch { provider.observeLocation(options).collect { reading: LocationReading -> into += reading } }

    @Test
    fun `the seed is the first value and the cache is read before any registration`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 5_000))
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(LocationUpdatesOptions(), values)
        runCurrent()

        assertEquals(listOf("fix:41.0"), tags(values), "the first and only value is the cached seed")
        assertTrue(probe.attemptsAtCacheRead.isNotEmpty(), "control: the cache must have been read")
        assertEquals(0, probe.attemptsAtCacheRead.first(), "the seed must be read before any registration is attempted")
        assertEquals(1, backend.registeredCount, "control: the stream registered after reading the seed")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `a live fix delivered while registering is not overtaken by the older cached seed`() = runTest(timeout = 60.seconds) {
        backend.setCached(gps, backend.locationAt(gps, 1.0, ageMillis = 5_000))
        probe.onRegistered = { registered: String ->
            // The main looper delivering a newer fix exactly at the point of registration.
            if (registered == gps) backend.deliver(gps, backend.locationAt(gps, 2.0))
        }
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(bothSources, values)
        runCurrent()

        assertEquals("fix:2.0", tag(values.last()), "the collector must end on the live fix, never on the older seed: ${tags(values)}")
        assertTrue(
            tags(values) == listOf("fix:1.0", "fix:2.0") || tags(values) == listOf("fix:2.0"),
            "the seed may be conflated away but must never follow the live fix: ${tags(values)}",
        )
        assertEquals(2, backend.registeredCount, "control: the stream stayed registered on both sources")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `live fixes after the seed keep their order`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 1.0, ageMillis = 5_000))
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(LocationUpdatesOptions(), values)
        runCurrent()
        assertEquals(listOf("fix:1.0"), tags(values), "the seed comes first")
        backend.deliver(network, backend.locationAt(network, 2.0))
        runCurrent()
        backend.deliver(network, backend.locationAt(network, 3.0))
        runCurrent()

        assertEquals(listOf("fix:1.0", "fix:2.0", "fix:3.0"), tags(values), "each live fix follows the seed in the order delivered")
        job.cancelAndJoin()
    }

    @Test
    fun `a refused registration is the last value and the seed never follows it`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 5_000))
        probe.registrationFailure = SecurityException("registration refused")
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(LocationUpdatesOptions(), values)
        runCurrent()

        assertEquals("none:PERMISSION_MISSING", tag(values.last()), "the terminal reason must be last: ${tags(values)}")
        assertTrue(
            tags(values) == listOf("fix:41.0", "none:PERMISSION_MISSING") || tags(values) == listOf("none:PERMISSION_MISSING"),
            "the seed may be conflated away but must not follow the reason: ${tags(values)}",
        )
        assertTrue(job.isCompleted, "the stream must complete after PERMISSION_MISSING")
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `with no cached fix the first value is Unavailable NO_FIX and the stream stays open`() = runTest(timeout = 60.seconds) {
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(LocationUpdatesOptions(), values)
        runCurrent()

        assertEquals(listOf("none:NO_FIX"), tags(values))
        assertTrue(job.isActive, "NO_FIX is not terminal: the stream stays open")
        assertEquals(1, backend.registeredCount, "control: the stream is registered and waiting for a live fix")
        backend.deliver(network, backend.locationAt(network, 41.0))
        runCurrent()
        assertEquals(listOf("none:NO_FIX", "fix:41.0"), tags(values))
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `a seed older than maxSeedAge is not used`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 40_000))
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(LocationUpdatesOptions(maxSeedAge = 30.seconds), values)
        runCurrent()

        assertEquals(listOf("none:NO_FIX"), tags(values), "a 40 s old cache must not seed a 30 s stream")
        assertEquals(1, backend.registeredCount, "control: the stream still registered for live fixes")
        job.cancelAndJoin()
    }

    @Test
    fun `a seed exactly maxSeedAge old is used and the boundaries ZERO and INFINITE hold`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 30_000))
        val exact: MutableList<LocationReading> = mutableListOf()
        val exactJob: Job = collectInto(LocationUpdatesOptions(maxSeedAge = 30.seconds), exact)
        runCurrent()
        assertEquals(listOf("fix:41.0"), tags(exact), "an age equal to maxSeedAge is still acceptable")
        exactJob.cancelAndJoin()

        backend.setCached(network, backend.locationAt(network, 42.0, ageMillis = 0))
        val zero: MutableList<LocationReading> = mutableListOf()
        val zeroJob: Job = collectInto(LocationUpdatesOptions(maxSeedAge = Duration.ZERO), zero)
        runCurrent()
        assertEquals(listOf("none:NO_FIX"), tags(zero), "maxSeedAge ZERO takes no seed, even a brand new one")
        zeroJob.cancelAndJoin()

        backend.setCached(network, backend.locationAt(network, 43.0, ageMillis = 10.hours.inWholeMilliseconds))
        val infinite: MutableList<LocationReading> = mutableListOf()
        val infiniteJob: Job = collectInto(LocationUpdatesOptions(maxSeedAge = Duration.INFINITE), infinite)
        runCurrent()
        assertEquals(listOf("fix:43.0"), tags(infinite), "maxSeedAge INFINITE accepts a cache of any age")
        infiniteJob.cancelAndJoin()
        assertEquals(0, backend.registeredCount, "re-initialisation: three collections in a row released everything")
    }

    @Test
    fun `the seed is the freshest of both sources`() = runTest(timeout = 60.seconds) {
        backend.setCached(gps, backend.locationAt(gps, 10.0, ageMillis = 20_000))
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 2_000))
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(bothSources, values)
        runCurrent()

        assertEquals(listOf("fix:41.0"), tags(values), "the younger cached fix seeds the stream")
        job.cancelAndJoin()
    }

    @Test
    fun `every collection seeds on its own and never sees another collector's seed`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 1.0, ageMillis = 5_000))
        val flow: Flow<LocationReading> = provider.observeLocation(LocationUpdatesOptions())
        val first: MutableList<LocationReading> = mutableListOf()
        val second: MutableList<LocationReading> = mutableListOf()

        val firstJob: Job = backgroundScope.launch { flow.collect { reading: LocationReading -> first += reading } }
        runCurrent()
        backend.setCached(network, backend.locationAt(network, 3.0, ageMillis = 1_000))
        val secondJob: Job = backgroundScope.launch { flow.collect { reading: LocationReading -> second += reading } }
        runCurrent()

        assertEquals("fix:1.0", tag(first.first()), "the first collection seeded with the cache of its own start")
        assertEquals("fix:3.0", tag(second.first()), "the second collection seeded with the cache of its own start")
        assertEquals(2, backend.registeredCount, "control: two collections, two registrations")
        assertEquals(2, backend.subscriberCount, "control: two collections, two service subscriptions")
        firstJob.cancelAndJoin()
        assertEquals(1, backend.registeredCount, "cancelling one collection must keep the other's registration")
        assertFalse(secondJob.isCompleted, "the other collection stays open")
        secondJob.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }
}
