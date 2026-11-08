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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * `observeLocation()` against Robolectric's real `LocationManager` shadow, across a screen's lifetime:
 * a fix, the service going off (null) and on again, a second collection after the first left, and no
 * listener left registered after the collectors are cancelled.
 */
@RunWith(AndroidJUnit4::class)
class ObserveLocationRealManagerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun fix(lat: Double): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat; longitude = 1.0; time = System.currentTimeMillis(); elapsedRealtimeNanos = System.nanoTime()
        accuracy = 5f
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        var spins = 0
        while (!condition()) {
            check(spins++ < 1_500) { "timed out waiting for $what" }
            shadowOf(android.os.Looper.getMainLooper()).idle() // the listener is called on the main looper
            delay(2)
        }
    }

    @Test
    fun `a screen's collections see fixes and the service going off and on and leave no listener`() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val provider: LocationProvider = createLocationProvider(application)
        withTimeout(30_000) {
            repeat(3) { screen ->
                val seen = java.util.concurrent.CopyOnWriteArrayList<GeoCoordinates?>()
                val job = launch(Dispatchers.Default) { provider.observeLocation().collect { seen += it } }
                until("seed in screen $screen") { seen.isNotEmpty() }
                // the seed is sent before the registration, so wait for the registration itself
                until("registration in screen $screen") { shadowOf(manager).getRequestLocationUpdateListeners().isNotEmpty() }

                shadowOf(manager).simulateLocation(fix(10.0 + screen))
                until("fix in screen $screen") { seen.lastOrNull()?.latitude == 10.0 + screen }

                shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
                shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
                until("no-signal null in screen $screen") { seen.lastOrNull() == null }
                shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
                shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
                shadowOf(manager).simulateLocation(fix(20.0 + screen))
                until("fix after re-enable in screen $screen") { seen.lastOrNull()?.latitude == 20.0 + screen }

                job.cancelAndJoin()
                assertEquals(0, shadowOf(manager).getRequestLocationUpdateListeners().size, "screen $screen: listeners left after cancel")
            }
        }
    }
}
