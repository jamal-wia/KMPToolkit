@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted

/**
 * Breaker round 3 of group loc-ios (ticket 5.1.0, LOC-8 / LOC-9): attacks on the round-4 fixes, the
 * trailing conflation of [LocationProvider.observeLocation] (M1) and the restart policy after a
 * `kCLErrorDenied` while authorization stays granted (M2: the `DENIAL_HOLD` state and its reset rules).
 *
 * [DenyingEnvironment] wraps the executor's fake: it records every manager call made off the main
 * dispatcher and can answer a number of later `startUpdatingLocation` calls with `kCLErrorDenied`,
 * optionally preceded by another delegate event in the same main-queue turn (a fresh fix, a
 * `kCLErrorLocationUnknown`), delivered asynchronously on the main dispatcher as CoreLocation would.
 * Every scripted loop is bounded by a counter, so `runCurrent` always returns. Virtual time only;
 * seeded randomness with `seed=` in every message; hang bounds only.
 */
class IosLocationProviderBreakerR3Test {

    // ---------------------------------------------------------------------------------------------
    // Seams

    private class DenyingEnvironment(val fake: FakeIosLocationEnvironment, val scope: CoroutineScope) : IosLocationEnvironment by fake {
        val offMainManagerCalls: MutableList<String> = mutableListOf()

        /** How many of the next `startUpdatingLocation` calls CoreLocation answers with `kCLErrorDenied`. */
        var answersLeft: Int = 0

        /** What CoreLocation delivers just before each scripted denial, in the same main-queue turn. */
        var beforeDenial: (FakeIosLocationManager) -> Unit = {}

        /** Scripted denials actually handed to a live delegate. */
        var denialsDelivered: Int = 0

        val onMain: Boolean get() = fake.main.isRunning

        override fun createManager(events: IosLocationEvents): IosLocationManager {
            if (!onMain) offMainManagerCalls += "createManager"
            return DenyingManager(this, fake.createManager(events) as FakeIosLocationManager)
        }

        override fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
            if (!onMain) offMainManagerCalls += "addBecameActiveListener"
            return fake.addBecameActiveListener(onActive)
        }
    }

    private class DenyingManager(private val env: DenyingEnvironment, private val inner: FakeIosLocationManager) : IosLocationManager {
        private fun touch(name: String) {
            if (!env.onMain) env.offMainManagerCalls += name
        }

        override val authorizationStatus: Int
            get() {
                touch("manager.authorizationStatus")
                return inner.authorizationStatus
            }
        override val isAccuracyReduced: Boolean
            get() {
                touch("manager.isAccuracyReduced")
                return inner.isAccuracyReduced
            }
        override val lastLocation: IosRawFix?
            get() {
                touch("manager.lastLocation")
                return inner.lastLocation
            }

        override fun apply(config: IosManagerConfig) {
            touch("manager.apply")
            inner.apply(config)
        }

        override fun startUpdatingLocation() {
            touch("manager.start")
            inner.startUpdatingLocation()
            if (env.answersLeft > 0) {
                env.answersLeft--
                env.scope.launch(env.fake.mainDispatcher) {
                    env.beforeDenial(inner)
                    if (!inner.detached) env.denialsDelivered++
                    inner.fail(IosFailureKind.DENIED)
                }
            }
        }

        override fun stopUpdatingLocation() {
            touch("manager.stop")
            inner.stopUpdatingLocation()
        }

        override fun requestLocation() {
            touch("manager.requestLocation")
            inner.requestLocation()
        }

        override fun detach() {
            touch("manager.detach")
            inner.detach()
        }
    }

    private class Fixture(val env: DenyingEnvironment, val provider: IosLocationProvider) {
        val fake: FakeIosLocationEnvironment get() = env.fake
    }

    private fun TestScope.fixture(): Fixture {
        val env = DenyingEnvironment(FakeIosLocationEnvironment(testScheduler), backgroundScope)
        return Fixture(env, IosLocationProvider(env, NoopLogger))
    }

    /** CoreLocation calls the delegate on the main queue: run [block] there, as the platform would. */
    private fun TestScope.onMain(fake: FakeIosLocationEnvironment, block: () -> Unit) {
        backgroundScope.launch(fake.mainDispatcher) { block() }
        runCurrent()
    }

    private fun label(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix${reading.fix.coordinates.latitude.toInt()}"
        is LocationReading.Unavailable -> reading.reason.name
    }

    private class Probe {
        lateinit var job: Job
        val labels: MutableList<String> = mutableListOf()
        var completed: Boolean = false
    }

    private fun TestScope.collect(f: Fixture, options: LocationUpdatesOptions = LocationUpdatesOptions()): Probe {
        val probe = Probe()
        probe.job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                f.provider.observeLocation(options).collect { reading: LocationReading -> probe.labels += label(reading) }
                probe.completed = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
        runCurrent()
        return probe
    }

    // ---------------------------------------------------------------------------------------------
    // M2 fix: the reset rules of the restart bound reopen the loop

    /**
     * Collects a stream with one fix, then makes CoreLocation answer a denial: the first by hand, then
     * [rounds] more, one for each later `startUpdatingLocation`, each preceded in the same main-queue
     * turn by [before]. Authorization and the switch stay granted and on, and no authorization callback
     * arrives. Returns the start count and the number of readings emitted after the fix.
     */
    private fun TestScope.underDenialsAfter(rounds: Int, before: (FakeIosLocationEnvironment, FakeIosLocationManager) -> Unit): Pair<Int, Int> {
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        val probe: Probe = collect(f)
        val manager: FakeIosLocationManager = fake.managers.single()
        onMain(fake) { manager.deliver(rawFix(fake.clock, latitude = 5.0)) }
        assertEquals(listOf("NO_FIX", "fix5"), probe.labels, "rounds=$rounds: control: seed and fix")
        f.env.beforeDenial = { inner: FakeIosLocationManager -> before(fake, inner) }
        f.env.answersLeft = rounds
        onMain(fake) { manager.fail(IosFailureKind.DENIED) }
        runCurrent()
        val result: Pair<Int, Int> = manager.startCount to probe.labels.size - 2
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount, "rounds=$rounds: released once")
        assertTrue(fake.liveManagers.isEmpty(), "rounds=$rounds: no live manager after the cancel")
        return result
    }

    /**
     * Defect class: livelock / ping-pong (the M2 loop reopened by the fix's own reset rule). CoreLocation
     * commonly hands a just-started manager the last position at once; here every restart is answered by
     * a FRESH fix and then the same `kCLErrorDenied`, the status still granted. The round-4 code resets
     * the denial streak on every accepted live fix (`denialStreak = 0` in `onLocations`), so the bound is
     * never reached: stop, re-read, reseed, start, fix, denial, for as long as the platform keeps it up.
     * Restarts and emitted readings must not grow with the number of rounds.
     * Mutation by reading: dropping the reset in `onLocations` (or holding after the first denial until
     * the next authorization callback) turns it green; the round-4 code is red (3: 5 starts, 30: 32).
     */
    @Test
    fun `a fresh fix delivered before every denial does not reopen the restart loop`() = runTest {
        val before: (FakeIosLocationEnvironment, FakeIosLocationManager) -> Unit = { fake, inner -> inner.deliver(rawFix(fake.clock, latitude = 9.0)) }
        val (startsFew: Int, readingsFew: Int) = underDenialsAfter(rounds = 3, before = before)
        val (startsMany: Int, readingsMany: Int) = underDenialsAfter(rounds = 30, before = before)
        assertEquals(startsFew, startsMany, "restarts must not grow with the rounds of (fix, denial) (3: $startsFew starts, 30: $startsMany starts)")
        assertEquals(readingsFew, readingsMany, "emitted readings must not grow with the rounds of (fix, denial) (3: $readingsFew, 30: $readingsMany)")
    }

    /**
     * Defect class: livelock (same loop, the other reset). Every restart is answered by the transient
     * `kCLErrorLocationUnknown` and then `kCLErrorDenied`. The round-4 code resets the streak on
     * `LOCATION_UNKNOWN`, so the bound is never reached.
     * Mutation by reading: dropping the reset on `LOCATION_UNKNOWN` turns it green; the round-4 code is red.
     */
    @Test
    fun `a transient failure delivered before every denial does not reopen the restart loop`() = runTest {
        val before: (FakeIosLocationEnvironment, FakeIosLocationManager) -> Unit = { _, inner -> inner.fail(IosFailureKind.LOCATION_UNKNOWN) }
        val (startsFew: Int, readingsFew: Int) = underDenialsAfter(rounds = 3, before = before)
        val (startsMany: Int, readingsMany: Int) = underDenialsAfter(rounds = 30, before = before)
        assertEquals(startsFew, startsMany, "restarts must not grow with the rounds of (locationUnknown, denial) (3: $startsFew starts, 30: $startsMany starts)")
        assertEquals(readingsFew, readingsMany, "emitted readings must not grow with the rounds (3: $readingsFew, 30: $readingsMany)")
    }

    // ---------------------------------------------------------------------------------------------
    // M2 fix: the hold must be left again (NO_FIX promises recovery, plan D8 / D20)

    /**
     * Defect class: wrong (stuck) state / lost liveness. The trigger M2 names for a denial under a
     * granted status is starting updates while the app cannot run them (When In Use, not active): every
     * start is denied, the status never changes. The stream holds behind NO_FIX. When the app becomes
     * active CoreLocation serves it again, but no authorization callback comes (the status did not
     * change), so the hold, which only an authorization callback leaves, keeps the stream at NO_FIX for
     * good. Plan D8: the stream stays open through NO_FIX; D20's rationale names a NO_FIX that "would
     * silently never recover" as the defect to avoid, and `didBecomeActive` as the restart signal the
     * provider already owns. Default options (no auto-pause opt-in), so no listener exists yet.
     * Mutation by reading: leaving the hold on `didBecomeActive` (a listener registered while holding)
     * turns it green; the round-4 code is red at "the stream restarts when the app becomes active".
     */
    @Test
    fun `a stream held after denials recovers when the app becomes active without an authorization callback`() = runTest {
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        val probe: Probe = collect(f)
        val manager: FakeIosLocationManager = fake.managers.single()
        onMain(fake) { manager.deliver(rawFix(fake.clock, latitude = 5.0)) }
        f.env.answersLeft = 20
        onMain(fake) { manager.fail(IosFailureKind.DENIED) }
        runCurrent()
        assertFalse(manager.updating, "control: the platform's denials brought the stream to a stop: ${probe.labels}")
        assertEquals(LocationUnavailability.NO_FIX.name, probe.labels.last(), "control: the hold said NO_FIX: ${probe.labels}")
        assertFalse(probe.completed, "control: the stream stays open")
        // The app comes to the foreground: CoreLocation serves updates again; the status never changed.
        f.env.answersLeft = 0
        onMain(fake) { fake.activate() }
        assertTrue(manager.updating, "the stream restarts when the app becomes active (no authorization callback comes): ${probe.labels}")
        onMain(fake) { manager.deliver(rawFix(fake.clock, latitude = 11.0)) }
        assertEquals("fix11", probe.labels.last(), "a live fix reaches the collector again: ${probe.labels}")
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount, "released once")
        assertTrue(fake.activeListeners.isEmpty(), "no did-become-active listener survives the release")
    }

    /**
     * Defect class: leak / lifecycle of the hold. A stream released while holding releases its manager
     * exactly once, on main, keeps no listener, and the next collection starts on a new manager with no
     * memory of the old streak (its first fix is delivered).
     * Mutation by reading: a hold that re-registers a listener without removing it in `release()`, or a
     * streak kept outside the session, turns it red.
     */
    @Test
    fun `a stream cancelled while holding is released once and the next collection starts clean`() = runTest {
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        val first: Probe = collect(f)
        val manager: FakeIosLocationManager = fake.managers.single()
        f.env.answersLeft = 20
        onMain(fake) { manager.fail(IosFailureKind.DENIED) }
        runCurrent()
        assertFalse(manager.updating, "control: holding")
        val startsAtHold: Int = manager.startCount
        repeat(5) { onMain(fake) { manager.fail(IosFailureKind.DENIED) } }
        assertEquals(startsAtHold, manager.startCount, "no restart from a denial inside the hold")
        first.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount, "released once")
        assertEquals(true, manager.detachedOnMain, "released on main")
        assertTrue(fake.activeListeners.isEmpty(), "no listener left")
        assertTrue(f.env.offMainManagerCalls.isEmpty(), "manager touched off main: ${f.env.offMainManagerCalls}")
        f.env.answersLeft = 0
        val second: Probe = collect(f)
        val next: FakeIosLocationManager = fake.managers.last()
        assertTrue(next !== manager, "control: a new manager")
        assertTrue(next.updating, "the next collection runs")
        onMain(fake) { next.deliver(rawFix(fake.clock, latitude = 12.0)) }
        assertEquals("fix12", second.labels.last(), "the next collection delivers")
        second.job.cancel()
        runCurrent()
        assertEquals(1, next.detachCount)
        assertTrue(fake.liveManagers.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // The hold under every ordering: a seeded storm with a denying platform

    private val statuses: List<Int> = listOf(
        kCLAuthorizationStatusAuthorizedWhenInUse,
        kCLAuthorizationStatusAuthorizedAlways,
        kCLAuthorizationStatusAuthorizedWhenInUse,
        kCLAuthorizationStatusDenied,
        kCLAuthorizationStatusNotDetermined,
        kCLAuthorizationStatusRestricted,
    )

    /**
     * Defect class: wrong state after an ordering that reaches the hold (the round-2 storm never reaches
     * it: its longest lone-denial run is 3). Like the round-2 storm, plus a step that makes CoreLocation
     * deny the next 1..6 starts, so the hold is entered and left under every ordering: switch flips and
     * the switch-off / revoke pairs in both orders, status changes, reduced flips, transient failures,
     * pause/resume/foreground, time. After every step: PERMISSION_MISSING is last, the collection over and
     * released; with the switch off the last reading is SERVICE_DISABLED and updates are stopped; with it
     * on either the updates run or (only after a denial since the last authorization callback) they are
     * held behind a last NO_FIX; SERVICE_DISABLED never follows itself; no step makes more than 5 starts
     * (a restart loop is bounded); no manager touched off main; every manager released once; no listener left.
     * Mutation by reading: a hold not left by an authorization callback, a hold that survives a revoke, or
     * an unbounded restart turn a seed red.
     */
    @Test
    fun `a seeded storm with a denying platform leaves the stream agreeing with the platform after every step`() = runTest {
        var holds = 0
        for (seed: Int in 3001..3020) holds += denyingStorm(seed)
        assertTrue(holds > 0, "control: no seed of 3001..3020 reached a held stream, so the storm attacks nothing new")
    }

    /** Runs one seed; returns how many steps ended with the stream held (stopped behind a last NO_FIX). */
    private fun TestScope.denyingStorm(seed: Int): Int {
        val random = Random(seed)
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        val problems: MutableList<String> = mutableListOf()
        var probe: Probe? = null
        var probeManagers: List<FakeIosLocationManager> = emptyList()
        var collections = 0
        var deniedSinceCallback = false
        var holdsSeen = 0

        fun begin() {
            fake.status = kCLAuthorizationStatusAuthorizedWhenInUse
            val before: Int = fake.managers.size
            val deniedBefore: Int = f.env.denialsDelivered
            val options = LocationUpdatesOptions(
                maxSeedAge = listOf(0.seconds, 30.seconds, Duration.INFINITE)[random.nextInt(3)],
                platform = IosLocationUpdatesOptions(pausesLocationUpdatesAutomatically = random.nextBoolean()),
            )
            probe = collect(f, options)
            probeManagers = fake.managers.drop(before)
            deniedSinceCallback = f.env.denialsDelivered > deniedBefore
            collections++
        }

        fun pair(first: () -> Unit, second: () -> Unit) {
            onMain(fake) {
                if (random.nextBoolean()) {
                    first()
                    second()
                } else {
                    second()
                    first()
                }
            }
        }

        begin()
        for (step: Int in 1..150) {
            val manager: FakeIosLocationManager? = fake.liveManagers.lastOrNull()
            val startsBefore: Int = probeManagers.sumOf { it.startCount }
            val deniedBefore: Int = f.env.denialsDelivered
            val action: Int = random.nextInt(12)
            if (action in setOf(2, 3, 4, 5, 7)) deniedSinceCallback = false
            if (action == 6) deniedSinceCallback = true
            when (action) {
                0 -> onMain(fake) { manager?.deliver(rawFix(fake.clock, age = random.nextInt(0, 3).seconds, latitude = random.nextDouble(-80.0, 80.0))) }
                1 -> f.env.answersLeft = random.nextInt(1, 7)
                2 -> {
                    fake.servicesOn = !fake.servicesOn
                    onMain(fake) { fake.announceChange() }
                }
                3 -> {
                    fake.status = statuses[random.nextInt(statuses.size)]
                    onMain(fake) { fake.announceChange() }
                }
                4 -> {
                    fake.servicesOn = false
                    pair({ manager?.authorizationChanged() }, { manager?.fail(IosFailureKind.DENIED) })
                }
                5 -> {
                    fake.status = kCLAuthorizationStatusDenied
                    pair({ manager?.authorizationChanged() }, { manager?.fail(IosFailureKind.DENIED) })
                }
                6 -> onMain(fake) { manager?.fail(IosFailureKind.DENIED) }
                7 -> {
                    fake.reduced = !fake.reduced
                    onMain(fake) { fake.announceChange() }
                }
                8 -> onMain(fake) { manager?.fail(if (random.nextBoolean()) IosFailureKind.LOCATION_UNKNOWN else IosFailureKind.OTHER) }
                9 -> onMain(fake) {
                    when (random.nextInt(3)) {
                        0 -> manager?.pause()
                        1 -> manager?.resume()
                        else -> fake.activate()
                    }
                }
                else -> {
                    fake.clock.advance(random.nextInt(1, 40).seconds)
                    fake.cached = if (random.nextBoolean()) rawFix(fake.clock, age = random.nextInt(0, 60).seconds, latitude = 33.0) else null
                    advanceTimeBy(random.nextInt(1, 4000).milliseconds)
                }
            }
            runCurrent()
            if (f.env.denialsDelivered > deniedBefore) deniedSinceCallback = true
            val current: Probe = probe ?: continue
            val labels: List<String> = current.labels
            val where = "seed=$seed step=$step action=$action collection=$collections"
            val starts: Int = probeManagers.sumOf { it.startCount } - startsBefore
            if (starts > 5) problems += "$where: $starts starts in one step (a restart loop): $labels"
            val terminalAt: Int = labels.indexOf(LocationUnavailability.PERMISSION_MISSING.name)
            val reason: LocationUnavailability? = authorizationReason(fake.status, fake.servicesOn)
            val own: FakeIosLocationManager? = probeManagers.singleOrNull()
            for (i: Int in 1 until labels.size) {
                if (labels[i] == LocationUnavailability.SERVICE_DISABLED.name && labels[i - 1] == labels[i]) {
                    problems += "$where: SERVICE_DISABLED twice in a row: $labels"
                    break
                }
            }
            if (terminalAt >= 0) {
                if (terminalAt != labels.lastIndex) problems += "$where: a reading after PERMISSION_MISSING: $labels"
                if (!current.completed) problems += "$where: the stream did not complete after PERMISSION_MISSING"
                if (probeManagers.any { !it.detached }) problems += "$where: a manager survived the terminal reading"
                begin()
                continue
            }
            if (own == null) {
                problems += "$where: expected exactly one manager for the collection, got ${probeManagers.size}"
                continue
            }
            when (reason) {
                LocationUnavailability.PERMISSION_MISSING -> problems += "$where: status ${fake.status} with services ${fake.servicesOn} did not end the stream: $labels"
                LocationUnavailability.SERVICE_DISABLED -> {
                    if (labels.lastOrNull() != LocationUnavailability.SERVICE_DISABLED.name) problems += "$where: switch off but last reading ${labels.lastOrNull()}: $labels"
                    if (own.updating) problems += "$where: switch off but the updates run"
                }
                null -> {
                    val holding: Boolean = !own.updating && labels.lastOrNull() == LocationUnavailability.NO_FIX.name
                    if (holding) holdsSeen++
                    if (!own.updating && !(holding && deniedSinceCallback)) {
                        problems += "$where: authorized and on but the updates are stopped (denial since the last callback: $deniedSinceCallback): $labels"
                    }
                    if (labels.lastOrNull() == LocationUnavailability.SERVICE_DISABLED.name) problems += "$where: switch on but still SERVICE_DISABLED: $labels"
                }
                else -> problems += "$where: unexpected reason $reason"
            }
            if (problems.size > 5) break
        }
        f.env.answersLeft = 0
        probe?.job?.cancel()
        runCurrent()
        assertTrue(problems.isEmpty(), "seed=$seed: ${problems.take(5)}")
        assertTrue(f.env.offMainManagerCalls.isEmpty(), "seed=$seed: manager touched off main: ${f.env.offMainManagerCalls.take(5)}")
        assertEquals(0, fake.statusReadsOffMain, "seed=$seed: the authorization status was read off main")
        assertEquals(0, fake.managerStatusReadsOffMain, "seed=$seed: a manager's status was read off main")
        assertTrue(f.env.denialsDelivered > 0, "seed=$seed: control: the platform denied some starts")
        assertTrue(fake.liveManagers.isEmpty(), "seed=$seed: a manager survived")
        assertTrue(fake.managers.all { it.detachCount == 1 }, "seed=$seed: every manager released exactly once")
        assertTrue(fake.activeListeners.isEmpty(), "seed=$seed: a did-become-active listener leaked")
        return holdsSeen
    }
}
