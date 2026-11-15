package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationListener
import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
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
 * Defect class: a stream that ends on a stale event, completes, or leaks when the service flaps. Providers
 * switch off and on, the device-wide switch flips, and fixes arrive in seeded random order while a
 * collector reads the conflated flow. The collector must always end on the newest thing that happened
 * (a fix, `SERVICE_DISABLED` once every source is off, or the `NO_FIX` reseed when the service returns),
 * a transition is reported once even though a provider callback and a broadcast both announce it,
 * the stream never completes through `SERVICE_DISABLED` or `NO_FIX`, it reseeds from the cache only while
 * the cache is young, and cancelling leaves `registeredCount` and `subscriberCount` at zero, with a late
 * platform callback after the end staying harmless. The seeded test checks a model of the newest event
 * at every checkpoint. Virtual time and a scripted backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ObserveLocationProviderFlapTest {

    private val backend = FakeLocationBackend()
    private val provider: LocationProvider = newProvider(backend)
    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    /** Both sources: the gps and the network provider. */
    private val bothSources = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)

    private fun tag(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix:${reading.fix.coordinates.latitude}"
        is LocationReading.Unavailable -> "none:${reading.reason}"
    }

    private fun tags(readings: List<LocationReading>): List<String> = readings.map { reading: LocationReading -> tag(reading) }

    private fun TestScope.collectInto(options: LocationUpdatesOptions, into: MutableList<LocationReading>): Job =
        backgroundScope.launch { provider.observeLocation(options).collect { reading: LocationReading -> into += reading } }

    /**
     * Switches both sources. Coming back, the network (the one that holds the cache in these tests) is
     * switched on first: the stream reseeds at the first source that returns, from the sources on at that moment.
     */
    private fun switchBoth(on: Boolean) {
        val order: List<String> = if (on) listOf(network, gps) else listOf(gps, network)
        for (source: String in order) backend.setProviderEnabled(source, on)
    }

    @Test
    fun `seeded random flapping ends on the newest event never completes and leaves no listener`() = runTest(timeout = 60.seconds) {
        for (seed: Int in SEEDS) flap(seed)
    }

    private suspend fun TestScope.flap(seed: Int = DEFAULT_SEED) {
        val random = Random(seed)
        backend.setDeviceLocationEnabled(true)
        switchBoth(true)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collectInto(bothSources, seen)
        runCurrent()
        assertEquals(listOf("none:NO_FIX"), tags(seen), "seed=$seed: with nothing cached the seed is NO_FIX")
        assertEquals(2, backend.registeredCount, "seed=$seed: control: both sources are registered")
        assertEquals(1, backend.subscriberCount, "seed=$seed: control: the service is watched")

        var gpsOn = true
        var networkOn = true
        var deviceOn = true
        var disabled = false
        var expected = "none:NO_FIX"
        repeat(STEPS) { step: Int ->
            when (random.nextInt(4)) {
                0 -> {
                    val source: String = if (random.nextBoolean()) gps else network
                    backend.deliver(source, backend.locationAt(source, 10.0 + step))
                    expected = "fix:${10.0 + step}"
                }
                1 -> {
                    gpsOn = random.nextBoolean()
                    backend.setProviderEnabled(gps, gpsOn)
                }
                2 -> {
                    networkOn = random.nextBoolean()
                    backend.setProviderEnabled(network, networkOn)
                }
                else -> {
                    deviceOn = random.nextBoolean()
                    backend.setDeviceLocationEnabled(deviceOn)
                }
            }
            // The newest event is the state change when one happened: off, or the reseed when it is back on.
            val nowDisabled: Boolean = !deviceOn || (!gpsOn && !networkOn)
            if (nowDisabled != disabled) {
                disabled = nowDisabled
                expected = if (disabled) "none:SERVICE_DISABLED" else "none:NO_FIX"
            }
            if (random.nextInt(3) == 0) {
                runCurrent()
                assertEquals(expected, tag(seen.last()), "seed=$seed step=$step: the collector is not on the newest event, saw ${tags(seen)}")
                assertTrue(job.isActive, "seed=$seed step=$step: the stream must never complete")
            }
        }

        backend.setDeviceLocationEnabled(true)
        switchBoth(true)
        backend.deliver(gps, backend.locationAt(gps, 60.0 + seed))
        runCurrent()
        assertEquals("fix:${60.0 + seed}", tag(seen.last()), "seed=$seed: the last delivered fix must be the last value")
        switchBoth(false)
        runCurrent()
        assertEquals("none:SERVICE_DISABLED", tag(seen.last()), "seed=$seed: every provider off must end on SERVICE_DISABLED")
        assertTrue(job.isActive, "seed=$seed: the stream must still be open")

        assertEquals(2, backend.registeredCount, "seed=$seed: control: the stream is still registered before it is cancelled")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount, "seed=$seed: a listener was left registered")
        assertEquals(0, backend.subscriberCount, "seed=$seed: a service subscription was left")
        assertTrue(backend.removalCount > 0, "seed=$seed: the listener must have been removed through the platform")
    }

    @Test
    fun `every transition is reported once although a provider callback and a broadcast announce it`() = runTest(timeout = 60.seconds) {
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collectInto(bothSources, seen)
        runCurrent()

        backend.setProviderEnabled(gps, false)
        runCurrent()
        assertEquals(listOf("none:NO_FIX"), tags(seen), "one provider off while another is on is not a service-off")
        backend.setProviderEnabled(network, false)
        runCurrent()
        backend.setProviderEnabled(network, true)
        runCurrent()
        backend.setProviderEnabled(gps, true)
        runCurrent()
        backend.setDeviceLocationEnabled(false)
        runCurrent()
        backend.setDeviceLocationEnabled(true)
        runCurrent()

        assertEquals(
            listOf(
                "none:NO_FIX",
                "none:SERVICE_DISABLED",
                "none:NO_FIX",
                "none:SERVICE_DISABLED",
                "none:NO_FIX",
            ),
            tags(seen),
            "each of the four transitions must produce exactly one reading",
        )
        assertTrue(job.isActive)
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `a single provider flapping while another stays on never reports SERVICE_DISABLED`() = runTest(timeout = 60.seconds) {
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collectInto(bothSources, seen)
        runCurrent()

        repeat(5) {
            backend.setProviderEnabled(gps, false)
            runCurrent()
            backend.setProviderEnabled(gps, true)
            runCurrent()
        }
        backend.deliver(network, backend.locationAt(network, 41.0))
        runCurrent()

        assertEquals(listOf("none:NO_FIX", "fix:41.0"), tags(seen), "gps flapping must send nothing while the network is on")
        assertTrue(job.isActive)
        assertEquals(2, backend.registeredCount, "control: the stream stayed registered on both sources")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `when the service returns the stream reseeds from the cache only while the cache is young`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 1_000))
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collectInto(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, maxSeedAge = 30.seconds), seen)
        runCurrent()
        assertEquals(listOf("fix:41.0"), tags(seen), "the seed is the young cache")

        switchBoth(false)
        runCurrent()
        switchBoth(true)
        runCurrent()
        assertEquals(listOf("fix:41.0", "none:SERVICE_DISABLED", "fix:41.0"), tags(seen), "the cache is still young: the reseed uses it")

        switchBoth(false)
        runCurrent()
        backend.nowNanos += 5.minutes.inWholeNanoseconds
        switchBoth(true)
        runCurrent()
        assertEquals("none:NO_FIX", tag(seen.last()), "the cache went stale while the service was off: the reseed must be NO_FIX")
        backend.deliver(gps, backend.locationAt(gps, 5.0))
        runCurrent()
        assertEquals("fix:5.0", tag(seen.last()))
        assertTrue(job.isActive, "the stream never completes through SERVICE_DISABLED or NO_FIX")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `a burst of flaps before the collector runs ends on the newest event`() = runTest(timeout = 60.seconds) {
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collectInto(bothSources, seen)
        runCurrent()

        repeat(10) {
            switchBoth(false)
            switchBoth(true)
        }
        switchBoth(false)
        runCurrent()
        assertEquals("none:SERVICE_DISABLED", tag(seen.last()), "the newest event is the last switch-off")

        repeat(10) {
            switchBoth(true)
            switchBoth(false)
        }
        backend.deliver(gps, backend.locationAt(gps, 7.0))
        switchBoth(true)
        backend.deliver(network, backend.locationAt(network, 8.0))
        runCurrent()
        assertEquals("fix:8.0", tag(seen.last()), "the newest event is the last delivered fix")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `cancelling while the service is off releases everything and a late callback is harmless`() = runTest(timeout = 60.seconds) {
        switchBoth(false)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collectInto(bothSources, seen)
        runCurrent()
        assertEquals(listOf("none:SERVICE_DISABLED"), tags(seen))
        val listeners: List<LocationListener> =
            backend.registered().map { registration: FakeLocationBackend.Registration -> registration.listener }
        assertEquals(2, listeners.size, "control: the disabled stream stays registered on both sources")
        assertEquals(1, backend.subscriberCount, "control: and watches the service")

        job.cancelAndJoin()

        assertEquals(0, backend.registeredCount, "a stream cancelled while off left a listener")
        assertEquals(0, backend.subscriberCount, "a stream cancelled while off left a subscription")
        // Callbacks the platform had already queued must neither throw nor bring anything back.
        val late = backend.locationAt(gps, 9.0)
        for (listener: LocationListener in listeners) {
            listener.onProviderEnabled(gps)
            listener.onProviderDisabled(gps)
            listener.onLocationChanged(late)
        }
        backend.setProviderEnabled(gps, true)
        runCurrent()
        assertEquals(0, backend.registeredCount, "a late callback must not register again")
        assertEquals(listOf("none:SERVICE_DISABLED"), tags(seen), "nothing may be delivered to a cancelled collector")
        assertFalse(seen.isEmpty())
    }

    @Test
    fun `a new collection after a release registers and seeds afresh`() = runTest(timeout = 60.seconds) {
        backend.setCached(network, backend.locationAt(network, 41.0, ageMillis = 1_000))
        repeat(3) { round: Int ->
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = collectInto(bothSources, seen)
            runCurrent()
            assertEquals(listOf("fix:41.0"), tags(seen), "round $round: every collection starts with its own seed")
            assertEquals(2, backend.registeredCount, "round $round: control: registered afresh")
            assertEquals(1, backend.subscriberCount, "round $round: control: subscribed afresh")
            switchBoth(false)
            switchBoth(true)
            job.cancelAndJoin()
            assertEquals(0, backend.registeredCount, "round $round: a listener was left registered")
            assertEquals(0, backend.subscriberCount, "round $round: a subscription was left")
        }
    }

    private companion object {
        const val DEFAULT_SEED: Int = 3
        val SEEDS: List<Int> = listOf(DEFAULT_SEED, 4, 5, 6, 7, 8)
        const val STEPS: Int = 60
    }
}
