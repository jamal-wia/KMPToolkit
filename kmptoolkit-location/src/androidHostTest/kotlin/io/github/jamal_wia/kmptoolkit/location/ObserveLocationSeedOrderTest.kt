package io.github.jamal_wia.kmptoolkit.location

import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith

/**
 * The contract of `observeLocation()`'s first value: the freshest cached fix, delivered before any
 * live fix, so the collector never ends on a cached position that is older than a live one. A live
 * fix is delivered on the main looper while the collector's block runs on its own dispatcher, so the
 * test lands one at the exact point of registration.
 */
@RunWith(AndroidJUnit4::class)
class ObserveLocationSeedOrderTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class FakeSource(
        private val cached: Map<String, Location?>,
        private val onRegister: (LocationListener) -> Unit = {},
    ) : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var removed = 0
        override fun isProviderEnabled(provider: String): Boolean = true
        override fun lastKnownLocation(provider: String): Location? = cached[provider]
        override fun requestUpdates(
            provider: String,
            minTimeMillis: Long,
            minDistanceMeters: Float,
            listener: LocationListener,
        ) {
            if (provider == LocationManager.GPS_PROVIDER) onRegister(listener)
        }

        override fun removeUpdates(listener: LocationListener) {
            removed++
        }
    }

    private fun fix(latitude: Double, time: Long): Location =
        Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = 0.0
            this.time = time
        }

    private fun provider(source: LocationUpdatesSource): LocationProvider =
        AndroidLocationProvider(application, LocationProviderConfig(), NoopLogger, SystemScreenLauncher.SeparateTask, source)

    @Test
    fun `a live fix delivered while registering is not overtaken by the older cached seed`() = runBlocking {
        val older: Location = fix(latitude = 1.0, time = 1_000)
        val newer: Location = fix(latitude = 2.0, time = 2_000)
        val source = FakeSource(
            cached = mapOf(LocationManager.GPS_PROVIDER to older),
            onRegister = { listener: LocationListener -> listener.onLocationChanged(newer) },
        )

        val values: List<GeoCoordinates?> = withContext(Dispatchers.Default) {
            provider(source).observeLocation().take(2).toList()
        }

        assertEquals(listOf(GeoCoordinates(1.0, 0.0), GeoCoordinates(2.0, 0.0)), values)
        assertEquals(1, source.removed)
    }

    @Test
    fun `with no cached fix the first value is null`() = runBlocking {
        val source = FakeSource(cached = emptyMap())

        val first: GeoCoordinates? = withContext(Dispatchers.Default) {
            provider(source).observeLocation().take(1).toList().single()
        }

        assertNull(first)
    }
}
