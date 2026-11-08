package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The library supports API 24, where `LocationListener.onStatusChanged`, `onProviderEnabled` and
 * `onProviderDisabled` are abstract (they became interface default methods in API 30) and where the platform
 * does call them: a listener built against a newer SDK that does not implement all of them dies with
 * `AbstractMethodError` on the main thread the first time a provider is switched on or reports a status.
 * Both the live collection and the single-shot request must survive those callbacks on every supported level.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 28, 29, 30, 35])
class LocationListenerApiLevelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private suspend fun until(what: String, condition: () -> Boolean) {
        val deadline: Long = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            delay(2)
        }
    }

    @Test
    fun `the live collection's listener answers every callback the platform makes on this level`() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider: LocationProvider = createLocationProvider(application)
        withTimeout(30_000) {
            val job = launch(Dispatchers.Default) { provider.observeLocation().collect { } }
            until("registration") { shadowOf(manager).getRequestLocationUpdateListeners().isNotEmpty() }
            val listener: LocationListener = assertNotNull(shadowOf(manager).getRequestLocationUpdateListeners().first())

            val failures: List<String> = listOf<Pair<String, () -> Unit>>(
                "onProviderEnabled" to { listener.onProviderEnabled(LocationManager.GPS_PROVIDER) },
                "onStatusChanged" to { @Suppress("DEPRECATION") listener.onStatusChanged(LocationManager.GPS_PROVIDER, 2, null) },
                "onProviderDisabled" to { listener.onProviderDisabled(LocationManager.GPS_PROVIDER) },
            ).mapNotNull { (name, call) -> runCatching { call() }.exceptionOrNull()?.let { "$name -> $it" } }
            assertEquals(emptyList(), failures, "the listener does not implement what this platform level calls")

            // And through the real dispatch path of the platform shadow: switch the provider off and on again.
            val dispatch: Throwable? = runCatching {
                shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
                shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
                shadowOf(Looper.getMainLooper()).idle()
            }.exceptionOrNull()
            assertEquals(null, dispatch?.toString(), "switching a provider off and on crashed the dispatch")
            job.cancelAndJoin()
        }
    }

    @Test
    fun `the single shot request's listener answers every callback the platform makes on this level`() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider: LocationProvider = createLocationProvider(application, LocationProviderConfig(singleFixTimeoutMillis = 15_000))
        withTimeout(30_000) {
            val job = launch(Dispatchers.Default) { provider.getCurrentLocation() }
            until("registration") { shadowOf(manager).getRequestLocationUpdateListeners().isNotEmpty() }
            val listener: LocationListener = assertNotNull(shadowOf(manager).getRequestLocationUpdateListeners().first())

            val failures: List<String> = listOf<Pair<String, () -> Unit>>(
                "onProviderEnabled" to { listener.onProviderEnabled(LocationManager.GPS_PROVIDER) },
                "onProviderDisabled" to { listener.onProviderDisabled(LocationManager.GPS_PROVIDER) },
                "onStatusChanged" to { @Suppress("DEPRECATION") listener.onStatusChanged(LocationManager.GPS_PROVIDER, 2, null) },
            ).mapNotNull { (name, call) -> runCatching { call() }.exceptionOrNull()?.let { "$name -> ${it::class.simpleName}" } }
            assertEquals(emptyList(), failures, "the single-shot listener does not implement what this platform level calls")
            job.cancelAndJoin()
        }
    }
}
