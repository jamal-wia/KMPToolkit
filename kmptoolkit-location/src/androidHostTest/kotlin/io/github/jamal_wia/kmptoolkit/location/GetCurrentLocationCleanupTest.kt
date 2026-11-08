package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * `getCurrentLocation()` with no cached fix registers a single-shot listener; whether the call times
 * out, is cancelled by its caller (a screen leaving), or is repeated, no listener may stay registered
 * with the system service.
 */
@RunWith(AndroidJUnit4::class)
class GetCurrentLocationCleanupTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun registered(): Int = shadowOf(manager).getRequestLocationUpdateListeners().size

    @Test
    fun `timeouts and cancellations leave no single shot listener registered`() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider: LocationProvider = createLocationProvider(application, LocationProviderConfig(singleFixTimeoutMillis = 100))
        withTimeout(30_000) {
            repeat(5) {
                assertNull(provider.getCurrentLocation(), "no fix and no cache must time out to null")
                assertEquals(0, registered(), "a timed-out single fix left its listener registered")
            }
            repeat(5) {
                val job = launch(Dispatchers.Default) { provider.getCurrentLocation() }
                delay(30)
                assertEquals(1, registered(), "control: the pending single fix must be registered, or this test proves nothing")
                job.cancelAndJoin()
                assertEquals(0, registered(), "a cancelled single fix left its listener registered")
            }
        }
    }
}
