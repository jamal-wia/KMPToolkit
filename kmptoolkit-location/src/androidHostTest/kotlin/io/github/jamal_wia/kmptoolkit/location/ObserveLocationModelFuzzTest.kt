package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Defect class: the stream's state machine drifting from its contract under an arbitrary history.
 * Seeded random sequences (`Random(seed)`, every failure message names `seed=$seed` and the full event
 * history, so a failure replays exactly) of collectors starting and stopping, live fixes, cached fixes of
 * every age, the boot clock advancing, providers and the device switch going off and on, are run
 * against a reference model written from the documented contract, not from the implementation: a
 * stream seeds with the freshest cached fix of an enabled source within `maxSeedAge` (age equal to the
 * limit accepted, `Duration.ZERO` accepts none, `Duration.INFINITE` any), else `NO_FIX`, or
 * `SERVICE_DISABLED` while the service is off; it stays open; a live fix replaces the reading; going off
 * emits `SERVICE_DISABLED` once, coming back reseeds; and registrations always equal live collectors times
 * sources, down to zero when all collectors stop. Virtual time: no sleeps, no wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ObserveLocationModelFuzzTest {

    /** What the model expects a collector to hold: a [GeoCoordinates] for a fix, a [LocationUnavailability] for a reason. */
    private class Collector(val id: Int, var expected: Any) {
        var last: Any? = null
        lateinit var job: Job
    }

    private class CachedFix(val coordinates: GeoCoordinates, val takenAtMs: Long)

    /** The documented behaviour of a two-source (gps, network) stream, with no throttle. */
    private class Model(private val maxSeedAge: Duration) {
        private val sources: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var nowMs: Long = 0L
        var deviceOn: Boolean = true
        val enabled: MutableMap<String, Boolean> = sources.associateWith { true }.toMutableMap()
        val cache: MutableMap<String, CachedFix> = mutableMapOf()

        fun disabled(): Boolean = !deviceOn || enabled.values.none { it }

        /** What a stream shows now: the reason while off, else the seed rule. */
        fun reading(): Any = if (disabled()) LocationUnavailability.SERVICE_DISABLED else seed()

        private fun seed(): Any {
            var best: CachedFix? = null
            var bestAge: Long = Long.MAX_VALUE
            for (source: String in sources) {
                if (!enabled.getValue(source)) continue
                val cached: CachedFix = cache[source] ?: continue
                val age: Long = nowMs - cached.takenAtMs
                if (!(maxSeedAge.isPositive() && age.milliseconds <= maxSeedAge)) continue
                if (best == null || age < bestAge) {
                    best = cached
                    bestAge = age
                }
            }
            return best?.coordinates ?: LocationUnavailability.NO_FIX
        }
    }

    private fun shape(reading: LocationReading): Any = when (reading) {
        is LocationReading.Available -> reading.fix.coordinates
        is LocationReading.Unavailable -> reading.reason
    }

    /** One seeded run; returns its event history (the same seed must always give the same one). */
    private suspend fun TestScope.runScenario(seed: Int, maxSeedAge: Duration): String {
        val gps: String = LocationManager.GPS_PROVIDER
        val network: String = LocationManager.NETWORK_PROVIDER
        val options = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, maxSeedAge = maxSeedAge)
        val random = Random(seed)
        val backend = FakeLocationBackend()
        val provider: LocationProvider = newProvider(backend, testScheduler.timeSource)
        val model = Model(maxSeedAge)
        val collectors: MutableList<Collector> = mutableListOf()
        val history = StringBuilder()
        var comparisons = 0
        var nextId = 0

        fun randomCoordinates(): GeoCoordinates = GeoCoordinates(random.nextDouble(-80.0, 80.0), random.nextDouble(-170.0, 170.0))

        fun randomSource(): String = if (random.nextBoolean()) gps else network

        /** Applies a service transition seen by every live collector: off sends the reason once, back on reseeds. */
        fun serviceChanged(disabledBefore: Boolean) {
            val disabledAfter: Boolean = model.disabled()
            if (disabledBefore == disabledAfter) return
            for (collector: Collector in collectors) collector.expected = model.reading()
        }

        try {
            repeat(STEPS) { step: Int ->
                // The first event of a run always starts a collector, so every run compares something.
                when (if (step == 0) 0 else random.nextInt(10)) {
                    0, 1 -> {
                        val collector = Collector(nextId++, model.reading())
                        collector.job = launch {
                            provider.observeLocation(options).collect { reading: LocationReading -> collector.last = shape(reading) }
                        }
                        collectors += collector
                        history.append("start#${collector.id} ")
                    }
                    2 -> if (collectors.isNotEmpty()) {
                        val gone: Collector = collectors.removeAt(random.nextInt(collectors.size))
                        gone.job.cancelAndJoin()
                        history.append("stop#${gone.id} ")
                    }
                    3, 4 -> {
                        val source: String = randomSource()
                        // A platform delivers fixes only while its source and the service are on.
                        if (model.enabled.getValue(source) && model.deviceOn) {
                            val where: GeoCoordinates = randomCoordinates()
                            backend.setCached(source, backend.locationAt(source, where.latitude, where.longitude))
                            model.cache[source] = CachedFix(where, model.nowMs)
                            backend.deliver(source, backend.locationAt(source, where.latitude, where.longitude))
                            for (collector: Collector in collectors) collector.expected = where
                            history.append("fix($source) ")
                        }
                    }
                    5 -> {
                        val source: String = randomSource()
                        if (model.enabled.getValue(source)) {
                            val before: Boolean = model.disabled()
                            model.enabled[source] = false
                            backend.setProviderEnabled(source, false)
                            serviceChanged(before)
                            history.append("off($source) ")
                        }
                    }
                    6 -> {
                        val source: String = randomSource()
                        if (!model.enabled.getValue(source)) {
                            val before: Boolean = model.disabled()
                            model.enabled[source] = true
                            backend.setProviderEnabled(source, true)
                            serviceChanged(before)
                            history.append("on($source) ")
                        }
                    }
                    7 -> {
                        val before: Boolean = model.disabled()
                        model.deviceOn = !model.deviceOn
                        backend.setDeviceLocationEnabled(model.deviceOn)
                        serviceChanged(before)
                        history.append(if (model.deviceOn) "deviceOn " else "deviceOff ")
                    }
                    8 -> {
                        val source: String = randomSource()
                        val ageMs: Long = random.nextLong(0L, 45_000L)
                        val where: GeoCoordinates = randomCoordinates()
                        backend.setCached(source, backend.locationAt(source, where.latitude, where.longitude, ageMs))
                        model.cache[source] = CachedFix(where, model.nowMs - ageMs)
                        history.append("cache($source,${ageMs}ms) ")
                    }
                    else -> {
                        val advanceMs: Long = random.nextLong(1L, 20_000L)
                        backend.nowNanos += advanceMs * 1_000_000L
                        model.nowMs += advanceMs
                        history.append("clock+${advanceMs}ms ")
                    }
                }
                runCurrent()
                for (collector: Collector in collectors) {
                    comparisons++
                    if (collector.expected != collector.last) {
                        fail("seed=$seed step=$step history=[$history]: collector #${collector.id} expected ${collector.expected} but holds ${collector.last}")
                    }
                }
                if (backend.registeredCount != collectors.size * 2) {
                    fail("seed=$seed step=$step history=[$history]: ${backend.registeredCount} registrations for ${collectors.size} live collectors")
                }
                if (backend.subscriberCount != collectors.size) {
                    fail("seed=$seed step=$step history=[$history]: ${backend.subscriberCount} service subscriptions for ${collectors.size} live collectors")
                }
            }
        } finally {
            for (collector: Collector in collectors) collector.job.cancelAndJoin()
        }
        assertTrue(comparisons > 0, "seed=$seed history=[$history]: the run compared nothing")
        assertEquals(0, backend.registeredCount, "seed=$seed history=[$history]: listeners left after all collectors stopped")
        assertEquals(0, backend.subscriberCount, "seed=$seed history=[$history]: subscriptions left after all collectors stopped")
        return history.toString()
    }

    @Test
    fun `random lifecycles with fixes and switches keep every collector on the value the contract implies`() =
        runTest(timeout = 120.seconds) {
            for (seed: Int in 1..SEEDS) runScenario(seed, maxSeedAge = 30.seconds)
        }

    @Test
    fun `with maxSeedAge ZERO no cached fix ever seeds a stream`() = runTest(timeout = 120.seconds) {
        for (seed: Int in 1..SEEDS) runScenario(seed, maxSeedAge = Duration.ZERO)
    }

    @Test
    fun `with maxSeedAge INFINITE a cache of any age seeds a stream`() = runTest(timeout = 120.seconds) {
        for (seed: Int in 1..SEEDS) runScenario(seed, maxSeedAge = Duration.INFINITE)
    }

    @Test
    fun `the same seed replays the same history`() = runTest(timeout = 60.seconds) {
        val seed = 7
        val first: String = runScenario(seed, maxSeedAge = 30.seconds)
        val second: String = runScenario(seed, maxSeedAge = 30.seconds)
        assertEquals(first, second, "seed=$seed: a seeded run must be reproducible")
        assertTrue(first.isNotBlank(), "seed=$seed: control, the history must not be empty")
    }

    private companion object {
        const val STEPS: Int = 60
        const val SEEDS: Int = 100
    }
}
