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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * A listener the platform holds must not outlive a collection that failed to finish registering:
 * `observeLocation()` registers the GPS and network providers one after the other, so the second
 * can fail after the first has succeeded.
 */
@RunWith(AndroidJUnit4::class)
class ObserveLocationRegistrationFailureTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    /** Throws [failure] on the network registration; tracks which listeners are registered. */
    private class FailingSecondSource(private val failure: Throwable) : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val registered: MutableSet<LocationListener> = mutableSetOf()
        override fun isProviderEnabled(provider: String): Boolean = true
        override fun lastKnownLocation(provider: String): Location? = null
        override fun requestUpdates(
            provider: String,
            minTimeMillis: Long,
            minDistanceMeters: Float,
            listener: LocationListener,
        ) {
            if (provider == LocationManager.NETWORK_PROVIDER) throw failure
            registered += listener
        }

        override fun removeUpdates(listener: LocationListener) {
            registered -= listener
        }
    }

    private fun provider(source: LocationUpdatesSource): LocationProvider =
        AndroidLocationProvider(application, LocationProviderConfig(), NoopLogger, SystemScreenLauncher.SeparateTask, source)

    @Test
    fun `a permission failure on the second provider removes the first provider's listener`() = runBlocking {
        withTimeout(5_000) {
        val source = FailingSecondSource(SecurityException("network denied"))

        val values: List<GeoCoordinates?> = provider(source).observeLocation().toList()

        assertEquals(listOf(null, null), values)
        assertTrue(source.registered.isEmpty(), "listener still registered: ${source.registered.size}")
        }
    }

    @Test
    fun `any other failure on the second provider removes the listener and reaches the collector`() = runBlocking {
        withTimeout(5_000) {
        val source = FailingSecondSource(IllegalStateException("dead system server"))

        assertFailsWith<IllegalStateException> { provider(source).observeLocation().first { false } }

        assertTrue(source.registered.isEmpty(), "listener still registered: ${source.registered.size}")
        }
    }
}
