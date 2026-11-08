package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
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
 * Providers flapping off and on and fixes arriving in random order while a collector on a real thread
 * reads the conflated flow: the collector always ends on the newest thing that happened (a fix, or `null`
 * when every provider is off), and cancelling leaves no listener.
 */
@RunWith(AndroidJUnit4::class)
class ObserveLocationProviderFlapTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun fix(lat: Double): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat; longitude = 1.0; time = System.currentTimeMillis(); elapsedRealtimeNanos = System.nanoTime(); accuracy = 5f
    }

    private fun setBoth(enabled: Boolean) {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, enabled)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, enabled)
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        var spins = 0
        while (!condition()) {
            check(spins++ < 2_000) { "timed out waiting for $what" }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            delay(2)
        }
    }

    @Test
    fun `random flapping ends on the newest event and leaves no listener`() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val provider: LocationProvider = createLocationProvider(application)
        withTimeout(60_000) {
            for (seed in 1..6) {
                val random = Random(seed)
                setBoth(true)
                val seen = java.util.concurrent.CopyOnWriteArrayList<GeoCoordinates?>()
                val job = launch(Dispatchers.Default) { provider.observeLocation().collect { seen += it } }
                until("registration, seed $seed") { shadowOf(manager).getRequestLocationUpdateListeners().isNotEmpty() }
                repeat(60) { i ->
                    when (random.nextInt(3)) {
                        0 -> shadowOf(manager).simulateLocation(fix(10.0 + i))
                        1 -> setBoth(false)
                        else -> setBoth(true)
                    }
                    if (random.nextInt(4) == 0) { shadowOf(android.os.Looper.getMainLooper()).idle(); delay(1) }
                }
                setBoth(true)
                shadowOf(manager).simulateLocation(fix(500.0 + seed))
                until("the last fix, seed $seed") { seen.lastOrNull()?.latitude == 500.0 + seed }
                setBoth(false)
                until("the final null, seed $seed") { seen.lastOrNull() == null }
                assertNull(seen.last(), "seed $seed: the collector did not end on null after every provider went off")
                job.cancelAndJoin()
                assertEquals(0, shadowOf(manager).getRequestLocationUpdateListeners().size, "seed $seed: listener left")
            }
        }
    }
}
