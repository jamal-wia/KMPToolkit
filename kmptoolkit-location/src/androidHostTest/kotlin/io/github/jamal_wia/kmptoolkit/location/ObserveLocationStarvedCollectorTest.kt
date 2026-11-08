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
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * `observeLocation()` while the collector's dispatcher is starved: every worker of `Dispatchers.Default`
 * is blocked for a while, fixes keep arriving from another thread, and when the workers come back the
 * collector ends on the newest fix, whatever the delay was. Waits are state-driven, never a fixed sleep.
 */
@RunWith(AndroidJUnit4::class)
class ObserveLocationStarvedCollectorTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class Source : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        private val lock = Any()
        private val registrations: MutableList<LocationListener> = mutableListOf()
        @Volatile var lastListener: LocationListener? = null
        val registeredCount: Int get() = synchronized(lock) { registrations.size }
        override fun isProviderEnabled(provider: String): Boolean = true
        override fun lastKnownLocation(provider: String): Location? = null
        override fun requestUpdates(provider: String, minTimeMillis: Long, minDistanceMeters: Float, listener: LocationListener) {
            synchronized(lock) { registrations += listener }
            lastListener = listener
        }
        override fun removeUpdates(listener: LocationListener) { synchronized(lock) { registrations.removeAll { it === listener } } }
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
    fun `a collector whose dispatcher was starved ends on the newest fix once it is served`() = runBlocking {
        withTimeout(60_000) {
            repeat(5) { round ->
                val source = Source()
                val received = CopyOnWriteArrayList<GeoCoordinates?>()
                val gate = CompletableDeferred<Unit>()
                val provider: LocationProvider =
                    AndroidLocationProvider(application, LocationProviderConfig(), NoopLogger, SystemScreenLauncher.SeparateTask, source)
                val job = launch(Dispatchers.Default) {
                    provider.observeLocation().collect { value ->
                        received += value
                        if (received.size == 1) gate.await()
                    }
                }
                waitUntil("registration, round $round") { source.registeredCount == 2 }
                waitUntil("the seed, round $round") { received.size == 1 }
                val listener: LocationListener = source.lastListener!!

                // Starve: every Default worker blocked in a real sleep while 400 fixes arrive from a plain thread.
                val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2) + 2
                val blockers = (1..parallelism).map { launch(Dispatchers.Default) { Thread.sleep(600) } }
                thread { repeat(400) { i -> listener.onLocationChanged(fix(i.toDouble())) } }.join(10_000)
                gate.complete(Unit)
                blockers.forEach { it.join() }

                waitUntil("the newest fix reaches the collector, round $round") { received.lastOrNull()?.latitude == 399.0 }
                job.cancelAndJoin()
                assertEquals(0, source.registeredCount, "round $round: listener left")
                assertEquals(GeoCoordinates(399.0, 0.0), received.last(), "round $round")
            }
        }
    }
}
