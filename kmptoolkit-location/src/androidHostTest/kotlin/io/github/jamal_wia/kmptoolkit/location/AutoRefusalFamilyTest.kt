package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Group loc-android (ticket 5.1.0), correction round 3: the AUTO refusal family of breaker round 2 (M1,
 * M2, M3) on the edges the breaker tests do not reach. A source the platform refuses this caller for (an
 * approximate-only grant on API 24-30 is refused gps) is passed over by AUTO, remembered for the life of
 * the stream, and never turns an open "off" state into a terminal PERMISSION_MISSING (PLAN D16, D19, 4.2).
 * Virtual time, a scriptable backend, no sleeps; every case ends with the registration counters at zero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AutoRefusalFamilyTest {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)

    private fun providersOf(fake: FakeLocationBackend): List<String> =
        fake.registered().map { registration: FakeLocationBackend.Registration -> registration.provider }.sorted()

    @Test
    fun `a low power AUTO stream whose passive source refuses the caller reads SERVICE_DISABLED while the network is off`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(network, passive)).apply {
                fine = false
                coarse = true
                refuse(passive)
            }
            fake.setProviderEnabled(network, false)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch {
                newProvider(fake).observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.LOW_POWER)).collect { seen += it }
            }
            runCurrent()

            assertFalse(permissionMissing in seen, "a source that is merely off must not end the stream: $seen")
            assertTrue(job.isActive, "the stream completed: $seen")
            assertEquals(serviceDisabled, seen.last(), "nothing this app can use is on: $seen")
            assertEquals(listOf(network), providersOf(fake), "control: the network source stays registered while off")

            fake.setProviderEnabled(network, true)
            runCurrent()
            fake.deliver(network, fake.locationAt(network, 44.0))
            runCurrent()
            assertEquals(GeoCoordinates(44.0, 2.0), seen.last().fixOrNull()?.coordinates, "the network returned: $seen")

            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `an AUTO stream whose every candidate refuses the caller still ends with PERMISSION_MISSING and nothing registered`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
                refuse(gps)
                refuse(network)
            }
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch {
                newProvider(fake).observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)).collect { seen += it }
            }
            runCurrent()

            assertEquals(permissionMissing, seen.last(), "every candidate refused: the permission is what is missing: $seen")
            assertTrue(job.isCompleted, "a terminal reading completes the stream")
            assertEquals(0, fake.registeredCount, "a listener was left")
            assertEquals(0, fake.subscriberCount, "a subscription was left")
        }

    @Test
    fun `a refused rebind target is not tried again on later events`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
            fine = false
            coarse = true
            refuse(gps)
        }
        val job: Job = backgroundScope.launch {
            newProvider(fake).observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)).collect { }
        }
        runCurrent()
        fake.setProviderEnabled(network, false)
        runCurrent()
        val attemptsAfterFirstRefusal: Int = fake.registrationAttemptCount
        // Control: the refusal was met once (gps tried), the registration was put back (network).
        assertEquals(listOf(network), providersOf(fake), "control: the network registration was put back")

        repeat(10) {
            fake.setProviderEnabled(network, it % 2 == 0)
            runCurrent()
        }

        // gps is remembered as refused: the only registration traffic is none (network never moves away).
        assertEquals(attemptsAfterFirstRefusal, fake.registrationAttemptCount, "a refused source is tried again")
        assertEquals(listOf(network), providersOf(fake))
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    @Test
    fun `a platform failure of the rebind target keeps the old registration and the off decision`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            newProvider(fake).observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)).collect { seen += it }
        }
        runCurrent()
        assertEquals(listOf(network), providersOf(fake), "control: the balanced stream starts on the network")

        // An OEM failure (not a refusal) of the move target; gps is on, the network goes off.
        fake.failRegistration(gps, IllegalArgumentException("oem"))
        fake.setProviderEnabled(gps, true)
        fake.setProviderEnabled(network, false)
        runCurrent()

        assertTrue(job.isActive, "a failing move must not end the stream: $seen")
        assertEquals(listOf(network), providersOf(fake), "the old registration was put back")
        assertEquals(serviceDisabled, seen.last(), "the decision is still reached over what is registered: $seen")

        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    @Test
    fun `a one-shot call ends at once when the refusal of one source leaves only switched-off sources`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network), supportsCurrentLocation = true).apply {
                fine = false
                coarse = true
            }
            val call: Deferred<LocationReading> = async {
                newProvider(fake).getCurrentLocation(
                    CurrentLocationOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, maxAge = Duration.ZERO, timeout = 30.seconds),
                )
            }
            runCurrent()
            assertEquals(2, fake.currentCalls().size, "control: both sources asked the framework")

            // The platform now refuses gps (its answer was empty, the one-update fallback is refused); the network went off.
            fake.refuse(gps)
            fake.setProviderEnabled(network, false)
            runCurrent()
            assertFalse(call.isCompleted, "gps is still waiting and on: the call cannot know yet")

            fake.answerCurrentCall(index = 0, location = null)
            runCurrent()

            assertTrue(call.isCompleted, "after the refusal only the switched-off network is left: a known answer at once")
            assertEquals(serviceDisabled, call.await())
            assertEquals(0, fake.registeredCount, "a listener was left")
            assertEquals(0, fake.subscriberCount, "a subscription was left")
        }

    @Test
    fun `a one-shot call whose every source refuses answers PERMISSION_MISSING at once and never SERVICE_DISABLED`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
                refuse(gps)
                refuse(network)
            }
            val reading: LocationReading = newProvider(fake).getCurrentLocation(
                CurrentLocationOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, maxAge = Duration.ZERO, timeout = 30.seconds),
            )

            assertEquals(permissionMissing, reading, "an empty race (every source refused) is not a switch-off")
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }
}
