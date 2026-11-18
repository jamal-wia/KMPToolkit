package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Group loc-android (ticket 5.1.0), correction round 8, finding m1 of breaker round 7: an evaluation of the service
 * state that does not finish (ANY platform read in it fails) must not leave the recorded "state last reported" claiming
 * a state the collector no longer holds. The failure leaves the record unknown, so the next service event reports the
 * true state even when a live fix was sent in between (the fix makes the stream read "on" while the record still said
 * "off"; the next switch-off then found "already reported" and sent nothing).
 *
 * Own tests, written from the contract (PLAN 4.2: SERVICE_DISABLED at once, whenever every source is off), one per
 * read site of an evaluation and per failure kind; the reviewer's breaker class `AndroidLocationProviderBreakerR7Test`
 * stays untouched and is the exhaustive attack. Virtual time, no sleeps; every case ends with the registration and
 * subscription counters at zero, and has control assertions that the armed read really failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FailedEvaluationLeavesStateUnknownRound8Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val explicitGps = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS))
    private val autoBalanced = LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)

    /** Failure kinds a platform read can show: unchecked, and checked (Kotlin has no checked exceptions to declare). */
    private val kinds: List<Pair<String, () -> Throwable>> = listOf(
        "RuntimeException" to { IllegalStateException("round8: runtime failure") },
        "Exception" to { Exception("round8: checked failure") },
    )

    /** Every read an evaluation makes of the platform. */
    private val readSites: List<String> = listOf(
        "isLocationEnabled",
        "isProviderEnabled",
        "hasProvider",
        "hasFinePermission",
        "hasCoarsePermission",
        "lastKnownLocation",
        "elapsedRealtimeNanos",
    )

    /** Reads every one of these scenarios makes during the re-check (the other sites are skipped when not read). */
    private val mustReach: Set<String> = setOf("isLocationEnabled", "hasFinePermission", "lastKnownLocation", "elapsedRealtimeNanos")

    /** The armed read ([site]) throws [failure] on every call until [site] is cleared; [thrown] counts the failures. */
    private class SiteFailingBackend(val fake: FakeLocationBackend) : LocationBackend by fake {
        @Volatile var site: String? = null
        @Volatile var failure: Throwable = IllegalStateException("round8")
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

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
        timeSource = TestTimeSource(),
    )

    private fun latitudeOf(reading: LocationReading): Double? = (reading as? LocationReading.Available)?.fix?.coordinates?.latitude

    private fun describe(readings: List<LocationReading>): String =
        readings.map { reading: LocationReading ->
            latitudeOf(reading)?.let { latitude: Double -> "fix($latitude)" } ?: (reading as LocationReading.Unavailable).reason.name
        }.toString()

    // ---------------------------------------------------------------------------------------------
    // The reviewer's scenario, every read site: off, on (the re-check fails), a live fix, off again
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: torn state (the record says "off" while a fix was sent). An explicit gps stream with a cached fix
     * reads SERVICE_DISABLED while the device is off. The device comes on and the re-check fails at one read (each
     * read site and failure kind in turn). A live fix arrives, then the device goes off again: the stream MUST read
     * SERVICE_DISABLED last, never the stale position. Turns red when a failed evaluation leaves the old record.
     */
    @Test
    fun `explicit stream - a failed re-check of any read never hides the next device switch-off`() = runTest(timeout = 120.seconds) {
        val reached: MutableSet<String> = mutableSetOf()
        for (site: String in readSites) {
            for ((kindName: String, make: () -> Throwable) in kinds) {
                val label = "site=$site kind=$kindName"
                val fake = FakeLocationBackend(providers = setOf(gps)).apply { locationEnabled = false }
                fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
                val backend = SiteFailingBackend(fake).apply { failure = make() }
                val seen: MutableList<LocationReading> = mutableListOf()
                val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
                runCurrent()
                assertEquals(listOf(serviceDisabled), seen, "$label: control: off")
                backend.site = site
                fake.setDeviceLocationEnabled(true)
                runCurrent()
                backend.site = null
                if (backend.thrown.get() == 0) {
                    // This read is not made on this path (nothing to prove for it): the sites that are made carry the case.
                    job.cancelAndJoin()
                    continue
                }
                reached += site
                fake.deliver(gps, fake.locationAt(gps, latitude = 9.0))
                runCurrent()
                assertEquals(9.0, latitudeOf(seen.last()), "$label: control: the live fix is delivered: ${describe(seen)}")
                fake.setDeviceLocationEnabled(false)
                runCurrent()
                assertEquals(serviceDisabled, seen.last(), "$label: the device is off but the stream shows a position: ${describe(seen)}")
                job.cancelAndJoin()
                assertEquals(0, fake.registeredCount, "$label: registration released")
                assertEquals(0, fake.subscriberCount, "$label: subscription released")
            }
        }
        assertTrue(reached.containsAll(mustReach), "control: every read an evaluation makes was failed, reached=$reached")
    }

    /**
     * The same with the source switch instead of the device switch: gps goes off (SERVICE_DISABLED), comes on while a
     * read fails, a live fix arrives, gps goes off again.
     */
    @Test
    fun `explicit stream - a failed re-check of any read never hides the next source switch-off`() = runTest(timeout = 120.seconds) {
        val reached: MutableSet<String> = mutableSetOf()
        for (site: String in readSites) {
            for ((kindName: String, make: () -> Throwable) in kinds) {
                val label = "site=$site kind=$kindName"
                val fake = FakeLocationBackend(providers = setOf(gps))
                fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
                val backend = SiteFailingBackend(fake).apply { failure = make() }
                val seen: MutableList<LocationReading> = mutableListOf()
                val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
                runCurrent()
                fake.setProviderEnabled(gps, false)
                runCurrent()
                assertEquals(serviceDisabled, seen.last(), "$label: control: gps off reads SERVICE_DISABLED: ${describe(seen)}")
                backend.site = site
                fake.setProviderEnabled(gps, true)
                runCurrent()
                backend.site = null
                if (backend.thrown.get() == 0) {
                    // This read is not made on this path (nothing to prove for it): the sites that are made carry the case.
                    job.cancelAndJoin()
                    continue
                }
                reached += site
                fake.deliver(gps, fake.locationAt(gps, latitude = 9.0))
                runCurrent()
                assertEquals(9.0, latitudeOf(seen.last()), "$label: control: the live fix is delivered: ${describe(seen)}")
                fake.setProviderEnabled(gps, false)
                runCurrent()
                assertEquals(serviceDisabled, seen.last(), "$label: gps is off but the stream shows a position: ${describe(seen)}")
                job.cancelAndJoin()
                assertEquals(0, fake.registeredCount, "$label: registration released")
                assertEquals(0, fake.subscriberCount, "$label: subscription released")
            }
        }
        assertTrue(reached.containsAll(mustReach), "control: every read an evaluation makes was failed, reached=$reached")
    }

    /**
     * The same on an AUTO stream (network and gps in the table): the switch-off that must not be hidden is the device
     * switch, with a live fix from the registered source in between.
     */
    @Test
    fun `AUTO stream - a failed re-check of any read never hides the next device switch-off`() = runTest(timeout = 120.seconds) {
        val reached: MutableSet<String> = mutableSetOf()
        for (site: String in readSites) {
            for ((kindName: String, make: () -> Throwable) in kinds) {
                val label = "site=$site kind=$kindName"
                val fake = FakeLocationBackend(providers = setOf(gps, network)).apply { locationEnabled = false }
                fake.setCached(network, fake.locationAt(network, latitude = 7.0, ageMillis = 1_000L))
                val backend = SiteFailingBackend(fake).apply { failure = make() }
                val seen: MutableList<LocationReading> = mutableListOf()
                val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(autoBalanced).collect { seen += it } }
                runCurrent()
                assertEquals(serviceDisabled, seen.last(), "$label: control: off: ${describe(seen)}")
                backend.site = site
                fake.setDeviceLocationEnabled(true)
                runCurrent()
                backend.site = null
                if (backend.thrown.get() == 0) {
                    // This read is not made on this path (nothing to prove for it): the sites that are made carry the case.
                    job.cancelAndJoin()
                    continue
                }
                reached += site
                assertTrue(fake.registeredCount > 0, "$label: control: a source is registered")
                fake.deliverToAll(fake.locationAt(gps, latitude = 9.0))
                runCurrent()
                assertEquals(9.0, latitudeOf(seen.last()), "$label: control: the live fix is delivered: ${describe(seen)}")
                fake.setDeviceLocationEnabled(false)
                runCurrent()
                assertEquals(serviceDisabled, seen.last(), "$label: the device is off but the stream shows a position: ${describe(seen)}")
                job.cancelAndJoin()
                assertEquals(0, fake.registeredCount, "$label: registration released")
                assertEquals(0, fake.subscriberCount, "$label: subscription released")
            }
        }
        assertTrue(reached.containsAll(mustReach), "control: every read an evaluation makes was failed, reached=$reached")
    }

    // ---------------------------------------------------------------------------------------------
    // The failure that lasts, then heals; the Error; the callback entry; the waiting stream; the cost of "unknown"
    // ---------------------------------------------------------------------------------------------

    /**
     * A failure that lasts over several service events and then heals. Every event meanwhile fails and sends nothing;
     * the live fix still flows; the first switch-off after the heal is reported.
     */
    @Test
    fun `a failure that lasts over several events then heals still reports the next switch-off`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps)).apply { locationEnabled = false }
        fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
        val backend = SiteFailingBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        backend.site = "isLocationEnabled"
        fake.setDeviceLocationEnabled(true)
        fake.setDeviceLocationEnabled(false)
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertTrue(backend.thrown.get() >= 3, "control: every event of the outage failed: ${backend.thrown.get()}")
        assertEquals(listOf(serviceDisabled), seen, "the outage sent nothing")
        fake.deliver(gps, fake.locationAt(gps, latitude = 9.0))
        runCurrent()
        backend.site = null
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "the first switch-off after the heal is reported: ${describe(seen)}")
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertEquals(7.0, latitudeOf(seen.last()), "back on: the cached fix is the reseed: ${describe(seen)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    /**
     * An Error from a read passes out of the callback (it is not a platform failure to swallow), and it leaves the
     * record unknown too: the gate is not wedged and the next switch-off is reported after a live fix.
     */
    @Test
    fun `an Error during a re-check passes out and the next switch-off is still reported`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps)).apply { locationEnabled = false }
        fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
        val backend = SiteFailingBackend(fake).apply { failure = StackOverflowError("round8") }
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        backend.site = "isLocationEnabled"
        val escaped: Throwable? = runCatching { fake.setDeviceLocationEnabled(true) }.exceptionOrNull()
        backend.site = null
        assertTrue(escaped is StackOverflowError, "an Error passes: $escaped")
        fake.deliver(gps, fake.locationAt(gps, latitude = 9.0))
        runCurrent()
        assertEquals(9.0, latitudeOf(seen.last()), "control: the live fix is delivered: ${describe(seen)}")
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "the device is off but the stream shows a position: ${describe(seen)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    /**
     * The listener callback ("provider enabled") is an evaluation entry too, next to the broadcast: both mark the record
     * unknown when the evaluation does not finish.
     */
    @Test
    fun `a failed provider callback re-check never hides the next provider switch-off`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps))
        fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
        val backend = SiteFailingBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        fake.setProviderEnabled(gps, false)
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "control: gps off: ${describe(seen)}")
        backend.site = "isProviderEnabled"
        fake.setProviderEnabled(gps, true)
        runCurrent()
        backend.site = null
        assertTrue(backend.thrown.get() > 0, "control: the re-check failed")
        fake.deliver(gps, fake.locationAt(gps, latitude = 9.0))
        runCurrent()
        assertEquals(9.0, latitudeOf(seen.last()), "control: the live fix is delivered: ${describe(seen)}")
        fake.setProviderEnabled(gps, false)
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "gps is off but the stream shows a position: ${describe(seen)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    /**
     * The D-30 waiting stream whose grant lands: a coarse grant, gps refused while the device is off (waiting,
     * SERVICE_DISABLED). The fine grant lands, the device comes on, and the evaluation registers gps and then fails reading the
     * service state. A live fix arrives; the device goes off: SERVICE_DISABLED must be the last reading.
     */
    @Test
    fun `a waiting stream that registers and then fails its re-check does not hide the next switch-off`() = runTest(timeout = 60.seconds) {
        var proven = 0
        for (site: String in readSites) {
            val label = "site=$site"
            val fake = FakeLocationBackend(providers = setOf(gps)).apply {
                fine = false
                coarse = true
                refuse(gps)
                locationEnabled = false
            }
            fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
            val backend = SiteFailingBackend(fake)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(serviceDisabled), seen, "$label: control: waiting reads SERVICE_DISABLED: ${describe(seen)}")
            assertEquals(0, fake.registeredCount, "$label: control: nothing registered")
            fake.fine = true
            fake.allow(gps)
            backend.site = site
            fake.setDeviceLocationEnabled(true)
            runCurrent()
            backend.site = null
            if (backend.thrown.get() == 0 || fake.registeredCount == 0) {
                // This read is not made on this path, or the failure kept gps from registering (a different path,
                // the explicit retry at the next event): the other sites carry the case.
                job.cancelAndJoin()
                continue
            }
            proven++
            fake.deliver(gps, fake.locationAt(gps, latitude = 9.0))
            runCurrent()
            assertEquals(9.0, latitudeOf(seen.last()), "$label: control: the live fix is delivered: ${describe(seen)}")
            fake.setDeviceLocationEnabled(false)
            runCurrent()
            assertEquals(serviceDisabled, seen.last(), "$label: the device is off but the stream shows a position: ${describe(seen)}")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount, "$label: registration released")
            assertEquals(0, fake.subscriberCount, "$label: subscription released")
        }
        assertTrue(proven > 0, "control: at least one read site failed after gps registered")
    }

    /**
     * What the record's "unknown" costs, and no more: after a failed re-check with NO fix in between, the next event
     * reports the true state once (a duplicate of the reading the stream already shows, at worst), and the stream then
     * dedupes again: the event after that sends nothing.
     */
    @Test
    fun `after a failed re-check the true state is reported once and then deduplicated again`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps)).apply { locationEnabled = false }
        fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
        val backend = SiteFailingBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        backend.site = "isLocationEnabled"
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        backend.site = null
        assertEquals(listOf(serviceDisabled), seen, "the failed re-check sent nothing")
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertEquals(2, seen.size, "the next event reports the true state once: ${describe(seen)}")
        assertEquals(7.0, latitudeOf(seen.last()), "with the cached fix: ${describe(seen)}")
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertEquals(2, seen.size, "and the event after that sends nothing: ${describe(seen)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }
}
