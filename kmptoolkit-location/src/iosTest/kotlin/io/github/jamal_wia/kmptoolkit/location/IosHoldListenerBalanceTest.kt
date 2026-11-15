@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLAuthorizationStatusDenied

/**
 * Own tests of group loc-ios: every did-become-active registration the hold makes is removed on every path
 * that ends the hold or the collection. Each test balances `registered` against `unregistered` (and the live
 * list against empty) at the point the hold ends and again at the end, so a leak shows as a number, and a
 * double removal shows as a number too.
 *
 * Why these exist: the breaker round-1 storm counts `unregistered` against the opted-in collections only. The hold
 * design (breaker round 3, M3) adds a second, legitimate registration per hold, which that count cannot know about;
 * these tests pin the true invariant (registrations equal removals, nothing live) per path instead.
 */
class IosHoldListenerBalanceTest {

    private class Probe {
        lateinit var job: Job
        val labels: MutableList<String> = mutableListOf()
        var completed: Boolean = false
    }

    private fun label(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix${reading.fix.coordinates.latitude.toInt()}"
        is LocationReading.Unavailable -> reading.reason.name
    }

    private val optedIn = LocationUpdatesOptions(
        platform = IosLocationUpdatesOptions(pausesLocationUpdatesAutomatically = true),
    )

    private fun TestScope.collect(
        env: IosLocationEnvironment,
        options: LocationUpdatesOptions = LocationUpdatesOptions(),
    ): Probe {
        val probe = Probe()
        val provider = IosLocationProvider(env, NoopLogger)
        probe.job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                provider.observeLocation(options).collect { reading: LocationReading -> probe.labels += label(reading) }
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

    private fun assertBalanced(env: FakeIosLocationEnvironment, registered: Int, where: String) {
        assertEquals(registered, env.registered, "$where: registrations")
        assertEquals(registered, env.unregistered, "$where: removals (one per registration)")
        assertTrue(env.activeListeners.isEmpty(), "$where: a listener is still registered")
    }

    /**
     * An environment that runs [onAdd] inside the registration call and [onRemove] inside the removal call,
     * the places where a cancellation can land in the middle of the hold's bookkeeping.
     */
    private class SeamEnvironment(
        private val inner: FakeIosLocationEnvironment,
        private val onAdd: () -> Unit,
        private val onRemove: () -> Unit,
    ) : IosLocationEnvironment by inner {
        override fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
            onAdd()
            val remove: () -> Unit = inner.addBecameActiveListener(onActive)
            return {
                onRemove()
                remove()
            }
        }
    }

    @Test
    fun `a stream cancelled while holding removes the hold listener exactly once`() = runTest {
        val env = FakeIosLocationEnvironment(testScheduler)
        val probe: Probe = collect(env)
        val manager: FakeIosLocationManager = env.managers.single()
        denial(manager)
        assertEquals(1, env.activeListeners.size, "control: holding")
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount)
        assertBalanced(env, registered = 1, where = "cancelled while holding")
    }

    @Test
    fun `a cancellation landing inside the registration call of the hold leaves no listener`() = runTest {
        val env = FakeIosLocationEnvironment(testScheduler)
        var job: Job? = null
        val seam = SeamEnvironment(env, onAdd = { job?.cancel() }, onRemove = {})
        val probe: Probe = collect(seam)
        job = probe.job
        val manager: FakeIosLocationManager = env.managers.single()
        denial(manager)
        runCurrent()
        assertTrue(probe.job.isCancelled, "control: the collection was cancelled inside the registration")
        assertEquals(1, manager.detachCount, "released once")
        assertBalanced(env, registered = 1, where = "cancelled inside the registration call")
    }

    @Test
    fun `a cancellation landing inside the removal call of an authorization callback leaves no listener and releases once`() = runTest {
        val env = FakeIosLocationEnvironment(testScheduler)
        var job: Job? = null
        val seam = SeamEnvironment(env, onAdd = {}, onRemove = { job?.cancel() })
        val probe: Probe = collect(seam)
        job = probe.job
        val manager: FakeIosLocationManager = env.managers.single()
        denial(manager)
        assertEquals(1, env.activeListeners.size, "control: holding")
        env.announceChange()
        runCurrent()
        assertTrue(probe.job.isCancelled, "control: the collection was cancelled inside the removal")
        assertEquals(1, manager.detachCount, "released once")
        assertBalanced(env, registered = 1, where = "cancelled inside the removal call")
    }

    @Test
    fun `a release in the same turn as the denial never registers a hold listener`() = runTest {
        val env = FakeIosLocationEnvironment(testScheduler)
        val probe: Probe = collect(env)
        val manager: FakeIosLocationManager = env.managers.single()
        manager.fail(IosFailureKind.DENIED)
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount)
        assertBalanced(env, registered = 0, where = "denial and cancellation in one turn")
    }

    @Test
    fun `the app becoming active and a release in the same turn remove the hold listener exactly once`() = runTest {
        val env = FakeIosLocationEnvironment(testScheduler)
        val probe: Probe = collect(env)
        val manager: FakeIosLocationManager = env.managers.single()
        denial(manager)
        assertEquals(1, env.activeListeners.size, "control: holding")
        env.activate()
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount)
        assertBalanced(env, registered = 1, where = "activation and release in one turn")
    }

    @Test
    fun `an authorization callback and a release in the same turn remove the hold listener exactly once`() = runTest {
        val env = FakeIosLocationEnvironment(testScheduler)
        val probe: Probe = collect(env)
        val manager: FakeIosLocationManager = env.managers.single()
        denial(manager)
        env.announceChange()
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount)
        assertBalanced(env, registered = 1, where = "authorization callback and release in one turn")
    }

    @Test
    fun `two activations in one turn leave the hold once and remove its listener once`() = runTest {
        val env = FakeIosLocationEnvironment(testScheduler)
        val probe: Probe = collect(env)
        val manager: FakeIosLocationManager = env.managers.single()
        denial(manager)
        env.activate()
        env.activate()
        runCurrent()
        assertEquals(2, manager.startCount, "one restart, not two")
        assertBalanced(env, registered = 1, where = "two activations")
        probe.job.cancel()
        runCurrent()
        assertBalanced(env, registered = 1, where = "two activations, after the release")
    }

    @Test
    fun `holds that follow each other register and remove one listener each`() = runTest {
        val env = FakeIosLocationEnvironment(testScheduler)
        val probe: Probe = collect(env)
        val manager: FakeIosLocationManager = env.managers.single()
        denial(manager)
        env.announceChange()
        runCurrent()
        assertBalanced(env, registered = 1, where = "first hold left by a callback")
        denial(manager)
        assertEquals(1, env.activeListeners.size, "control: the second hold")
        assertEquals(2, env.registered)
        env.activate()
        runCurrent()
        assertBalanced(env, registered = 2, where = "second hold left by an activation")
        denial(manager)
        assertEquals(1, env.activeListeners.size, "control: the third hold")
        probe.job.cancel()
        runCurrent()
        assertBalanced(env, registered = 3, where = "third hold left by the release")
        assertEquals(1, manager.detachCount)
    }

    @Test
    fun `a hold ended by a switch-off or a revoke removes its listener before the next state`() = runTest {
        val env = FakeIosLocationEnvironment(testScheduler)
        val probe: Probe = collect(env)
        val manager: FakeIosLocationManager = env.managers.single()
        denial(manager)
        env.servicesOn = false
        env.announceChange()
        runCurrent()
        assertEquals(LocationUnavailability.SERVICE_DISABLED.name, probe.labels.last())
        assertBalanced(env, registered = 1, where = "hold then switch-off")
        env.servicesOn = true
        env.announceChange()
        runCurrent()
        assertTrue(manager.updating, "control: running again")
        denial(manager)
        assertEquals(1, env.activeListeners.size, "control: a new hold after the flip")
        env.status = kCLAuthorizationStatusDenied
        env.announceChange()
        runCurrent()
        assertTrue(probe.completed, "control: PERMISSION_MISSING ended the stream")
        assertEquals(1, manager.detachCount)
        assertBalanced(env, registered = 2, where = "second hold then revoke")
    }

    @Test
    fun `a denial that meets a switch-off before the re-read never leaves a hold listener behind`() = runTest {
        val env = FakeIosLocationEnvironment(testScheduler)
        val probe: Probe = collect(env)
        val manager: FakeIosLocationManager = env.managers.single()
        env.servicesOn = false
        manager.fail(IosFailureKind.DENIED)
        env.announceChange()
        runCurrent()
        assertEquals(LocationUnavailability.SERVICE_DISABLED.name, probe.labels.last())
        assertEquals(0, env.registered, "the re-read said off: no hold, no listener")
        env.servicesOn = true
        env.announceChange()
        runCurrent()
        assertTrue(manager.updating, "control: running again, the pending denial did not turn into a hold")
        assertEquals(0, env.registered, "a stale denial does not hold later")
        probe.job.cancel()
        runCurrent()
        assertBalanced(env, registered = 0, where = "denial then switch-off")
    }

    @Test
    fun `the opted-in restart listener and the hold listener are two registrations removed once each`() = runTest {
        val env = FakeIosLocationEnvironment(testScheduler)
        val probe: Probe = collect(env, optedIn)
        val manager: FakeIosLocationManager = env.managers.single()
        assertEquals(1, env.activeListeners.size, "control: the opt-in listener")
        denial(manager)
        assertEquals(2, env.activeListeners.size, "the hold adds its own listener to the opt-in one")
        env.activate()
        runCurrent()
        assertEquals(1, env.activeListeners.size, "only the hold listener left with the hold")
        assertEquals(1, env.unregistered)
        denial(manager)
        assertEquals(2, env.activeListeners.size, "a second hold, a second listener")
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount)
        assertBalanced(env, registered = 3, where = "opted-in collection with two holds")
    }

    @Test
    fun `a collection that never holds registers nothing without the opt-in and one listener with it`() = runTest {
        val plain = FakeIosLocationEnvironment(testScheduler)
        val plainProbe: Probe = collect(plain)
        plain.managers.single().deliver(rawFix(plain.clock, latitude = 2.0))
        runCurrent()
        assertFalse(plainProbe.completed)
        plainProbe.job.cancel()
        runCurrent()
        assertBalanced(plain, registered = 0, where = "no opt-in, no hold")

        val opted = FakeIosLocationEnvironment(testScheduler)
        val optedProbe: Probe = collect(opted, optedIn)
        optedProbe.job.cancel()
        runCurrent()
        assertBalanced(opted, registered = 1, where = "opt-in, no hold")
    }
}
