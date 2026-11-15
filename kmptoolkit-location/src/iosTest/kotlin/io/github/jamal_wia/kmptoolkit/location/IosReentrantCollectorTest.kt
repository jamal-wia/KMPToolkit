@file:OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied

/**
 * Re-entrancy of the collector (ticket 5.1.0, round 8, finding M1 of breaker round 5). The real provider runs
 * on `Dispatchers.Main.immediate`, and so does a UI collector: a reading sent from a main task resumes the
 * collector in place, inside the send, so a collector that ends on that reading (`first { ... }`, a cancel)
 * runs the whole release before the send returns. Contract (provider KDoc): "When a collection ends or is
 * cancelled the manager is already stopped and released". Derived from that sentence and D-12 (main queue
 * only, strong delegate + nil before drop, every listener removed exactly once), not from the code.
 *
 * One test per place where the session or the one-shot request hands something to the collector and could
 * then touch its manager: the first seed of the stream, the restart seed, SERVICE_DISABLED, the denial hold,
 * PERMISSION_MISSING, a batch of fixes, a pause, and the one-shot outcome. Every ending is tried both ways
 * (`first { ... }` and a cancel from the collector) and with the pause opt-in on and off. Virtual time only;
 * the storm is seeded and prints `seed=` in every message.
 */
class IosReentrantCollectorTest {

    // ---------------------------------------------------------------------------------------------
    // Seams

    /**
     * `Dispatchers.Main.immediate` on the fake main queue: no dispatch is needed while a main task already runs,
     * so a coroutine resumed from main code runs in place, inside the caller's stack.
     */
    private class ImmediateMain(private val main: TrackedDispatcher) : CoroutineDispatcher(), Delay by main {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !main.isRunning

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            main.dispatch(context, block)
        }
    }

    private class ImmediateEnvironment(val fake: FakeIosLocationEnvironment) : IosLocationEnvironment by fake {
        override val mainDispatcher: CoroutineDispatcher = ImmediateMain(fake.main)
    }

    private class Fixture(scheduler: TestCoroutineScheduler) {
        val fake = FakeIosLocationEnvironment(scheduler)
        val env = ImmediateEnvironment(fake)
        val provider = IosLocationProvider(env, NoopLogger)
    }

    private class Probe {
        lateinit var job: Job
        val labels: MutableList<String> = mutableListOf()
        var failure: Throwable? = null
    }

    private enum class End { FIRST, CANCEL }

    private fun label(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix${reading.fix.coordinates.latitude.toInt()}"
        is LocationReading.Unavailable -> reading.reason.name
    }

    private fun options(optIn: Boolean): LocationUpdatesOptions =
        LocationUpdatesOptions(platform = IosLocationUpdatesOptions(pausesLocationUpdatesAutomatically = optIn))

    /**
     * A collector on the immediate main dispatcher (a UI collector) that ends as soon as [shouldEnd] says so about
     * the labels seen so far, the current one included: with `first { ... }` or with a cancel from the collector.
     */
    private fun TestScope.collectUntil(
        f: Fixture,
        options: LocationUpdatesOptions,
        end: End,
        shouldEnd: (List<String>) -> Boolean,
    ): Probe {
        val probe = Probe()
        probe.job = backgroundScope.launch(f.env.mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                when (end) {
                    End.FIRST -> f.provider.observeLocation(options).first { reading: LocationReading ->
                        probe.labels += label(reading)
                        shouldEnd(probe.labels)
                    }
                    End.CANCEL -> f.provider.observeLocation(options).collect { reading: LocationReading ->
                        probe.labels += label(reading)
                        if (shouldEnd(probe.labels)) currentCoroutineContext().job.cancel()
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                probe.failure = failure
            }
        }
        runCurrent()
        return probe
    }

    /** A collector that never ends by itself. */
    private fun TestScope.collectForever(f: Fixture, options: LocationUpdatesOptions): Probe =
        collectUntil(f, options, End.CANCEL) { false }

    /** CoreLocation and the notification center call on the main queue: one main task running [block]. */
    private fun TestScope.onMain(f: Fixture, block: () -> Unit) {
        backgroundScope.launch(f.fake.mainDispatcher) { block() }
        runCurrent()
    }

    /** What must hold once every collection is over: nothing running, every manager released once, every listener balanced. */
    private fun finalProblems(f: Fixture): List<String> = buildList {
        f.fake.managers.forEachIndexed { index: Int, manager: FakeIosLocationManager ->
            if (manager.detachCount != 1) add("manager $index detached ${manager.detachCount} times")
            if (manager.updating) add("manager $index is updating after its release (starts=${manager.startCount})")
            if (manager.detachedOnMain != true) add("manager $index detached off main")
        }
        if (f.fake.activeListeners.isNotEmpty()) add("${f.fake.activeListeners.size} did-become-active listeners left")
        if (f.fake.registered != f.fake.unregistered) add("registered ${f.fake.registered} vs unregistered ${f.fake.unregistered}")
    }

    private fun assertEndedAndReleased(f: Fixture, probe: Probe, where: String) {
        assertEquals(null, probe.failure, "$where: the collector failed")
        assertTrue(probe.job.isCompleted, "$where: the collection ended: ${probe.labels}")
        val problems: List<String> = finalProblems(f)
        assertTrue(problems.isEmpty(), "$where: $problems")
    }

    private inline fun forEachVariant(block: (optIn: Boolean, end: End, where: String) -> Unit) {
        for (optIn: Boolean in listOf(false, true)) {
            for (end: End in End.values()) block(optIn, end, "optIn=$optIn end=$end")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 1. The first seed of the stream

    /**
     * Defect class: work after release. `start()` runs the first `enter(null)` inside the producer, so the release
     * cannot interleave there, but the collector ends on the very first reading and the manager must end up
     * stopped and released once, with the pause opt-in's listener removed.
     */
    @Test
    fun `a collector that ends at the first reading of the stream leaves nothing running`() = runTest {
        forEachVariant { optIn: Boolean, end: End, where: String ->
            val f = Fixture(testScheduler)
            val probe: Probe = collectUntil(f, options(optIn), end) { true }
            assertEndedAndReleased(f, probe, where)
            assertEquals(listOf("NO_FIX"), probe.labels, "$where: the seed")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. The restart seed (the breaker's M1, with the re-init that follows)

    /**
     * Defect classes: work after release, re-init after release. Leaving a hold by an activation restarts the
     * stream from the re-read on main: the seed's send runs the collector in place, which ends and releases the
     * session; the restart must not start the manager that was just released. Afterwards a new collection on the
     * same provider gets a fresh manager and works, and the released one stays stopped.
     */
    @Test
    fun `a collector that ends at the restart seed releases once and a new collection starts fresh`() = runTest {
        forEachVariant { optIn: Boolean, end: End, where: String ->
            val f = Fixture(testScheduler)
            val probe: Probe = collectUntil(f, options(optIn), end) { it.lastOrNull() == "fix7" }
            val first: FakeIosLocationManager = f.fake.managers.single()
            onMain(f) { first.fail(IosFailureKind.DENIED) }
            runCurrent()
            assertFalse(first.updating, "$where: control: holding")
            assertFalse(probe.job.isCompleted, "$where: control: the collection is still running")
            f.fake.cached = rawFix(f.fake.clock, latitude = 7.0)
            onMain(f) { f.fake.activate() }
            runCurrent()
            assertEquals(1, first.startCount, "$where: no start after the release")
            assertEndedAndReleased(f, probe, where)

            val again: Probe = collectForever(f, options(optIn))
            val second: FakeIosLocationManager = f.fake.managers.last()
            assertEquals(2, f.fake.managers.size, "$where: a fresh manager for the new collection")
            assertTrue(second.updating, "$where: the new collection runs")
            assertFalse(first.updating, "$where: the released manager stays stopped")
            assertEquals(1, first.startCount, "$where: the released manager was never started again")
            again.job.cancel()
            runCurrent()
            val problems: List<String> = finalProblems(f)
            assertTrue(problems.isEmpty(), "$where: $problems")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. SERVICE_DISABLED, the denial hold, PERMISSION_MISSING

    /** Defect class: work after release. `out` is the last statement of the SERVICE_DISABLED branch. */
    @Test
    fun `a collector that ends at SERVICE_DISABLED leaves nothing running`() = runTest {
        forEachVariant { optIn: Boolean, end: End, where: String ->
            val f = Fixture(testScheduler)
            val probe: Probe = collectUntil(f, options(optIn), end) { it.lastOrNull() == "SERVICE_DISABLED" }
            val manager: FakeIosLocationManager = f.fake.managers.single()
            f.fake.servicesOn = false
            onMain(f) { f.fake.announceChange() }
            runCurrent()
            assertEndedAndReleased(f, probe, where)
            assertEquals(1, manager.startCount, "$where: the switch-off started nothing")
        }
    }

    /**
     * Defect classes: leak, work after release. The collector ends at the hold's NO_FIX, in place: the hold's
     * did-become-active listener (registered by the denial) is removed exactly once by the release that runs
     * inside `hold()`, and an activation afterwards starts nothing.
     */
    @Test
    fun `a collector that ends at the hold NO_FIX removes the hold listener once and starts nothing`() = runTest {
        forEachVariant { optIn: Boolean, end: End, where: String ->
            val f = Fixture(testScheduler)
            f.fake.cached = rawFix(f.fake.clock, latitude = 5.0)
            val probe: Probe = collectUntil(f, options(optIn), end) { it.lastOrNull() == "NO_FIX" }
            val manager: FakeIosLocationManager = f.fake.managers.single()
            assertEquals(listOf("fix5"), probe.labels, "$where: control: the seed is the cached fix")
            onMain(f) { manager.fail(IosFailureKind.DENIED) }
            runCurrent()
            assertEndedAndReleased(f, probe, where)
            onMain(f) { f.fake.activate() }
            runCurrent()
            assertEquals(1, manager.startCount, "$where: an activation after the release starts nothing")
            val problems: List<String> = finalProblems(f)
            assertTrue(problems.isEmpty(), "$where: after the activation: $problems")
        }
    }

    /** Defect class: work after release. PERMISSION_MISSING after a running stream is terminal and the last element. */
    @Test
    fun `a collector that ends at PERMISSION_MISSING leaves nothing running`() = runTest {
        forEachVariant { optIn: Boolean, end: End, where: String ->
            val f = Fixture(testScheduler)
            val probe: Probe = collectUntil(f, options(optIn), end) { it.lastOrNull() == "PERMISSION_MISSING" }
            f.fake.status = kCLAuthorizationStatusDenied
            onMain(f) { f.fake.announceChange() }
            runCurrent()
            assertEndedAndReleased(f, probe, where)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. A batch of fixes and a pause

    /**
     * Defect class: work after release. A delegate delivery of three fixes: the collector ends at the first one,
     * in place, and the loop must neither emit the others nor touch the released manager.
     */
    @Test
    fun `a collector that ends at the first fix of a batch stops the batch`() = runTest {
        forEachVariant { optIn: Boolean, end: End, where: String ->
            val f = Fixture(testScheduler)
            val probe: Probe = collectUntil(f, options(optIn), end) { it.lastOrNull() == "fix1" }
            val manager: FakeIosLocationManager = f.fake.managers.single()
            onMain(f) { manager.deliver(rawFix(f.fake.clock, latitude = 1.0), rawFix(f.fake.clock, latitude = 2.0), rawFix(f.fake.clock, latitude = 3.0)) }
            runCurrent()
            assertEndedAndReleased(f, probe, where)
            assertEquals(listOf("NO_FIX", "fix1"), probe.labels, "$where: nothing after the fix the collector ended on")
            onMain(f) { manager.deliver(rawFix(f.fake.clock, latitude = 4.0)) }
            assertEquals(listOf("NO_FIX", "fix1"), probe.labels, "$where: a late delivery is inert")
        }
    }

    /** Defect classes: work after release, leak. The pause's NO_FIX ends the collector in place (opt-in only). */
    @Test
    fun `a collector that ends at the pause NO_FIX releases the stream and its active listener`() = runTest {
        for (end: End in End.values()) {
            val where = "end=$end"
            val f = Fixture(testScheduler)
            f.fake.cached = rawFix(f.fake.clock, latitude = 5.0)
            val probe: Probe = collectUntil(f, options(optIn = true), end) { it.lastOrNull() == "NO_FIX" }
            val manager: FakeIosLocationManager = f.fake.managers.single()
            assertTrue(f.fake.activeListeners.size == 1, "$where: control: the opt-in registered its listener")
            onMain(f) { manager.pause() }
            runCurrent()
            assertEndedAndReleased(f, probe, where)
            onMain(f) { f.fake.activate() }
            runCurrent()
            assertEquals(1, manager.startCount, "$where: an activation after the release restarts nothing")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. The one-shot outcome

    /**
     * Defect classes: work after release, double release. The one-shot waiter resumes in place when the outcome
     * is completed from a delegate callback: the request's own `finally` (stop, detach) runs inside
     * `outcome.complete(...)`. Nothing may touch the manager afterwards, and a transient error followed by a fix
     * starts the ordinary updates exactly once.
     */
    @Test
    fun `a one-shot call that resumes in place releases once and starts nothing afterwards`() = runTest {
        for (scenario: String in listOf("fresh fix", "batch of fixes", "denied", "authorization revoked", "other failure", "unknown then fix")) {
            val f = Fixture(testScheduler)
            val call: Deferred<LocationReading> = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                f.provider.getCurrentLocation(CurrentLocationOptions())
            }
            runCurrent()
            val manager: FakeIosLocationManager = f.fake.managers.single()
            assertFalse(call.isCompleted, "$scenario: control: the call waits")
            onMain(f) {
                when (scenario) {
                    "fresh fix" -> manager.deliver(rawFix(f.fake.clock, latitude = 1.0))
                    "batch of fixes" -> manager.deliver(rawFix(f.fake.clock, latitude = 1.0), rawFix(f.fake.clock, latitude = 2.0))
                    "denied" -> manager.fail(IosFailureKind.DENIED)
                    "authorization revoked" -> {
                        f.fake.status = kCLAuthorizationStatusDenied
                        manager.authorizationChanged()
                    }
                    "other failure" -> manager.fail(IosFailureKind.OTHER)
                    else -> {
                        manager.fail(IosFailureKind.LOCATION_UNKNOWN)
                        manager.deliver(rawFix(f.fake.clock, latitude = 1.0))
                    }
                }
            }
            runCurrent()
            assertTrue(call.isCompleted, "$scenario: the call ended")
            if (scenario == "fresh fix" || scenario == "batch of fixes" || scenario == "unknown then fix") {
                assertEquals("fix1", label(assertIs<LocationReading.Available>(call.await())), "$scenario: the first fix")
            }
            assertEquals(1, manager.detachCount, "$scenario: released once")
            assertEquals(true, manager.detachedOnMain, "$scenario: released on main")
            assertFalse(manager.updating, "$scenario: nothing runs after the call")
            assertEquals(if (scenario == "unknown then fix") 1 else 0, manager.startCount, "$scenario: starts")
            assertEquals(1, manager.requestCount, "$scenario: one request")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Seeded storm

    /**
     * Defect classes: work after release, leak, double release, re-init. Seeded storm on the immediate main
     * dispatcher: events (denial, activation, authorization callback, switch flip, status flip, batches of fixes,
     * pause/resume, transient error) interleaved with collectors that end on the n-th reading, both ways, with
     * and without the pause opt-in, with and without a cached position, services on or off at the start. After
     * every step a manager that was released is neither updating nor started again (its start count is frozen at
     * its release); at the end every manager was released once and every listener removed.
     */
    @Test
    fun `a seeded storm with collectors that end on any reading never runs a released manager`() = runTest {
        var restarts = 0
        var endedOnSeedOrNoFix = 0
        for (seed: Int in 1..40) {
            val random = Random(seed)
            val f = Fixture(testScheduler)
            val frozenStarts: MutableMap<FakeIosLocationManager, Int> = mutableMapOf()
            val problems: MutableList<String> = mutableListOf()
            var probe: Probe? = null

            fun begin() {
                f.fake.status = kCLAuthorizationStatusAuthorizedWhenInUse
                f.fake.servicesOn = random.nextInt(4) != 0
                f.fake.cached = if (random.nextBoolean()) rawFix(f.fake.clock, latitude = random.nextInt(1, 60).toDouble()) else null
                val endAfter: Int = random.nextInt(1, 6)
                val end: End = if (random.nextBoolean()) End.FIRST else End.CANCEL
                probe = collectUntil(f, options(random.nextInt(3) == 0), end) { labels: List<String> -> labels.size >= endAfter }
            }

            fun event(kind: Int, manager: FakeIosLocationManager) {
                when (kind) {
                    0 -> manager.fail(IosFailureKind.DENIED)
                    1 -> f.fake.activate()
                    2 -> manager.authorizationChanged()
                    3 -> {
                        f.fake.servicesOn = !f.fake.servicesOn
                        f.fake.announceChange()
                    }
                    4 -> {
                        f.fake.status = when (random.nextInt(6)) {
                            0 -> kCLAuthorizationStatusDenied
                            1 -> kCLAuthorizationStatusAuthorizedAlways
                            else -> kCLAuthorizationStatusAuthorizedWhenInUse
                        }
                        f.fake.announceChange()
                    }
                    5 -> manager.deliver(
                        *Array(random.nextInt(1, 4)) { rawFix(f.fake.clock, latitude = random.nextInt(1, 60).toDouble()) },
                    )
                    6 -> if (random.nextBoolean()) manager.pause() else manager.resume()
                    else -> manager.fail(IosFailureKind.LOCATION_UNKNOWN)
                }
            }

            begin()
            for (step: Int in 1..80) {
                val current: Probe = probe ?: break
                val manager: FakeIosLocationManager? = f.fake.liveManagers.lastOrNull()
                if (current.job.isCompleted || manager == null) {
                    if (current.labels.size == 1) endedOnSeedOrNoFix++
                    begin()
                    continue
                }
                val before: Int = f.fake.managers.sumOf { it.startCount }
                val kinds: List<Int> = List(random.nextInt(1, 3)) { random.nextInt(8) }
                onMain(f) { kinds.forEach { kind: Int -> event(kind, manager) } }
                runCurrent()
                restarts += f.fake.managers.sumOf { it.startCount } - before
                for ((index: Int, m: FakeIosLocationManager) in f.fake.managers.withIndex()) {
                    if (m.detached) {
                        val frozen: Int = frozenStarts.getOrPut(m) { m.startCount }
                        if (m.startCount != frozen) problems += "step $step: released manager $index was started again (starts ${m.startCount}, at release $frozen)"
                        if (m.updating) problems += "step $step: released manager $index is updating"
                    }
                }
                if (current.failure != null) problems += "step $step: the collector failed: ${current.failure}"
                if (problems.isNotEmpty()) break
            }
            probe?.job?.cancel()
            runCurrent()
            problems += finalProblems(f)
            assertTrue(problems.isEmpty(), "seed=$seed: ${problems.take(6)}")
        }
        assertTrue(restarts > 40, "control: the storm restarted updates only $restarts times")
        assertTrue(endedOnSeedOrNoFix > 0, "control: no collection ended on its first reading")
    }
}
