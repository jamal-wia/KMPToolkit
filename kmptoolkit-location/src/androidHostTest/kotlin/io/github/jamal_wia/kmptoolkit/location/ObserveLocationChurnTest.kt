package io.github.jamal_wia.kmptoolkit.location

import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * `observeLocation()` under churn: many collect/cancel cycles, a cancel racing the registration,
 * newest-fix-wins for a slow collector, and a listener that fires after the stop. Everything bounded.
 */
@RunWith(AndroidJUnit4::class)
class ObserveLocationChurnTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class Source(
        private val cached: Map<String, Location?> = emptyMap(),
        private val onRegister: (String, LocationListener) -> Unit = { _, _ -> },
    ) : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        private val lock = Any()
        private val registrations: MutableList<Pair<String, LocationListener>> = mutableListOf()
        @Volatile var lastListener: LocationListener? = null
        val registeredCount: Int get() = synchronized(lock) { registrations.size }
        override fun isProviderEnabled(provider: String): Boolean = true
        override fun lastKnownLocation(provider: String): Location? = cached[provider]
        override fun requestUpdates(provider: String, minTimeMillis: Long, minDistanceMeters: Float, listener: LocationListener) {
            onRegister(provider, listener)
            synchronized(lock) { registrations += provider to listener }
            lastListener = listener
        }
        override fun removeUpdates(listener: LocationListener) {
            synchronized(lock) { registrations.removeAll { it.second === listener } }
        }
    }

    private fun fix(latitude: Double, time: Long = 0L): Location =
        Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = 0.0
            this.time = time
        }

    private fun provider(source: LocationUpdatesSource): LocationProvider =
        AndroidLocationProvider(application, LocationProviderConfig(), NoopLogger, SystemScreenLauncher.SeparateTask, source)

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        var spins = 0
        while (!condition()) {
            check(spins++ < 2_000) { "timed out waiting for $what" }
            delay(2)
        }
    }

    @Test
    fun `two hundred collect and cancel cycles leave no listener registered`() = runBlocking {
        withTimeout(60_000) {
            val source = Source()
            val provider: LocationProvider = provider(source)
            repeat(200) { cycle ->
                val job = launch(Dispatchers.Default) { provider.observeLocation().collect { } }
                waitUntil("registration in cycle $cycle") { source.registeredCount == 2 }
                job.cancelAndJoin()
                assertEquals(0, source.registeredCount, "cycle $cycle: a listener is still registered")
            }
        }
    }

    @Test
    fun `a cancel that lands while the second provider is registering still removes both listeners`() = runBlocking {
        withTimeout(30_000) {
            repeat(20) { cycle ->
                val entered = CountDownLatch(1)
                val proceed = CountDownLatch(1)
                val source = Source(onRegister = { provider, _ ->
                    if (provider == LocationManager.NETWORK_PROVIDER) {
                        entered.countDown()
                        proceed.await(5, TimeUnit.SECONDS)
                    }
                })
                val job = launch(Dispatchers.Default) { provider(source).observeLocation().collect { } }
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                val cancelling = launch(Dispatchers.Default) { job.cancelAndJoin() }
                delay(5)
                proceed.countDown()
                cancelling.join()
                assertEquals(0, source.registeredCount, "cycle $cycle: listeners leaked after a cancel during registration")
            }
        }
    }

    @Test
    fun `a listener that fires after the collection ended is harmless and nothing more is emitted`() = runBlocking {
        withTimeout(30_000) {
            val source = Source()
            val values: MutableList<GeoCoordinates?> = mutableListOf()
            val job = launch(Dispatchers.Default) { provider(source).observeLocation().collect { values += it } }
            waitUntil("registration") { source.registeredCount == 2 }
            val listener: LocationListener = source.lastListener!!
            job.cancelAndJoin()
            val seen: Int = values.size

            listener.onLocationChanged(fix(5.0))
            listener.onProviderDisabled(LocationManager.GPS_PROVIDER)
            delay(50)

            assertEquals(seen, values.size)
        }
    }

    @Test
    fun `two hundred rounds with the live fix delivered from another thread never end on the older seed`() = runBlocking {
        withTimeout(60_000) {
            repeat(200) { round ->
                val older: Location = fix(latitude = 1.0, time = 1_000)
                val newer: Location = fix(latitude = 2.0, time = 2_000)
                val source = Source(
                    cached = mapOf(LocationManager.GPS_PROVIDER to older),
                    onRegister = { provider, listener ->
                        if (provider == LocationManager.GPS_PROVIDER) {
                            thread { listener.onLocationChanged(newer) }.join(5_000)
                        }
                    },
                )
                val values: List<GeoCoordinates?> = withContext(Dispatchers.Default) {
                    provider(source).observeLocation().take(2).toList()
                }
                assertEquals(listOf(GeoCoordinates(1.0, 0.0), GeoCoordinates(2.0, 0.0)), values, "round $round")
            }
        }
    }

}
