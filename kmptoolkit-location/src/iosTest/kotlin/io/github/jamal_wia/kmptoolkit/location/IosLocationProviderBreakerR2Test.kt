@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 * Breaker round 2 of group loc-ios (ticket 5.1.0, LOC-8 / LOC-9): attacks on the round-2 code (the
 * `Flow<StreamReading>` stream, the TERMINAL / SERVICE_DISABLED state machine of m2/m3, the main-queue
 * status read of m4) and on the stream contract of [LocationProvider.observeLocation] ("cold,
 * conflated ... a collector that is busy skips intermediate readings and ends on the newest").
 *
 * [ScriptedEnvironment] wraps the executor's fake: it records every manager call made off the main
 * dispatcher and can answer a number of later `startUpdatingLocation` calls with `kCLErrorDenied`,
 * delivered asynchronously on the main dispatcher as CoreLocation would. Every delegate event in this
 * class is delivered on the main dispatcher. Virtual time only; seeded randomness with `seed=` in every
 * message; hang bounds only (every scripted loop is bounded by a counter, so `runCurrent` returns).
 */
class IosLocationProviderBreakerR2Test {

    // ---------------------------------------------------------------------------------------------
    // Seams

    private class ScriptedEnvironment(val fake: FakeIosLocationEnvironment, val scope: CoroutineScope) : IosLocationEnvironment by fake {
        val offMainManagerCalls: MutableList<String> = mutableListOf()

        /** How many of the next `startUpdatingLocation` calls CoreLocation answers with `kCLErrorDenied`. */
        var denialsOnStart: Int = 0

        val onMain: Boolean get() = fake.main.isRunning

        override fun createManager(events: IosLocationEvents): IosLocationManager {
            if (!onMain) offMainManagerCalls += "createManager"
            return ScriptedManager(this, fake.createManager(events) as FakeIosLocationManager)
        }

        override fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
            if (!onMain) offMainManagerCalls += "addBecameActiveListener"
            return fake.addBecameActiveListener(onActive)
        }
    }

    private class ScriptedManager(private val env: ScriptedEnvironment, private val inner: FakeIosLocationManager) : IosLocationManager {
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
            if (env.denialsOnStart > 0) {
                env.denialsOnStart--
                // CoreLocation answers asynchronously, on the run loop of the manager's thread.
                env.scope.launch(env.fake.mainDispatcher) { inner.fail(IosFailureKind.DENIED) }
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

    private class Fixture(val env: ScriptedEnvironment, val provider: IosLocationProvider) {
        val fake: FakeIosLocationEnvironment get() = env.fake
    }

    private fun TestScope.fixture(): Fixture {
        val env = ScriptedEnvironment(FakeIosLocationEnvironment(testScheduler), backgroundScope)
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
    // M1: the stream is not conflated at its end

    /**
     * Defect class: backlog instead of conflation (contract of [LocationProvider.observeLocation]: "Cold,
     * conflated stream ... A collector that is busy skips intermediate readings and ends on the newest").
     * The collector is busy with the seed while CoreLocation delivers 20 fixes. The iOS stream conflates
     * only the platform half (`updates(..).conflate()`); the throttle after it has a 64-slot inbox and
     * emits straight into the collector, so the busy collector is handed every stale fix in order.
     * Mutation by reading: `.conflate()` after `completingAfterTerminalReading()` in observeLocation turns
     * it green (the collector then sees `[NO_FIX, fix29]`); removing it again turns it red.
     */
    @Test
    fun `a busy collector skips intermediate readings and ends on the newest`() = runTest {
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val labels: MutableList<String> = mutableListOf()
        val job: Job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            f.provider.observeLocation().collect { reading: LocationReading ->
                labels += label(reading)
                if (labels.size == 1) gate.await()
            }
        }
        runCurrent()
        assertEquals(listOf("NO_FIX"), labels, "control: the seed arrived and the collector is now busy with it")
        val manager: FakeIosLocationManager = fake.managers.single()
        for (latitude: Int in 10..29) onMain(fake) { manager.deliver(rawFix(fake.clock, latitude = latitude.toDouble())) }
        assertTrue(manager.updating, "control: the updates run while the collector is busy")
        gate.complete(Unit)
        runCurrent()
        assertEquals("fix29", labels.last(), "the busy collector ends on the newest reading: $labels")
        assertTrue(
            labels.size <= 3,
            "a busy collector must skip intermediate readings (observeLocation is conflated); it was handed ${labels.size - 1} " +
                "readings for one busy period: $labels",
        )
        job.cancel()
        runCurrent()
    }

    // ---------------------------------------------------------------------------------------------
    // M2: a denial with authorization still granted restarts the updates without bound

    /**
     * Collects a stream, lets one fix arrive, then makes CoreLocation answer a denial: the first by
     * hand, then [denials] more, one for each later `startUpdatingLocation`. Authorization and the
     * services switch stay granted and on all along, and no authorization callback arrives. Returns
     * the start count of the manager and the number of readings emitted after the fix.
     */
    private fun TestScope.underRepeatedDenial(denials: Int): Pair<Int, Int> {
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        val probe: Probe = collect(f)
        val manager: FakeIosLocationManager = fake.managers.single()
        assertEquals(1, manager.startCount, "control: one start for the collection")
        onMain(fake) { manager.deliver(rawFix(fake.clock, latitude = 5.0)) }
        assertEquals(listOf("NO_FIX", "fix5"), probe.labels, "control: seed and fix")
        f.env.denialsOnStart = denials
        onMain(fake) { manager.fail(IosFailureKind.DENIED) }
        runCurrent()
        val result: Pair<Int, Int> = manager.startCount to probe.labels.size - 2
        probe.job.cancel()
        runCurrent()
        assertEquals(1, manager.detachCount, "denials=$denials: released once")
        return result
    }

    /**
     * Defect class: livelock / ping-pong between the provider and CoreLocation. On `kCLErrorDenied`
     * the session stops, re-reads, finds authorization still granted (the status did not change: a
     * denial for another reason, for example starting updates from the background under When In Use),
     * reseeds and calls `startUpdatingLocation()` again, which CoreLocation answers with the same
     * denial, and so on: every round restarts the updates and emits another reseed. Nothing but the
     * platform drives the loop, so it never ends (the scripted platform here stops after [denials]).
     * The number of restarts and of emitted reseeds must not grow with the number of denials.
     * Mutation by reading: not restarting from IDLE after a denial until the next authorization callback
     * (or restarting at most once per authorization callback) turns it green.
     */
    @Test
    fun `a platform that answers every start with a denial while authorization stays granted does not drive an unbounded restart loop`() = runTest {
        val (startsFew: Int, readingsFew: Int) = underRepeatedDenial(denials = 3)
        val (startsMany: Int, readingsMany: Int) = underRepeatedDenial(denials = 30)
        assertEquals(
            startsFew,
            startsMany,
            "restarts must not grow with the number of denials (3 denials: $startsFew starts, 30 denials: $startsMany starts)",
        )
        assertEquals(
            readingsFew,
            readingsMany,
            "emitted readings must not grow with the number of denials (3: $readingsFew, 30: $readingsMany)",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The reseed source

    /**
     * Defect class: wrong seed (stale position chosen) after a flap. The reseed takes the younger of
     * `manager.location` and the last fix this stream received, each within maxSeedAge; the older one
     * must never win, in either direction, and a fix older than maxSeedAge must not seed at all.
     * Mutation by reading: preferring lastDelivery unconditionally (or the cache unconditionally) turns
     * one of the three cases red.
     */
    @Test
    fun `the reseed after a flap takes the younger of the cached and the received fix within maxSeedAge`() = runTest {
        data class Case(val name: String, val cachedAge: Duration?, val expected: String)
        val cases: List<Case> = listOf(
            Case("cache younger than the received fix", cachedAge = 2.seconds, expected = "fix8"),
            Case("cache older than the received fix", cachedAge = 15.seconds, expected = "fix7"),
            Case("cache beyond maxSeedAge", cachedAge = 40.seconds, expected = "fix7"),
        )
        for (case: Case in cases) {
            val f: Fixture = fixture()
            val fake: FakeIosLocationEnvironment = f.fake
            val probe: Probe = collect(f, LocationUpdatesOptions(maxSeedAge = 30.seconds))
            val manager: FakeIosLocationManager = fake.managers.single()
            onMain(fake) { manager.deliver(rawFix(fake.clock, latitude = 7.0)) }
            fake.servicesOn = false
            onMain(fake) { fake.announceChange() }
            fake.clock.advance(10.seconds)
            fake.cached = case.cachedAge?.let { age: Duration -> rawFix(fake.clock, age = age, latitude = 8.0) }
            fake.servicesOn = true
            onMain(fake) { fake.announceChange() }
            assertEquals(listOf("NO_FIX", "fix7", "SERVICE_DISABLED", case.expected), probe.labels, case.name)
            probe.job.cancel()
            runCurrent()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The state machine under every ordering: a seeded storm with an oracle of the final state

    private val statuses: List<Int> = listOf(
        kCLAuthorizationStatusAuthorizedWhenInUse,
        kCLAuthorizationStatusAuthorizedAlways,
        kCLAuthorizationStatusAuthorizedWhenInUse,
        kCLAuthorizationStatusDenied,
        kCLAuthorizationStatusNotDetermined,
        kCLAuthorizationStatusRestricted,
    )

    /**
     * Defect class: wrong state after an ordering of events (m2/m3 fixes are new code). Every step
     * changes something and lets the platform announce it the way iOS does (the services switch, the
     * status, the accuracy grade, the switch-off and the revoke as a PAIR of callbacks, authorization
     * and kCLErrorDenied, in a random order inside one main-queue turn, a lone denial, transient and
     * other failures, pauses, foregrounding, time). After every step the stream must agree with the
     * platform: PERMISSION_MISSING is last and the collection is over and released; otherwise with the
     * switch off the last reading is SERVICE_DISABLED and updates are stopped, and with it on the
     * updates run and the last reading is not SERVICE_DISABLED; SERVICE_DISABLED never follows itself;
     * no manager is touched off main; the status is read on main.
     * Mutation by reading: dropping the TERMINAL guard of enter(), resetting SERVICE_DISABLED to IDLE
     * on a denial, or skipping the re-read after a denial each turn a seed red.
     */
    @Test
    fun `a seeded storm of event orderings leaves the stream agreeing with the platform after every step`() = runTest {
        for (seed: Int in 2001..2030) orderingStorm(seed)
    }

    private fun TestScope.orderingStorm(seed: Int) {
        val random = Random(seed)
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        val problems: MutableList<String> = mutableListOf()
        var probe: Probe? = null
        var probeManagers: List<FakeIosLocationManager> = emptyList()
        var collections = 0

        fun begin() {
            fake.status = kCLAuthorizationStatusAuthorizedWhenInUse
            val before: Int = fake.managers.size
            val options = LocationUpdatesOptions(
                maxSeedAge = listOf(0.seconds, 30.seconds, Duration.INFINITE)[random.nextInt(3)],
                platform = IosLocationUpdatesOptions(pausesLocationUpdatesAutomatically = random.nextBoolean()),
            )
            probe = collect(f, options)
            probeManagers = fake.managers.drop(before)
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
        for (step: Int in 1..200) {
            val manager: FakeIosLocationManager? = fake.liveManagers.lastOrNull()
            val action: Int = random.nextInt(11)
            when (action) {
                0, 1 -> onMain(fake) { manager?.deliver(rawFix(fake.clock, age = random.nextInt(0, 3).seconds, latitude = random.nextDouble(-80.0, 80.0))) }
                2 -> {
                    fake.servicesOn = !fake.servicesOn
                    onMain(fake) { fake.announceChange() }
                }
                3 -> {
                    fake.status = statuses[random.nextInt(statuses.size)]
                    onMain(fake) { fake.announceChange() }
                }
                4 -> {
                    // The services switch goes off: iOS reports it through both callbacks, in either order.
                    fake.servicesOn = false
                    pair({ manager?.authorizationChanged() }, { manager?.fail(IosFailureKind.DENIED) })
                }
                5 -> {
                    // A revoke: the same pair.
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
            val current: Probe = probe ?: continue
            val labels: List<String> = current.labels
            val where = "seed=$seed step=$step action=$action collection=$collections"
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
                    if (!own.updating) problems += "$where: authorized and on but the updates are stopped: $labels"
                    if (labels.lastOrNull() == LocationUnavailability.SERVICE_DISABLED.name) problems += "$where: switch on but still SERVICE_DISABLED: $labels"
                }
                else -> problems += "$where: unexpected reason $reason"
            }
            if (problems.size > 5) break
        }
        probe?.job?.cancel()
        runCurrent()
        assertTrue(problems.isEmpty(), "seed=$seed: ${problems.take(5)}")
        assertTrue(f.env.offMainManagerCalls.isEmpty(), "seed=$seed: manager touched off main: ${f.env.offMainManagerCalls.take(5)}")
        assertEquals(0, fake.statusReadsOffMain, "seed=$seed: the authorization status was read off main")
        assertEquals(0, fake.managerStatusReadsOffMain, "seed=$seed: a manager's status was read off main")
        assertTrue(fake.statusReads + fake.managerStatusReads > collections, "seed=$seed: control: the status was read")
        assertTrue(fake.liveManagers.isEmpty(), "seed=$seed: a manager survived")
        assertTrue(fake.managers.all { it.detachCount == 1 }, "seed=$seed: every manager released exactly once")
        assertTrue(fake.activeListeners.isEmpty(), "seed=$seed: a did-become-active listener leaked")
    }
}
