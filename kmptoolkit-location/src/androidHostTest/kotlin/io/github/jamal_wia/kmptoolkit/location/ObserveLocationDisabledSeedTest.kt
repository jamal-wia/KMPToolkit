package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Defect class: a stale position shown for a service that is off. An app that comes back to the
 * foreground after the user switched location off, and collects again, must begin on
 * `Unavailable(SERVICE_DISABLED)`, never on the cached position of a service that is off (a platform
 * that still hands out its last fix for a disabled provider). This holds for the device-wide switch, for
 * every source off, and for a named source that is off; a cached fix of a disabled source is never the
 * seed of a stream whose other source is on. The stream stays open through it, and when the service
 * returns it reseeds (with the cache only while it is still within `maxSeedAge`). Virtual time and a
 * scripted backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ObserveLocationDisabledSeedTest {

    private val backend = FakeLocationBackend()
    private val provider: LocationProvider = newProvider(backend)
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

    /** Both caches are young; the network fix is the younger one, so it is the freshest whenever both count. */
    private fun cacheBoth() {
        backend.setCached(gps, backend.locationAt(gps, 12.0, longitude = 34.0, ageMillis = 2_000))
        backend.setCached(network, backend.locationAt(network, 41.0, longitude = 2.0, ageMillis = 1_000))
    }

    /**
     * Switches both sources. Coming back, the network (the one that holds the cache in these tests) is
     * switched on first: the stream reseeds at the first source that returns, from the sources on at that moment.
     */
    private fun switchBoth(on: Boolean) {
        val order: List<String> = if (on) listOf(network, gps) else listOf(gps, network)
        for (source: String in order) backend.setProviderEnabled(source, on)
    }

    @Test
    fun `a collection that starts while every provider is off does not begin on the cached position`() = runTest(timeout = 60.seconds) {
        cacheBoth()
        switchBoth(false)
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(bothSources, values)
        runCurrent()

        assertEquals(listOf("none:SERVICE_DISABLED"), tags(values), "the first value was a cached fix while the service is off")
        assertTrue(job.isActive, "SERVICE_DISABLED is not terminal: the stream stays open")
        assertTrue(backend.registeredCount > 0, "control: the stream stays registered so it can reseed")
        assertEquals(1, backend.subscriberCount, "control: the stream watches the service so it can reseed")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount, "cancelling a stream that began disabled must release it")
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `the device-wide switch off begins on SERVICE_DISABLED even with the providers on`() = runTest(timeout = 60.seconds) {
        cacheBoth()
        backend.setDeviceLocationEnabled(false)
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(bothSources, values)
        runCurrent()

        assertEquals(listOf("none:SERVICE_DISABLED"), tags(values))
        assertTrue(job.isActive)
        assertTrue(backend.registeredCount > 0, "control: the stream is registered")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `a named source that is off begins on SERVICE_DISABLED although another source is on and cached`() = runTest(timeout = 60.seconds) {
        cacheBoth()
        backend.setProviderEnabled(gps, false)
        val gpsOnly = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS))
        val values: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(gpsOnly, values)
        runCurrent()

        assertEquals(listOf("none:SERVICE_DISABLED"), tags(values), "the named source is off: that is the answer, whatever the network holds")
        job.cancelAndJoin()
    }

    @Test
    fun `a cached fix of a disabled source never seeds a stream whose other source is on`() = runTest(timeout = 60.seconds) {
        backend.setCached(gps, backend.locationAt(gps, 12.0, longitude = 34.0))
        backend.setProviderEnabled(gps, false)
        val withoutOwnCache: MutableList<LocationReading> = mutableListOf()

        val job: Job = collectInto(bothSources, withoutOwnCache)
        runCurrent()
        assertEquals(listOf("none:NO_FIX"), tags(withoutOwnCache), "the disabled gps cache must not seed; the network is on and empty")
        assertEquals(2, backend.registeredCount, "control: the stream registered both sources")
        job.cancelAndJoin()

        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 20_000))
        val withOwnCache: MutableList<LocationReading> = mutableListOf()
        val second: Job = collectInto(bothSources, withOwnCache)
        runCurrent()
        assertEquals(listOf("fix:41.0"), tags(withOwnCache), "the enabled network's older cache beats the disabled gps's newer one")
        second.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `when the providers return the stream reseeds from the cache while it is still young`() = runTest(timeout = 60.seconds) {
        cacheBoth()
        switchBoth(false)
        val values: MutableList<LocationReading> = mutableListOf()
        val job: Job = collectInto(bothSources, values)
        runCurrent()
        assertEquals(listOf("none:SERVICE_DISABLED"), tags(values))

        switchBoth(true)
        runCurrent()

        assertEquals(listOf("none:SERVICE_DISABLED", "fix:41.0"), tags(values), "the service returned: one reseed with the freshest cache")
        assertTrue(job.isActive)
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `when the service returns the reseed does not use a cache older than maxSeedAge`() = runTest(timeout = 60.seconds) {
        cacheBoth()
        backend.setDeviceLocationEnabled(false)
        val values: MutableList<LocationReading> = mutableListOf()
        val job: Job = collectInto(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, maxSeedAge = 30.seconds), values)
        runCurrent()
        assertEquals(listOf("none:SERVICE_DISABLED"), tags(values))

        backend.nowNanos += 5.minutes.inWholeNanoseconds
        backend.setDeviceLocationEnabled(true)
        runCurrent()

        assertEquals(
            listOf("none:SERVICE_DISABLED", "none:NO_FIX"),
            tags(values),
            "the cache went stale while the service was off: the reseed must be NO_FIX, not the old position",
        )
        job.cancelAndJoin()
    }

    @Test
    fun `an app that collects, leaves, and collects again after location was switched off starts on SERVICE_DISABLED`() = runTest(timeout = 60.seconds) {
        cacheBoth()
        val before: MutableList<LocationReading> = mutableListOf()
        val firstJob: Job = collectInto(bothSources, before)
        runCurrent()
        assertEquals("fix:41.0", tag(before.first()), "control: while the service is on the cache seeds")
        firstJob.cancelAndJoin()
        assertEquals(0, backend.registeredCount, "control: the first collection released everything")

        switchBoth(false)
        val after: MutableList<LocationReading> = mutableListOf()
        val secondJob: Job = collectInto(bothSources, after)
        runCurrent()

        assertEquals(listOf("none:SERVICE_DISABLED"), tags(after), "the second collection must not start on the old cached position")
        secondJob.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }
}
