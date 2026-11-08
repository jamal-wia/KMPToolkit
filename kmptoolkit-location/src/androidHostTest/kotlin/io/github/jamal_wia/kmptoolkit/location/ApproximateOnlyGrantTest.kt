package io.github.jamal_wia.kmptoolkit.location

import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Docs (05-platform-notes, "Relationship to kmptoolkit-permission"): "An approximate-only grant ... this module
 * still delivers fixes with it, at reduced accuracy", and 02-getting-started offers ACCESS_COARSE_LOCATION alone
 * as the manifest choice. The platform rule: GPS_PROVIDER needs ACCESS_FINE_LOCATION (a SecurityException for a
 * coarse-only app), NETWORK_PROVIDER accepts either. The fixes the network provider holds must still be served.
 */
@RunWith(AndroidJUnit4::class)
class ApproximateOnlyGrantTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** A manager as a coarse-only app sees it: every GPS call is a SecurityException, the network works. */
    private class CoarseOnlySource(val networkFix: Location?) : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val registered: MutableSet<LocationListener> = java.util.concurrent.CopyOnWriteArraySet()
        val disabled: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        override fun isProviderEnabled(provider: String): Boolean = provider !in disabled
        override fun lastKnownLocation(provider: String): Location? {
            if (provider == LocationManager.GPS_PROVIDER) throw SecurityException("gps provider requires ACCESS_FINE_LOCATION permission")
            return networkFix
        }

        override fun requestUpdates(provider: String, minTimeMillis: Long, minDistanceMeters: Float, listener: LocationListener) {
            if (provider == LocationManager.GPS_PROVIDER) throw SecurityException("gps provider requires ACCESS_FINE_LOCATION permission")
            registered += listener
        }

        override fun removeUpdates(listener: LocationListener) {
            registered -= listener
        }
    }

    private fun networkFix(): Location = Location(LocationManager.NETWORK_PROVIDER).apply {
        latitude = 41.0; longitude = 2.0; time = System.currentTimeMillis(); elapsedRealtimeNanos = System.nanoTime(); accuracy = 2000f
    }

    @Test
    fun `observeLocation on a coarse-only grant is seeded from the network provider`() = runBlocking {
        withTimeout(10_000) {
            val source = CoarseOnlySource(networkFix())
            val provider: LocationProvider =
                AndroidLocationProvider(application, LocationProviderConfig(), NoopLogger, SystemScreenLauncher.SeparateTask, source)

            val values: List<GeoCoordinates?> = provider.observeLocation().take(1).toList()

            assertEquals(listOf<GeoCoordinates?>(GeoCoordinates(41.0, 2.0)), values, "an approximate-only grant still delivers fixes")
        }
    }

    @Test
    fun `getCurrentLocation on a coarse-only grant answers the cached network fix`() = runBlocking {
        // The real (Robolectric) manager names GPS as the best enabled provider; the cache source answers as a
        // coarse-only app would see it.
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val provider: LocationProvider = AndroidLocationProvider(
            application, LocationProviderConfig(singleFixTimeoutMillis = 400), NoopLogger, SystemScreenLauncher.SeparateTask,
            CoarseOnlySource(networkFix()),
        )

        assertEquals(GeoCoordinates(41.0, 2.0), withTimeout(10_000) { provider.getCurrentLocation() }, "coarse-only still gets the network fix")
    }

    /** Spins (bounded) until [condition] holds, so a hang fails fast instead of blocking the Gradle run. */
    private suspend fun until(what: String, step: () -> Unit = {}, condition: () -> Boolean) {
        var spins = 0
        while (!condition()) {
            check(spins++ < 2_000) { "timed out waiting for $what" }
            step()
            delay(2)
        }
    }

    @Test
    fun `getCurrentLocation with no cached fix registers on the network provider after the gps provider refuses`() = runBlocking {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val source = CoarseOnlySource(networkFix = null)
        val provider: LocationProvider = AndroidLocationProvider(
            application, LocationProviderConfig(singleFixTimeoutMillis = 8_000), NoopLogger, SystemScreenLauncher.SeparateTask, source,
        )

        withTimeout(10_000) {
            val answer = async(Dispatchers.Default) { provider.getCurrentLocation() }
            until("the single-shot registration") { source.registered.isNotEmpty() }
            source.registered.first().onLocationChanged(networkFix())

            assertEquals(GeoCoordinates(41.0, 2.0), answer.await(), "the network provider delivered the single fix")
        }
        assertTrue(source.registered.isEmpty(), "the single-shot listener was left registered")
    }

    /** Every provider refuses, in the cache and in the registration. */
    private class RefusingSource : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        override fun isProviderEnabled(provider: String): Boolean = true
        override fun lastKnownLocation(provider: String): Location? = throw SecurityException("$provider refused")
        override fun requestUpdates(provider: String, minTimeMillis: Long, minDistanceMeters: Float, listener: LocationListener) =
            throw SecurityException("$provider refused")

        override fun removeUpdates(listener: LocationListener) = Unit
    }

    @Test
    fun `when every provider refuses the documented no-permission answers stay`() = runBlocking {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val provider: LocationProvider = AndroidLocationProvider(
            application, LocationProviderConfig(singleFixTimeoutMillis = 400), NoopLogger, SystemScreenLauncher.SeparateTask, RefusingSource(),
        )

        withTimeout(10_000) {
            assertEquals(listOf<GeoCoordinates?>(null, null), provider.observeLocation().toList())
            assertEquals(null, provider.getCurrentLocation())
        }
    }

    @Test
    fun `a collection reports null when the only provider that accepted is switched off`() = runBlocking {
        val source = CoarseOnlySource(networkFix())
        val provider: LocationProvider =
            AndroidLocationProvider(application, LocationProviderConfig(), NoopLogger, SystemScreenLauncher.SeparateTask, source)
        val seen = java.util.concurrent.CopyOnWriteArrayList<GeoCoordinates?>()

        withTimeout(10_000) {
            val job = launch(Dispatchers.Default) { provider.observeLocation().collect { seen += it } }
            until("the registration") { source.registered.isNotEmpty() }
            // The gps provider stays "on" but refused; the network provider, the only one that can deliver, goes off.
            source.disabled += LocationManager.NETWORK_PROVIDER
            until(
                "the null after the network provider went off",
                step = { source.registered.firstOrNull()?.onProviderDisabled(LocationManager.NETWORK_PROVIDER) },
            ) { seen.lastOrNull() == null && seen.isNotEmpty() }

            assertEquals(null, seen.last(), "no fix can ever come: the flow must say so")
            job.cancelAndJoin()
        }
    }
}
