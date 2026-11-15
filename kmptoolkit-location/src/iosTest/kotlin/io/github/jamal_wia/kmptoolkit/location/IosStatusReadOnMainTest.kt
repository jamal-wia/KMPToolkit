@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined

/**
 * Group loc-ios, breaker round 1, finding m4: the authorization status read creates a
 * `CLLocationManager` too, so it must run on the main queue like every other manager access (the KDoc of
 * `createLocationProvider` promises it). The fake environment counts reads made outside a task of its main
 * dispatcher; a read on the calling thread would leave that counter above zero.
 */
class IosStatusReadOnMainTest {

    private fun TestScope.fixture(): Pair<FakeIosLocationEnvironment, IosLocationProvider> {
        val env = FakeIosLocationEnvironment(testScheduler)
        return env to IosLocationProvider(env, NoopLogger)
    }

    @Test
    fun `getCurrentLocation reads the authorization status on the main queue in every state`() = runTest {
        val (env, provider) = fixture()
        for (status: Int in listOf(
            kCLAuthorizationStatusNotDetermined,
            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusAuthorizedWhenInUse,
        )) {
            env.status = status
            val before: Int = env.statusReads
            val call: Deferred<LocationReading> = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { provider.getCurrentLocation() }
            runCurrent()
            if (status == kCLAuthorizationStatusAuthorizedWhenInUse) call.cancel() else assertIs<LocationReading.Unavailable>(call.await())
            runCurrent()
            assertTrue(env.statusReads > before, "control: status $status was read")
        }
        assertEquals(0, env.statusReadsOffMain, "the status read created a CLLocationManager off the main queue")
    }

    @Test
    fun `observeLocation reads the authorization status on the main queue on its first check and on every re-read`() = runTest {
        val (env, provider) = fixture()
        val job: Job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { provider.observeLocation().collect { } }
        runCurrent()
        val afterStart: Int = env.statusReads
        assertTrue(afterStart > 0, "control: the first check read the status")
        env.servicesOn = false
        env.announceChange()
        runCurrent()
        assertTrue(env.statusReads > afterStart, "control: the re-read read the status again")
        job.cancel()
        runCurrent()
        assertEquals(0, env.statusReadsOffMain, "the status read created a CLLocationManager off the main queue")
    }

    @Test
    fun `an unauthorized stream answers without a manager and still reads the status on the main queue`() = runTest {
        val (env, provider) = fixture()
        env.status = kCLAuthorizationStatusDenied
        env.servicesOn = true
        val readings: MutableList<LocationReading> = mutableListOf()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { provider.observeLocation().collect { reading: LocationReading -> readings += reading } }
        runCurrent()
        assertEquals(listOf<LocationReading>(LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)), readings)
        assertTrue(env.managers.isEmpty(), "no manager for an unauthorized stream")
        assertTrue(env.statusReads > 0, "control: the status was read")
        assertEquals(0, env.statusReadsOffMain, "the status read created a CLLocationManager off the main queue")
    }
}
