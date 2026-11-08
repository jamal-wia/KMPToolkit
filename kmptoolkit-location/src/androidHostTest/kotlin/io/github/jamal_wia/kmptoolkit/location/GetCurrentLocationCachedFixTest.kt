package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * `getCurrentLocation()` "prefers a cached fix the platform already has; falls back to requesting a fresh
 * one" only when there is none. `observeLocation()` seeds from the freshest cached fix across the enabled
 * providers; a one-shot call on a device whose GPS is on but has no fix yet (indoors, just switched on) while
 * the network provider holds one must answer with that cached fix at once, not wait out the single-fix
 * timeout and answer `null`.
 */
@RunWith(AndroidJUnit4::class)
class GetCurrentLocationCachedFixTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun fix(provider: String, lat: Double): Location = Location(provider).apply {
        latitude = lat; longitude = 2.0; time = System.currentTimeMillis(); elapsedRealtimeNanos = System.nanoTime(); accuracy = 20f
    }

    @Test
    fun `a cached network fix answers the call while the gps provider has none`() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(LocationManager.NETWORK_PROVIDER, 41.0))
        val provider: LocationProvider = createLocationProvider(application, LocationProviderConfig(singleFixTimeoutMillis = 400))

        val answer: GeoCoordinates? = withTimeout(10_000) { provider.getCurrentLocation() }

        assertEquals(GeoCoordinates(41.0, 2.0), answer, "the platform held a cached fix and the call did not use it")
    }

    @Test
    fun `control - a cached network fix answers the call when only the network provider is on`() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(LocationManager.NETWORK_PROVIDER, 41.0))
        val provider: LocationProvider = createLocationProvider(application, LocationProviderConfig(singleFixTimeoutMillis = 400))

        assertEquals(GeoCoordinates(41.0, 2.0), withTimeout(10_000) { provider.getCurrentLocation() })
    }

    private fun fixAt(provider: String, lat: Double, time: Long): Location = fix(provider, lat).apply { this.time = time }

    @Test
    fun `with both providers holding a cached fix the newer one answers`() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val now: Long = System.currentTimeMillis()
        val provider: LocationProvider = createLocationProvider(application, LocationProviderConfig(singleFixTimeoutMillis = 400))

        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fixAt(LocationManager.GPS_PROVIDER, 10.0, now - 60_000))
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fixAt(LocationManager.NETWORK_PROVIDER, 41.0, now))
        assertEquals(GeoCoordinates(41.0, 2.0), withTimeout(10_000) { provider.getCurrentLocation() }, "network is newer")

        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fixAt(LocationManager.GPS_PROVIDER, 10.0, now + 60_000))
        assertEquals(GeoCoordinates(10.0, 2.0), withTimeout(10_000) { provider.getCurrentLocation() }, "gps is newer")
    }

    @Test
    fun `a cached fix of a disabled provider is not used`() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(LocationManager.NETWORK_PROVIDER, 41.0))
        val provider: LocationProvider = createLocationProvider(application, LocationProviderConfig(singleFixTimeoutMillis = 400))

        assertEquals(null, withTimeout(10_000) { provider.getCurrentLocation() }, "the network provider is off: its cache is stale by rule")
    }
}
