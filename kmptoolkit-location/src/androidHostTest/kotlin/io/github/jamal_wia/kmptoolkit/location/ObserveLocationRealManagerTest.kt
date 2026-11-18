package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `observeLocation()` through the real `ManagerLocationBackend` against Robolectric's `LocationManager`
 * shadow, across a screen's lifetime: a fix, the service going off (`SERVICE_DISABLED`, stream open) and
 * on again, a second collection after the first left, and neither a listener nor a broadcast receiver
 * left registered after the collectors are cancelled. The test thread is the main looper, so a delivery
 * is `idle()` and no timing is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30, 35])
class ObserveLocationRealManagerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    private fun fix(lat: Double): Location = Location(gps).apply {
        latitude = lat
        longitude = 1.0
        time = 1_700_000_000_000L
        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        accuracy = 5f
    }

    @Suppress("DEPRECATION")
    private fun listeners(): Int = shadowOf(manager).getRequestLocationUpdateListeners().size

    private fun tag(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix:${reading.fix.coordinates.latitude}"
        is LocationReading.Unavailable -> "none:${reading.reason}"
    }

    @Test
    fun `a screen's collections see fixes and the service going off and on and leave nothing registered`() = runTest(timeout = 60.seconds) {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(gps, true)
        shadowOf(manager).setProviderEnabled(network, true)
        val provider: LocationProvider = createLocationProvider(application)
        val options = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)
        val receiversBefore: Int = shadowOf(application).registeredReceivers.size

        repeat(3) { screen: Int ->
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { provider.observeLocation(options).collect { reading: LocationReading -> seen += reading } }
            runCurrent()
            // The platform keeps the last fix of the previous screen: it is the seed (within maxSeedAge) from the second screen on.
            val expectedSeed: String = if (screen == 0) "none:NO_FIX" else "fix:${20.0 + screen - 1}"
            assertEquals(listOf(expectedSeed), seen.map(::tag), "screen $screen: the seed comes first")
            assertEquals(true, listeners() > 0, "screen $screen: control, the collection registered a listener")
            assertEquals(receiversBefore + 1, shadowOf(application).registeredReceivers.size, "screen $screen: control, the service receiver is registered")

            shadowOf(manager).simulateLocation(fix(10.0 + screen))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals("fix:${10.0 + screen}", tag(seen.last()), "screen $screen: the fix")

            shadowOf(manager).setProviderEnabled(gps, false)
            shadowOf(manager).setProviderEnabled(network, false)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals("none:SERVICE_DISABLED", tag(seen.last()), "screen $screen: the off state")
            assertEquals(true, job.isActive, "screen $screen: SERVICE_DISABLED does not end the stream")

            shadowOf(manager).setProviderEnabled(gps, true)
            shadowOf(manager).setProviderEnabled(network, true)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            shadowOf(manager).simulateLocation(fix(20.0 + screen))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals("fix:${20.0 + screen}", tag(seen.last()), "screen $screen: a fix after the service returned")

            job.cancelAndJoin()
            assertEquals(0, listeners(), "screen $screen: listeners left after cancel")
            assertEquals(receiversBefore, shadowOf(application).registeredReceivers.size, "screen $screen: receiver left after cancel")
        }
    }
}
