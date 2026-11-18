@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 4 of group loc-ios (ticket 5.1.0, LOC-8 / LOC-9): attacks on the HOLD design of loc-ios
 * round 5 (a running stream that gets `kCLErrorDenied` under a granted status stops, says NO_FIX once and
 * holds until the authorization changes or the app becomes active). Derived from PLAN 4.2 (NO_FIX = "all
 * on, no fix"), D8 (the stream stays open through NO_FIX), D20 (a NO_FIX that silently never recovers is
 * the defect; `didBecomeActive` is the provider's restart signal) and the hold's KDoc in
 * LocationProvider.ios.kt ("Only new evidence leaves the hold: an authorization callback or the app
 * becoming active").
 *
 * Everything runs through the seams on virtual time; events are delivered on the main dispatcher as
 * CoreLocation and NSNotificationCenter (main queue) do. No randomness.
 */
class IosLocationProviderBreakerR4Test {

    /**
     * The real seam with one hook: [duringServicesRead] runs inside the off-main services read, i.e. while
     * a re-read is between the event that asked for it and its main-queue landing.
     */
    private class WindowEnvironment(val fake: FakeIosLocationEnvironment) : IosLocationEnvironment by fake {
        var duringServicesRead: (() -> Unit)? = null

        override fun locationServicesEnabled(): Boolean {
            val hook: (() -> Unit)? = duringServicesRead
            duringServicesRead = null
            hook?.invoke()
            return fake.locationServicesEnabled()
        }
    }

    private class Probe {
        lateinit var job: Job
        val labels: MutableList<String> = mutableListOf()
        var completed: Boolean = false
    }

    private fun label(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix${reading.fix.coordinates.latitude.toInt()}"
        is LocationReading.Unavailable -> reading.reason.name
    }

    private fun options(optIn: Boolean): LocationUpdatesOptions =
        LocationUpdatesOptions(platform = IosLocationUpdatesOptions(pausesLocationUpdatesAutomatically = optIn))

    private fun TestScope.collect(env: IosLocationEnvironment, options: LocationUpdatesOptions): Probe {
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

    /** CoreLocation / the notification center call on the main queue: run [block] there. */
    private fun TestScope.onMain(fake: FakeIosLocationEnvironment, block: () -> Unit) {
        backgroundScope.launch(fake.mainDispatcher) { block() }
        runCurrent()
    }

    private fun CoroutineScope.later(fake: FakeIosLocationEnvironment, block: () -> Unit) {
        launch(fake.mainDispatcher) { block() }
    }

    // ---------------------------------------------------------------------------------------------
    // Lost wake-up: the activation arrives after the denial but before the re-read lands

    /**
     * Defect class: lost wake-up (check-then-act across a queue hop). The hold's own trigger, "updates were
     * started while the app could not run them", is exactly the moment the app is about to become active:
     * CoreLocation answers the start with `kCLErrorDenied` and `UIApplicationDidBecomeActiveNotification`
     * follows on the main queue. The denial only marks the stream (`denialPending`) and asks for a re-read;
     * the re-read reads the switch off main and lands on main later, and only THEN registers the
     * did-become-active listener (`hold()`). An activation posted in between finds no listener, so the
     * stream holds behind NO_FIX although the app became active after the denial: with the status unchanged
     * no authorization callback ever comes, and the stream stays at NO_FIX until the next foreground.
     * An authorization callback in the very same window is honoured (it clears `denialPending`), which makes
     * the asymmetry visible: the control below is the identical script with a callback and is green.
     * Two windows: the activation in the same main turn right after the denial, and the activation while
     * the re-read is reading the switch off main. Both with and without the auto-pause opt-in (its restart
     * listener acts only on READY + paused, so it does not help).
     * Mutation by reading: registering the hold listener when the denial marks the stream (or remembering
     * an activation since the denial) and letting it clear `denialPending` turns it green.
     */
    @Test
    fun `an activation between a denial and its re-read is not lost`() = runTest {
        val lost: MutableList<String> = mutableListOf()
        for (optIn: Boolean in listOf(false, true)) {
            for (window: String in listOf("same turn as the denial", "during the off-main re-read")) {
                val fake = FakeIosLocationEnvironment(testScheduler)
                val env = WindowEnvironment(fake)
                val probe: Probe = collect(env, options(optIn))
                val manager: FakeIosLocationManager = fake.managers.single()
                onMain(fake) { manager.deliver(rawFix(fake.clock, latitude = 5.0)) }
                val where = "optIn=$optIn window=$window"
                assertEquals("fix5", probe.labels.last(), "$where: control: running with a fix: ${probe.labels}")
                assertEquals(1, manager.startCount, "$where: control: one start")
                when (window) {
                    "same turn as the denial" -> onMain(fake) {
                        manager.fail(IosFailureKind.DENIED)
                        fake.activate()
                    }
                    else -> {
                        env.duringServicesRead = { backgroundScope.later(fake) { fake.activate() } }
                        onMain(fake) { manager.fail(IosFailureKind.DENIED) }
                    }
                }
                runCurrent()
                if (!manager.updating) {
                    lost += "$where: held (starts=${manager.startCount}, live listeners=${fake.activeListeners.size}): ${probe.labels}"
                }
                probe.job.cancel()
                runCurrent()
                assertEquals(1, manager.detachCount, "$where: released once")
                assertTrue(fake.activeListeners.isEmpty(), "$where: no listener left")
                assertEquals(fake.registered, fake.unregistered, "$where: every registration removed")
            }
        }
        assertTrue(lost.isEmpty(), "the app became active after the denial, so the stream must not hold: $lost")
    }

    /**
     * Control for the test above (expected green on the current code): the same two windows with an
     * authorization callback instead of an activation. The callback clears the pending denial, so the
     * re-read restarts. The hold KDoc names both events as equal evidence.
     */
    @Test
    fun `an authorization callback between a denial and its re-read restarts the stream`() = runTest {
        for (window: String in listOf("same turn as the denial", "during the off-main re-read")) {
            val fake = FakeIosLocationEnvironment(testScheduler)
            val env = WindowEnvironment(fake)
            val probe: Probe = collect(env, options(false))
            val manager: FakeIosLocationManager = fake.managers.single()
            onMain(fake) { manager.deliver(rawFix(fake.clock, latitude = 5.0)) }
            when (window) {
                "same turn as the denial" -> onMain(fake) {
                    manager.fail(IosFailureKind.DENIED)
                    manager.authorizationChanged()
                }
                else -> {
                    env.duringServicesRead = { backgroundScope.later(fake) { manager.authorizationChanged() } }
                    onMain(fake) { manager.fail(IosFailureKind.DENIED) }
                }
            }
            runCurrent()
            assertTrue(manager.updating, "window=$window: restarted after the callback: ${probe.labels}")
            assertEquals(2, manager.startCount, "window=$window: exactly one restart")
            probe.job.cancel()
            runCurrent()
            assertTrue(fake.activeListeners.isEmpty(), "window=$window: no listener left")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Hold with several collectors and several events

    /**
     * Defect classes: cross-talk and leak between collections. Two collections on one provider are held at
     * the same time (each has its own manager and its own hold listener). One activation restarts both, each
     * removes exactly its own listener; cancelling the first leaves the second running and its manager
     * untouched; cancelling the second leaves nothing.
     * Mutation by reading: a hold listener shared across sessions (or stored outside the session) turns it red.
     */
    @Test
    fun `two held collections recover on one activation and each removes only its own listener`() = runTest {
        val fake = FakeIosLocationEnvironment(testScheduler)
        val provider = IosLocationProvider(fake, NoopLogger)
        val probes: List<Probe> = List(2) {
            val probe = Probe()
            probe.job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                provider.observeLocation(LocationUpdatesOptions()).collect { reading: LocationReading -> probe.labels += label(reading) }
            }
            runCurrent()
            probe
        }
        val managers: List<FakeIosLocationManager> = fake.managers.toList()
        assertEquals(2, managers.size, "control: one manager per collection")
        managers.forEach { manager: FakeIosLocationManager -> onMain(fake) { manager.fail(IosFailureKind.DENIED) } }
        runCurrent()
        assertTrue(managers.none { it.updating }, "control: both held")
        assertEquals(2, fake.activeListeners.size, "control: one hold listener per collection")
        onMain(fake) { fake.activate() }
        assertTrue(managers.all { it.updating }, "one activation restarts both: ${probes.map { it.labels }}")
        assertEquals(listOf(2, 2), managers.map { it.startCount }, "exactly one restart each")
        assertTrue(fake.activeListeners.isEmpty(), "both hold listeners removed")
        onMain(fake) { managers[1].fail(IosFailureKind.DENIED) }
        assertEquals(1, fake.activeListeners.size, "control: the second holds again")
        probes[0].job.cancel()
        runCurrent()
        assertEquals(1, managers[0].detachCount, "the first is released once")
        assertEquals(0, managers[1].detachCount, "the second is untouched by the first's release")
        assertEquals(1, fake.activeListeners.size, "the first's release keeps the second's hold listener")
        probes[1].job.cancel()
        runCurrent()
        assertEquals(1, managers[1].detachCount, "the second is released once")
        assertTrue(fake.activeListeners.isEmpty(), "nothing left")
        assertEquals(fake.registered, fake.unregistered, "every registration removed")
    }

    /**
     * Defect class: double restart / double removal. While holding, an activation and an authorization
     * callback arrive in one main turn, in both orders, with and without the opt-in (whose restart listener
     * also fires): the stream restarts exactly once, every listener is removed exactly once.
     */
    @Test
    fun `an activation and an authorization callback in one turn while holding restart exactly once`() = runTest {
        for (optIn: Boolean in listOf(false, true)) {
            for (activationFirst: Boolean in listOf(true, false)) {
                val where = "optIn=$optIn activationFirst=$activationFirst"
                val fake = FakeIosLocationEnvironment(testScheduler)
                val probe: Probe = collect(fake, options(optIn))
                val manager: FakeIosLocationManager = fake.managers.single()
                onMain(fake) { manager.fail(IosFailureKind.DENIED) }
                runCurrent()
                assertFalse(manager.updating, "$where: control: holding")
                assertEquals("NO_FIX", probe.labels.last(), "$where: control: the hold said NO_FIX")
                onMain(fake) {
                    if (activationFirst) {
                        fake.activate()
                        manager.authorizationChanged()
                    } else {
                        manager.authorizationChanged()
                        fake.activate()
                    }
                }
                runCurrent()
                assertTrue(manager.updating, "$where: restarted: ${probe.labels}")
                assertEquals(2, manager.startCount, "$where: exactly one restart")
                assertEquals(if (optIn) 1 else 0, fake.activeListeners.size, "$where: only the opt-in listener stays")
                probe.job.cancel()
                runCurrent()
                assertTrue(fake.activeListeners.isEmpty(), "$where: no listener left")
                assertEquals(fake.registered, fake.unregistered, "$where: every registration removed once")
                assertEquals(1, manager.detachCount, "$where: released once")
            }
        }
    }

    /**
     * Defect classes: work after release, cancellation in the middle of leaving the hold. The activation
     * leaves the hold (its listener is removed at once) and asks for a re-read; the collector is cancelled
     * while that re-read reads the switch off main. The release must win: no start after the release, the
     * manager released once on main, no listener removed twice.
     */
    @Test
    fun `a cancellation inside the re-read that an activation started leaves the hold without a restart`() = runTest {
        val fake = FakeIosLocationEnvironment(testScheduler)
        val env = WindowEnvironment(fake)
        val probe: Probe = collect(env, options(false))
        val manager: FakeIosLocationManager = fake.managers.single()
        onMain(fake) { manager.fail(IosFailureKind.DENIED) }
        runCurrent()
        assertFalse(manager.updating, "control: holding")
        assertEquals(1, fake.activeListeners.size, "control: the hold listener exists")
        env.duringServicesRead = { probe.job.cancel() }
        onMain(fake) { fake.activate() }
        runCurrent()
        assertEquals(1, manager.startCount, "no restart after the collector was cancelled")
        assertFalse(manager.updating, "the released manager does not run")
        assertEquals(1, manager.detachCount, "released once")
        assertEquals(true, manager.detachedOnMain, "released on main")
        assertTrue(fake.activeListeners.isEmpty(), "no listener left")
        assertEquals(1, fake.registered, "control: one registration (the hold)")
        assertEquals(1, fake.unregistered, "removed exactly once")
        assertFalse(probe.completed, "a cancelled collection does not complete normally")
    }
}
