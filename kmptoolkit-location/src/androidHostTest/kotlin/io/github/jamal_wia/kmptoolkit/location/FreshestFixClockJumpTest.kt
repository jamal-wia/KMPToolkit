package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

/**
 * "Freshest cached fix": the platform says (Location.getTime) that the UTC time of a fix "is not monotonic: it can
 * jump forward or backwards unpredictably. So always use getElapsedRealtimeNanos when calculating age." A device
 * whose wall clock was corrected between two fixes (NTP after boot, the user changing the time) holds an older fix
 * with the larger UTC time and the newer fix with the smaller one.
 */
@RunWith(AndroidJUnit4::class)
class FreshestFixClockJumpTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun fix(provider: String, lat: Double, utcMillis: Long, ageMillis: Long): Location = Location(provider).apply {
        latitude = lat; longitude = 2.0; time = utcMillis
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMillis * 1_000_000L
        accuracy = 20f
    }

    private fun arrange(): LocationProvider {
        // Robolectric's paused clock starts a fraction of a second after "boot"; a fix ten minutes old would get a
        // negative monotonic stamp, which no device produces. Move the clock well past the fixtures' ages first.
        println("FRESHEST-CLOCK elapsedRealtimeNanos at start = ${SystemClock.elapsedRealtimeNanos()}")
        ShadowSystemClock.advanceBy(java.time.Duration.ofHours(2))
        assertTrue(SystemClock.elapsedRealtimeNanos() > 3_600_000_000_000L, "fixture precondition: the clock is past one hour")
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val now: Long = System.currentTimeMillis()
        // GPS fix: taken 10 minutes ago, stamped with the (then wrong, ahead) wall clock.
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(LocationManager.GPS_PROVIDER, 10.0, now + 3_600_000, 600_000))
        // Network fix: taken a second ago, after the clock was corrected back.
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(LocationManager.NETWORK_PROVIDER, 41.0, now, 1_000))
        return createLocationProvider(application, LocationProviderConfig(singleFixTimeoutMillis = 400))
    }

    @Test
    fun `getCurrentLocation answers the fix that is younger by the monotonic clock`() = runBlocking {
        assertEquals(GeoCoordinates(41.0, 2.0), withTimeout(10_000) { arrange().getCurrentLocation() })
    }

    @Test
    fun `observeLocation seeds with the fix that is younger by the monotonic clock`() = runBlocking {
        assertEquals(GeoCoordinates(41.0, 2.0), withTimeout(10_000) { arrange().observeLocation().first() })
    }
}
