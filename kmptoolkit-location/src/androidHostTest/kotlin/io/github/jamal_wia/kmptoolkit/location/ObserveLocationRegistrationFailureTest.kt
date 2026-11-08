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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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

    /**
     * Throws [failure] on the network registration (and on the GPS one too when [firstFailsToo]); tracks which
     * listeners are registered.
     */
    private class FailingSecondSource(
        private val failure: Throwable,
        private val removalFailure: Throwable? = null,
        private val firstFailsToo: Boolean = false,
    ) : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val registered: MutableSet<LocationListener> = java.util.concurrent.CopyOnWriteArraySet()
        override fun isProviderEnabled(provider: String): Boolean = true
        override fun lastKnownLocation(provider: String): Location? = null
        override fun requestUpdates(
            provider: String,
            minTimeMillis: Long,
            minDistanceMeters: Float,
            listener: LocationListener,
        ) {
            if (provider == LocationManager.NETWORK_PROVIDER || firstFailsToo) throw failure
            registered += listener
        }

        override fun removeUpdates(listener: LocationListener) {
            registered -= listener
            if (removalFailure != null) throw removalFailure
        }
    }

    /** Spins (bounded) until [condition] holds, so a hang fails fast instead of blocking the Gradle run. */
    private suspend fun until(what: String, condition: () -> Boolean) {
        var spins = 0
        while (!condition()) {
            check(spins++ < 2_000) { "timed out waiting for $what" }
            delay(2)
        }
    }

    private fun provider(source: LocationUpdatesSource): LocationProvider =
        AndroidLocationProvider(application, LocationProviderConfig(), NoopLogger, SystemScreenLauncher.SeparateTask, source)

    @Test
    fun `a provider that refuses its permission is skipped and the flow stays live on the other`() = runBlocking {
        withTimeout(10_000) {
            val source = FailingSecondSource(SecurityException("network denied"))
            val seen = java.util.concurrent.CopyOnWriteArrayList<GeoCoordinates?>()

            val job = launch(Dispatchers.Default) { provider(source).observeLocation().collect { seen += it } }
            until("the seed and the gps registration") { seen.isNotEmpty() && source.registered.isNotEmpty() }
            assertEquals(listOf<GeoCoordinates?>(null), seen.toList(), "the first value is the null seed")

            val fix = Location(LocationManager.GPS_PROVIDER).apply { latitude = 5.0; longitude = 6.0 }
            source.registered.first().onLocationChanged(fix)
            until("the delivered fix") { seen.lastOrNull() != null }
            assertEquals(GeoCoordinates(5.0, 6.0), seen.last())

            job.cancelAndJoin()
            assertTrue(source.registered.isEmpty(), "listener still registered after the cancel: ${source.registered.size}")
        }
    }

    @Test
    fun `when every provider refuses its permission the flow ends on null and nothing stays registered`() = runBlocking {
        withTimeout(5_000) {
            val source = FailingSecondSource(SecurityException("denied"), firstFailsToo = true)

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

    @Test
    fun `a failing removal on the permission path still emits null instead of throwing`() = runBlocking {
        withTimeout(5_000) {
            val source = FailingSecondSource(
                SecurityException("denied"),
                removalFailure = IllegalStateException("dead"),
                firstFailsToo = true,
            )

            val values: List<GeoCoordinates?> = provider(source).observeLocation().toList()

            assertEquals(listOf(null, null), values)
        }
    }

    @Test
    fun `a failing removal on the other path keeps the original failure and suppresses the removal's`() = runBlocking {
        withTimeout(5_000) {
            val removal = IllegalArgumentException("dead listener")
            val original = IllegalStateException("original")
            val source = FailingSecondSource(original, removalFailure = removal)

            val thrown: IllegalStateException =
                assertFailsWith<IllegalStateException> { provider(source).observeLocation().first { false } }

            assertEquals("original", thrown.message)
            // Coroutines may rethrow a stack-trace-recovered copy, so look at the original itself.
            assertTrue(original.suppressed.any { suppressed -> suppressed === removal })
        }
    }
}
