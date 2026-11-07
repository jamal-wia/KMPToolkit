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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * The stream "emits null when ... location service disabled". An app that comes back to the foreground
 * after the user switched location off, and collects again, must not start on the cached position of
 * a service that is off (a platform that still hands out its last fix for a disabled provider).
 */
@RunWith(AndroidJUnit4::class)
class ObserveLocationDisabledSeedTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class Source : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        override fun isProviderEnabled(provider: String): Boolean = false
        override fun lastKnownLocation(provider: String): Location? =
            Location(provider).apply { latitude = 12.0; longitude = 34.0; time = 1L }
        override fun requestUpdates(provider: String, minTimeMillis: Long, minDistanceMeters: Float, listener: LocationListener) = Unit
        override fun removeUpdates(listener: LocationListener) = Unit
    }

    @Test
    fun `a collection that starts while every provider is off does not begin on the cached position`() = runBlocking {
        withTimeout(10_000) {
            val provider: LocationProvider =
                AndroidLocationProvider(application, LocationProviderConfig(), NoopLogger, SystemScreenLauncher.SeparateTask, Source())
            assertEquals(null, provider.observeLocation().first(), "first value was a stale cached fix while the service is off")
        }
    }
}
