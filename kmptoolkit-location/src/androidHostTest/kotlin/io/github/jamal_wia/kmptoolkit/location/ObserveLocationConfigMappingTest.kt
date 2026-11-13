package io.github.jamal_wia.kmptoolkit.location

import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * The documented mapping of [LocationProviderConfig] onto the platform request (docs/kmptoolkit-location/04-api-reference.md:
 * `minUpdateDistanceMeters` maps to `requestLocationUpdates` minDistance, `minUpdateIntervalMillis` to minTime): every
 * provider an `observeLocation` registers gets exactly the configured pair, and the single-shot request behind
 * `getCurrentLocation` asks for no throttling at all. Swapping the two values, or sending the defaults, is invisible
 * to a collector that sees fixes arrive, so the arguments themselves are recorded.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ObserveLocationConfigMappingTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class Request(val provider: String, val minTimeMillis: Long, val minDistanceMeters: Float)

    private class RecordingSource : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val requests: MutableList<Request> = CopyOnWriteArrayList()
        val registered: MutableSet<LocationListener> = CopyOnWriteArraySet()
        override fun isProviderEnabled(provider: String): Boolean = true
        override fun lastKnownLocation(provider: String): Location? = null
        override fun requestUpdates(provider: String, minTimeMillis: Long, minDistanceMeters: Float, listener: LocationListener) {
            requests += Request(provider, minTimeMillis, minDistanceMeters)
            registered += listener
        }

        override fun removeUpdates(listener: LocationListener) {
            registered -= listener
        }
    }

    private fun providerOver(source: LocationUpdatesSource, config: LocationProviderConfig): LocationProvider =
        AndroidLocationProvider(application, config, NoopLogger, SystemScreenLauncher.SeparateTask, source)

    @Test
    fun `observeLocation registers every provider with the configured distance and interval and not the other way round`() = runBlocking {
        withTimeout(20_000) {
            val source = RecordingSource()
            val provider: LocationProvider = providerOver(source, LocationProviderConfig(minUpdateDistanceMeters = 7.5f, minUpdateIntervalMillis = 1234L))

            // first() is the seed; the registration follows it, so collect until the registrations are in.
            val job = launch(Dispatchers.Default) { provider.observeLocation().collect { } }
            while (source.requests.size < 2) {
                kotlinx.coroutines.yield()
            }
            job.cancelAndJoin()

            assertEquals(setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER), source.requests.map { it.provider }.toSet())
            for (request: Request in source.requests) {
                assertEquals(1234L, request.minTimeMillis, "minTime of ${request.provider}")
                assertEquals(7.5f, request.minDistanceMeters, "minDistance of ${request.provider}")
            }
            assertTrue(source.registered.isEmpty(), "the cancelled collection left a listener")
        }
    }

    @Test
    fun `the default configuration asks the platform for every update`() = runBlocking {
        withTimeout(20_000) {
            val source = RecordingSource()
            val provider: LocationProvider = providerOver(source, LocationProviderConfig())

            val job = launch(Dispatchers.Default) { provider.observeLocation().collect { } }
            while (source.requests.size < 2) {
                kotlinx.coroutines.yield()
            }
            job.cancelAndJoin()

            for (request: Request in source.requests) {
                assertEquals(0L, request.minTimeMillis)
                assertEquals(0f, request.minDistanceMeters)
            }
        }
    }

    @Test
    fun `the single shot behind getCurrentLocation is never throttled by the observe settings`() = runBlocking {
        withTimeout(20_000) {
            val source = RecordingSource()
            val provider: LocationProvider = providerOver(
                source,
                LocationProviderConfig(minUpdateDistanceMeters = 50f, minUpdateIntervalMillis = 60_000L, singleFixTimeoutMillis = 50L),
            )

            assertEquals(null, provider.getCurrentLocation())

            assertTrue(source.requests.isNotEmpty(), "no cached fix: a request was made")
            for (request: Request in source.requests) {
                assertEquals(0L, request.minTimeMillis, "a single fix must not wait for the observe interval")
                assertEquals(0f, request.minDistanceMeters, "a single fix must not wait for the observe distance")
            }
            assertTrue(source.registered.isEmpty(), "the timed-out request left a listener")
        }
    }
}
