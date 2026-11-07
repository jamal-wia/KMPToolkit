package io.github.jamal_wia.kmptoolkit.location

import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Seeded random sequences of collectors starting and stopping while fixes arrive and providers go off
 * and on, against a model of the platform (a disabled provider has no cached fix): every live collector
 * ends on the value the events imply, and registrations always equal live collectors times providers.
 */
@RunWith(AndroidJUnit4::class)
class ObserveLocationModelFuzzTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val gps = LocationManager.GPS_PROVIDER
    private val net = LocationManager.NETWORK_PROVIDER

    private class ModelSource : LocationUpdatesSource {
        override val allProviders: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val enabled: MutableMap<String, Boolean> = mutableMapOf(LocationManager.GPS_PROVIDER to true, LocationManager.NETWORK_PROVIDER to true)
        val cache: MutableMap<String, Location> = mutableMapOf()
        val registrations: MutableList<Pair<String, LocationListener>> = mutableListOf()
        override fun isProviderEnabled(provider: String): Boolean = enabled.getValue(provider)
        override fun lastKnownLocation(provider: String): Location? = if (enabled.getValue(provider)) cache[provider] else null
        override fun requestUpdates(provider: String, minTimeMillis: Long, minDistanceMeters: Float, listener: LocationListener) {
            registrations += provider to listener
        }
        override fun removeUpdates(listener: LocationListener) {
            registrations.removeAll { it.second === listener }
        }
    }

    private class Collector(var expected: GeoCoordinates?) {
        var last: GeoCoordinates? = null
        lateinit var job: Job
    }

    @Test
    fun `random collector lifecycles with fixes and provider switches keep every collector on the implied value`() = runBlocking {
        withTimeout(90_000) {
            for (seed in 1..150) {
                val random = Random(seed)
                val source = ModelSource()
                val provider: LocationProvider =
                    AndroidLocationProvider(application, LocationProviderConfig(), NoopLogger, SystemScreenLauncher.SeparateTask, source)
                val collectors: MutableList<Collector> = mutableListOf()
                val log = StringBuilder()
                var clock = 0L
                try {
                    repeat(60) { step ->
                        when (random.nextInt(6)) {
                            0 -> {
                                // the seed is the freshest cached fix of the enabled providers, else null
                                val seedFix: Location? = source.allProviders.mapNotNull { source.lastKnownLocation(it) }.maxByOrNull { it.time }
                                val expectedSeed: GeoCoordinates? = seedFix?.let { GeoCoordinates(it.latitude, it.longitude) }
                                val c = Collector(expectedSeed)
                                c.job = launch(Dispatchers.Unconfined) {
                                    provider.observeLocation().collect { c.last = it }
                                }
                                collectors += c
                                log.append("start ")
                            }
                            1 -> if (collectors.isNotEmpty()) { collectors.removeAt(random.nextInt(collectors.size)).job.cancelAndJoin(); log.append("stop ") }
                            2, 3 -> {
                                val p: String = if (random.nextBoolean()) gps else net
                                if (source.enabled.getValue(p)) {
                                    val loc = Location(p).apply { latitude = random.nextDouble(-80.0, 80.0); longitude = random.nextDouble(-170.0, 170.0); time = ++clock }
                                    source.cache[p] = loc
                                    val expected = GeoCoordinates(loc.latitude, loc.longitude)
                                    source.registrations.toList().filter { it.first == p }.forEach { it.second.onLocationChanged(loc) }
                                    collectors.forEach { it.expected = expected }
                                    log.append("fix($p) ")
                                }
                            }
                            4 -> {
                                val p: String = if (random.nextBoolean()) gps else net
                                if (source.enabled.getValue(p)) {
                                    source.enabled[p] = false
                                    source.registrations.toList().filter { it.first == p }.forEach { it.second.onProviderDisabled(p) }
                                    if (source.enabled.values.none { it }) collectors.forEach { it.expected = null }
                                    log.append("off($p) ")
                                }
                            }
                            else -> { val p: String = if (random.nextBoolean()) gps else net; source.enabled[p] = true; log.append("on($p) ") }
                        }
                        var spins = 0
                        while (collectors.any { it.last != it.expected } && spins++ < 500) delay(2)
                        collectors.forEachIndexed { i, c -> assertEquals(c.expected, c.last, "seed $seed step $step ($log): collector $i") }
                        assertEquals(collectors.size * 2, source.registrations.size, "seed $seed step $step ($log): registrations")
                    }
                } finally {
                    collectors.forEach { it.job.cancelAndJoin() }
                }
                assertEquals(0, source.registrations.size, "seed $seed ($log): listeners left after all collectors stopped")
            }
        }
    }
}
