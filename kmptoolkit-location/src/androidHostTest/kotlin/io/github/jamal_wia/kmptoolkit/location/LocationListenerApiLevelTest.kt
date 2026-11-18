package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Bug class 3 (the 4.0.0 `AbstractMethodError`): the library supports API 24, where
 * `LocationListener.onStatusChanged`, `onProviderEnabled` and `onProviderDisabled` are abstract (they
 * became interface default methods in API 30) and where the platform does call them: a listener built
 * against a newer SDK that does not implement all of them dies on the main thread the first time a
 * provider is switched on or reports a status. Both the stream's listener and the one-shot call's
 * one-update listener must survive those callbacks on every supported level, through the real backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 28, 29, 30, 35])
class LocationListenerApiLevelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val gps: String = LocationManager.GPS_PROVIDER

    @Suppress("DEPRECATION")
    private fun liveListeners(): List<LocationListener> = shadowOf(manager).getRequestLocationUpdateListeners().toList()

    private fun callEverything(listener: LocationListener): List<String> = listOf<Pair<String, () -> Unit>>(
        "onProviderEnabled" to { listener.onProviderEnabled(gps) },
        "onStatusChanged" to { @Suppress("DEPRECATION") listener.onStatusChanged(gps, 2, null) },
        "onProviderDisabled" to { listener.onProviderDisabled(gps) },
    ).mapNotNull { (name: String, call: () -> Unit) -> runCatching { call() }.exceptionOrNull()?.let { failure: Throwable -> "$name -> $failure" } }

    @Test
    fun `the stream's listener answers every callback the platform makes on this level`() = runTest(timeout = 60.seconds) {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(gps, true)
        val provider: LocationProvider = createLocationProvider(application)
        val job: Job = backgroundScope.launch {
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { }
        }
        runCurrent()
        val listener: LocationListener = assertNotNull(liveListeners().firstOrNull(), "control: the stream registered a listener, API ${Build.VERSION.SDK_INT}")

        assertEquals(emptyList(), callEverything(listener), "the listener does not implement what API ${Build.VERSION.SDK_INT} calls")

        // And through the real dispatch path of the platform shadow: switch the provider off and on again.
        shadowOf(manager).setProviderEnabled(gps, false)
        shadowOf(manager).setProviderEnabled(gps, true)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(true, job.isActive, "the dispatch crashed or ended the stream")
        job.cancelAndJoin()
        assertEquals(0, liveListeners().size)
    }

    @Test
    fun `the one-update listener of the one-shot call answers every callback on this level`() = runTest(timeout = 60.seconds) {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(gps, true)
        val provider: LocationProvider = createLocationProvider(application)
        val call = async {
            provider.getCurrentLocation(
                CurrentLocationOptions(maxAge = kotlin.time.Duration.ZERO, platform = AndroidCurrentLocationOptions(AndroidLocationSource.GPS)),
            )
        }
        runCurrent()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Below API 30 the call registers its own one-update listener: exercise it directly.
            val listener: LocationListener = assertNotNull(liveListeners().firstOrNull(), "control: the call registered a listener")
            assertEquals(emptyList(), callEverything(listener), "API ${Build.VERSION.SDK_INT}")
        }
        call.cancel()
        runCurrent()
        assertEquals(0, liveListeners().size, "a cancelled call left a listener, API ${Build.VERSION.SDK_INT}")
    }
}
