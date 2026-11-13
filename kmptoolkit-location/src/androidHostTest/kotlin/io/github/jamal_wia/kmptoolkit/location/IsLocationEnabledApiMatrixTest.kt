package io.github.jamal_wia.kmptoolkit.location

import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `isLocationEnabled()` reports "the device-wide location toggle" (docs/kmptoolkit-location/04-api-reference.md)
 * and has two implementations: `LocationManager.isLocationEnabled` from API 28 and the union of the GPS and
 * network providers below it. The scenarios run on both sides of that branch and at six levels, the most
 * the timing-hygiene rule TH6 (ruling R-8) allows in one class: 24 and 26 (union path), 28 (the first level of the
 * platform method), 29 and 30 (the levels the legacy callbacks change on) and 35 (the newest this build can run).
 * No branch of this method lies between 30 and 35, so 31, 33 and 34 would repeat the same code path.
 *
 * The row that tells the two implementations apart is "the device toggle on and no provider flag on": the
 * platform method answers `true` (API 28 and up), the provider union answers `false` (24 and 26, where
 * `LocationManager.isLocationEnabled` does not exist and a call would throw `NoSuchMethodError`). Run in this
 * order (toggle first, then the provider flags) it separates the two sides of the branch at API 28 as well.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 28, 29, 30, 35])
class IsLocationEnabledApiMatrixTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val provider: LocationProvider = createLocationProvider(application)
    private val level: Int = Build.VERSION.SDK_INT

    private fun enabled(): Boolean = runBlocking { provider.isLocationEnabled() }

    @Test
    fun `a device with the GPS provider on reports location enabled`() {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        assertEquals(true, enabled(), "GPS only, API $level")
    }

    @Test
    fun `a device with only the network provider on reports location enabled`() {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        assertEquals(true, enabled(), "network only, API $level")
    }

    @Test
    fun `a device with both providers on reports location enabled`() {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        assertEquals(true, enabled(), "both providers, API $level")
    }

    @Test
    fun `switching the device toggle off reports location disabled even with provider flags left on`() {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(manager).setLocationEnabled(false)
        assertEquals(false, enabled(), "toggle off, API $level")
        shadowOf(manager).setLocationEnabled(true)
        assertEquals(true, enabled(), "toggle back on, API $level")
    }

    @Test
    fun `a device with no provider on and the toggle off reports location disabled`() {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(manager).setLocationEnabled(false)
        assertEquals(false, enabled(), "nothing on, API $level")
    }

    @Test
    fun `from API 28 the device toggle alone decides and below it the provider list does`() {
        shadowOf(manager).setLocationEnabled(true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        if (level >= Build.VERSION_CODES.P) {
            assertEquals(true, enabled(), "toggle on and no provider flag on, API $level asks the platform toggle")
        } else {
            // Below API 28 the answer is the provider list (the platform method does not exist there).
            assertEquals(false, enabled(), "no provider flag on, API $level reads the provider list")
        }
    }
}
