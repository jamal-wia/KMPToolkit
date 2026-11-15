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
 * Own tests of group loc-ios for the code changes of breaker rounds 2 and 3 (M1 conflation, M2 hold), next to the breaker
 * classes (which stay as they are): the trailing conflation of the stream must never skip or strand the
 * terminal reading, and the hold after a denial (breaker round 3: no restart bound, no reset by a fix or a
 * transient failure) must be left by exactly the events that are new evidence: an authorization callback
 * or the app becoming active. Virtual time only; no randomness.
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
    // M2: a denial under a granted status holds the stream behind one NO_FIX (no restart loop)

    @Test
    fun `a denial of a running stream holds the updates stopped with one NO_FIX until the authorization changes`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        assertEquals(1, manager.startCount, "control: one start")
        assertTrue(env.activeListeners.isEmpty(), "control: no listener while the stream runs")
        denial(manager)
        assertEquals(1, manager.startCount, "the first denial does not restart")
        assertFalse(manager.updating, "the updates stay stopped")
        assertEquals(LocationUnavailability.NO_FIX.name, probe.labels.last())
        assertEquals(1, env.activeListeners.size, "the hold listens for the app becoming active")
        val sizeAtHold: Int = probe.labels.size
        repeat(5) { denial(manager) }
        assertEquals(sizeAtHold, probe.labels.size, "a further denial in the hold emits nothing")
        assertEquals(1, manager.startCount, "a further denial in the hold does not restart")
        env.announceChange()
        runCurrent()
        assertEquals(2, manager.startCount, "an authorization callback leaves the hold and restarts")
        assertTrue(manager.updating)
        assertTrue(env.activeListeners.isEmpty(), "the hold's listener is gone with the hold")
        manager.deliver(rawFix(env.clock, latitude = 7.0))
        runCurrent()
        assertEquals("fix7", probe.labels.last())
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount)
    }

    @Test
    fun `the app becoming active leaves the hold without an authorization callback and only while holding`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        env.activate()
        runCurrent()
        assertEquals(1, manager.startCount, "an activation while the stream runs restarts nothing (no opt-in listener)")
        denial(manager)
        assertFalse(manager.updating, "control: holding")
        assertEquals(1, env.activeListeners.size, "control: the hold listens")
        env.activate()
        runCurrent()
        assertEquals(2, manager.startCount, "the activation leaves the hold and restarts")
        assertTrue(manager.updating)
        assertTrue(env.activeListeners.isEmpty(), "the listener is removed when the hold ends")
        assertEquals(1, env.unregistered, "removed exactly once")
        env.activate()
        runCurrent()
        assertEquals(2, manager.startCount, "a later activation does nothing")
        manager.deliver(rawFix(env.clock, latitude = 4.0))
        runCurrent()
        assertEquals("fix4", probe.labels.last())
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount)
        assertTrue(env.activeListeners.isEmpty())
    }

    @Test
    fun `a live fix and a transient failure are no evidence and do not leave the hold`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        denial(manager)
        assertFalse(manager.updating, "control: holding")
        val sizeAtHold: Int = probe.labels.size
        manager.deliver(rawFix(env.clock, latitude = 3.0))
        runCurrent()
        manager.fail(IosFailureKind.LOCATION_UNKNOWN)
        runCurrent()
        denial(manager)
        assertEquals(1, manager.startCount, "nothing but an authorization callback or an activation restarts")
        assertFalse(manager.updating)
        assertEquals(sizeAtHold, probe.labels.size, "nothing is emitted inside the hold: ${probe.labels}")
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount, "released once")
        assertTrue(env.activeListeners.isEmpty(), "no listener survives the release")
    }

    @Test
    fun `a revoke during the hold ends the stream and releases the manager and the hold listener`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        denial(manager)
        assertFalse(manager.updating, "control: the hold is in effect")
        assertEquals(1, env.activeListeners.size, "control: the hold listens")
        env.status = kCLAuthorizationStatusDenied
        env.announceChange()
        runCurrent()
        assertEquals(LocationUnavailability.PERMISSION_MISSING.name, probe.labels.last())
        assertTrue(probe.completed)
        assertEquals(1, manager.detachCount)
        assertTrue(env.activeListeners.isEmpty(), "the hold listener is released")
    }

    @Test
    fun `a switch-off during the hold says SERVICE_DISABLED and switching on restarts the updates`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        denial(manager)
        assertFalse(manager.updating, "control: the hold is in effect")
        env.servicesOn = false
        env.announceChange()
        runCurrent()
        assertEquals(LocationUnavailability.SERVICE_DISABLED.name, probe.labels.last())
        assertTrue(env.activeListeners.isEmpty(), "the hold is over, so is its listener")
        env.servicesOn = true
        env.announceChange()
        runCurrent()
        assertTrue(manager.updating, "switching on restarts the stream")
        assertEquals(2, manager.startCount)
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount)
    }
}
