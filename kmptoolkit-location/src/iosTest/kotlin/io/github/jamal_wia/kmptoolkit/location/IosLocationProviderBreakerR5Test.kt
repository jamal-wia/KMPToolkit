@file:OptIn(
    ExperimentalCoroutinesApi::class,
    InternalCoroutinesApi::class,
    NativeRuntimeApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.coroutines.CoroutineContext
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied

/**
 * Breaker round 5 of group loc-ios (ticket 5.1.0, LOC-8 / LOC-9). Attacks the fix of round 7 (d16082dc): the
 * hold's did-become-active listener is registered when a `kCLErrorDenied` marks a running stream, before the
 * re-read decides, and an activation in that window counts as new evidence. Derived from the hold KDoc in
 * LocationProvider.ios.kt ("Only new evidence leaves the hold: an authorization callback or the app becoming
 * active"), PLAN 4.2 / D8 / D20 (NO_FIX means "all on, no fix"; a NO_FIX that never recovers is the defect),
 * D-12 (main queue only, strong delegate + nil before drop, every listener removed exactly once) and the
 * provider KDoc ("When a collection ends or is cancelled the manager is already stopped and released").
 *
 * Every listener is tracked per registration ([LedgerEnvironment]): who registered it, whether on main, how
 * often its removal ran, whether off main, and whether its manager was already detached. Virtual time only;
 * the storm is seeded and prints `seed=` in every message.
 */
class IosLocationProviderBreakerR5Test {

    // ---------------------------------------------------------------------------------------------
    // Seams

    /**
     * `Dispatchers.Main.immediate` on the fake main queue: no dispatch is needed while a main task already runs,
     * so a coroutine resumed from main code runs in place, inside the caller's stack. That is what the real
     * provider uses ([SystemIosLocationEnvironment.mainDispatcher]) and what a UI collector usually runs on.
     */
    private class ImmediateMain(private val main: TrackedDispatcher) : CoroutineDispatcher(), Delay by main {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !main.isRunning

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            main.dispatch(context, block)
        }
    }

    /** One did-become-active registration and what happened to it. */
    private class Entry(val id: Int, val owner: FakeIosLocationManager?, val kind: String, val onMain: Boolean) {
        var removals: Int = 0
        var removedOffMain: Int = 0
        var removedAfterDetach: Boolean = false
        val live: Boolean get() = removals == 0

        override fun toString(): String =
            "#$id $kind owner=${owner?.let { "m${it.hashCode() % 1000}" }} onMain=$onMain removals=$removals offMain=$removedOffMain afterDetach=$removedAfterDetach"
    }

    private class LedgerEnvironment(val fake: FakeIosLocationEnvironment, immediate: Boolean = false) : IosLocationEnvironment by fake {
        override val mainDispatcher: CoroutineDispatcher = if (immediate) ImmediateMain(fake.main) else fake.main
        val ledger: MutableList<Entry> = mutableListOf()

        /** The manager whose delegate event is being delivered (set by the tests), the owner of a registration made inside it. */
        var deliveringTo: FakeIosLocationManager? = null

        /** Runs once, inside the next off-main services read: the window between an event and its re-read's landing. */
        var duringServicesRead: (() -> Unit)? = null
        var failNextServicesRead: Boolean = false

        /** Called at every seam call of interest ("services", "add", "remove"): a cancellation can be armed there. */
        var onSeam: ((String) -> Unit)? = null

        /** Hands each new listener closure to a test, which must keep it only weakly (or on purpose strongly). */
        var onRegistered: ((() -> Unit) -> Unit)? = null

        override fun locationServicesEnabled(): Boolean {
            onSeam?.invoke("services")
            val hook: (() -> Unit)? = duringServicesRead
            duringServicesRead = null
            hook?.invoke()
            if (failNextServicesRead) {
                failNextServicesRead = false
                throw IllegalStateException("scripted failure of the services read")
            }
            return fake.locationServicesEnabled()
        }

        override fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
            val owner: FakeIosLocationManager? = deliveringTo ?: fake.liveManagers.lastOrNull()
            val kind: String = when {
                owner == null -> "orphan"
                owner.startCount == 0 -> "opt-in"
                else -> "hold"
            }
            val entry = Entry(ledger.size, owner, kind, fake.main.isRunning)
            ledger += entry
            onRegistered?.invoke(onActive)
            onSeam?.invoke("add")
            val remove: () -> Unit = fake.addBecameActiveListener(onActive)
            return {
                onSeam?.invoke("remove")
                entry.removals++
                if (!fake.main.isRunning) entry.removedOffMain++
                if (entry.owner?.detached == true) entry.removedAfterDetach = true
                remove()
            }
        }

        fun holds(owner: FakeIosLocationManager): List<Entry> = ledger.filter { it.owner === owner && it.kind == "hold" }

        fun liveHolds(owner: FakeIosLocationManager): List<Entry> = holds(owner).filter { it.live }

        fun deny(manager: FakeIosLocationManager) {
            deliveringTo = manager
            manager.fail(IosFailureKind.DENIED)
            deliveringTo = null
        }

        /** Per-registration problems: orphan, off-main registration or removal, double removal, removal after detach. */
        fun ledgerProblems(): List<String> = ledger.mapNotNull { entry: Entry ->
            when {
                entry.kind == "orphan" -> "registered without a live manager: $entry"
                !entry.onMain -> "registered off main: $entry"
                entry.removals > 1 -> "removed twice: $entry"
                entry.removedOffMain > 0 -> "removed off main: $entry"
                entry.removedAfterDetach -> "removed after its manager was detached (nil before drop broken): $entry"
                else -> null
            }
        }

        /** At the end of a run: every registration removed exactly once and the fake's totals agree. */
        fun finalProblems(): List<String> {
            val problems: MutableList<String> = ledgerProblems().toMutableList()
            ledger.filter { it.removals != 1 }.forEach { problems += "not removed exactly once: $it" }
            if (fake.activeListeners.isNotEmpty()) problems += "${fake.activeListeners.size} listeners still registered"
            if (fake.registered != fake.unregistered) problems += "registered ${fake.registered} vs unregistered ${fake.unregistered}"
            if (ledger.size != fake.registered) problems += "control: ledger ${ledger.size} vs fake ${fake.registered}"
            fake.managers.filter { it.detachCount != 1 }.forEach { problems += "a manager detached ${it.detachCount} times" }
            fake.managers.filter { it.detachedOnMain != true }.forEach { _ -> problems += "a manager detached off main" }
            fake.managers.filter { it.updating }.forEach { _ -> problems += "a released manager is updating" }
            return problems
        }
    }

    private class Probe {
        lateinit var job: Job
        val labels: MutableList<String> = mutableListOf()
        var completed: Boolean = false
        var failure: Throwable? = null
    }

    private fun label(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix${reading.fix.coordinates.latitude.toInt()}"
        is LocationReading.Unavailable -> reading.reason.name
    }

    private fun options(optIn: Boolean): LocationUpdatesOptions =
        LocationUpdatesOptions(platform = IosLocationUpdatesOptions(pausesLocationUpdatesAutomatically = optIn))

    private fun TestScope.collect(
        provider: IosLocationProvider,
        options: LocationUpdatesOptions,
        context: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
    ): Probe {
        val probe = Probe()
        probe.job = backgroundScope.launch(context, start = CoroutineStart.UNDISPATCHED) {
            try {
                provider.observeLocation(options).collect { reading: LocationReading -> probe.labels += label(reading) }
                probe.completed = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                probe.failure = failure
            }
        }
        runCurrent()
        return probe
    }

    /** CoreLocation and the notification center call on the main queue: one main task running [block]. */
    private fun TestScope.onMain(fake: FakeIosLocationEnvironment, block: () -> Unit) {
        backgroundScope.launch(fake.mainDispatcher) { block() }
        runCurrent()
    }

    private fun TestScope.laterOnMain(fake: FakeIosLocationEnvironment, block: () -> Unit) {
        backgroundScope.launch(fake.mainDispatcher) { block() }
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Every path that ends a pending denial removes the listener the denial registered

    private class Ctx(val scope: TestScope, val fake: FakeIosLocationEnvironment, val env: LedgerEnvironment, val probe: Probe, val manager: FakeIosLocationManager)

    /**
     * One path out of the pending denial. [inWindow] is what happens between the denial and its re-read's landing
     * (run in the same main turn as the denial, or as a main task queued while the re-read reads the switch off
     * main); null means the path is the re-read itself ([setUp] arranges it). [expect] returns the problems of the
     * state after the path, before the collection is cancelled.
     */
    private class Path(
        val name: String,
        val setUp: (Ctx) -> Unit = {},
        val inWindow: ((Ctx) -> Unit)?,
        val expect: (Ctx) -> List<String>,
    )

    private fun restarted(c: Ctx): List<String> = buildList {
        if (!c.manager.updating) add("not restarted: ${c.probe.labels}")
        if (c.manager.startCount != 2) add("starts=${c.manager.startCount}, want exactly one restart")
        if (c.env.liveHolds(c.manager).isNotEmpty()) add("a hold listener is live after the restart: ${c.env.ledger}")
    }

    private fun held(c: Ctx): List<String> = buildList {
        if (c.manager.updating) add("updates run, want the hold")
        if (c.probe.labels.lastOrNull() != "NO_FIX") add("last reading ${c.probe.labels.lastOrNull()}, want NO_FIX: ${c.probe.labels}")
        if (c.env.liveHolds(c.manager).size != 1) add("live hold listeners ${c.env.liveHolds(c.manager)}, want exactly one")
        if (c.env.holds(c.manager).size != 1) add("hold registrations ${c.env.holds(c.manager)}, want one for the whole hold")
    }

    private fun released(c: Ctx): List<String> = buildList {
        if (c.manager.detachCount != 1) add("detached ${c.manager.detachCount} times")
        if (c.manager.updating) add("the released manager is updating")
        if (c.manager.startCount != 1) add("starts=${c.manager.startCount}: a start after the release")
        if (c.fake.activeListeners.isNotEmpty()) add("listeners left: ${c.env.ledger}")
    }

    private val paths: List<Path> = listOf(
        Path("release", inWindow = { c: Ctx -> c.probe.job.cancel() }, expect = ::released),
        Path("authorization callback", inWindow = { c: Ctx -> c.manager.authorizationChanged() }, expect = ::restarted),
        Path("activation", inWindow = { c: Ctx -> c.fake.activate() }, expect = ::restarted),
        Path(
            "services switch-off",
            inWindow = { c: Ctx ->
                c.fake.servicesOn = false
                c.fake.announceChange()
            },
            expect = { c: Ctx ->
                buildList {
                    if (c.probe.labels.lastOrNull() != "SERVICE_DISABLED") add("last ${c.probe.labels}")
                    if (c.manager.updating) add("updating while the switch is off")
                    if (c.env.liveHolds(c.manager).isNotEmpty()) add("hold listener live under SERVICE_DISABLED: ${c.env.ledger}")
                }
            },
        ),
        Path(
            "revoke",
            inWindow = { c: Ctx ->
                c.fake.status = kCLAuthorizationStatusDenied
                c.fake.announceChange()
            },
            expect = { c: Ctx ->
                buildList {
                    if (!c.probe.completed) add("not completed after PERMISSION_MISSING: ${c.probe.labels}")
                    if (c.probe.labels.lastOrNull() != "PERMISSION_MISSING") add("last ${c.probe.labels}")
                    addAll(released(c))
                }
            },
        ),
        // The status grew to "always" without a callback reaching this manager: still granted and on, so the
        // re-read holds and reuses the listener of the denial (one registration for the whole hold).
        Path("re-read finds the app authorized", setUp = { c: Ctx -> c.fake.status = kCLAuthorizationStatusAuthorizedAlways }, inWindow = null, expect = ::held),
        Path("second denial", inWindow = { c: Ctx -> c.env.deny(c.manager) }, expect = ::held),
        // The re-read itself finds the switch off or the authorization revoked, with no callback before it
        // (the callback may come later or, for the switch, in either order with the error): the re-read's own
        // SERVICE_DISABLED / PERMISSION_MISSING branch must remove the denial's listener.
        Path(
            "re-read finds the switch off without a callback",
            setUp = { c: Ctx -> c.fake.servicesOn = false },
            inWindow = null,
            expect = { c: Ctx ->
                buildList {
                    if (c.probe.labels.lastOrNull() != "SERVICE_DISABLED") add("last ${c.probe.labels}")
                    if (c.env.liveHolds(c.manager).isNotEmpty()) add("hold listener live under SERVICE_DISABLED: ${c.env.ledger}")
                }
            },
        ),
        Path(
            "re-read finds the authorization revoked without a callback",
            setUp = { c: Ctx -> c.fake.status = kCLAuthorizationStatusDenied },
            inWindow = null,
            expect = { c: Ctx ->
                buildList {
                    if (c.probe.labels.lastOrNull() != "PERMISSION_MISSING") add("last ${c.probe.labels}")
                    addAll(released(c))
                }
            },
        ),
        Path(
            "re-read fails",
            setUp = { c: Ctx -> c.env.failNextServicesRead = true },
            inWindow = null,
            expect = { c: Ctx ->
                buildList {
                    if (c.probe.failure == null) add("the failure of the re-read did not reach the collector")
                    addAll(released(c))
                }
            },
        ),
        Path(
            "re-read cancelled",
            setUp = { c: Ctx -> c.env.duringServicesRead = { c.probe.job.cancel() } },
            inWindow = null,
            expect = ::released,
        ),
    )

    /**
     * Defect classes: listener leak, double removal, removal after the manager was dropped, off-main removal,
     * double registration, wrong state. A denial marks a running stream and registers the hold listener at
     * once; every path that ends before the hold is entered (release, authorization callback, activation,
     * switch-off, revoke, re-read failure or cancellation) must remove it exactly once, on main, before the
     * manager is detached; the paths that reach the hold (a re-read of "granted", a second denial) must reuse
     * it. Both windows (same main turn as the denial, while the re-read reads the switch off main), with and
     * without the auto-pause opt-in (a second, independent registration).
     * Mutation by reading: dropping `leaveHold()` from any of SERVICE_DISABLED / PERMISSION_MISSING /
     * `onAuthorizationChanged` / `becameActiveInHold`, or registering in `hold()` unconditionally, turns a row red.
     */
    @Test
    fun `every path that ends a pending denial removes the listener the denial registered exactly once`() = runTest {
        val problems: MutableList<String> = mutableListOf()
        var rows = 0
        for (optIn: Boolean in listOf(false, true)) {
            for (path: Path in paths) {
                val windows: List<String> = if (path.inWindow == null) listOf("the re-read itself") else listOf("same turn", "during the re-read")
                for (window: String in windows) {
                    rows++
                    val where = "path=${path.name} window=$window optIn=$optIn"
                    val fake = FakeIosLocationEnvironment(testScheduler)
                    val env = LedgerEnvironment(fake)
                    val provider = IosLocationProvider(env, NoopLogger)
                    val probe: Probe = collect(provider, options(optIn))
                    val manager: FakeIosLocationManager = fake.managers.single()
                    onMain(fake) { manager.deliver(rawFix(fake.clock, latitude = 5.0)) }
                    assertEquals("fix5", probe.labels.last(), "$where: control: running")
                    val c = Ctx(this, fake, env, probe, manager)
                    path.setUp(c)
                    val action: ((Ctx) -> Unit)? = path.inWindow
                    when {
                        action == null -> onMain(fake) { env.deny(manager) }
                        window == "same turn" -> onMain(fake) {
                            env.deny(manager)
                            assertEquals(1, env.liveHolds(manager).size, "$where: control: the denial registered the hold listener")
                            action(c)
                        }
                        else -> {
                            env.duringServicesRead = { laterOnMain(fake) { action(c) } }
                            onMain(fake) { env.deny(manager) }
                        }
                    }
                    runCurrent()
                    path.expect(c).forEach { problems += "$where: $it" }
                    env.ledgerProblems().forEach { problems += "$where: mid-run: $it" }
                    if (env.holds(manager).size != 1) problems += "$where: hold registrations ${env.holds(manager)}, want exactly one"
                    probe.job.cancel()
                    runCurrent()
                    env.finalProblems().forEach { problems += "$where: end: $it" }
                    val optInRegistrations: Int = env.ledger.count { it.kind == "opt-in" }
                    if (optInRegistrations != (if (optIn) 1 else 0)) problems += "$where: opt-in registrations $optInRegistrations"
                }
            }
        }
        assertEquals(2 * (6 * 2 + 5), rows, "control: every row ran")
        assertTrue(problems.isEmpty(), "${problems.size} problems: ${problems.joinToString("\n")}")
    }

    // ---------------------------------------------------------------------------------------------
    // 2. A denial while another collector holds

    /**
     * Defect classes: cross-talk between sessions, leak, removal of the wrong listener. Collection A holds;
     * collection B gets a denial and, in the same turn, either ends or meets an activation, or meets an
     * activation queued while its re-read runs. A's listener belongs to A only: B's end leaves it live and A
     * held; one activation leaves both A's hold and B's pending denial; each registration is removed once.
     */
    @Test
    fun `a denial while another collection holds touches only its own listener`() = runTest {
        for (variant: String in listOf("B ends in the denial's turn", "activation in the denial's turn", "activation during B's re-read")) {
            val fake = FakeIosLocationEnvironment(testScheduler)
            val env = LedgerEnvironment(fake)
            val provider = IosLocationProvider(env, NoopLogger)
            val a: Probe = collect(provider, LocationUpdatesOptions())
            val b: Probe = collect(provider, LocationUpdatesOptions())
            val (ma: FakeIosLocationManager, mb: FakeIosLocationManager) = fake.managers.toList()
            onMain(fake) { env.deny(ma) }
            runCurrent()
            assertFalse(ma.updating, "$variant: control: A holds")
            assertEquals(1, env.liveHolds(ma).size, "$variant: control: A's hold listener")
            when (variant) {
                "B ends in the denial's turn" -> {
                    onMain(fake) {
                        env.deny(mb)
                        b.job.cancel()
                    }
                    runCurrent()
                    assertEquals(1, mb.detachCount, "$variant: B released once")
                    assertTrue(env.holds(mb).single().removals == 1, "$variant: B's listener removed once: ${env.ledger}")
                    assertEquals(1, env.liveHolds(ma).size, "$variant: A's listener untouched by B's end: ${env.ledger}")
                    assertFalse(ma.updating, "$variant: A still holds")
                    onMain(fake) { fake.activate() }
                    assertTrue(ma.updating, "$variant: A restarts on the activation: ${a.labels}")
                    assertEquals(2, ma.startCount, "$variant: A restarts once")
                }
                "activation in the denial's turn" -> {
                    onMain(fake) {
                        env.deny(mb)
                        fake.activate()
                    }
                    runCurrent()
                    assertTrue(ma.updating && mb.updating, "$variant: both restart: ${a.labels} ${b.labels}")
                    assertEquals(listOf(2, 2), listOf(ma.startCount, mb.startCount), "$variant: one restart each")
                }
                else -> {
                    env.duringServicesRead = { laterOnMain(fake) { fake.activate() } }
                    onMain(fake) { env.deny(mb) }
                    runCurrent()
                    assertTrue(ma.updating && mb.updating, "$variant: both restart: ${a.labels} ${b.labels}")
                    assertEquals(listOf(2, 2), listOf(ma.startCount, mb.startCount), "$variant: one restart each")
                }
            }
            assertTrue(env.liveHolds(ma).isEmpty() && env.liveHolds(mb).isEmpty(), "$variant: no live hold listener: ${env.ledger}")
            a.job.cancel()
            b.job.cancel()
            runCurrent()
            val problems: List<String> = env.finalProblems()
            assertTrue(problems.isEmpty(), "$variant: $problems")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. A stale listener closure across re-collect

    /**
     * Defect classes: stale listener, work after release, cross-session wake-up. `addObserverForName` with
     * the main queue enqueues the block when the notification is posted; a block already enqueued can still
     * run after `removeObserver`. So the closure of a released session may be called late, also while a NEW
     * collection on the same provider is in its own pending denial. It must be inert: no start of the old
     * manager, no effect on the new session (which still holds), no registration, no removal.
     * Mutation by reading: dropping `released` from the guard of `becameActiveInHold`, or keeping the hold
     * listener in provider-wide state instead of the session, turns it red.
     */
    @Test
    fun `a late call of a removed hold listener after release is inert also during a new collection's pending denial`() = runTest {
        val fake = FakeIosLocationEnvironment(testScheduler)
        val env = LedgerEnvironment(fake)
        val closures: MutableList<() -> Unit> = mutableListOf()
        env.onRegistered = { closure: () -> Unit -> closures += closure }
        val provider = IosLocationProvider(env, NoopLogger)
        val first: Probe = collect(provider, LocationUpdatesOptions())
        val m1: FakeIosLocationManager = fake.managers.single()
        onMain(fake) { env.deny(m1) }
        runCurrent()
        assertEquals(1, closures.size, "control: the denial registered one listener")
        val stale: () -> Unit = closures.single()
        first.job.cancel()
        runCurrent()
        assertEquals(1, m1.detachCount, "control: the first collection is released")
        val second: Probe = collect(provider, LocationUpdatesOptions())
        val m2: FakeIosLocationManager = fake.managers.last()
        assertTrue(m2 !== m1 && m2.updating, "control: the second collection runs")
        val registrations: Int = env.ledger.size
        onMain(fake) { stale() }
        assertEquals(1, m1.startCount, "the stale call started the released manager")
        assertFalse(m1.updating, "the released manager runs")
        assertEquals(1, m2.startCount, "the stale call restarted the second collection")
        assertEquals(registrations, env.ledger.size, "the stale call registered something")
        // The new collection's own pending denial, and the stale call inside its window.
        onMain(fake) {
            env.deny(m2)
            stale()
        }
        runCurrent()
        assertFalse(m2.updating, "a stale activation of the released session left the second session's pending denial: ${second.labels}")
        assertEquals("NO_FIX", second.labels.last(), "the second collection holds: ${second.labels}")
        assertEquals(1, env.liveHolds(m2).size, "the second session's own listener is live: ${env.ledger}")
        second.job.cancel()
        runCurrent()
        val problems: List<String> = env.finalProblems()
        assertTrue(problems.isEmpty(), "$problems")
    }

    // ---------------------------------------------------------------------------------------------
    // 4. The listener closure (which captures the session) is not retained

    private class WeakSlot {
        var ref: WeakReference<() -> Unit>? = null
    }

    private fun alive(slot: WeakSlot): Boolean = slot.ref?.value != null

    private fun collectGarbage() {
        repeat(3) { GC.collect() }
    }

    /**
     * Defect class: retention of the session by its did-become-active closure (the real observer block holds
     * it, and through it the session, its manager and the collector's channel). After the listener's
     * removal the closure must be unreachable: after a release from the pending denial, after a release from
     * the hold, and while the collection still runs after an activation left the hold (the session must have
     * dropped its remover, not only called it). Control: the closure is reachable while the listener is live.
     * Mutation by reading: `leaveHold()` that invokes but does not null `unregisterHold`, or a remover that
     * does not remove the observer, turns a variant red.
     */
    @Test
    fun `the hold listener closure is collectable once its listener is removed`() = runTest {
        for (variant: String in listOf("release while the denial is pending", "release while holding", "activation left the hold, collection running")) {
            val fake = FakeIosLocationEnvironment(testScheduler)
            val env = LedgerEnvironment(fake)
            val slot = WeakSlot()
            env.onRegistered = { closure: () -> Unit -> slot.ref = WeakReference(closure) }
            val provider = IosLocationProvider(env, NoopLogger)
            val probe: Probe = collect(provider, LocationUpdatesOptions())
            val manager: FakeIosLocationManager = fake.managers.single()
            when (variant) {
                "release while the denial is pending" -> onMain(fake) {
                    env.deny(manager)
                    probe.job.cancel()
                }
                "release while holding" -> {
                    onMain(fake) { env.deny(manager) }
                    runCurrent()
                    collectGarbage()
                    assertTrue(alive(slot), "$variant: control: the live listener's closure is reachable")
                    probe.job.cancel()
                }
                else -> {
                    onMain(fake) { env.deny(manager) }
                    runCurrent()
                    collectGarbage()
                    assertTrue(alive(slot), "$variant: control: the live listener's closure is reachable")
                    onMain(fake) { fake.activate() }
                    runCurrent()
                    assertTrue(manager.updating, "$variant: control: restarted")
                }
            }
            runCurrent()
            assertTrue(slot.ref != null, "$variant: control: a hold listener was registered")
            assertTrue(fake.activeListeners.isEmpty(), "$variant: control: removed")
            collectGarbage()
            assertFalse(alive(slot), "$variant: the removed listener's closure (and the session it captures) is still reachable")
            probe.job.cancel()
            runCurrent()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. A collector on Main.immediate that ends at the restart seed

    /**
     * Defect classes: work after release, re-entrancy (a collector that ends from inside a delivery). The real
     * provider runs on `Dispatchers.Main.immediate`, and so does a UI collector (viewModelScope,
     * lifecycleScope). Leaving a hold (activation or authorization callback) or a SERVICE_DISABLED restarts the
     * stream from the re-read on main: `enter(null)` emits the seed, then starts the updates. With an immediate
     * main dispatcher the seed's `trySend` resumes the downstream in place, so a collector that ends on that seed
     * (`first { it is Available }`, the most common one-fix use of the stream, or an explicit cancel) runs the
     * whole cancellation, including the provider's `finally { session.release() }`, BEFORE `trySend` returns.
     * `enter` then continues with `beginUpdates(current)` on the manager that was just stopped and detached:
     * the updates run on a dropped manager with a nil delegate (the location indicator stays on, nothing stops it).
     * Control: the same script with a collector that does not end restarts normally.
     * Mutation by reading: `if (released) return` (or `manager !== current`) after `emitSeed(current)` in
     * `enter(null)` turns it green.
     */
    @Test
    fun `a collector on an immediate main dispatcher that ends at the restart seed leaves no manager running`() = runTest {
        val running: MutableList<String> = mutableListOf()
        for (cause: String in listOf("activation leaves the hold", "authorization callback leaves the hold", "services back on")) {
            for (end: String in listOf("first", "cancel from the collector", "control: does not end")) {
                val where = "cause=$cause end=$end"
                val fake = FakeIosLocationEnvironment(testScheduler)
                val env = LedgerEnvironment(fake, immediate = true)
                val provider = IosLocationProvider(env, NoopLogger)
                val probe = Probe()
                probe.job = backgroundScope.launch(env.mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
                    when (end) {
                        "first" -> {
                            val reading: LocationReading = provider.observeLocation(LocationUpdatesOptions())
                                .first { it is LocationReading.Available && it.fix.coordinates.latitude == 7.0 }
                            probe.labels += label(reading)
                            probe.completed = true
                        }
                        "cancel from the collector" -> provider.observeLocation(LocationUpdatesOptions()).collect { reading: LocationReading ->
                            probe.labels += label(reading)
                            if (label(reading) == "fix7") probe.job.cancel()
                        }
                        else -> provider.observeLocation(LocationUpdatesOptions()).collect { reading: LocationReading -> probe.labels += label(reading) }
                    }
                }
                runCurrent()
                val manager: FakeIosLocationManager = fake.managers.single()
                assertTrue(manager.updating, "$where: control: started")
                when (cause) {
                    "services back on" -> {
                        fake.servicesOn = false
                        onMain(fake) { fake.announceChange() }
                        runCurrent()
                        assertFalse(manager.updating, "$where: control: stopped by the switch")
                        fake.cached = rawFix(fake.clock, latitude = 7.0)
                        fake.servicesOn = true
                        onMain(fake) { fake.announceChange() }
                    }
                    else -> {
                        onMain(fake) { env.deny(manager) }
                        runCurrent()
                        assertFalse(manager.updating, "$where: control: holding")
                        fake.cached = rawFix(fake.clock, latitude = 7.0)
                        onMain(fake) { if (cause.startsWith("activation")) fake.activate() else manager.authorizationChanged() }
                    }
                }
                runCurrent()
                assertTrue("fix7" in probe.labels, "$where: control: the restart's seed reached the collector: ${probe.labels}")
                if (end.startsWith("control")) {
                    assertTrue(manager.updating, "$where: control: restarted")
                    assertEquals(2, manager.startCount, "$where: control: one restart")
                    probe.job.cancel()
                    runCurrent()
                } else {
                    assertTrue(probe.job.isCompleted, "$where: control: the collection ended at the seed")
                    assertEquals(1, manager.detachCount, "$where: released once")
                    if (manager.updating) running += "$where: the released manager is updating (starts=${manager.startCount})"
                }
                val problems: List<String> = env.ledgerProblems() +
                    (if (fake.activeListeners.isEmpty()) emptyList() else listOf("listeners left: ${env.ledger}"))
                assertTrue(problems.isEmpty(), "$where: $problems")
            }
        }
        assertTrue(running.isEmpty(), "updates started after the collection ended and the manager was released:\n${running.joinToString("\n")}")
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Seeded storm on the pending window

    /**
     * Defect classes: leak, double registration, double removal, removal after detach, off-main calls, hold
     * listener live without a hold, hold without a listener. Each step is a burst of 1..3 events: one per main
     * turn, all in one main turn, or the first now and the rest queued from inside the next off-main services
     * read (between an event and its re-read's landing, the window the round-7 fix opened). Events: denial,
     * activation, authorization callback, switch flip, status flip, fix, pause/resume, a cancellation armed
     * inside the n-th next add/remove/services seam call, re-collect. After every step (quiescent): the ledger
     * is clean, at most one live hold listener per manager, a live hold listener exactly when the stream holds
     * (updates stopped behind a last NO_FIX), hold registrations never more than denials delivered. At the end:
     * every registration removed exactly once, on main, before its manager's detach.
     * Mutation by reading: any path from the pending denial that skips `leaveHold()`, or `watchActivation`
     * without its null check, turns a seed red.
     */
    @Test
    fun `a seeded storm on the pending denial window keeps one listener per hold and removes each once`() = runTest {
        var windowEvents = 0
        var holds = 0
        var activationsInWindow = 0
        for (seed: Int in 1..60) {
            val result: IntArray = pendingStorm(seed)
            holds += result[0]
            windowEvents += result[1]
            activationsInWindow += result[2]
        }
        assertTrue(holds > 0, "control: no hold registration in seeds 1..60")
        assertTrue(windowEvents > 0, "control: no event landed in a pending window")
        assertTrue(activationsInWindow > 0, "control: no activation landed in a pending window")
    }

    private fun TestScope.pendingStorm(seed: Int): IntArray {
        val random = Random(seed)
        val fake = FakeIosLocationEnvironment(testScheduler)
        val env = LedgerEnvironment(fake)
        val provider = IosLocationProvider(env, NoopLogger)
        val problems: MutableList<String> = mutableListOf()
        val denials: MutableMap<FakeIosLocationManager, Int> = mutableMapOf()
        var probe: Probe? = null
        var cancelInSeams: Int = -1
        env.onSeam = { _: String ->
            if (cancelInSeams > 0) {
                cancelInSeams--
                if (cancelInSeams == 0) probe?.job?.cancel()
            }
        }
        var windowEvents = 0
        var activationsInWindow = 0

        fun begin() {
            fake.status = kCLAuthorizationStatusAuthorizedWhenInUse
            fake.servicesOn = true
            probe = collect(provider, options(random.nextInt(4) == 0))
        }

        fun event(kind: Int, manager: FakeIosLocationManager?) {
            when (kind) {
                0, 1 -> if (manager != null) {
                    denials[manager] = (denials[manager] ?: 0) + 1
                    env.deny(manager)
                }
                2 -> fake.activate()
                3 -> manager?.authorizationChanged()
                4 -> {
                    fake.servicesOn = !fake.servicesOn
                    fake.announceChange()
                }
                5 -> {
                    fake.status = when (random.nextInt(6)) {
                        0 -> kCLAuthorizationStatusDenied
                        1 -> kCLAuthorizationStatusAuthorizedAlways
                        else -> kCLAuthorizationStatusAuthorizedWhenInUse
                    }
                    fake.announceChange()
                }
                6 -> manager?.deliver(rawFix(fake.clock, latitude = random.nextDouble(-60.0, 60.0)))
                7 -> if (random.nextBoolean()) manager?.pause() else manager?.resume()
                8 -> cancelInSeams = random.nextInt(1, 5)
                else -> manager?.fail(IosFailureKind.LOCATION_UNKNOWN)
            }
        }

        begin()
        for (step: Int in 1..150) {
            val manager: FakeIosLocationManager? = fake.liveManagers.lastOrNull()
            val p: Probe? = probe
            if (p == null || p.job.isCompleted || manager == null) {
                begin()
                continue
            }
            if (random.nextInt(25) == 0) {
                p.job.cancel()
                runCurrent()
                begin()
                continue
            }
            // A burst: the first event usually a denial, so the window is open often.
            val kinds: List<Int> = List(random.nextInt(1, 4)) { index: Int -> if (index == 0 && random.nextInt(3) != 0) 0 else random.nextInt(10) }
            when (random.nextInt(3)) {
                0 -> kinds.forEach { kind: Int -> onMain(fake) { event(kind, manager) } }
                1 -> onMain(fake) {
                    kinds.forEachIndexed { index: Int, kind: Int ->
                        if (index > 0 && kinds[0] <= 1) {
                            windowEvents++
                            if (kind == 2) activationsInWindow++
                        }
                        event(kind, manager)
                    }
                }
                else -> {
                    val rest: List<Int> = kinds.drop(1)
                    if (rest.isNotEmpty()) {
                        env.duringServicesRead = {
                            laterOnMain(fake) {
                                rest.forEach { kind: Int ->
                                    if (kinds[0] <= 1) {
                                        windowEvents++
                                        if (kind == 2) activationsInWindow++
                                    }
                                    event(kind, manager)
                                }
                            }
                        }
                    }
                    onMain(fake) { event(kinds[0], manager) }
                }
            }
            runCurrent()
            env.duringServicesRead = null
            env.ledgerProblems().forEach { problems += "step $step: $it" }
            for (live: FakeIosLocationManager in fake.liveManagers) {
                val liveHolds: List<Entry> = env.liveHolds(live)
                val labels: List<String> = probe?.labels ?: emptyList()
                val heldBehindNoFix: Boolean = !live.updating && labels.lastOrNull() == "NO_FIX"
                if (liveHolds.size > 1) problems += "step $step: ${liveHolds.size} live hold listeners: $liveHolds"
                if (liveHolds.isNotEmpty() && !heldBehindNoFix) {
                    problems += "step $step: a hold listener is live without a hold (updating=${live.updating}, last=${labels.lastOrNull()})"
                }
                if (heldBehindNoFix && liveHolds.isEmpty()) problems += "step $step: held behind NO_FIX with no listener to leave it: ${labels.takeLast(4)}"
                if (env.holds(live).size > (denials[live] ?: 0)) {
                    problems += "step $step: ${env.holds(live).size} hold registrations for ${denials[live] ?: 0} denials"
                }
            }
            if (problems.isNotEmpty()) break
        }
        probe?.job?.cancel()
        runCurrent()
        env.onSeam = null
        problems += env.finalProblems()
        assertTrue(problems.isEmpty(), "seed=$seed: ${problems.take(6)} ledger=${env.ledger.takeLast(6)}")
        return intArrayOf(env.ledger.count { it.kind == "hold" }, windowEvents, activationsInWindow)
    }
}
