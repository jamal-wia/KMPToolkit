package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Group loc-android (ticket 5.1.0), correction round 7, findings m1 and m2 of breaker round 6: a permission (grant)
 * read is a platform call that can fail (a dead package manager). Two shapes were unguarded: a state recorded BEFORE
 * the fallible read that builds the reading it describes (the stream then reads "already reported" for a reading that
 * was never sent), and a grant read made OUTSIDE the guarded gate action, in a platform listener callback (an
 * uncaught exception on the main looper). Own tests, one per grant read of `LocationProvider.android.kt` that those
 * shapes touch; the reviewer's breaker tests stay untouched. Virtual time, no sleeps, no wall clock; the grant reader
 * is [FlakyGrantBackend] over [FakeLocationBackend], every case ends with the registration and subscription counters
 * at zero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GrantReadFailureRound7Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val noFix: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX)
    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)
    private val explicitGps = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS))
    private val explicitNetwork = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.NETWORK))

    /**
     * The grant reads fail while armed. [failFromRead] counts the reads made since [arm]: the first [failFromRead] - 1
     * of them answer, every later one throws [failure] (1 = every read fails). [coarseOnlyFails]: only the coarse read
     * throws (the fine read answers), the second read of a coarse-only check.
     */
    private class FlakyGrantBackend(val fake: FakeLocationBackend) : LocationBackend by fake {
        @Volatile var armed: Boolean = false
        @Volatile var failFromRead: Int = 1
        @Volatile var coarseOnlyFails: Boolean = false
        @Volatile var failure: Throwable = IllegalStateException("round7: package manager died")
        private val readsSinceArm = AtomicInteger(0)
        val thrown = AtomicInteger(0)

        fun arm(fromRead: Int = 1) {
            readsSinceArm.set(0)
            failFromRead = fromRead
            armed = true
        }

        fun heal() {
            armed = false
        }

        private fun check(coarse: Boolean) {
            if (!armed) return
            val index: Int = readsSinceArm.incrementAndGet()
            if (coarseOnlyFails && !coarse) return
            if (index >= failFromRead) {
                thrown.incrementAndGet()
                throw failure
            }
        }

        override fun hasFinePermission(): Boolean {
            check(coarse = false)
            return fake.hasFinePermission()
        }

        override fun hasCoarsePermission(): Boolean {
            check(coarse = true)
            return fake.hasCoarsePermission()
        }
    }

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
        timeSource = TestTimeSource(),
    )

    private fun providersOf(fake: FakeLocationBackend): List<String> =
        fake.registered().map { registration: FakeLocationBackend.Registration -> registration.provider }.sorted()

    private fun latitudeOf(reading: LocationReading): Double? = (reading as? LocationReading.Available)?.fix?.coordinates?.latitude

    // ---------------------------------------------------------------------------------------------
    // m1: the reseed of a stream (evaluateLocked's transition) builds its reading before it records the state
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: torn state (recorded as reported, never sent). An explicit gps stream (fine grant) reads
     * SERVICE_DISABLED while the device is off. The device comes on and the reading of the reseed fails to build (the
     * grant read inside it). Nothing may be recorded for it: the next event reseeds, with the cached fix when there
     * is one. Turns red when `disabledNow` is written before the reading is built.
     */
    @Test
    fun `an explicit stream whose reseed fails to build reseeds at the next event`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps)).apply { locationEnabled = false }
        fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
        val backend = FlakyGrantBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(serviceDisabled), seen, "control: off")
        assertEquals(listOf(gps), providersOf(fake), "control: gps registered")
        backend.arm()
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        backend.heal()
        assertTrue(backend.thrown.get() > 0, "control: the failing evaluation read the grant")
        assertEquals(listOf(serviceDisabled), seen, "the failed reseed sent nothing")
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertEquals(2, seen.size, "exactly one reseed after the heal: $seen")
        assertEquals(7.0, latitudeOf(seen.last()), "the reseed carries the cached fix: $seen")
        assertEquals(listOf(gps), providersOf(fake), "the registration was never touched")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    /** The same through AUTO (a balanced stream registers one source; the device switch is what is off). */
    @Test
    fun `an AUTO stream whose reseed fails to build reseeds at the next event`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network)).apply { locationEnabled = false }
        val backend = FlakyGrantBackend(fake)
        val options = LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(options).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(serviceDisabled), seen, "control: off")
        val registered: List<String> = providersOf(fake)
        assertEquals(1, registered.size, "control: one source registered: $registered")
        backend.arm()
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        backend.heal()
        assertTrue(backend.thrown.get() > 0, "control: the failing evaluation read the grant")
        assertEquals(listOf(serviceDisabled), seen, "the failed reseed sent nothing: $seen")
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertEquals(listOf(serviceDisabled, noFix), seen, "one reseed at the next event")
        assertEquals(registered, providersOf(fake), "the registration was never touched")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    /**
     * The start's own seed (same order: build, then record, then send). A failing grant read inside the start's seed
     * ends the collection with the failure (the documented path of a platform failure at start), nothing stays
     * registered or subscribed, and a new collection after the heal works from scratch (re-init).
     */
    @Test
    fun `a failing grant read while the stream seeds ends the collection cleanly and a new collection works`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps))
            val backend = FlakyGrantBackend(fake)
            // The first read is the stream's permission gate; every later one (the seed's coarse-only check) fails.
            backend.arm(fromRead = 2)
            val failure: IllegalStateException = assertFailsWith<IllegalStateException> { providerOver(backend).observeLocation(explicitGps).collect { } }
            assertTrue(backend.thrown.get() > 0, "control: the seed read the grant")
            assertEquals(0, fake.registeredCount, "nothing registered after a failed start")
            assertEquals(0, fake.subscriberCount, "nothing subscribed after a failed start")
            assertEquals("round7: package manager died", failure.message)
            backend.heal()
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(noFix), seen, "re-init: a fresh collection seeds")
            assertEquals(listOf(gps), providersOf(fake))
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    // ---------------------------------------------------------------------------------------------
    // The other grant reads of an evaluation: the waiting branch and the move
    // ---------------------------------------------------------------------------------------------

    /**
     * A waiting stream (nothing registered, every candidate refused, service off) whose service returns while a grant
     * read fails: the first read of the evaluation (the refusals' grant check) or the second (the terminal's recheck).
     * Nothing is sent and nothing is recorded: the stream still reads SERVICE_DISABLED and is open; at the next event
     * (grant read healed) it ends with PERMISSION_MISSING, the last element.
     */
    @Test
    fun `a waiting stream whose terminal evaluation fails to read the grant ends at the next event`() = runTest(timeout = 60.seconds) {
        for (failingRead: Int in 1..2) {
            val fake = FakeLocationBackend(providers = setOf(gps)).apply {
                fine = false
                coarse = true
                refuse(gps)
                locationEnabled = false
            }
            val backend = FlakyGrantBackend(fake)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(serviceDisabled), seen, "read $failingRead, control: waiting on SERVICE_DISABLED")
            assertEquals(0, fake.registeredCount, "read $failingRead, control: nothing registered while waiting")
            backend.arm(fromRead = failingRead)
            fake.setDeviceLocationEnabled(true)
            runCurrent()
            backend.heal()
            assertTrue(backend.thrown.get() > 0, "read $failingRead, control: a grant read failed")
            assertEquals(listOf(serviceDisabled), seen, "read $failingRead: the failed evaluation sent nothing")
            assertTrue(job.isActive, "read $failingRead: the stream is still open")
            fake.setDeviceLocationEnabled(true)
            runCurrent()
            assertEquals(listOf(serviceDisabled, permissionMissing), seen, "read $failingRead: the terminal comes at the next event")
            job.join()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }
    }

    /**
     * An AUTO move (network goes off while gps is on: the registration moves to gps) whose registration's grant read
     * fails, both for the move and for putting the previous source back. Nothing is registered, the stream reads the
     * state it can judge (SERVICE_DISABLED: the previous source is off), nothing throws into the callback; at the next
     * event after the heal the stream moves to gps and reseeds. One registration at the end, none after cancel.
     */
    @Test
    fun `an AUTO move whose registration cannot read the grant is retried at the next event`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val backend = FlakyGrantBackend(fake)
        val options = LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)
        val seen: MutableList<LocationReading> = mutableListOf()
        val thrownIntoCollector: MutableList<Throwable> = mutableListOf()
        val job: Job = backgroundScope.launch {
            try {
                providerOver(backend).observeLocation(options).collect { seen += it }
            } catch (failure: IllegalStateException) {
                thrownIntoCollector += failure
            }
        }
        runCurrent()
        assertEquals(listOf(network), providersOf(fake), "control: the balanced stream registers network")
        backend.arm()
        val escaped: Throwable? = runCatching { fake.setProviderEnabled(network, false) }.exceptionOrNull()
        runCurrent()
        backend.heal()
        assertNull(escaped, "a failing grant read escaped into a platform callback")
        assertTrue(backend.thrown.get() > 0, "control: the move read the grant")
        assertEquals(emptyList(), providersOf(fake), "the failed move left nothing registered")
        assertEquals(serviceDisabled, seen.last(), "the stream reads the state it can judge: $seen")
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertEquals(listOf(gps), providersOf(fake), "the next event moves to gps")
        assertEquals(noFix, seen.last(), "and reseeds: $seen")
        assertTrue(thrownIntoCollector.isEmpty(), "nothing reached the collector: $thrownIntoCollector")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }
}
