package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.location.LocationUnavailability.SERVICE_DISABLED
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Group loc-android (ticket 5.1.0), correction round 9, finding m1 of breaker round 8: after an evaluation of the
 * service state did not finish, the record of "the state last decided" is unknown, and the next event must resolve it
 * against what the COLLECTOR SHOWS (the last element sent, a live fix included), not by always resending.
 *
 * Contract (PLAN 4.2 plus the `LocationProvider` KDoc: the collector never ends on a stale reading): a reading is
 * sent when and only when the collector shows something other than the truth. A state that changes nothing for the
 * request never replaces a live position with NO_FIX or an older cached fix; SERVICE_DISABLED is reported as soon as
 * the service is off and the collector does not already show it; the service coming on again reseeds only while the
 * collector shows SERVICE_DISABLED. The reviewer's class `AndroidLocationProviderBreakerR8Test` is the exhaustive
 * attack and stays untouched; these tests pin the four cells of the resolution table, a live fix between the failure
 * and the next event, the waiting stream, and a seeded model fuzz. Virtual time, no sleeps; every case ends with the
 * registration and subscription counters at zero, and control assertions prove that the armed read really failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class UnknownStateResolvedAgainstShownRound9Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(SERVICE_DISABLED)
    private val explicitGps = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS))

    private val kinds: List<Pair<String, () -> Throwable>> = listOf(
        "RuntimeException" to { IllegalStateException("round9: runtime failure") },
        "Exception" to { Exception("round9: checked failure") },
    )

    private val readSites: List<String> = listOf(
        "isLocationEnabled",
        "isProviderEnabled",
        "hasProvider",
        "hasFinePermission",
        "hasCoarsePermission",
        "lastKnownLocation",
        "elapsedRealtimeNanos",
    )

    /** The armed read ([site]) throws [failure] on every call until [site] is cleared; [thrown] counts the failures. */
    private class SiteFailingBackend(val fake: FakeLocationBackend) : LocationBackend by fake {
        @Volatile var site: String? = null
        @Volatile var failure: Throwable = IllegalStateException("round9")
        val thrown: AtomicInteger = AtomicInteger(0)

        private fun check(name: String) {
            if (site == name) {
                thrown.incrementAndGet()
                throw failure
            }
        }

        override fun hasProvider(provider: String): Boolean {
            check("hasProvider")
            return fake.hasProvider(provider)
        }

        override fun isProviderEnabled(provider: String): Boolean {
            check("isProviderEnabled")
            return fake.isProviderEnabled(provider)
        }

        override fun isLocationEnabled(): Boolean {
            check("isLocationEnabled")
            return fake.isLocationEnabled()
        }

        override fun hasFinePermission(): Boolean {
            check("hasFinePermission")
            return fake.hasFinePermission()
        }

        override fun hasCoarsePermission(): Boolean {
            check("hasCoarsePermission")
            return fake.hasCoarsePermission()
        }

        override fun lastKnownLocation(provider: String): Location? {
            check("lastKnownLocation")
            return fake.lastKnownLocation(provider)
        }

        override fun elapsedRealtimeNanos(): Long {
            check("elapsedRealtimeNanos")
            return fake.elapsedRealtimeNanos()
        }
    }

    private fun latitudeOf(reading: LocationReading): Double? = (reading as? LocationReading.Available)?.fix?.coordinates?.latitude

    private fun describeOne(reading: LocationReading): String =
        latitudeOf(reading)?.let { latitude: Double -> "fix($latitude)" } ?: (reading as LocationReading.Unavailable).reason.name

    private fun describe(readings: List<LocationReading>): String = readings.map(::describeOne).toString()

    // ---------------------------------------------------------------------------------------------
    // Cell 1: the collector shows a position and the service is on: nothing is sent
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: wrong reading after a fix (a spurious reseed). A stationary phone: the platform cache mirrors the
     * live fix and ages past maxSeedAge. A no-change broadcast fails at one read (each site and failure kind in turn), the
     * next no-change broadcast succeeds: the stream keeps the live position, never NO_FIX or an older cached fix.
     * Run with the default maxSeedAge (cache past it: NO_FIX would be the wrong reading) and with maxSeedAge zero.
     */
    @Test
    fun `position shown, service on - a no-change event after a failed evaluation sends nothing`() = runTest(timeout = 120.seconds) {
        var proven = 0
        for (maxSeedAge: Duration in listOf(30.seconds, Duration.ZERO)) {
            for ((kindName: String, make: () -> Throwable) in kinds) {
                for (site: String in readSites) {
                    val label = "maxSeedAge=$maxSeedAge site=$site kind=$kindName"
                    val fake = FakeLocationBackend(providers = setOf(gps, network))
                    // The cache holds an OLDER fix than the live one: a reseed would also roll the position back.
                    fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
                    val backend = SiteFailingBackend(fake)
                    val options = LocationUpdatesOptions(
                        maxSeedAge = maxSeedAge,
                        platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS),
                    )
                    val seen: MutableList<LocationReading> = mutableListOf()
                    val job: Job = backgroundScope.launch { newProviderOver(backend).observeLocation(options).collect { seen += it } }
                    runCurrent()
                    fake.deliver(gps, fake.locationAt(gps, latitude = 10.0))
                    runCurrent()
                    val before: Int = seen.size
                    assertEquals(10.0, latitudeOf(seen.last()), "$label: control: the live fix is shown: ${describe(seen)}")
                    fake.nowNanos += 40_000_000_000L
                    backend.failure = make()
                    backend.site = site
                    fake.setProviderEnabled(network, false)
                    runCurrent()
                    backend.site = null
                    if (backend.thrown.get() == 0) {
                        job.cancelAndJoin()
                        continue
                    }
                    proven++
                    assertEquals(before, seen.size, "$label: the failed evaluation sent something: ${describe(seen)}")
                    fake.setProviderEnabled(network, true)
                    runCurrent()
                    assertEquals(before, seen.size, "$label: a no-change event replaced the live position: ${describe(seen)}")
                    fake.setProviderEnabled(network, false)
                    runCurrent()
                    assertEquals(before, seen.size, "$label: and so did the one after: ${describe(seen)}")
                    job.cancelAndJoin()
                    assertEquals(0, fake.registeredCount, "$label: registration released")
                    assertEquals(0, fake.subscriberCount, "$label: subscription released")
                }
            }
        }
        assertTrue(proven > 0, "control: at least one read site failed during the no-change evaluation")
    }

    // ---------------------------------------------------------------------------------------------
    // Cell 2: the collector shows a position and the service is off: SERVICE_DISABLED, once
    // ---------------------------------------------------------------------------------------------

    /**
     * Round 7 m1 must stay fixed: device on with a live fix shown, the switch-off evaluation fails, then the next event
     * runs. The collector must end on SERVICE_DISABLED, sent once; the events after that send nothing.
     */
    @Test
    fun `position shown, service off - the next event reports SERVICE_DISABLED once`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val backend = SiteFailingBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { newProviderOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        fake.deliver(gps, fake.locationAt(gps, latitude = 10.0))
        runCurrent()
        backend.site = "isLocationEnabled"
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        backend.site = null
        assertTrue(backend.thrown.get() > 0, "control: the switch-off evaluation failed")
        assertEquals(10.0, latitudeOf(seen.last()), "control: nothing was sent by the failed evaluation: ${describe(seen)}")
        val before: Int = seen.size
        fake.setProviderEnabled(network, false)
        runCurrent()
        assertEquals(before + 1, seen.size, "the next event sends exactly one reading: ${describe(seen)}")
        assertEquals(serviceDisabled, seen.last(), "and it is SERVICE_DISABLED: ${describe(seen)}")
        fake.setProviderEnabled(network, true)
        runCurrent()
        fake.setProviderEnabled(network, false)
        runCurrent()
        assertEquals(before + 1, seen.size, "the events after that change nothing: ${describe(seen)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // Cell 3: the collector shows SERVICE_DISABLED and the service is on: reseed
    // ---------------------------------------------------------------------------------------------

    /**
     * Device off (SERVICE_DISABLED shown), the switch-on evaluation fails, the next event runs: the collector must
     * not stay on SERVICE_DISABLED. It receives the reseed (the cached fix) exactly once.
     */
    @Test
    fun `SERVICE_DISABLED shown, service on - the next event reseeds once`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network)).apply { locationEnabled = false }
        fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
        val backend = SiteFailingBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { newProviderOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(serviceDisabled), seen, "control: the device is off at the start")
        backend.site = "isLocationEnabled"
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        backend.site = null
        assertTrue(backend.thrown.get() > 0, "control: the switch-on evaluation failed")
        assertEquals(listOf(serviceDisabled), seen, "control: the failed evaluation sent nothing")
        fake.setProviderEnabled(network, false)
        runCurrent()
        assertEquals(listOf("SERVICE_DISABLED", "fix(7.0)"), seen.map(::describeOne), "the next event reseeds once: ${describe(seen)}")
        fake.setProviderEnabled(network, true)
        runCurrent()
        assertEquals(2, seen.size, "and then nothing more: ${describe(seen)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // Cell 4: the collector shows SERVICE_DISABLED and the service is off: nothing is sent
    // ---------------------------------------------------------------------------------------------

    /** Device off (SERVICE_DISABLED shown), a no-change evaluation fails, the next one succeeds: no duplicate reading. */
    @Test
    fun `SERVICE_DISABLED shown, service off - a failed evaluation does not make the next event repeat it`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network)).apply { locationEnabled = false }
        val backend = SiteFailingBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { newProviderOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(serviceDisabled), seen, "control: the device is off at the start")
        backend.site = "isLocationEnabled"
        fake.setProviderEnabled(network, false)
        runCurrent()
        backend.site = null
        assertTrue(backend.thrown.get() > 0, "control: the evaluation failed")
        fake.setProviderEnabled(network, true)
        runCurrent()
        assertEquals(listOf(serviceDisabled), seen, "no duplicate SERVICE_DISABLED: ${describe(seen)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // A live fix between the failure and the next event changes what the unknown record is resolved against
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: torn state. Device off (SERVICE_DISABLED), the switch-on evaluation fails (unknown), a live fix
     * arrives (the collector now shows a position), a no-change event follows: nothing is sent (the old record "off"
     * is NOT used, and no reseed replaces the fix). Then the device goes off: SERVICE_DISABLED.
     */
    @Test
    fun `a live fix after the failure makes the unknown record resolve to the position`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network)).apply { locationEnabled = false }
        fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
        val backend = SiteFailingBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { newProviderOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        backend.site = "isLocationEnabled"
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        backend.site = null
        assertTrue(backend.thrown.get() > 0, "control: the switch-on evaluation failed")
        fake.deliver(gps, fake.locationAt(gps, latitude = 10.0))
        runCurrent()
        assertEquals(listOf("SERVICE_DISABLED", "fix(10.0)"), seen.map(::describeOne), "control: the live fix is shown")
        fake.setProviderEnabled(network, false)
        runCurrent()
        assertEquals(listOf("SERVICE_DISABLED", "fix(10.0)"), seen.map(::describeOne), "a no-change event sends nothing: ${describe(seen)}")
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "the switch-off is reported: ${describe(seen)}")
        assertEquals(3, seen.size, "exactly once: ${describe(seen)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // Throttle: a flapping read on no-change events does not defeat minUpdateInterval
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: storm and a defeated `minUpdateInterval`. A read that fails on every second evaluation, events that
     * change nothing, gps delivering every second, maxSeedAge zero (every spurious reseed would be NO_FIX, which the
     * throttle never delays) and a 60 s interval. Over 20 s at most one reading may reach the consumer.
     */
    @Test
    fun `a flapping read with an interval and maxSeedAge zero sends at most one reading in the interval`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val backend = SiteFailingBackend(fake)
        val options = LocationUpdatesOptions(
            minUpdateInterval = 60.seconds,
            maxSeedAge = Duration.ZERO,
            platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS),
        )
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            newProviderOver(backend, testScheduler.timeSource).observeLocation(options).collect { seen += it }
        }
        runCurrent()
        fake.deliver(gps, fake.locationAt(gps, latitude = 10.0))
        runCurrent()
        val before: Int = seen.size
        for (cycle: Int in 1..20) {
            backend.site = if (cycle % 2 == 0) null else "isLocationEnabled"
            fake.setProviderEnabled(network, cycle % 4 < 2)
            runCurrent()
            backend.site = null
            advanceTimeBy(1.seconds)
            fake.deliver(gps, fake.locationAt(gps, latitude = 10.0 + cycle * 0.01))
            runCurrent()
        }
        assertTrue(backend.thrown.get() >= 5, "control: the read failed repeatedly: ${backend.thrown.get()}")
        val after: List<String> = seen.drop(before).map(::describeOne)
        assertTrue(after.size <= 1, "40 no-change events in 20 s of a 60 s interval produced ${after.size} readings: $after")
        job.cancel()
        runCurrent()
        assertEquals(0, fake.registeredCount)
    }

    // ---------------------------------------------------------------------------------------------
    // The waiting stream (D-30)
    // ---------------------------------------------------------------------------------------------

    /**
     * A waiting stream (coarse grant, gps refused) with the device off shows SERVICE_DISABLED. A no-change evaluation
     * fails (unknown); the next one finds the device still off while the collector already shows SERVICE_DISABLED: it
     * sends nothing. Then the fine grant lands and the device comes on: the stream registers and reseeds.
     */
    @Test
    fun `waiting stream - SERVICE_DISABLED is not repeated after a failed evaluation`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
            fine = false
            coarse = true
            refuse(gps)
            locationEnabled = false
        }
        fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
        val backend = SiteFailingBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { newProviderOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(serviceDisabled), seen, "control: waiting reads SERVICE_DISABLED")
        assertEquals(0, fake.registeredCount, "control: nothing registered")
        backend.site = "isLocationEnabled"
        fake.setProviderEnabled(network, false)
        runCurrent()
        backend.site = null
        assertTrue(backend.thrown.get() > 0, "control: the evaluation failed")
        fake.setProviderEnabled(network, true)
        runCurrent()
        assertEquals(listOf(serviceDisabled), seen, "no duplicate SERVICE_DISABLED on a waiting stream: ${describe(seen)}")
        fake.fine = true
        fake.allow(gps)
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertEquals(1, fake.registeredCount, "the grant landed: gps is registered")
        assertEquals(listOf("SERVICE_DISABLED", "fix(7.0)"), seen.map(::describeOne), "and the reseed is delivered: ${describe(seen)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // Seeded model fuzz: a reading is sent when and only when the collector shows something other than the truth
    // ---------------------------------------------------------------------------------------------

    private enum class Step { DEVICE_ON, DEVICE_OFF, NETWORK_ON, NETWORK_OFF, FIX }

    /**
     * Model for an explicit gps stream with the platform cache older than every live fix. Each step is a platform event;
     * a service event may fail at its first read (probability 0.3). After a step whose evaluation completed, the
     * collector must show the truth (SERVICE_DISABLED iff the device is off), and a reading was sent exactly when the
     * collector showed something else before. After a failed evaluation nothing was sent. A live fix is never replaced
     * by a reseed. Seeds are fixed and printed in every failure message.
     */
    @Test
    fun `seeded model - a reading is sent only when the collector shows something other than the truth`() = runTest(timeout = 120.seconds) {
        for (seed: Int in listOf(9, 27, 161)) {
            val random = Random(seed)
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
            val backend = SiteFailingBackend(fake)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { newProviderOver(backend).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            var deviceOn = true
            var failures = 0
            var fixes = 0
            for (index: Int in 1..250) {
                val step: Step = Step.entries[random.nextInt(Step.entries.size)]
                val trail = "seed=$seed step=$index $step"
                val shownDisabled: Boolean = seen.last() == serviceDisabled
                val before: Int = seen.size
                if (step == Step.FIX) {
                    if (!deviceOn) continue
                    fixes++
                    fake.deliver(gps, fake.locationAt(gps, latitude = 10.0 + fixes))
                    runCurrent()
                    assertEquals(before + 1, seen.size, "$trail: a live fix is one reading: ${describe(seen)}")
                    assertEquals(10.0 + fixes, latitudeOf(seen.last()), "$trail: the fix is delivered: ${describe(seen)}")
                    continue
                }
                val fail: Boolean = random.nextDouble() < 0.3
                if (fail) backend.site = "isLocationEnabled"
                val thrownBefore: Int = backend.thrown.get()
                when (step) {
                    Step.DEVICE_ON -> { deviceOn = true; fake.setDeviceLocationEnabled(true) }
                    Step.DEVICE_OFF -> { deviceOn = false; fake.setDeviceLocationEnabled(false) }
                    Step.NETWORK_ON -> fake.setProviderEnabled(network, true)
                    Step.NETWORK_OFF -> fake.setProviderEnabled(network, false)
                    Step.FIX -> Unit
                }
                runCurrent()
                backend.site = null
                if (backend.thrown.get() > thrownBefore) {
                    failures++
                    assertEquals(before, seen.size, "$trail: a failed evaluation sent a reading: ${describe(seen)}")
                    continue
                }
                if (shownDisabled == !deviceOn) {
                    assertEquals(before, seen.size, "$trail: the collector already showed the truth, but a reading was sent: ${describe(seen)}")
                } else {
                    assertEquals(before + 1, seen.size, "$trail: the collector showed the wrong state: ${describe(seen)}")
                    assertEquals(!deviceOn, seen.last() == serviceDisabled, "$trail: the reading is not the truth: ${describe(seen)}")
                }
            }
            assertTrue(failures >= 10 && fixes >= 10, "control: seed=$seed failures=$failures fixes=$fixes")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount, "seed=$seed: registration released")
            assertEquals(0, fake.subscriberCount, "seed=$seed: subscription released")
        }
    }

    private fun newProviderOver(backend: LocationBackend, timeSource: TimeSource = TestTimeSource()): LocationProvider =
        AndroidLocationProvider(
            context = ApplicationProvider.getApplicationContext<Context>(),
            logger = NoopLogger,
            systemScreenLauncher = SystemScreenLauncher.SeparateTask,
            backend = backend,
            timeSource = timeSource,
        )
}
