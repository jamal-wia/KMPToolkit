package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * M10 and decision D-21, wired through the Android provider (the throttle itself has its own tests in
 * `ReadingThrottleTest`): `minUpdateInterval` and `minUpdateDistanceMeters` never delay or drop an
 * `Unavailable` (a throttled `SERVICE_DISABLED` would hide the off state), a reason forgets the
 * reference so the next fix passes at once, and a cached seed starts no interval, so the first live fix
 * is not held behind a cached position. A fix that arrives early is emitted at the deadline, not lost.
 * The throttle clock is the test scheduler's, so the timer and the clock agree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ThrottleNeverHidesUnavailableTest {

    private val backend = FakeLocationBackend()
    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val interval = LocationUpdatesOptions(
        accuracy = LocationAccuracy.HIGH_ACCURACY,
        minUpdateInterval = 60.seconds,
    )

    private fun TestScope.collect(into: MutableList<LocationReading>, options: LocationUpdatesOptions = interval): Job {
        val provider: LocationProvider = newProvider(backend, testScheduler.timeSource)
        return backgroundScope.launch { provider.observeLocation(options).collect { reading: LocationReading -> into += reading } }
    }

    private fun tag(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix:${reading.fix.coordinates.latitude}"
        is LocationReading.Unavailable -> "none:${reading.reason}"
    }

    private fun tags(readings: List<LocationReading>): List<String> = readings.map { reading: LocationReading -> tag(reading) }

    @Test
    fun `a reason inside the interval is never delayed and the next fix passes at once`() = runTest(timeout = 60.seconds) {
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collect(seen)
        runCurrent()
        backend.deliver(gps, backend.locationAt(gps, 10.0))
        runCurrent()
        assertEquals(listOf("none:NO_FIX", "fix:10.0"), tags(seen), "control: seed, then the first fix")

        advanceTimeBy(10.seconds)
        backend.setProviderEnabled(gps, false)
        backend.setProviderEnabled(network, false)
        runCurrent()
        assertEquals("none:SERVICE_DISABLED", tag(seen.last()), "the reason must not wait for the interval")
        assertEquals(10_000L, testScheduler.currentTime)

        advanceTimeBy(1.seconds)
        backend.setProviderEnabled(network, true)
        backend.setProviderEnabled(gps, true)
        runCurrent()
        backend.deliver(gps, backend.locationAt(gps, 11.0))
        runCurrent()
        assertEquals("fix:11.0", tag(seen.last()), "after a reason the reference is forgotten: the next fix passes at once")
        assertEquals(11_000L, testScheduler.currentTime)
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `a cached seed starts no interval so the first live fix passes at once`() = runTest(timeout = 60.seconds) {
        backend.setCached(gps, backend.locationAt(gps, 40.0, ageMillis = 5_000))
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collect(seen)
        runCurrent()
        assertEquals(listOf("fix:40.0"), tags(seen), "control: the cached seed is the first value")

        advanceTimeBy(1.seconds)
        backend.deliver(gps, backend.locationAt(gps, 41.0))
        runCurrent()
        assertEquals(listOf("fix:40.0", "fix:41.0"), tags(seen), "the live fix 1 s after a 5 s old seed must not wait 60 s")

        advanceTimeBy(1.seconds)
        backend.deliver(gps, backend.locationAt(gps, 42.0))
        runCurrent()
        assertEquals(listOf("fix:40.0", "fix:41.0"), tags(seen), "the interval started with the live fix: the next one is held")
        advanceTimeBy(59.seconds)
        runCurrent()
        assertEquals("fix:42.0", tag(seen.last()), "a held fix is emitted at the deadline, not lost")
        assertEquals(61_000L, testScheduler.currentTime)
        job.cancelAndJoin()
    }

    @Test
    fun `a reseed in the middle of the stream starts no interval either`() = runTest(timeout = 60.seconds) {
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collect(seen)
        runCurrent()
        backend.deliver(gps, backend.locationAt(gps, 10.0))
        runCurrent()
        advanceTimeBy(1.seconds)
        backend.setProviderEnabled(gps, false)
        backend.setProviderEnabled(network, false)
        runCurrent()
        assertEquals(listOf("none:NO_FIX", "fix:10.0", "none:SERVICE_DISABLED"), tags(seen), "control: the service went off")

        advanceTimeBy(1.seconds)
        backend.setCached(gps, backend.locationAt(gps, 40.0, ageMillis = 3_000))
        backend.setProviderEnabled(gps, true)
        backend.setProviderEnabled(network, true)
        runCurrent()
        assertEquals("fix:40.0", tag(seen.last()), "control: the service returned and reseeded from the cache")

        advanceTimeBy(1.seconds)
        backend.deliver(gps, backend.locationAt(gps, 41.0))
        runCurrent()
        assertEquals("fix:41.0", tag(seen.last()), "the live fix 1 s after a reseed must not wait behind the cached position")
        assertEquals(3_000L, testScheduler.currentTime)

        advanceTimeBy(1.seconds)
        backend.deliver(gps, backend.locationAt(gps, 42.0))
        runCurrent()
        assertEquals("fix:41.0", tag(seen.last()), "the interval started with the live fix: the next one is held")
        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
    }

    @Test
    fun `a reason discards the fix the interval was holding`() = runTest(timeout = 60.seconds) {
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collect(seen)
        runCurrent()
        backend.deliver(gps, backend.locationAt(gps, 10.0))
        advanceTimeBy(5.seconds)
        backend.deliver(gps, backend.locationAt(gps, 11.0))
        runCurrent()
        assertEquals(listOf("none:NO_FIX", "fix:10.0"), tags(seen), "control: the second fix is held")

        backend.setProviderEnabled(gps, false)
        backend.setProviderEnabled(network, false)
        runCurrent()
        advanceTimeBy(120.seconds)
        runCurrent()

        assertEquals(listOf("none:NO_FIX", "fix:10.0", "none:SERVICE_DISABLED"), tags(seen), "no fix may follow a reason that it predates")
        job.cancelAndJoin()
    }

    @Test
    fun `cancelling while a fix is held releases the listener and leaves no timer behind`() = runTest(timeout = 60.seconds) {
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collect(seen)
        runCurrent()
        backend.deliver(gps, backend.locationAt(gps, 10.0))
        advanceTimeBy(5.seconds)
        backend.deliver(gps, backend.locationAt(gps, 11.0))
        runCurrent()
        assertEquals(2, backend.registeredCount, "control: the stream is registered while a fix is held")

        job.cancelAndJoin()

        assertEquals(0, backend.registeredCount, "a cancelled throttled stream left a listener")
        assertEquals(0, backend.subscriberCount)
        val before: Long = testScheduler.currentTime
        testScheduler.advanceUntilIdle()
        assertEquals(before, testScheduler.currentTime, "a timer survived the cancellation")
        assertEquals(2, seen.size, "the held fix was delivered after the cancel")
    }
}
