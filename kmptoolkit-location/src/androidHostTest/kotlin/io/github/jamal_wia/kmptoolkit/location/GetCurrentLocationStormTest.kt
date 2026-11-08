package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
 * Many concurrent `getCurrentLocation()` calls with no cached fix: every one times out to `null` or takes
 * a fix, none throws (a second resume of a continuation would), cancelled callers leave nothing behind, and
 * no single-shot listener stays registered with the system service.
 */
@RunWith(AndroidJUnit4::class)
class GetCurrentLocationStormTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun registered(): Int = shadowOf(manager).getRequestLocationUpdateListeners().size

    private fun fix(lat: Double): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat; longitude = 1.0; time = System.currentTimeMillis(); elapsedRealtimeNanos = System.nanoTime(); accuracy = 5f
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        val deadline: Long = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            delay(2)
        }
    }

    @Test
    fun `sixty concurrent calls with no fix all end null and leave no listener`() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider: LocationProvider = createLocationProvider(application, LocationProviderConfig(singleFixTimeoutMillis = 150))
        withTimeout(60_000) {
            val results: List<GeoCoordinates?> = (1..60).map { async(Dispatchers.Default) { provider.getCurrentLocation() } }.awaitAll()
            assertTrue(results.all { it == null }, "no fix and no cache: every call must time out to null, got $results")
            until("every listener removed") { registered() == 0 }
            assertEquals(0, registered(), "a single-shot listener is still registered")
        }
    }

    @Test
    fun `calls cancelled at random moments while fixes arrive leave nothing behind and never throw`() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider: LocationProvider = createLocationProvider(application, LocationProviderConfig(singleFixTimeoutMillis = 120))
        withTimeout(90_000) {
            for (seed in 1..5) {
                val random = Random(seed)
                val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
                val jobs = (1..30).map {
                    launch(Dispatchers.Default) {
                        try { provider.getCurrentLocation() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { failures += e }
                    }
                }
                repeat(40) { step ->
                    when (random.nextInt(3)) {
                        0 -> shadowOf(manager).simulateLocation(fix(step.toDouble()))
                        1 -> jobs.random(random).cancel()
                        else -> delay(random.nextLong(0, 8))
                    }
                    shadowOf(Looper.getMainLooper()).idle()
                }
                jobs.forEach { it.cancelAndJoin() }
                until("listeners removed, seed $seed") { registered() == 0 }
                assertEquals(emptyList(), failures.map { it.toString() }, "seed $seed: a call threw")
                assertEquals(0, registered(), "seed $seed: listener left")
            }
        }
    }
}
