@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 5 of group loc-heading (LOC-11, decision D-23): the iOS heading provider through its seams, attacked
 * systematically instead of one site at a time.
 *
 * - The authorization bookkeeping when the manager delivers inside its own calls. The provider KDoc: location updates
 *   run "only while authorized ... start when authorization arrives mid-collection and stop on revocation, never
 *   without authorization"; breaker round 1 pinned "callbacks that arrive while the manager is being opened neither
 *   start location twice". The session writes its `locationRunning` flag after the native call returns, so an
 *   authorization change delivered inside `startLocation`, `stopLocation` or `startHeading` is decided on a flag that
 *   no longer tells the truth. (CoreLocation itself does not deliver inside a call; the seam allows it and the session
 *   KDoc claims to be robust to "a manager that delivers in place from inside such a call".)
 * - The calibration overlay answered for a collection that has ended: `showsSystemCalibration` lets iOS show its
 *   overlay for a collection; CoreLocation keeps that overlay up until calibration completes or
 *   `dismissHeadingCalibrationDisplay` is called, and nothing can call it on a released manager.
 * - The exhaustive model: every point where a value reaches the collector (a heading, a calibration request, a grant,
 *   a revocation, a heading delivered inside `startHeading`, `startLocation`, `stopLocation` or `release`) x every
 *   collector behaviour on that value (continue, cancel itself, `first`, throw, start a fresh collection and cancel
 *   itself) x every platform fault (none, or an exception or an `Error` from `startHeading`, `startLocation`,
 *   `stopLocation`, `isAuthorized` or `release`) x confine (inline, queued). Invariants: no call on a released
 *   manager, every manager released exactly once and nothing left running, an exception of the platform never
 *   escapes into a callback, every `Error` and the collector's own failure surface, and a live manager runs location
 *   updates exactly while it is authorized.
 *
 * Deterministic: a hand-driven main queue, an unconfined collector (the `Dispatchers.Main.immediate` model), exhaustive
 * loops, no randomness, no sleeps.
 */
class HeadingIosBreakerR5Test {

    private enum class Call { START_HEADING, START_LOCATION, STOP_LOCATION, IS_AUTHORIZED, RELEASE }

    /** A device whose managers keep a ledger, and can deliver or fail once inside a chosen call. */
    private class LedgerDevice : IosHeadingDevice {
        override val isHeadingAvailable: Boolean = true
        var authorizedAtOpen: Boolean = false
        val managers: MutableList<LedgerManager> = mutableListOf()
        val problems: MutableList<String> = mutableListOf()

        /** Runs once, inside the first call of this kind on any manager, after the call took effect. */
        var insideCall: Call? = null
        var inside: (LedgerManager) -> Unit = { }

        /** Thrown once by the first call of this kind on the first manager (by `release` after it took effect). */
        var faultCall: Call? = null
        var fault: Throwable? = null
        var faultFired: Boolean = false

        override fun openManager(events: IosHeadingEvents, orientation: ScreenRotation, headingFilterDegrees: Double): IosHeadingManager =
            LedgerManager(events, authorizedAtOpen).also { manager: LedgerManager -> managers += manager }

        inner class LedgerManager(val events: IosHeadingEvents, var authorized: Boolean) : IosHeadingManager {
            var heading: Boolean = false
            var location: Boolean = false
            var released: Boolean = false
            var releases: Int = 0
            var locationStarts: Int = 0
            var locationStops: Int = 0

            private fun check(call: Call) {
                if (released && call != Call.RELEASE) problems += "$call after release"
            }

            private fun faultAt(call: Call) {
                if (faultCall == call && !faultFired && managers.firstOrNull() === this) {
                    faultFired = true
                    throw fault!!
                }
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
                locationStarts++
                location = true
                deliverInside(Call.START_LOCATION)
            }

            override fun stopLocation() {
                check(Call.STOP_LOCATION)
                faultAt(Call.STOP_LOCATION)
                locationStops++
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

        /** Runs [block] on the main thread, as CoreLocation calls back; what escapes it is recorded. */
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

    private fun Throwable.graph(seen: MutableSet<Throwable> = mutableSetOf()): Set<Throwable> {
        if (!seen.add(this)) return seen
        cause?.graph(seen)
        suppressedExceptions.forEach { suppressed: Throwable -> suppressed.graph(seen) }
        return seen
    }

    private fun TestScope.collectForever(provider: HeadingProvider, into: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeHeading(minChangeDegrees = 0.0).collect { heading: Heading -> into += heading }
        }

    // region the authorization bookkeeping when the manager delivers inside its own calls

    @Test
    fun `a revocation delivered inside startLocation of a grant leaves no location updates running unauthorized`() = runTest {
        val device = LedgerDevice()
        val main = MainQueue(inlineAlways = true)
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collectForever(IosHeadingProvider(device, false, CountingLogger(), main.confine), received)
        val manager: LedgerDevice.LedgerManager = device.managers.single()
        main.run { manager.events.onHeading(10.0, -1.0, 5.0) }
        device.insideCall = Call.START_LOCATION
        device.inside = { inside: LedgerDevice.LedgerManager ->
            inside.authorized = false
            inside.events.onAuthorizationChanged()
        }

        main.run {
            manager.authorized = true
            manager.events.onAuthorizationChanged()
        }

        assertEquals(1, manager.locationStarts, "control: the grant started location updates and the revocation came inside that call")
        assertEquals(emptyList(), main.escaped)
        assertFalse(manager.authorized, "control: the manager ends unauthorized")
        assertFalse(
            manager.location,
            "location updates run on an unauthorized manager (starts=${manager.locationStarts}, stops=${manager.locationStops})",
        )
        collection.cancelAndJoin()
        assertEquals(1, manager.releases)
    }

    @Test
    fun `a revocation delivered inside the opening startLocation leaves no location updates running unauthorized`() = runTest {
        val device = LedgerDevice()
        device.authorizedAtOpen = true
        device.insideCall = Call.START_LOCATION
        device.inside = { inside: LedgerDevice.LedgerManager ->
            inside.authorized = false
            inside.events.onAuthorizationChanged()
        }
        val main = MainQueue(inlineAlways = true)
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = collectForever(IosHeadingProvider(device, false, CountingLogger(), main.confine), received)

        val manager: LedgerDevice.LedgerManager = device.managers.single()
        assertEquals(1, manager.locationStarts, "control: the opening started location updates and the revocation came inside that call")
        assertFalse(manager.authorized, "control: the manager ends unauthorized")
        assertFalse(
            manager.location,
            "location updates run on an unauthorized manager (starts=${manager.locationStarts}, stops=${manager.locationStops})",
        )
        collection.cancelAndJoin()
        assertEquals(1, manager.releases)
    }

    @Test
    fun `an authorization report delivered inside the opening startHeading starts location updates once`() = runTest {
        val device = LedgerDevice()
        device.authorizedAtOpen = true
        device.insideCall = Call.START_HEADING
        // CoreLocation reports the authorization once the delegate is installed (breaker round 1 pinned the same
        // report arriving from openManager: "neither start location twice").
        device.inside = { inside: LedgerDevice.LedgerManager -> inside.events.onAuthorizationChanged() }
        val main = MainQueue(inlineAlways = true)
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = collectForever(IosHeadingProvider(device, false, CountingLogger(), main.confine), received)

        val manager: LedgerDevice.LedgerManager = device.managers.single()
        assertTrue(manager.location, "control: location updates run while authorized")
        assertEquals(1, manager.locationStarts, "location updates were started more than once on one manager")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases)
    }

    @Test
    fun `a grant delivered inside stopLocation of a revocation leaves location updates running while authorized`() = runTest {
        val device = LedgerDevice()
        device.authorizedAtOpen = true
        val main = MainQueue(inlineAlways = true)
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collectForever(IosHeadingProvider(device, false, CountingLogger(), main.confine), received)
        val manager: LedgerDevice.LedgerManager = device.managers.single()
        assertTrue(manager.location, "control: location updates run while authorized")
        device.insideCall = Call.STOP_LOCATION
        device.inside = { inside: LedgerDevice.LedgerManager ->
            inside.authorized = true
            inside.events.onAuthorizationChanged()
        }

        main.run {
            manager.authorized = false
            manager.events.onAuthorizationChanged()
        }

        assertEquals(1, manager.locationStops, "control: the revocation stopped location updates and the grant came inside that call")
        assertTrue(manager.authorized, "control: the manager ends authorized")
        assertTrue(
            manager.location,
            "an authorized manager runs no location updates, so true north never comes back " +
                "(starts=${manager.locationStarts}, stops=${manager.locationStops})",
        )
        collection.cancelAndJoin()
        assertEquals(1, manager.releases)
    }

    // endregion

    // region the calibration overlay of a collection that has ended

    @Test
    fun `the overlay is not requested for a manager released inside the calibration callback`() = runTest {
        val device = LedgerDevice()
        val main = MainQueue(inlineAlways = true)
        val provider = IosHeadingProvider(device, true, CountingLogger(), main.confine)
        var ended: Heading? = null
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ended = provider.observeHeading(minChangeDegrees = 90.0).first { heading: Heading -> heading.needsCalibration }
        }
        val manager: LedgerDevice.LedgerManager = device.managers.single()
        main.run { manager.events.onHeading(100.0, -1.0, 5.0) }
        var stale: Boolean? = null
        var answer: Boolean? = null

        main.run { answer = manager.events.onCalibrationRequested() }

        assertTrue(ended?.needsCalibration == true, "control: the request reached the collector inside the callback")
        assertTrue(collection.isCompleted, "control: the collection ended inside the callback")
        assertEquals(1, manager.releases, "control: the manager was released inside the callback")
        assertEquals(false, answer, "CoreLocation was told to show its overlay for a manager released inside this callback")
        main.run { stale = manager.events.onCalibrationRequested() }
        assertEquals(false, stale, "a stale overlay question of a released manager asks for the overlay")
    }

    @Test
    fun `the overlay is still requested while the collection lives`() = runTest {
        val device = LedgerDevice()
        val main = MainQueue(inlineAlways = true)
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collectForever(IosHeadingProvider(device, true, CountingLogger(), main.confine), received)
        val manager: LedgerDevice.LedgerManager = device.managers.single()
        var answer: Boolean? = null

        main.run { answer = manager.events.onCalibrationRequested() }

        assertEquals(true, answer, "the configured overlay is allowed while the collection runs")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases)
    }

    // endregion

    // region the exhaustive model

    private enum class Point {
        HEADING, CALIBRATION, GRANT, REVOCATION, IN_START_HEADING, IN_OPENING_START_LOCATION,
        IN_GRANT_START_LOCATION, IN_STOP_LOCATION, IN_RELEASE,
    }

    private enum class Behaviour { CONTINUE, CANCEL, FIRST, THROW, RECOLLECT }

    private class Fault(val call: Call?, val error: Boolean) {
        override fun toString(): String = if (call == null) "none" else "$call:${if (error) "Error" else "Exception"}"
    }

    @Test
    fun `every emission point collector behaviour platform fault and confine keeps the ledger`() = runTest {
        val faults: List<Fault> = listOf(Fault(null, false)) +
            Call.entries.flatMap { call: Call -> listOf(Fault(call, false), Fault(call, true)) }
        var endedInsideCallback = 0
        var faultsFired = 0
        var scenarios = 0
        for (point: Point in Point.entries) {
            for (behaviour: Behaviour in Behaviour.entries) {
                for (fault: Fault in faults) {
                    for (queued: Boolean in listOf(false, true)) {
                        scenarios++
                        val combination = "point=$point behaviour=$behaviour fault=$fault queued=$queued"
                        val device = LedgerDevice()
                        device.authorizedAtOpen = point in setOf(Point.REVOCATION, Point.IN_OPENING_START_LOCATION, Point.IN_STOP_LOCATION)
                        val injected: Throwable? = when {
                            fault.call == null -> null
                            fault.error -> AssertionError("injected Error at ${fault.call}")
                            else -> IllegalStateException("injected exception at ${fault.call}")
                        }
                        device.faultCall = fault.call
                        device.fault = injected
                        val main = MainQueue(inlineAlways = !queued)
                        val logger = CountingLogger()
                        val provider = IosHeadingProvider(device, false, logger, main.confine)
                        val collectorFailure = IllegalStateException("the collector's own failure")
                        val surfaced: MutableList<Throwable> = mutableListOf()
                        val received: MutableList<Heading> = mutableListOf()
                        val extra: MutableList<Job> = mutableListOf()
                        var armed = false
                        var acted = false
                        val inCall: Call? = when (point) {
                            Point.IN_START_HEADING -> Call.START_HEADING
                            Point.IN_OPENING_START_LOCATION, Point.IN_GRANT_START_LOCATION -> Call.START_LOCATION
                            Point.IN_STOP_LOCATION -> Call.STOP_LOCATION
                            Point.IN_RELEASE -> Call.RELEASE
                            else -> null
                        }
                        device.insideCall = inCall
                        device.inside = { manager: LedgerDevice.LedgerManager -> manager.events.onHeading(222.0, -1.0, 3.0) }

                        fun start(): Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                            try {
                                val flow = provider.observeHeading(minChangeDegrees = 0.0)
                                if (behaviour == Behaviour.FIRST) {
                                    flow.first { heading: Heading ->
                                        received += heading
                                        val now: Boolean = armed && !acted
                                        if (now) {
                                            acted = true
                                            if (main.onMain) endedInsideCallback++
                                        }
                                        now
                                    }
                                } else {
                                    flow.collect { heading: Heading ->
                                        received += heading
                                        if (armed && !acted) {
                                            acted = true
                                            when (behaviour) {
                                                Behaviour.CONTINUE -> Unit
                                                Behaviour.CANCEL -> {
                                                    if (main.onMain) endedInsideCallback++
                                                    cancel()
                                                }
                                                Behaviour.THROW -> {
                                                    if (main.onMain) endedInsideCallback++
                                                    throw collectorFailure
                                                }
                                                Behaviour.RECOLLECT -> {
                                                    if (main.onMain) endedInsideCallback++
                                                    // A fresh, independent collection of the same provider.
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

                        // Points inside the opening are armed from the start; the rest after a first heading.
                        if (point == Point.IN_START_HEADING || point == Point.IN_OPENING_START_LOCATION || point == Point.IN_RELEASE) armed = true
                        val collection: Job = start()
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
                            // A live manager runs location updates exactly while it is authorized, unless a fault
                            // interrupted the bookkeeping (a failed start is retried on the next report).
                            val bookkeepingFault: Boolean = device.faultFired &&
                                fault.call in setOf(Call.START_LOCATION, Call.STOP_LOCATION, Call.IS_AUTHORIZED)
                            for (manager: LedgerDevice.LedgerManager in device.managers) {
                                if (!manager.released && !bookkeepingFault) {
                                    assertEquals(manager.authorized, manager.location, "a live manager runs location updates exactly while authorized; $combination")
                                }
                            }
                            // Late callbacks of every manager, released or not.
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
                        if (device.faultFired) faultsFired++

                        assertTrue(device.managers.isNotEmpty(), "control: a manager was opened; $combination")
                        assertEquals(emptyList(), device.problems, "no call on a released manager; $combination")
                        for ((index: Int, manager: LedgerDevice.LedgerManager) in device.managers.withIndex()) {
                            assertEquals(1, manager.releases, "manager #$index is released exactly once; $combination; surfaced=$surfaced escaped=${main.escaped}")
                            assertFalse(manager.heading || manager.location, "manager #$index runs nothing after the end; $combination")
                        }
                        if (behaviour == Behaviour.RECOLLECT && acted && fault.call == null) {
                            assertEquals(2, device.managers.size, "control: the fresh collection opened its own manager; $combination")
                        }
                        val escapedExceptions: List<Throwable> = main.escaped.filter { thrown: Throwable -> thrown !is Error }
                        assertEquals(emptyList(), escapedExceptions, "an exception escaped into a callback or the main queue; $combination")
                        val reachable: Set<Throwable> = (surfaced + main.escaped).flatMap { thrown: Throwable -> thrown.graph() }.toSet()
                        if (injected != null && device.faultFired) {
                            if (injected is Error) {
                                assertTrue(injected in reachable, "an Error is never absorbed: $injected; $combination; surfaced=$surfaced escaped=${main.escaped}")
                            } else {
                                assertTrue(
                                    injected in reachable || logger.handed.any { handed: Throwable -> handed === injected },
                                    "a platform exception is neither logged nor reachable; $combination",
                                )
                            }
                        }
                        if (behaviour == Behaviour.THROW && acted) {
                            assertTrue(collectorFailure in reachable, "the collector's own failure is lost; $combination; surfaced=$surfaced")
                        }
                        if (fault.call == null) {
                            assertTrue(acted, "control: the collector got the value of the point; $combination; received=$received")
                        }
                    }
                }
            }
        }
        assertTrue(endedInsideCallback >= 100, "control: collectors ended inside a callback ($endedInsideCallback of $scenarios)")
        assertTrue(faultsFired >= 300, "control: injected faults fired ($faultsFired of $scenarios)")
    }

    // endregion
}
