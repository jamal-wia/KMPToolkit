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
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Collectors and single-fix calls started and cancelled from many real threads against Robolectric's
 * real `LocationManager` shadow: no listener may stay registered, and a collection started afterwards
 * still works.
 */
@RunWith(AndroidJUnit4::class)
class LocationThreadStormTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun registered(): Int = shadowOf(manager).getRequestLocationUpdateListeners().size

    private fun fix(lat: Double): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat; longitude = 1.0; time = System.currentTimeMillis(); elapsedRealtimeNanos = System.nanoTime()
        accuracy = 5f
    }

    private fun enable() {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    @Test
    fun `start and stop storms of collectors and single fixes on real threads leave no listener`() = runBlocking {
        enable()
        val provider: LocationProvider = createLocationProvider(application, LocationProviderConfig(singleFixTimeoutMillis = 40))
        withTimeout(60_000) {
            val workers = List(8) { w ->
                async(Dispatchers.Default) {
                    repeat(40) { i ->
                        when ((w + i) % 3) {
                            0 -> {
                                val job = launch { provider.observeLocation().collect { } }
                                delay((i % 4).toLong())
                                job.cancelAndJoin()
                            }
                            1 -> provider.getCurrentLocation()
                            else -> {
                                val job = launch { provider.getCurrentLocation() }
                                delay((i % 3).toLong())
                                job.cancelAndJoin()
                            }
                        }
                    }
                }
            }
            // The shadow delivers on the main looper, which only this thread may drive.
            while (workers.any { !it.isCompleted }) { shadowOf(android.os.Looper.getMainLooper()).idle(); delay(2) }
            workers.awaitAll()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, registered(), "listeners left registered after the storm")

            val seen = java.util.concurrent.CopyOnWriteArrayList<GeoCoordinates?>()
            val job = launch(Dispatchers.Default) { provider.observeLocation().collect { seen += it } }
            var spins = 0
            while (registered() == 0) { check(spins++ < 1500) { "no registration after the storm" }; shadowOf(android.os.Looper.getMainLooper()).idle(); delay(2) }
            shadowOf(manager).simulateLocation(fix(42.0))
            spins = 0
            while (seen.lastOrNull()?.latitude != 42.0) { check(spins++ < 1500) { "no fix after the storm" }; shadowOf(android.os.Looper.getMainLooper()).idle(); delay(2) }
            job.cancelAndJoin()
            assertEquals(0, registered())
            assertTrue(seen.isNotEmpty())
        }
    }
}
