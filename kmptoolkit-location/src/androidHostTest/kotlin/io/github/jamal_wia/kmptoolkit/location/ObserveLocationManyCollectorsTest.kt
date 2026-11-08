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
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Two hundred collectors of one provider at once, each with its own pair of listeners: every one is served
 * the fix, cancelling any subset takes exactly its own listeners and leaves the others live, and cancelling
 * the rest leaves nothing registered.
 */
@RunWith(AndroidJUnit4::class)
class ObserveLocationManyCollectorsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class Source : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        private val lock = Any()
        private val registered: MutableList<LocationListener> = mutableListOf()
        val listeners: List<LocationListener> get() = synchronized(lock) { registered.toList() }
        override fun isProviderEnabled(provider: String): Boolean = true
        override fun lastKnownLocation(provider: String): Location? = null
        override fun requestUpdates(provider: String, minTimeMillis: Long, minDistanceMeters: Float, listener: LocationListener) { synchronized(lock) { registered += listener } }
        override fun removeUpdates(listener: LocationListener) { synchronized(lock) { registered.removeAll { it === listener } } }
    }

    private fun fix(latitude: Double): Location = Location(LocationManager.GPS_PROVIDER).apply { this.latitude = latitude; longitude = 0.0 }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline: Long = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            delay(2)
        }
    }

    @Test
    fun `two hundred collectors are served and cancelled independently`() = runBlocking {
        withTimeout(60_000) {
            val source = Source()
            val provider: LocationProvider =
                AndroidLocationProvider(application, LocationProviderConfig(), NoopLogger, SystemScreenLauncher.SeparateTask, source)
            val last = Array(200) { java.util.concurrent.atomic.AtomicReference<GeoCoordinates?>(GeoCoordinates(-1.0, -1.0)) }
            val jobs = (0 until 200).map { i -> launch(Dispatchers.Default) { provider.observeLocation().collect { last[i].set(it) } } }
            waitUntil("400 listeners") { source.listeners.size == 400 }

            source.listeners.toList().forEach { it.onLocationChanged(fix(1.0)) }
            waitUntil("every collector served") { last.all { it.get() == GeoCoordinates(1.0, 0.0) } }

            val cancelled = jobs.filterIndexed { i, _ -> i % 2 == 0 }
            cancelled.forEach { it.cancelAndJoin() }
            assertEquals(200, source.listeners.size, "cancelling half must take exactly their own 200 listeners")
            source.listeners.toList().forEach { it.onLocationChanged(fix(2.0)) }
            waitUntil("the live half served") { jobs.indices.filter { it % 2 == 1 }.all { last[it].get() == GeoCoordinates(2.0, 0.0) } }
            assertTrue(jobs.indices.filter { it % 2 == 0 }.all { last[it].get() == GeoCoordinates(1.0, 0.0) }, "a cancelled collector still received")

            jobs.filterIndexed { i, _ -> i % 2 == 1 }.also { it.forEach { job -> job.cancel() } }.joinAll()
            assertEquals(0, source.listeners.size)
        }
    }
}
