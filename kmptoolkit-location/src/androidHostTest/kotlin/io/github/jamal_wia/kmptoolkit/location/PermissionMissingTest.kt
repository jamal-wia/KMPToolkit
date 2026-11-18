package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * F3 (PLAN D8, section 4.2, section 6.3): with neither location permission the one-shot call answers
 * `Unavailable(PERMISSION_MISSING)` at once, and the stream emits it and completes, the same on every
 * platform (4.x emitted null and ended on Android while iOS kept running). Nothing throws and nothing is
 * registered. A grant of either permission is enough to proceed; coarse-only fixes read approximate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PermissionMissingTest {

    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)

    private fun noPermission(): FakeLocationBackend = FakeLocationBackend().apply {
        fine = false
        coarse = false
    }

    @Test
    fun `the one-shot call answers PERMISSION_MISSING at once and registers nothing`() = runTest(timeout = 60.seconds) {
        val backend: FakeLocationBackend = noPermission()

        val reading: LocationReading = newProvider(backend).getCurrentLocation(CurrentLocationOptions(timeout = 30.seconds))

        assertEquals(permissionMissing, reading)
        assertEquals(0L, currentTime, "a known answer must not wait out the timeout")
        assertEquals(0, backend.registrationAttemptCount)
    }

    @Test
    fun `the stream emits PERMISSION_MISSING and completes without registering`() = runTest(timeout = 60.seconds) {
        val backend: FakeLocationBackend = noPermission()

        val values: List<LocationReading> = newProvider(backend).observeLocation().toList()

        assertEquals(listOf(permissionMissing), values, "one terminal reason, then completion (toList returned)")
        assertEquals(0, backend.registrationAttemptCount)
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `a permission that appears later serves the next call of the same provider (re-init)`() = runTest(timeout = 60.seconds) {
        val backend: FakeLocationBackend = noPermission()
        val provider: LocationProvider = newProvider(backend)
        assertEquals(permissionMissing, provider.getCurrentLocation())

        backend.coarse = true
        backend.setCached(LocationManager.NETWORK_PROVIDER, backend.locationAt(LocationManager.NETWORK_PROVIDER, 41.0, ageMillis = 100))

        val fix: LocationFix? = provider.getCurrentLocation().fixOrNull()
        assertEquals(GeoCoordinates(41.0, 2.0), fix?.coordinates)
        assertEquals(true, fix?.isApproximate, "only the coarse permission is held: the fix is approximate")
    }

    @Test
    fun `fine permission gives precise fixes and coarse alone gives approximate ones`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        val provider: LocationProvider = newProvider(backend)
        backend.setCached(LocationManager.NETWORK_PROVIDER, backend.locationAt(LocationManager.NETWORK_PROVIDER, 41.0))

        assertEquals(false, provider.getCurrentLocation().fixOrNull()?.isApproximate, "fine and coarse held")
        backend.fine = false
        assertEquals(true, provider.getCurrentLocation().fixOrNull()?.isApproximate, "coarse only")
        backend.fine = true
        backend.coarse = false
        assertEquals(false, provider.getCurrentLocation().fixOrNull()?.isApproximate, "fine only")
    }

    // The real backend reads the permission through the Context.

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Test
    fun `real context - no grant answers PERMISSION_MISSING and a grant lifts it`() = runTest(timeout = 60.seconds) {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider: LocationProvider = createLocationProvider(application)

        assertEquals(permissionMissing, provider.getCurrentLocation())
        assertEquals(listOf(permissionMissing), provider.observeLocation().toList())

        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(
            LocationReading.Unavailable(LocationUnavailability.NO_FIX),
            provider.getCurrentLocation(CurrentLocationOptions(timeout = 1.seconds)),
            "granted and on, but no fix within the timeout",
        )
    }
}
