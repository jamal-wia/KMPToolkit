package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import android.os.Looper

/**
 * `AndroidLocationSource` (PLAN D19, section 4.2, section 6.3): each named source, an absent source
 * answers `UNSUPPORTED`, `FUSED` exists only where the device has it, a source that exists but is
 * switched off answers `SERVICE_DISABLED` at once (the one-shot call without waiting for the timeout, the
 * stream stays open and reseeds when it is switched back on), `AUTO` races only the enabled providers,
 * and `isLocationEnabled()` stays device-wide: it can read `true` while a named source is off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SourceSelectionTest {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val fused: String = "fused"

    private fun current(source: AndroidLocationSource, timeout: Duration = 30.seconds): CurrentLocationOptions = CurrentLocationOptions(
        maxAge = Duration.ZERO,
        timeout = timeout,
        platform = AndroidCurrentLocationOptions(source),
    )

    private fun updates(source: AndroidLocationSource): LocationUpdatesOptions =
        LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source))

    private fun providersOf(backend: FakeLocationBackend): Set<String> =
        backend.registered().map { registration: FakeLocationBackend.Registration -> registration.provider }.toSet()

    @Test
    fun `each named source registers exactly that source`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend(providers = setOf(gps, network, passive, fused))
        val provider: LocationProvider = newProvider(backend)
        val expected: Map<AndroidLocationSource, String> = mapOf(
            AndroidLocationSource.FUSED to fused,
            AndroidLocationSource.GPS to gps,
            AndroidLocationSource.NETWORK to network,
            AndroidLocationSource.PASSIVE to passive,
        )
        for ((source: AndroidLocationSource, name: String) in expected) {
            val call = async { provider.getCurrentLocation(current(source, timeout = 1.seconds)) }
            runCurrent()
            assertEquals(setOf(name), providersOf(backend), "one-shot, $source")
            call.cancel()
            runCurrent()
            val stream: Job = backgroundScope.launch { provider.observeLocation(updates(source)).collect { } }
            runCurrent()
            assertEquals(setOf(name), providersOf(backend), "stream, $source")
            stream.cancelAndJoin()
            assertEquals(0, backend.registeredCount, "control: $source released")
        }
    }

    @Test
    fun `a source absent on the device answers UNSUPPORTED on both calls`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend(providers = setOf(network))
        val provider: LocationProvider = newProvider(backend)
        val unsupported: LocationReading = LocationReading.Unavailable(LocationUnavailability.UNSUPPORTED)

        assertEquals(unsupported, provider.getCurrentLocation(current(AndroidLocationSource.GPS)))
        assertEquals(unsupported, provider.getCurrentLocation(current(AndroidLocationSource.FUSED)), "no fused provider below API 31")
        assertEquals(
            listOf(unsupported),
            provider.observeLocation(updates(AndroidLocationSource.GPS)).let { stream ->
                val seen: MutableList<LocationReading> = mutableListOf()
                stream.collect { reading: LocationReading -> seen += reading }
                seen
            },
            "the stream emits UNSUPPORTED and completes (collect returned)",
        )
        assertEquals(0, backend.registrationAttemptCount, "an absent source registers nothing")
    }

    @Test
    fun `AUTO uses fused when the device has it and the table otherwise`() = runTest(timeout = 60.seconds) {
        val withFused = FakeLocationBackend(providers = setOf(gps, network, fused))
        val call = async { newProvider(withFused).getCurrentLocation(current(AndroidLocationSource.AUTO, timeout = 1.seconds)) }
        runCurrent()
        assertEquals(setOf(fused), providersOf(withFused), "AUTO with fused")
        call.cancel()

        val without = FakeLocationBackend(providers = setOf(gps, network))
        val second = async { newProvider(without).getCurrentLocation(current(AndroidLocationSource.AUTO, timeout = 1.seconds)) }
        runCurrent()
        assertEquals(setOf(gps, network), providersOf(without), "AUTO without fused races gps and network")
        second.cancel()
    }

    @Test
    fun `an explicit source that is off answers SERVICE_DISABLED at once and registers nothing`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        backend.setProviderEnabled(network, false)
        val provider: LocationProvider = newProvider(backend)

        val reading: LocationReading = provider.getCurrentLocation(current(AndroidLocationSource.NETWORK, timeout = 30.seconds))

        assertEquals(LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED), reading)
        assertEquals(0L, currentTime, "a known answer must not wait out the timeout")
        assertEquals(0, backend.registrationAttemptCount)
        assertTrue(provider.isLocationEnabled(), "control: the device-wide service is on while the named source is off")
    }

    @Test
    fun `AUTO with the network off races only gps and reads SERVICE_DISABLED only when none is on`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend(providers = setOf(gps, network))
        backend.setProviderEnabled(network, false)
        val provider: LocationProvider = newProvider(backend)

        val call = async { provider.getCurrentLocation(current(AndroidLocationSource.AUTO, timeout = 1.seconds)) }
        runCurrent()
        assertEquals(setOf(gps), providersOf(backend), "only the enabled provider is raced")
        call.cancel()
        runCurrent()

        backend.setProviderEnabled(gps, false)
        assertEquals(
            LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED),
            provider.getCurrentLocation(current(AndroidLocationSource.AUTO)),
        )
        assertEquals(0L, currentTime)
    }

    @Test
    fun `the device-wide switch off answers SERVICE_DISABLED for every source`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        backend.setDeviceLocationEnabled(false)
        val provider: LocationProvider = newProvider(backend)

        for (source: AndroidLocationSource in listOf(AndroidLocationSource.AUTO, AndroidLocationSource.GPS, AndroidLocationSource.NETWORK)) {
            assertEquals(
                LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED),
                provider.getCurrentLocation(current(source)),
                "device off, $source",
            )
        }
        assertEquals(false, provider.isLocationEnabled())
    }

    @Test
    fun `a stream on a named source that is off emits the reason stays open and reseeds when it returns`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        backend.setProviderEnabled(network, false)
        val provider: LocationProvider = newProvider(backend)
        val seen: MutableList<LocationReading> = mutableListOf()

        val job: Job = backgroundScope.launch { provider.observeLocation(updates(AndroidLocationSource.NETWORK)).collect { seen += it } }
        runCurrent()
        assertEquals(listOf<LocationReading>(LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)), seen)
        assertTrue(job.isActive, "SERVICE_DISABLED is not terminal")
        assertEquals(1, backend.registeredCount, "control: the listener is kept while the source is off")

        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 1_000))
        backend.setProviderEnabled(network, true)
        runCurrent()
        assertEquals(GeoCoordinates(41.0, 2.0), seen.last().fixOrNull()?.coordinates, "reseeded from the cache when the source returned")

        backend.deliver(network, backend.locationAt(network, 42.0))
        runCurrent()
        assertEquals(GeoCoordinates(42.0, 2.0), seen.last().fixOrNull()?.coordinates)
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
    }

    // The real LocationManager of the platform shadow: the same rules through the real backend.

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Test
    fun `real manager - an explicit NETWORK request with the network off answers SERVICE_DISABLED without waiting`() = runTest(timeout = 60.seconds) {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(gps, true)
        shadowOf(manager).setProviderEnabled(network, false)
        val provider: LocationProvider = createLocationProvider(application)

        val reading: LocationReading = provider.getCurrentLocation(current(AndroidLocationSource.NETWORK, timeout = 30.seconds))

        assertEquals(LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED), reading)
        assertEquals(0L, currentTime)
        assertTrue(provider.isLocationEnabled(), "control: gps is on, so the device-wide service reads on")
    }

    @Test
    fun `real manager - the stream on NETWORK emits SERVICE_DISABLED then reseeds when the network is switched on`() = runTest(timeout = 60.seconds) {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(gps, true)
        shadowOf(manager).setProviderEnabled(network, false)
        val provider: LocationProvider = createLocationProvider(application)
        val seen: MutableList<LocationReading> = mutableListOf()

        val job: Job = backgroundScope.launch { provider.observeLocation(updates(AndroidLocationSource.NETWORK)).collect { seen += it } }
        runCurrent()
        assertEquals(listOf<LocationReading>(LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)), seen)

        shadowOf(manager).setProviderEnabled(network, true)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(LocationReading.Unavailable(LocationUnavailability.NO_FIX), seen.last(), "back on, nothing cached: searching")
        assertTrue(job.isActive)
        job.cancelAndJoin()
    }
}
