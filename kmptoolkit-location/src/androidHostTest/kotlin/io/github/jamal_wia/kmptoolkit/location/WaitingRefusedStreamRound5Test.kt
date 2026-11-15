package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
 * Group loc-android (ticket 5.1.0), correction round 5, finding M2 of breaker round 4: a stream whose every
 * candidate refuses the caller while the service is off. PLAN 4.2 ("denied with services off is SERVICE_DISABLED") and
 * D8 (one mapper for the stream and the one-shot, which answers SERVICE_DISABLED at once): the stream stays open on
 * SERVICE_DISABLED with nothing registered and ends with PERMISSION_MISSING when the service comes back; a grant that
 * arrives meanwhile is picked up. Virtual time, no sleeps; every case ends with the counters at zero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WaitingRefusedStreamRound5Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)
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

    private fun coarseApi24To30(vararg refusing: String): FakeLocationBackend = FakeLocationBackend(providers = setOf(gps)).apply {
        fine = false
        coarse = true
        for (provider: String in refusing) refuse(provider)
    }

    @Test
    fun `an explicit gps source that refuses while it is off reads SERVICE_DISABLED and ends with PERMISSION_MISSING when it comes on`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseApi24To30(gps)
            fake.setProviderEnabled(gps, false)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch {
                providerOver(fake).observeLocation(
                    LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS)),
                ).collect { seen += it }
            }
            runCurrent()
            assertEquals(serviceDisabled, seen.last(), "the service is off, whatever the grant: $seen")
            assertTrue(job.isActive, "the stream stays open while the service is off")
            assertEquals(0, fake.registeredCount, "nothing is registered while every candidate refuses")
            assertEquals(1, fake.subscriberCount, "the service broadcast is what brings it back")

            val attempts: Int = fake.registrationAttemptCount
            fake.setProviderEnabled(gps, true)
            runCurrent()
            assertEquals(permissionMissing, seen.last(), "the service is on: now the grant is what is missing: $seen")
            assertTrue(job.isCompleted, "a terminal reading completes the stream")
            assertEquals(attempts, fake.registrationAttemptCount, "the remembered refusal decides, no platform call")
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `an AUTO stream whose only candidate refuses while the device switch is off ends when the switch returns`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseApi24To30(gps)
            fake.setDeviceLocationEnabled(false)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(fake).observeLocation(high).collect { seen += it } }
            runCurrent()
            assertEquals(serviceDisabled, seen.last(), "$seen")
            assertTrue(job.isActive)
            assertEquals(0, fake.registeredCount)

            // Events that change nothing about the answer neither end the stream nor register anything.
            repeat(5) {
                fake.setDeviceLocationEnabled(false)
                runCurrent()
            }
            assertTrue(job.isActive, "still off: ${seen.takeLast(3)}")
            assertEquals(0, fake.registeredCount)

            fake.setDeviceLocationEnabled(true)
            runCurrent()
            assertEquals(permissionMissing, seen.last(), "$seen")
            assertTrue(job.isCompleted)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `a stream waiting off with every candidate refused registers the source once the fine grant arrives`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseApi24To30(gps)
            fake.setProviderEnabled(gps, false)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch {
                providerOver(fake).observeLocation(
                    LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS)),
                ).collect { seen += it }
            }
            runCurrent()
            assertEquals(0, fake.registeredCount, "control: waiting, nothing registered")

            fake.fine = true
            fake.allow(gps)
            fake.setDeviceLocationEnabled(true) // any service event: the new grant is picked up
            runCurrent()
            assertEquals(listOf(gps), providersOf(fake), "the grant arrived: gps is asked again and accepts")
            assertTrue(job.isActive)
            assertEquals(serviceDisabled, seen.last(), "gps itself is still off: $seen")

            fake.setProviderEnabled(gps, true)
            runCurrent()
            assertTrue(job.isActive, "gps came on and it is allowed: the stream continues")
            assertNotEquals(serviceDisabled, seen.last(), "$seen")
            assertFalse(seen.last() == permissionMissing)
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `a stream whose every candidate refuses while the service is on still ends at once with PERMISSION_MISSING`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseApi24To30(gps)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch {
                providerOver(fake).observeLocation(
                    LocationUpdatesOptions(accuracy = LocationAccuracy.LOW_POWER, platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS)),
                ).collect { seen += it }
            }
            runCurrent()
            assertEquals(permissionMissing, seen.last(), "$seen")
            assertTrue(job.isCompleted)
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `a stream waiting off with every candidate refused leaves nothing behind when it is cancelled`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(passive)).apply {
                fine = false
                coarse = true
                refuse(passive)
            }
            fake.setProviderEnabled(passive, false)
            val job: Job = backgroundScope.launch {
                providerOver(fake).observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.LOW_POWER)).collect { }
            }
            runCurrent()
            assertEquals(1, fake.subscriberCount, "control: the stream waits for the service")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount, "the subscription of a waiting stream was left")
        }
}
