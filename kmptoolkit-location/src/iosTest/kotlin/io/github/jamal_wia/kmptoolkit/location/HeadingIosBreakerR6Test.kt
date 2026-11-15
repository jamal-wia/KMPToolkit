@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 6 of group loc-heading (LOC-11, decision D-23): the iOS heading provider through its seams, after the
 * round-5 fixes (the `locationRunning` flag claimed before the native call and given back when the call throws; the
 * overlay refused once the collection is over).
 *
 * - The give-back against calls nested inside the failing call: the provider KDoc promises that location updates run
 *   "only while authorized ... start when authorization arrives mid-collection and stop on revocation, never without
 *   authorization", and the session KDoc that a manager delivering in place is decided "on what the call is doing".
 *   A failing call that had calls nested inside it (which succeeded, and wrote the flag) must not overwrite what they
 *   wrote with its own give-back.
 * - A seeded storm of authorization changes delivered inside the manager's calls, where a call that delivered inside
 *   itself may then fail (exception or `Error`): two fault positions in one run.
 * - The exhaustive model of round 5 with TWO platform faults in one run (every pair of distinct calls, exception or
 *   `Error` each) instead of one.
 * - The overlay answer against every collector end and cancel timing, with a manager that mirrors the real wrapper
 *   (an overlay agreed to is dismissed once at release, never when it was not agreed to, never twice).
 *
 * Deterministic: a hand-driven main queue, an unconfined collector (the `Dispatchers.Main.immediate` model),
 * exhaustive loops or fixed seeds (`seed=` in every message), no sleeps, no wall clock.
 */
class HeadingIosBreakerR6Test {

    private class PlatformError(message: String) : Error(message)

    private fun FakeHeadingDevice.FakeManager.flip(authorized: Boolean) {
        this.authorized = authorized
        events.onAuthorizationChanged()
    }

    private fun TestScope.collectForever(provider: HeadingProvider): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeHeading(minChangeDegrees = 0.0).collect { }
        }

    private fun Throwable.graph(seen: MutableSet<Throwable> = mutableSetOf()): Set<Throwable> {
        if (!seen.add(this)) return seen
        cause?.graph(seen)
        suppressedExceptions.forEach { suppressed: Throwable -> suppressed.graph(seen) }
        return seen
    }

    // region the give-back of a failing call against the calls nested inside it

    /**
     * A revocation stops location; inside that stop the manager delivers a grant (location starts again, flag
     * written), and inside that start a revocation (location stops again, flag written). Then the outer stop fails.
     * Whatever the outer stop itself did, the last call that took effect is a stop: location is stopped. The outer
     * give-back must not set the flag to "running", or the next grant starts nothing and true north never returns.
     */
    @Test
    fun `a failing stop does not overwrite the flag written by the calls nested inside it`() = runTest {
        val wrong: MutableList<String> = mutableListOf()
        for (error: Boolean in listOf(false, true)) {
            val device = FakeHeadingDevice()
            device.authorizedAtOpen = true
            val provider = IosHeadingProvider(device, false, RecordingLogger(), inlineConfine)
            val collection: Job = collectForever(provider)
            val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
            assertTrue(manager.locationRunning, "control: the opening started location; error=$error")
            var stage = 0
            device.afterStopLocation = { inside: FakeHeadingDevice.FakeManager ->
                if (stage == 0) {
                    stage = 1
                    inside.flip(authorized = true)
                    throw if (error) PlatformError("stop failed after delivering") else IllegalStateException("stop failed after delivering")
                }
            }
            device.afterStartLocation = { inside: FakeHeadingDevice.FakeManager ->
                if (stage == 1) {
                    stage = 2
                    inside.flip(authorized = false)
                }
            }

            val escaped: Throwable? = runCatching { manager.flip(authorized = false) }.exceptionOrNull()

            assertEquals(2, stage, "control: both nested deliveries happened; error=$error")
            assertEquals(2, manager.locationStops.load(), "control: the outer and the nested stop took effect; error=$error")
            assertEquals(2, manager.locationStarts.load(), "control: the opening and the nested start took effect; error=$error")
            assertFalse(manager.authorized || manager.locationRunning, "control: unauthorized and stopped; error=$error")
            assertEquals(error, escaped is PlatformError, "control: only an Error escapes; error=$error escaped=$escaped")
            device.afterStopLocation = { }
            device.afterStartLocation = { }

            manager.flip(authorized = true)

            if (!manager.locationRunning) wrong += "error=$error: an authorized manager runs no location updates (starts=${manager.locationStarts.load()})"
            assertEquals(emptyList(), device.problemList, "error=$error")
            collection.cancelAndJoin()
            assertEquals(1, manager.releases.load(), "error=$error")
        }
        assertEquals(emptyList(), wrong, "a grant after the failing stop must start location again (true north comes back)")
    }

    /**
     * The mirror: a grant starts location; inside that start a revocation stops it, and inside that stop a grant starts
     * it again; then the outer start fails. Location runs (the last call that took effect is a start). The outer
     * give-back must not set the flag to "stopped", or the next revocation stops nothing and location runs without
     * authorization.
     */
    @Test
    fun `a failing start does not overwrite the flag written by the calls nested inside it`() = runTest {
        val wrong: MutableList<String> = mutableListOf()
        for (error: Boolean in listOf(false, true)) {
            val device = FakeHeadingDevice()
            val provider = IosHeadingProvider(device, false, RecordingLogger(), inlineConfine)
            val collection: Job = collectForever(provider)
            val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
            var stage = 0
            device.afterStartLocation = { inside: FakeHeadingDevice.FakeManager ->
                if (stage == 0) {
                    stage = 1
                    inside.flip(authorized = false)
                    throw if (error) PlatformError("start failed after delivering") else IllegalStateException("start failed after delivering")
                }
            }
            device.afterStopLocation = { inside: FakeHeadingDevice.FakeManager ->
                if (stage == 1) {
                    stage = 2
                    inside.flip(authorized = true)
                }
            }

            runCatching { manager.flip(authorized = true) }

            assertEquals(2, stage, "control: both nested deliveries happened; error=$error")
            assertEquals(2, manager.locationStarts.load(), "control: the outer and the nested start took effect; error=$error")
            assertTrue(manager.authorized && manager.locationRunning, "control: authorized and running; error=$error")
            device.afterStopLocation = { }
            device.afterStartLocation = { }

            manager.flip(authorized = false)

            if (manager.locationRunning) wrong += "error=$error: location updates run on an unauthorized manager (stops=${manager.locationStops.load()})"
            assertEquals(emptyList(), device.problemList, "error=$error")
            collection.cancelAndJoin()
            assertEquals(1, manager.releases.load(), "error=$error")
        }
        assertEquals(emptyList(), wrong, "a revocation after the failing start must stop location")
    }

    /**
     * Seeded storm: authorization changes delivered inside `startHeading`, `startLocation` and `stopLocation` (depth up
     * to 3), and a call that delivered inside itself may then fail with an exception or an `Error` (two fault
     * positions in one call: the nested delivery and the throw). After every step a live manager runs location
     * updates exactly while it is authorized. A call fails only after a delivery inside it that made a call of its
     * own (start or stop), so the truth is never in doubt: the nested call took effect and returned, after whatever
     * the failing call did. A failure with nothing written inside it is not injected (whether such a call took effect
     * is the documented retry case of round 6).
     */
    @Test
    fun `a seeded storm of nested authorization changes with failing calls keeps location in step with authorization`() = runTest {
        var nestedDeliveries = 0
        var failures = 0
        val broken: MutableList<String> = mutableListOf()
        for (seed: Int in 1..60) {
            val rng = Random(HEADING_TEST_SEED + 606 + seed)
            val device = FakeHeadingDevice()
            device.authorizedAtOpen = rng.nextBoolean()
            var depth = 0
            var step = -1

            fun maybeFlipInside(manager: FakeHeadingDevice.FakeManager) {
                if (depth < 3 && rng.nextInt(3) == 0) {
                    nestedDeliveries++
                    depth++
                    val callsBefore: Int = manager.locationStarts.load() + manager.locationStops.load()
                    try {
                        manager.flip(authorized = rng.nextBoolean())
                    } finally {
                        depth--
                    }
                    // Fail only after a nested delivery that made a call of its own: what that call did (it took effect
                    // and returned) is the newest truth, whatever the failing outer call did before it.
                    val nestedCalled: Boolean = manager.locationStarts.load() + manager.locationStops.load() != callsBefore
                    if (nestedCalled && rng.nextInt(2) == 0) {
                        failures++
                        throw if (rng.nextBoolean()) PlatformError("seed=$seed step=$step failed after delivering") else IllegalStateException("seed=$seed step=$step failed after delivering")
                    }
                }
            }
            device.afterStartHeading = ::maybeFlipInside
            device.afterStartLocation = ::maybeFlipInside
            device.afterStopLocation = ::maybeFlipInside
            val provider = IosHeadingProvider(device, false, RecordingLogger(), inlineConfine)
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                try {
                    provider.observeHeading(minChangeDegrees = 0.0).collect { }
                } catch (thrown: Throwable) {
                    if (thrown is CancellationException) throw thrown
                }
            }
            val manager: FakeHeadingDevice.FakeManager? = device.allManagers.singleOrNull()
            if (manager == null || manager.isReleased) {
                // An Error inside the opening released the manager: nothing to keep in step.
                collection.cancelAndJoin()
                continue
            }
            if (manager.authorized != manager.locationRunning) broken += "seed=$seed step=open"
            for (s: Int in 0 until 40) {
                step = s
                runCatching { manager.flip(authorized = rng.nextBoolean()) }
                assertEquals(emptyList(), device.problemList, "seed=$seed step=$step: no call on a released manager, no start while unauthorized")
                if (manager.authorized != manager.locationRunning) {
                    broken += "seed=$seed step=$step authorized=${manager.authorized} running=${manager.locationRunning}"
                    break
                }
            }
            collection.cancelAndJoin()
            assertEquals(1, manager.releases.load(), "seed=$seed: released once")
        }
        assertTrue(nestedDeliveries >= 200, "control: many authorization changes were delivered inside a call ($nestedDeliveries)")
        assertTrue(failures >= 50, "control: many calls failed after a nested delivery ($failures)")
        assertEquals(emptyList(), broken, "location must run exactly while authorized after every step (nested=$nestedDeliveries failures=$failures)")
    }

    // endregion

    // region the exhaustive model with two faults in one run

    private enum class Call { START_HEADING, START_LOCATION, STOP_LOCATION, IS_AUTHORIZED, RELEASE }

    /** A device whose managers keep a ledger, deliver once inside a chosen call, and fail once at each faulted call. */
    private class LedgerDevice : IosHeadingDevice {
        override val isHeadingAvailable: Boolean = true
        var authorizedAtOpen: Boolean = false
        val managers: MutableList<LedgerManager> = mutableListOf()
        val problems: MutableList<String> = mutableListOf()
        var insideCall: Call? = null
        var inside: (LedgerManager) -> Unit = { }

        /** Thrown once by the first call of each kind on the first manager (by `release` after it took effect). */
        val faults: MutableMap<Call, Throwable> = LinkedHashMap()
        val fired: MutableList<Throwable> = mutableListOf()

        override fun openManager(events: IosHeadingEvents, orientation: ScreenRotation, headingFilterDegrees: Double): IosHeadingManager =
            LedgerManager(events, authorizedAtOpen).also { manager: LedgerManager -> managers += manager }

        inner class LedgerManager(val events: IosHeadingEvents, var authorized: Boolean) : IosHeadingManager {
            var heading: Boolean = false
            var location: Boolean = false
            var released: Boolean = false
            var releases: Int = 0

            private fun check(call: Call) {
                if (released && call != Call.RELEASE) problems += "$call after release"
            }

            private fun faultAt(call: Call) {
                if (managers.firstOrNull() !== this) return
                val failure: Throwable = faults.remove(call) ?: return
                fired += failure
                throw failure
            }

            private fun deliverInside(call: Call) {
                if (insideCall == call) {
                    insideCall = null
                    inside(this)
                }
            }

            override val isAuthorized: Boolean
                get() {
                    check(Call.IS_AUTHORIZED)
                    faultAt(Call.IS_AUTHORIZED)
                    return authorized
                }

            override fun startHeading() {
                check(Call.START_HEADING)
                faultAt(Call.START_HEADING)
                heading = true
                deliverInside(Call.START_HEADING)
            }

            override fun startLocation() {
                check(Call.START_LOCATION)
                if (!authorized) problems += "startLocation while unauthorized"
                faultAt(Call.START_LOCATION)
                location = true
                deliverInside(Call.START_LOCATION)
            }

            override fun stopLocation() {
                check(Call.STOP_LOCATION)
                faultAt(Call.STOP_LOCATION)
                location = false
                deliverInside(Call.STOP_LOCATION)
            }

            override fun release() {
                releases++
                heading = false
                location = false
                released = true
                deliverInside(Call.RELEASE)
                faultAt(Call.RELEASE)
            }
        }
    }

    /** The main queue: blocks run inline while on it, and are queued otherwise until [drain]. */
    private class MainQueue(private val inlineAlways: Boolean) {
        private val queue: ArrayDeque<() -> Unit> = ArrayDeque()
        var onMain: Boolean = false
            private set
        val escaped: MutableList<Throwable> = mutableListOf()

        val confine: (() -> Unit) -> Unit = { block: () -> Unit -> if (inlineAlways || onMain) block() else queue.addLast(block) }

        fun run(block: () -> Unit) {
            val was: Boolean = onMain
            onMain = true
            try {
                block()
            } catch (thrown: Throwable) {
                escaped += thrown
            } finally {
                onMain = was
            }
        }

        fun drain() {
            while (queue.isNotEmpty()) run { queue.removeFirst().invoke() }
        }
    }

    private class CountingLogger : Logger {
        val handed: MutableList<Throwable> = mutableListOf()
        override val tag: String = "CountingLogger"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            message()
            if (throwable != null) handed += throwable
        }
    }

    private enum class Point {
        HEADING, CALIBRATION, GRANT, REVOCATION, IN_START_HEADING, IN_OPENING_START_LOCATION,
        IN_GRANT_START_LOCATION, IN_STOP_LOCATION, IN_RELEASE,
    }

    private enum class Behaviour { CONTINUE, CANCEL, FIRST, THROW, RECOLLECT }

    @Test
    fun `every emission point collector behaviour and pair of platform faults keeps the ledger`() = runTest {
        val pairs: List<List<Pair<Call, Boolean>>> = buildList {
            for (a: Int in Call.entries.indices) {
                for (b: Int in a + 1 until Call.entries.size) {
                    for (errorA: Boolean in listOf(false, true)) {
                        for (errorB: Boolean in listOf(false, true)) add(listOf(Call.entries[a] to errorA, Call.entries[b] to errorB))
                    }
                }
            }
        }
        var endedInsideCallback = 0
        var twoFired = 0
        var scenarios = 0
        for (point: Point in Point.entries) {
            for (behaviour: Behaviour in Behaviour.entries) {
                for (pair: List<Pair<Call, Boolean>> in pairs) {
                    for (queued: Boolean in listOf(false, true)) {
                        scenarios++
                        val combination = "point=$point behaviour=$behaviour faults=${pair.joinToString { (call, error) -> "$call:${if (error) "Error" else "Exception"}" }} queued=$queued"
                        val device = LedgerDevice()
                        device.authorizedAtOpen = point in setOf(Point.REVOCATION, Point.IN_OPENING_START_LOCATION, Point.IN_STOP_LOCATION)
                        val injected: List<Throwable> = pair.map { (call: Call, error: Boolean) ->
                            val failure: Throwable = if (error) AssertionError("injected Error at $call") else IllegalStateException("injected exception at $call")
                            device.faults[call] = failure
                            failure
                        }
                        val main = MainQueue(inlineAlways = !queued)
                        val logger = CountingLogger()
                        val provider = IosHeadingProvider(device, false, logger, main.confine)
                        val collectorFailure = IllegalStateException("the collector's own failure")
                        val surfaced: MutableList<Throwable> = mutableListOf()
                        val extra: MutableList<Job> = mutableListOf()
                        var armed = false
                        var acted = false
                        device.insideCall = when (point) {
                            Point.IN_START_HEADING -> Call.START_HEADING
                            Point.IN_OPENING_START_LOCATION, Point.IN_GRANT_START_LOCATION -> Call.START_LOCATION
                            Point.IN_STOP_LOCATION -> Call.STOP_LOCATION
                            Point.IN_RELEASE -> Call.RELEASE
                            else -> null
                        }
                        device.inside = { manager: LedgerDevice.LedgerManager -> manager.events.onHeading(222.0, -1.0, 3.0) }

                        fun endHere() {
                            acted = true
                            if (main.onMain) endedInsideCallback++
                        }

                        if (point == Point.IN_START_HEADING || point == Point.IN_OPENING_START_LOCATION || point == Point.IN_RELEASE) armed = true
                        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                            try {
                                val flow = provider.observeHeading(minChangeDegrees = 0.0)
                                if (behaviour == Behaviour.FIRST) {
                                    flow.first { _: Heading ->
                                        val now: Boolean = armed && !acted
                                        if (now) endHere()
                                        now
                                    }
                                } else {
                                    flow.collect { _: Heading ->
                                        if (armed && !acted) {
                                            when (behaviour) {
                                                Behaviour.CONTINUE -> acted = true
                                                Behaviour.CANCEL -> {
                                                    endHere()
                                                    cancel()
                                                }
                                                Behaviour.THROW -> {
                                                    endHere()
                                                    throw collectorFailure
                                                }
                                                Behaviour.RECOLLECT -> {
                                                    endHere()
                                                    extra += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                                                        provider.observeHeading(minChangeDegrees = 0.0).collect { }
                                                    }
                                                    cancel()
                                                }
                                                Behaviour.FIRST -> Unit
                                            }
                                        }
                                    }
                                }
                            } catch (thrown: Throwable) {
                                surfaced += thrown
                                if (thrown is CancellationException) throw thrown
                            }
                        }
                        main.drain()
                        val first: LedgerDevice.LedgerManager? = device.managers.firstOrNull()
                        if (first != null) {
                            main.run { first.events.onHeading(10.0, -1.0, 5.0) }
                            armed = true
                            when (point) {
                                Point.HEADING -> main.run { first.events.onHeading(50.0, -1.0, 5.0) }
                                Point.CALIBRATION -> main.run { first.events.onCalibrationRequested() }
                                Point.GRANT, Point.IN_GRANT_START_LOCATION -> main.run {
                                    first.authorized = true
                                    first.events.onAuthorizationChanged()
                                }
                                Point.REVOCATION, Point.IN_STOP_LOCATION -> main.run {
                                    first.authorized = false
                                    first.events.onAuthorizationChanged()
                                }
                                else -> Unit
                            }
                            main.drain()
                            // A second report retries what a failed call left undone; afterwards a live manager runs
                            // location exactly while authorized (both faults fire at most once).
                            main.run { first.events.onAuthorizationChanged() }
                            main.drain()
                            main.run { first.events.onAuthorizationChanged() }
                            main.drain()
                            for (manager: LedgerDevice.LedgerManager in device.managers) {
                                if (!manager.released) {
                                    assertEquals(manager.authorized, manager.location, "a live manager runs location exactly while authorized after the retries; $combination")
                                }
                            }
                            for (manager: LedgerDevice.LedgerManager in device.managers.toList()) {
                                main.run {
                                    manager.events.onHeading(70.0, 75.0, 5.0)
                                    manager.events.onAuthorizationChanged()
                                    manager.events.onCalibrationRequested()
                                }
                            }
                            main.drain()
                        }
                        collection.cancel()
                        extra.forEach { job: Job -> job.cancel() }
                        main.drain()
                        testScheduler.runCurrent()
                        main.drain()
                        if (device.fired.size == 2) twoFired++

                        assertTrue(device.managers.isNotEmpty(), "control: a manager was opened; $combination")
                        assertEquals(emptyList(), device.problems, "no call on a released manager; $combination")
                        for ((index: Int, manager: LedgerDevice.LedgerManager) in device.managers.withIndex()) {
                            assertEquals(1, manager.releases, "manager #$index is released exactly once; $combination; surfaced=$surfaced escaped=${main.escaped}")
                            assertFalse(manager.heading || manager.location, "manager #$index runs nothing after the end; $combination")
                        }
                        assertEquals(emptyList(), main.escaped.filter { thrown: Throwable -> thrown !is Error }, "an exception escaped into a callback; $combination")
                        val reachable: Set<Throwable> = (surfaced + main.escaped).flatMap { thrown: Throwable -> thrown.graph() }.toSet()
                        for (failure: Throwable in injected) {
                            if (failure !in device.fired) continue
                            if (failure is Error) {
                                assertTrue(failure in reachable, "an Error is never absorbed: $failure; $combination; surfaced=$surfaced escaped=${main.escaped}")
                            } else {
                                assertTrue(
                                    failure in reachable || logger.handed.any { handed: Throwable -> handed === failure },
                                    "a platform exception is neither logged nor reachable: $failure; $combination",
                                )
                            }
                        }
                        if (behaviour == Behaviour.THROW && acted) {
                            assertTrue(collectorFailure in reachable, "the collector's own failure is lost; $combination; surfaced=$surfaced")
                        }
                    }
                }
            }
        }
        assertTrue(endedInsideCallback >= 300, "control: collectors ended inside a callback ($endedInsideCallback of $scenarios)")
        assertTrue(twoFired >= 600, "control: both injected faults fired in one run ($twoFired of $scenarios)")
    }

    // endregion

    // region the overlay against every collector end and cancel timing

    /** One answer to the overlay question and what was true when it was given. */
    private data class Answer(val show: Boolean, val releasedAtReturn: Boolean, val collectionLiveAtReturn: Boolean)

    /**
     * A manager that mirrors `CoreLocationHeadingManager` and `HeadingDelegate`: the question goes through the
     * collection's events, an answer `true` is remembered, and the first release dismisses the overlay exactly when it
     * was agreed to. It can also ask the question from inside `startHeading` and `release`.
     */
    private class OverlayDevice : IosHeadingDevice {
        override val isHeadingAvailable: Boolean = true
        val managers: MutableList<OverlayManager> = mutableListOf()
        var askInsideStartHeading: Boolean = false
        var askInsideRelease: Boolean = false
        var collectionLive: () -> Boolean = { true }

        override fun openManager(events: IosHeadingEvents, orientation: ScreenRotation, headingFilterDegrees: Double): IosHeadingManager =
            OverlayManager(events).also { manager: OverlayManager -> managers += manager }

        inner class OverlayManager(val events: IosHeadingEvents) : IosHeadingManager {
            private var held: Boolean = true
            var agreed: Boolean = false
            var dismissals: Int = 0
            var releases: Int = 0
            val answers: MutableList<Answer> = mutableListOf()

            fun ask(): Boolean {
                val show: Boolean = events.onCalibrationRequested()
                if (show) agreed = true
                answers += Answer(show, releasedAtReturn = !held, collectionLiveAtReturn = collectionLive())
                return show
            }

            override val isAuthorized: Boolean get() = false
            override fun startHeading() {
                if (askInsideStartHeading) {
                    askInsideStartHeading = false
                    events.onHeading(10.0, -1.0, 5.0)
                    ask()
                }
            }

            override fun startLocation() = Unit
            override fun stopLocation() = Unit

            override fun release() {
                releases++
                if (!held) return
                if (askInsideRelease) {
                    askInsideRelease = false
                    ask()
                }
                if (agreed) dismissals++
                held = false
            }
        }
    }

    private enum class Ending { CANCEL_IN_COLLECTOR, TAKE, FIRST, THROW, NEVER }

    private enum class Moment { LIVE, ON_THE_QUESTION, BEFORE_THE_QUESTION, BETWEEN_CLOSE_AND_RELEASE, INSIDE_OPENING, INSIDE_RELEASE, AFTER_RELEASE, TWICE }

    /**
     * Every moment of the question (while live; on the very value the question publishes, with the collector ending on
     * it; after the collector ended on the heading before it; between a close from off the main queue and the queued
     * release; inside the opening `startHeading`; inside the release; after the release; twice, the second time on the
     * collector's end) x every collector ending x both configurations x confine. Oracle: an answer is `true` exactly
     * when the configuration allows it and the collection is live at its return; an agreed overlay is dismissed once
     * at the release, a never agreed one never; the manager is released once.
     */
    @Test
    fun `the overlay is allowed only while the collection lives and an agreed overlay is dismissed once at its release`() = runTest {
        var scenarios = 0
        var agreedSome = 0
        var refusedAfterEnd = 0
        for (shows: Boolean in listOf(false, true)) {
            for (moment: Moment in Moment.entries) {
                for (ending: Ending in Ending.entries) {
                    for (queued: Boolean in listOf(false, true)) {
                        scenarios++
                        val combination = "shows=$shows moment=$moment ending=$ending queued=$queued"
                        val device = OverlayDevice()
                        device.askInsideStartHeading = moment == Moment.INSIDE_OPENING
                        val main = MainQueue(inlineAlways = !queued)
                        val provider = IosHeadingProvider(device, shows, CountingLogger(), main.confine)
                        // The value the collector ends on: the question's own value, or the heading before it.
                        val target: Int = when (moment) {
                            Moment.BEFORE_THE_QUESTION -> 1
                            Moment.TWICE -> 4
                            else -> 2
                        }
                        val received: MutableList<Heading> = mutableListOf()
                        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                            try {
                                val flow = provider.observeHeading(minChangeDegrees = 0.0)
                                when (ending) {
                                    Ending.NEVER -> flow.collect { value: Heading -> received += value }
                                    Ending.TAKE -> flow.take(target).collect { value: Heading -> received += value }
                                    Ending.FIRST -> flow.first { value: Heading ->
                                        received += value
                                        received.size == target
                                    }
                                    else -> flow.collect { value: Heading ->
                                        received += value
                                        if (received.size == target) {
                                            if (ending == Ending.THROW) throw IllegalStateException("collector ends") else cancel()
                                        }
                                    }
                                }
                            } catch (thrown: Throwable) {
                                if (thrown is CancellationException) throw thrown
                            }
                        }
                        device.collectionLive = { collection.isActive }
                        main.drain()
                        val manager: OverlayDevice.OverlayManager = device.managers.single()
                        when (moment) {
                            Moment.LIVE, Moment.ON_THE_QUESTION, Moment.BEFORE_THE_QUESTION -> {
                                main.run { manager.events.onHeading(10.0, -1.0, 5.0) }
                                if (moment == Moment.LIVE) main.run { manager.events.onHeading(11.0, -1.0, 5.0) }
                                main.run { manager.ask() }
                            }
                            Moment.TWICE -> {
                                main.run { manager.events.onHeading(10.0, -1.0, 5.0) }
                                main.run { manager.ask() }
                                // A valid heading clears the request, so the second question publishes a fourth value.
                                main.run { manager.events.onHeading(20.0, -1.0, 5.0) }
                                main.run { manager.ask() }
                            }
                            Moment.BETWEEN_CLOSE_AND_RELEASE -> {
                                main.run { manager.events.onHeading(10.0, -1.0, 5.0) }
                                collection.cancel()
                                testScheduler.runCurrent()
                                main.run { manager.ask() }
                            }
                            Moment.INSIDE_RELEASE -> {
                                main.run { manager.events.onHeading(10.0, -1.0, 5.0) }
                                device.askInsideRelease = true
                            }
                            Moment.INSIDE_OPENING, Moment.AFTER_RELEASE -> Unit
                        }
                        main.drain()
                        collection.cancel()
                        testScheduler.runCurrent()
                        main.drain()
                        if (moment == Moment.AFTER_RELEASE) {
                            main.run { manager.ask() }
                            main.drain()
                        }

                        assertEquals(emptyList(), main.escaped, "nothing escapes into a callback; $combination")
                        assertEquals(1, manager.releases, "released exactly once; $combination")
                        assertTrue(manager.answers.isNotEmpty(), "control: the question was asked; $combination")
                        for (answer: Answer in manager.answers) {
                            val allowed: Boolean = shows && !answer.releasedAtReturn && answer.collectionLiveAtReturn
                            assertEquals(allowed, answer.show, "the overlay is allowed exactly while the collection lives; $combination; answers=${manager.answers}")
                            if (shows && !answer.show) refusedAfterEnd++
                        }
                        assertEquals(if (manager.agreed) 1 else 0, manager.dismissals, "an agreed overlay is dismissed once at release, a never agreed one never; $combination")
                        if (manager.agreed) agreedSome++
                    }
                }
            }
        }
        assertTrue(agreedSome >= 20, "control: the overlay was agreed to in many scenarios ($agreedSome of $scenarios)")
        assertTrue(refusedAfterEnd >= 20, "control: the overlay was refused after the end in many scenarios ($refusedAfterEnd of $scenarios)")
    }

    // endregion

}
