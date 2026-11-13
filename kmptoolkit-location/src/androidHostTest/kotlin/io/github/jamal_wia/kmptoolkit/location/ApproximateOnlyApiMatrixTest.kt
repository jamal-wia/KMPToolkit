package io.github.jamal_wia.kmptoolkit.location

import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The coarse-only grant (docs/kmptoolkit-location/05-platform-notes.md: still delivers fixes at reduced accuracy;
 * GPS refuses with a SecurityException, the network provider accepts either permission) at the API levels where
 * the platform's location rules moved (29: background restriction, 31: approximate-location choice, 33, 34), with
 * the listener books kept: every listener a request registers is removed again, after a cancelled collection and
 * after a single-fix timeout, however often the cycle repeats.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29, 31, 33, 34])
class ApproximateOnlyApiMatrixTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class CoarseOnlySource(val networkFix: Location?) : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val registered: MutableSet<LocationListener> = CopyOnWriteArraySet()
        val disabled: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val added: AtomicInteger = AtomicInteger()
        val removed: AtomicInteger = AtomicInteger()
        override fun isProviderEnabled(provider: String): Boolean = provider !in disabled
        override fun lastKnownLocation(provider: String): Location? {
            if (provider == LocationManager.GPS_PROVIDER) throw SecurityException("gps provider requires ACCESS_FINE_LOCATION permission")
            return networkFix
        }

        override fun requestUpdates(provider: String, minTimeMillis: Long, minDistanceMeters: Float, listener: LocationListener) {
            if (provider == LocationManager.GPS_PROVIDER) throw SecurityException("gps provider requires ACCESS_FINE_LOCATION permission")
            added.incrementAndGet()
            registered += listener
        }

        override fun removeUpdates(listener: LocationListener) {
            removed.incrementAndGet()
            registered -= listener
        }
    }

    private fun networkFix(): Location = Location(LocationManager.NETWORK_PROVIDER).apply {
        // timing-hygiene-ignore: TH2 producer the stamps of a fix the fake source hands out, not a measurement
        latitude = 41.0; longitude = 2.0; time = System.currentTimeMillis(); elapsedRealtimeNanos = System.nanoTime(); accuracy = 2000f
    }

    private fun providerOver(source: LocationUpdatesSource, timeoutMillis: Long = 30_000): LocationProvider = AndroidLocationProvider(
        application, LocationProviderConfig(singleFixTimeoutMillis = timeoutMillis), NoopLogger, SystemScreenLauncher.SeparateTask, source,
    )

    @Test
    fun `a coarse-only observation is seeded from the network provider and a cached fix is served`() = runBlocking {
        withTimeout(20_000) {
            val source = CoarseOnlySource(networkFix())
            assertEquals(GeoCoordinates(41.0, 2.0), providerOver(source).observeLocation().first())
            assertEquals(GeoCoordinates(41.0, 2.0), providerOver(source, 400).getCurrentLocation())
        }
    }

    @Test
    fun `two hundred collect and cancel cycles leave no listener registered`() = runBlocking {
        val source = CoarseOnlySource(null)
        val provider: LocationProvider = providerOver(source)
        withTimeout(60_000) {
            repeat(200) {
                val job = launch(Dispatchers.Default) { provider.observeLocation().collect { } }
                // The seed is null (no fix); the registration itself is what is awaited (bounded by the withTimeout above).
                while (source.registered.isEmpty()) kotlinx.coroutines.yield()
                job.cancelAndJoin()
            }
        }
        assertTrue(source.registered.isEmpty(), "listeners left registered: ${source.registered.size}")
        assertEquals(source.added.get(), source.removed.get(), "every registration must be removed exactly once")
    }

    @Test
    fun `fifty single fix timeouts leave no listener registered and answer null`() = runBlocking {
        val source = CoarseOnlySource(null)
        val provider: LocationProvider = providerOver(source, timeoutMillis = 20)
        withTimeout(60_000) {
            repeat(50) { assertNull(provider.getCurrentLocation()) }
        }
        assertTrue(source.registered.isEmpty(), "listeners left registered: ${source.registered.size}")
        assertEquals(source.added.get(), source.removed.get())
    }
}
