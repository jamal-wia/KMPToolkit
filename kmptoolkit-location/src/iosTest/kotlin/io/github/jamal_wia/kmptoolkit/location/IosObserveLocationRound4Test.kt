@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLAuthorizationStatusDenied

/**
 * Own tests of group loc-ios for the two code changes of breaker round 2 (M1, M2), next to the breaker
 * classes (which stay as they are): the trailing conflation of the stream must never skip or strand the
 * terminal reading, and the bound on the restarts after a denial must be left by exactly the events
 * that prove the platform serves the stream again. Virtual time only; no randomness.
 */
class IosObserveLocationRound4Test {

    private class Probe {
        lateinit var job: Job
        val labels: MutableList<String> = mutableListOf()
        var completed: Boolean = false
    }

    private fun label(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix${reading.fix.coordinates.latitude.toInt()}"
        is LocationReading.Unavailable -> reading.reason.name
    }

    private fun TestScope.fixture(): Pair<FakeIosLocationEnvironment, IosLocationProvider> {
        val env = FakeIosLocationEnvironment(testScheduler)
        return env to IosLocationProvider(env, NoopLogger)
    }

    private fun TestScope.collect(provider: IosLocationProvider, gate: CompletableDeferred<Unit>? = null): Probe {
        val probe = Probe()
        probe.job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                provider.observeLocation().collect { reading: LocationReading ->
                    probe.labels += label(reading)
                    if (probe.labels.size == 1) gate?.await()
                }
                probe.completed = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
        runCurrent()
        return probe
    }

    private fun TestScope.denial(manager: FakeIosLocationManager) {
        manager.fail(IosFailureKind.DENIED)
        runCurrent()
    }

    // ---------------------------------------------------------------------------------------------
    // M1: the conflation after the completion

    /**
     * The trailing `conflate()` sits behind the completion on purpose: the terminal reading is the last
     * element of the flow, so a busy collector must still receive it and complete, and the platform
     * registration must be released while that collector is still busy (no wait for the collector).
     */
    @Test
    fun `a terminal reading emitted while the collector is busy is not skipped and releases the registration at once`() = runTest {
        val (env, provider) = fixture()
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val probe: Probe = collect(provider, gate)
        val manager: FakeIosLocationManager = env.managers.single()
        assertEquals(listOf("NO_FIX"), probe.labels, "control: the collector is busy with the seed")
        for (latitude: Int in 10..14) {
            manager.deliver(rawFix(env.clock, latitude = latitude.toDouble()))
            runCurrent()
        }
        env.status = kCLAuthorizationStatusDenied
        env.announceChange()
        runCurrent()
        assertEquals(1, manager.detachCount, "the registration is released while the collector is still busy")
        assertEquals(listOf("NO_FIX"), probe.labels, "control: the busy collector has seen nothing more yet")
        assertFalse(probe.completed, "control: the collector has not finished")
        gate.complete(Unit)
        runCurrent()
        assertEquals(LocationUnavailability.PERMISSION_MISSING.name, probe.labels.last(), "the terminal reading is delivered: ${probe.labels}")
        assertTrue(probe.completed, "the collection ends after the terminal reading")
        assertTrue(probe.labels.size <= 3, "the busy collector skipped the intermediate fixes: ${probe.labels}")
        assertEquals(1, manager.detachCount, "released exactly once")
    }

    // ---------------------------------------------------------------------------------------------
    // M2: the restarts after a denial are bounded

    @Test
    fun `denials beyond the restart bound hold the updates stopped with one NO_FIX until the authorization changes`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        repeat(3) { denial(manager) }
        assertEquals(4, manager.startCount, "control: each of the first three denials restarted the updates")
        assertTrue(manager.updating)
        denial(manager)
        assertEquals(4, manager.startCount, "the fourth denial in a row does not restart")
        assertFalse(manager.updating, "the updates stay stopped")
        assertEquals(LocationUnavailability.NO_FIX.name, probe.labels.last())
        val sizeAtHold: Int = probe.labels.size
        repeat(5) { denial(manager) }
        assertEquals(sizeAtHold, probe.labels.size, "a further denial in the hold emits nothing")
        assertEquals(4, manager.startCount)
        env.announceChange()
        runCurrent()
        assertEquals(5, manager.startCount, "an authorization callback leaves the hold and restarts")
        assertTrue(manager.updating)
        manager.deliver(rawFix(env.clock, latitude = 7.0))
        runCurrent()
        assertEquals("fix7", probe.labels.last())
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount)
    }

    @Test
    fun `a live fix and an authorization callback and a transient failure each start the restart bound again`() = runTest {
        val resets: List<Pair<String, (FakeIosLocationEnvironment, FakeIosLocationManager) -> Unit>> = listOf(
            "a live fix" to { env, manager -> manager.deliver(rawFix(env.clock, latitude = 3.0)) },
            "an authorization callback" to { env, _ -> env.announceChange() },
            "a transient failure" to { _, manager -> manager.fail(IosFailureKind.LOCATION_UNKNOWN) },
        )
        for ((name: String, reset: (FakeIosLocationEnvironment, FakeIosLocationManager) -> Unit) in resets) {
            val (env, provider) = fixture()
            val probe: Probe = collect(provider)
            val manager: FakeIosLocationManager = env.managers.single()
            repeat(3) { denial(manager) }
            reset(env, manager)
            runCurrent()
            repeat(3) { denial(manager) }
            assertEquals(7, manager.startCount, "$name: three more denials restart again")
            assertTrue(manager.updating, "$name: the updates run")
            probe.job.cancel()
            runCurrent()
            assertEquals(1, manager.detachCount, "$name: released once")
        }
    }

    @Test
    fun `a revoke during the hold ends the stream and releases the manager`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        repeat(4) { denial(manager) }
        assertFalse(manager.updating, "control: the hold is in effect")
        env.status = kCLAuthorizationStatusDenied
        env.announceChange()
        runCurrent()
        assertEquals(LocationUnavailability.PERMISSION_MISSING.name, probe.labels.last())
        assertTrue(probe.completed)
        assertEquals(1, manager.detachCount)
    }
}
