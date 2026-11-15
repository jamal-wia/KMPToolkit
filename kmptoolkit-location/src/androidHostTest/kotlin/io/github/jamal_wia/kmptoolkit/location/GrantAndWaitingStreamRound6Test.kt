package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * Group loc-android (ticket 5.1.0), correction round 6, findings m1 and m2 of breaker round 5: a terminal
 * PERMISSION_MISSING is decided on refusals that still stand under the CURRENT grant (start's refusal loop, start's
 * closing evaluation and every service event), and a waiting stream (nothing registered, every candidate refused)
 * never reseeds: it reads SERVICE_DISABLED until the terminal or a grant, from ONE read of the service state per
 * evaluation. Own tests, one per path; the breaker tests of the reviewer stay untouched. Virtual time, no sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GrantAndWaitingStreamRound6Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val noFix: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX)
    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)
    private val explicitGps = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS))
    private val high = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
        timeSource = TestTimeSource(),
    )

    private fun providersOf(fake: FakeLocationBackend): List<String> =
        fake.registered().map { registration: FakeLocationBackend.Registration -> registration.provider }.sorted()

    /** A coarse-only app (API 24-30) on a device whose only source is gps, which refuses it. */
    private fun coarseGpsOnly(): FakeLocationBackend = FakeLocationBackend(providers = setOf(gps)).apply {
        fine = false
        coarse = true
        refuse(gps)
    }

    /** The fine grant lands right after gps's first refusal (the platform's answer is already given). */
    private fun grantAfterFirstRefusal(fake: FakeLocationBackend): LocationBackend = object : LocationBackend by fake {
        private val granted: AtomicBoolean = AtomicBoolean(false)

        override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
            try {
                fake.requestUpdates(provider, request, listener)
            } catch (refusal: SecurityException) {
                if (granted.compareAndSet(false, true)) {
                    fake.fine = true
                    fake.allow(provider)
                }
                throw refusal
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // m1: the start's own refusal loop
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: wrong terminal state. The same shape as the breaker's start case, one step earlier: the service is
     * ON, so the start's refusal loop (not the closing evaluation) decides. The fine grant lands right after gps's
     * refusal; fine is held when the decision is made, so asking again once is the answer, not PERMISSION_MISSING.
     * Turns red when the start throws on refusals met under another grant than the current one.
     */
    @Test
    fun `an explicit gps stream whose grant lands right after the refusal at start registers gps instead of ending`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseGpsOnly()
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(grantAfterFirstRefusal(fake)).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            assertFalse(permissionMissing in seen, "fine is held and gps is on: the grant is not missing: $seen")
            assertTrue(job.isActive, "the stream stays open: $seen")
            assertEquals(listOf(gps), providersOf(fake), "gps is registered under the fine grant")
            assertEquals(2, fake.registrationAttemptCount, "one refusal under coarse, one retry under fine")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    /** The same through AUTO. */
    @Test
    fun `an AUTO stream whose grant lands right after the refusal at start registers gps instead of ending`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseGpsOnly()
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(grantAfterFirstRefusal(fake)).observeLocation(high).collect { seen += it } }
            runCurrent()
            assertFalse(permissionMissing in seen, "fine is held and gps is on: the grant is not missing: $seen")
            assertTrue(job.isActive, "the stream stays open: $seen")
            assertEquals(listOf(gps), providersOf(fake))
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    /**
     * Control: a refusal that stands under an unchanged grant still ends the stream at start (no retry loop, one
     * attempt), so the retry above is not a way around the refusal.
     */
    @Test
    fun `a refusal under an unchanged grant still ends the stream at start after one attempt`() = runTest(timeout = 60.seconds) {
        val fake: FakeLocationBackend = coarseGpsOnly()
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(fake).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        assertEquals(permissionMissing, seen.last(), "$seen")
        assertTrue(job.isCompleted)
        assertEquals(1, fake.registrationAttemptCount)
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // m1: the terminal of a service event
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: wrong terminal state. A waiting explicit gps stream (every candidate refused, the service off) gets
     * its service event; the fine grant lands inside that evaluation, after the evaluation's own grant read and before
     * its terminal decision. The terminal re-reads the grant: the evaluation runs again under the new grant and
     * registers gps. Turns red when the terminal is decided on the grant read at the top of the evaluation.
     */
    @Test
    fun `a grant that lands inside the evaluation that would end a waiting stream registers the source instead`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseGpsOnly().apply { setProviderEnabled(gps, false) }
            val armed = AtomicBoolean(false)
            val readsWhileArmed = AtomicInteger(0)
            val backend = object : LocationBackend by fake {
                // The grant is visible from the second read of the armed evaluation on: the first is its own top-of-run read.
                override fun hasFinePermission(): Boolean =
                    if (armed.get()) readsWhileArmed.incrementAndGet() >= 2 else fake.hasFinePermission()
            }
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(serviceDisabled), seen, "control: waiting while gps is off")
            assertEquals(0, fake.registeredCount, "control: nothing registered")

            armed.set(true)
            fake.allow(gps)
            fake.setProviderEnabled(gps, true)
            runCurrent()
            assertTrue(readsWhileArmed.get() >= 2, "control: the evaluation read the grant at least twice")
            assertFalse(permissionMissing in seen, "the grant is held when the decision is made: $seen")
            assertTrue(job.isActive, "the stream stays open: $seen")
            assertEquals(listOf(gps), providersOf(fake), "gps is registered under the new grant")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    // ---------------------------------------------------------------------------------------------
    // m2: a waiting stream never reseeds, one read per evaluation
    // ---------------------------------------------------------------------------------------------

    /** The device switch (not the provider) turns on right after the next read of it. */
    private fun switchOnAfterNextRead(fake: FakeLocationBackend, armed: AtomicBoolean): LocationBackend = object : LocationBackend by fake {
        override fun isLocationEnabled(): Boolean {
            val now: Boolean = fake.isLocationEnabled()
            if (armed.compareAndSet(true, false)) fake.locationEnabled = true
            return now
        }
    }

    /**
     * Defect class: a torn read publishes a false state, through AUTO (the breaker covers the explicit source). A
     * waiting AUTO stream evaluates an event, the device switch turns on right after the evaluation's read of it: the
     * evaluation keeps that one answer (off), reads SERVICE_DISABLED and never reseeds NO_FIX; the switch's own
     * broadcast ends the stream. Turns red when the waiting state reads the switch twice.
     */
    @Test
    fun `a waiting AUTO stream does not reseed NO_FIX when the device switch turns on right after its read`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseGpsOnly().apply { locationEnabled = false }
            val armed = AtomicBoolean(false)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(switchOnAfterNextRead(fake, armed)).observeLocation(high).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(serviceDisabled), seen, "control: waiting while the device is off")
            assertEquals(0, fake.registeredCount, "control: nothing registered")

            armed.set(true)
            fake.setProviderEnabled(gps, true)
            runCurrent()
            assertFalse(armed.get(), "control: the switch landed inside the evaluation")
            assertEquals(listOf(serviceDisabled), seen, "a waiting stream with nothing registered reseeded: $seen")
            assertTrue(job.isActive)

            fake.setDeviceLocationEnabled(true)
            runCurrent()
            assertEquals(listOf(serviceDisabled, permissionMissing), seen, "$seen")
            assertTrue(job.isCompleted)
            assertEquals(0, fake.subscriberCount)
            assertEquals(0, fake.registeredCount)
        }

    /**
     * Defect class: the waiting state must still report the state it is in. The start reads the device as ON for the
     * seed (NO_FIX), every candidate refuses, and the start's second read finds it OFF: the stream waits. The first
     * evaluation of the waiting state has to publish SERVICE_DISABLED (the transition the seed missed), not stay on a
     * NO_FIX that no registration backs, and it must not end the stream (the service is off). Turns red when a waiting
     * stream returns early without publishing the off state.
     */
    @Test
    fun `a stream that starts waiting after an on seed publishes SERVICE_DISABLED and waits for the service`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseGpsOnly().apply { locationEnabled = false }
            val reads = AtomicInteger(0)
            val backend = object : LocationBackend by fake {
                // Only the seed's own read sees the device on, as if it were switched off a moment later.
                override fun isLocationEnabled(): Boolean = if (reads.incrementAndGet() == 1) true else fake.isLocationEnabled()
            }
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(noFix, serviceDisabled), seen, "the seed said on, the closing evaluation corrects it: $seen")
            assertTrue(job.isActive, "the service is off: the stream waits")
            assertEquals(0, fake.registeredCount)
            assertEquals(1, fake.subscriberCount)

            fake.setDeviceLocationEnabled(true)
            runCurrent()
            assertEquals(listOf(noFix, serviceDisabled, permissionMissing), seen, "$seen")
            assertTrue(job.isCompleted)
            assertEquals(0, fake.subscriberCount)
        }
}
