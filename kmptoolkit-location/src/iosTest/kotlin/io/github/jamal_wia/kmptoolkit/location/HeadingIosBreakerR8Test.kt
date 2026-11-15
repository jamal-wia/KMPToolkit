@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 8 of group loc-heading (LOC-11): attacks on the round-8 redesign of the iOS heading session, where
 * `locationRunning` is `Boolean?` and a failing `startLocation` / `stopLocation` leaves it `null` (unknown), so the
 * next report that needs location in either state issues the native call again.
 *
 * Contract under attack (session KDoc, `IosHeadingProvider.kt`): location updates run only while authorized and never
 * on a released manager; a call that throws leaves the flag unknown and the next report repairs the manager; starting
 * or stopping twice is harmless but a report makes at most one call, so a known flag is never re-issued (no storm,
 * no livelock); the manager is released exactly once. Semantics of the fake are the ones of rounds 5 to 8: a call
 * that succeeds takes effect and then may deliver in place; a failing call delivers and throws, without effect or
 * after it took effect.
 *
 * What is new against the earlier models (R5..R7 breaker, executor round 8): the script also drives `startHeading`,
 * so the opening decision of `open()` meets an unknown flag; a call may deliver TWO events in place; an authorization
 * report may be lost (its `isAuthorized` read throws); the confine is inline or queued (the main queue, where a
 * delivery inside a call is decided only after the call returned); the release may fail with an exception or an
 * `Error`. The oracle adds two properties the redesign promises and nothing pinned yet: one fault-free report makes at
 * most ONE native call, and the report after a repair makes NONE (an unknown flag that never becomes known again would
 * re-issue a call on every report). Deterministic: exhaustive loops, no randomness, no sleeps, no wall clock.
 */
class HeadingIosBreakerR8Test {

    private class PlatformError(message: String) : Error(message)

    /** What a manager delivers in place, inside one of its calls. */
    private enum class Event {
        GRANT,
        REVOKE,

        /** A report with nothing changed. */
        SAME,

        /** The collection is cancelled. */
        END,

        /** The authorization flips and the report's `isAuthorized` read throws: the report is lost. */
        LOST_GRANT,
        LOST_REVOKE,
    }

    private enum class Outcome { OK, FAIL, FAIL_AFTER_EFFECT, ERROR }

    private enum class ReleaseFault { NONE, EXCEPTION, ERROR }

    /** What one manager call does: the events it delivers in place, in order, and how it ends. */
    private data class Step(val events: List<Event>, val outcome: Outcome)

    private class ModelDevice(private val authorizedAtOpen: Boolean, private val releaseFault: ReleaseFault) : IosHeadingDevice {
        override val isHeadingAvailable: Boolean = true
        val managers: MutableList<ModelManager> = mutableListOf()
        val problems: MutableList<String> = mutableListOf()
        val script: ArrayDeque<Step> = ArrayDeque()
        var onEnd: () -> Unit = { }
        var failedCalls: Int = 0
        var lostReads: Int = 0

        override fun openManager(events: IosHeadingEvents, orientation: ScreenRotation, headingFilterDegrees: Double): IosHeadingManager =
            ModelManager(events, authorizedAtOpen).also { manager: ModelManager -> managers += manager }

        inner class ModelManager(val events: IosHeadingEvents, var authorized: Boolean) : IosHeadingManager {
            var heading: Boolean = false
            var location: Boolean = false
            var released: Boolean = false
            var releases: Int = 0
            var startCalls: Int = 0
            var stopCalls: Int = 0
            var readFails: Boolean = false
            private var depth: Int = 0

            val locationCalls: Int get() = startCalls + stopCalls

            override val isAuthorized: Boolean
                get() {
                    if (released) problems += "isAuthorized after release"
                    if (readFails) {
                        readFails = false
                        lostReads++
                        throw IllegalStateException("authorization read failed")
                    }
                    return authorized
                }

            override fun startHeading() {
                if (released) problems += "startHeading after release"
                scripted { heading = true }
            }

            override fun startLocation() {
                startCalls++
                if (released) problems += "startLocation after release"
                if (!authorized) problems += "startLocation while unauthorized"
                scripted { location = true }
            }

            override fun stopLocation() {
                stopCalls++
                if (released) problems += "stopLocation after release"
                scripted { location = false }
            }

            override fun release() {
                releases++
                if (releases > 1) problems += "released twice"
                heading = false
                location = false
                released = true
                when (releaseFault) {
                    ReleaseFault.NONE -> Unit
                    ReleaseFault.EXCEPTION -> throw IllegalStateException("release failed after it took effect")
                    ReleaseFault.ERROR -> throw PlatformError("release failed after it took effect")
                }
            }

            /** A finite script nests finitely; a deeper stack means the session re-issues calls on its own. */
            private fun scripted(effect: () -> Unit) {
                depth++
                try {
                    if (depth > MAX_DEPTH) {
                        problems += "calls nested $depth deep: the session re-issues calls from inside its own call"
                        throw PlatformError("runaway nesting")
                    }
                    val step: Step = script.removeFirstOrNull() ?: return effect()
                    if (step.outcome == Outcome.OK || step.outcome == Outcome.FAIL_AFTER_EFFECT) effect()
                    for (event: Event in step.events) deliver(event)
                    when (step.outcome) {
                        Outcome.OK -> Unit
                        Outcome.FAIL, Outcome.FAIL_AFTER_EFFECT -> {
                            failedCalls++
                            throw IllegalStateException("manager call failed")
                        }
                        Outcome.ERROR -> {
                            failedCalls++
                            throw PlatformError("manager call failed")
                        }
                    }
                } finally {
                    depth--
                }
            }

            private fun deliver(event: Event) {
                when (event) {
                    Event.GRANT -> {
                        authorized = true
                        events.onAuthorizationChanged()
                    }
                    Event.REVOKE -> {
                        authorized = false
                        events.onAuthorizationChanged()
                    }
                    Event.SAME -> events.onAuthorizationChanged()
                    Event.END -> onEnd()
                    Event.LOST_GRANT -> {
                        authorized = true
                        readFails = true
                        events.onAuthorizationChanged()
                    }
                    Event.LOST_REVOKE -> {
                        authorized = false
                        readFails = true
                        events.onAuthorizationChanged()
                    }
                }
            }
        }

        private companion object {
            const val MAX_DEPTH: Int = 8
        }
    }

    private fun TestScope.collect(device: IosHeadingDevice, confine: (() -> Unit) -> Unit): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                IosHeadingProvider(device, false, RecordingLogger(), confine).observeHeading(minChangeDegrees = 0.0).collect { }
            } catch (thrown: Throwable) {
                // An Error that ends the collection is the test's to judge, not the scope's to crash on.
                if (thrown is CancellationException) throw thrown
            }
        }

    /** Runs every queued confine block; a block that throws (an `Error` on the main queue) does not stop the rest. */
    private fun DeferredConfine.settle(problems: MutableList<String>) {
        var rounds = 0
        while (pending > 0) {
            runCatching { drain() }
            if (++rounds > 1000) {
                problems += "the confine never drains: blocks keep queueing blocks"
                return
            }
        }
    }

    private class Tally {
        var scenarios: Int = 0
        var failedThenAlive: Int = 0
        var repaired: Int = 0
        var ended: Int = 0
        var lostReads: Int = 0
        var openFailures: Int = 0
        val broken: MutableList<String> = mutableListOf()
    }

    private suspend fun TestScope.runScenario(
        script: List<Step>,
        authorizedAtOpen: Boolean,
        deferred: Boolean,
        releaseFault: ReleaseFault,
        tally: Tally,
    ) {
        tally.scenarios++
        val combination = "open=$authorizedAtOpen deferred=$deferred release=$releaseFault script=$script"
        val device = ModelDevice(authorizedAtOpen, releaseFault)
        device.script.addAll(script)
        val queue = DeferredConfine()
        val confine: (() -> Unit) -> Unit = if (deferred) queue.confine else inlineConfine
        fun settle() {
            if (deferred) queue.settle(device.problems)
        }

        val collection: Job = collect(device, confine)
        device.onEnd = { collection.cancel() }
        settle()
        val manager: ModelDevice.ModelManager = device.managers.single()

        // One real flip mid-collection, through the rest of the script.
        if (!manager.released) {
            runCatching {
                manager.authorized = !manager.authorized
                manager.events.onAuthorizationChanged()
            }
            settle()
        } else {
            tally.openFailures++
        }

        device.script.clear()
        manager.readFails = false
        tally.lostReads += device.lostReads
        assertEquals(emptyList(), device.problems, "no call on a released manager, no start while unauthorized, no runaway; $combination")

        if (manager.released) {
            tally.ended++
        } else {
            if (device.failedCalls > 0) tally.failedThenAlive++

            val beforeRepair: Int = manager.locationCalls
            manager.events.onAuthorizationChanged()
            settle()
            val repairCalls: Int = manager.locationCalls - beforeRepair
            if (repairCalls == 1) tally.repaired++
            if (manager.location != manager.authorized) {
                tally.broken += "$combination: one fault-free unchanged report left authorized=${manager.authorized} location=${manager.location}"
            }
            if (repairCalls > 1) tally.broken += "$combination: one report made $repairCalls native calls"

            val beforeSecond: Int = manager.locationCalls
            manager.events.onAuthorizationChanged()
            settle()
            if (manager.locationCalls != beforeSecond) {
                tally.broken += "$combination: the report after the repair re-issued ${manager.locationCalls - beforeSecond} call(s): the flag stays unknown"
            }

            for (round: Int in 1..2) {
                val target: Boolean = !manager.authorized
                val beforeFlip: Int = manager.locationCalls
                manager.authorized = target
                manager.events.onAuthorizationChanged()
                settle()
                if (manager.location != target) tally.broken += "$combination: after flip $round to authorized=$target location=${manager.location}"
                if (manager.locationCalls - beforeFlip != 1) tally.broken += "$combination: flip $round made ${manager.locationCalls - beforeFlip} calls"
            }
            assertEquals(emptyList(), device.problems, "fault-free reports after the script; $combination")
        }

        collection.cancel()
        settle()
        collection.join()
        assertEquals(1, manager.releases, "released exactly once; $combination")
        assertFalse(manager.heading || manager.location, "nothing runs after the end; $combination")
        assertEquals(emptyList(), device.problems, "nothing after the end; $combination")
    }

    private fun eventLists(maxEvents: Int): List<List<Event>> {
        val result: MutableList<List<Event>> = mutableListOf(emptyList())
        var layer: List<List<Event>> = listOf(emptyList())
        repeat(maxEvents) {
            layer = layer.flatMap { prefix: List<Event> -> Event.entries.map { event: Event -> prefix + event } }
            result += layer
        }
        return result
    }

    private fun steps(maxEvents: Int): List<Step> =
        eventLists(maxEvents).flatMap { events: List<Event> -> Outcome.entries.map { outcome: Outcome -> Step(events, outcome) } }

    /**
     * Two scripted calls (the first is always the opening `startHeading`), each delivering up to TWO events in place
     * and ending in any outcome, from both starting states, inline confine. 2 x 172^2 scenarios.
     */
    @Test
    fun `two events delivered inside any call with any outcome leave a manager that one report repairs with one call`() = runTest(timeout = 300.seconds) {
        val all: List<Step> = steps(maxEvents = 2)
        val tally = Tally()
        for (authorizedAtOpen: Boolean in listOf(false, true)) {
            for (a: Step in all) for (b: Step in all) {
                runScenario(listOf(a, b), authorizedAtOpen, deferred = false, releaseFault = ReleaseFault.NONE, tally = tally)
            }
        }
        assertTrue(tally.failedThenAlive >= 1000, "control: failed calls on a live manager (${tally.failedThenAlive})")
        assertTrue(tally.repaired >= 1000, "control: repairs by the unchanged report (${tally.repaired})")
        assertTrue(tally.lostReads >= 1000, "control: lost authorization reads (${tally.lostReads})")
        assertTrue(tally.ended >= 1000, "control: collections that ended or whose opening failed (${tally.ended})")
        assertEquals(emptyList(), tally.broken.take(12), "${tally.broken.size} of ${tally.scenarios} broken")
    }

    /**
     * Three scripted calls with at most one event each, inline and queued confine, from both starting states: the
     * queued confine decides a delivery only after the call that made it returned (the flag may be unknown by then).
     * 2 x 2 x 28^3 scenarios.
     */
    @Test
    fun `three nested calls on an inline or a queued confine leave a manager that one report repairs with one call`() = runTest(timeout = 300.seconds) {
        val all: List<Step> = steps(maxEvents = 1)
        val tally = Tally()
        for (deferred: Boolean in listOf(false, true)) {
            for (authorizedAtOpen: Boolean in listOf(false, true)) {
                for (a: Step in all) for (b: Step in all) for (c: Step in all) {
                    runScenario(listOf(a, b, c), authorizedAtOpen, deferred, ReleaseFault.NONE, tally)
                }
            }
        }
        assertTrue(tally.failedThenAlive >= 1000, "control: failed calls on a live manager (${tally.failedThenAlive})")
        assertTrue(tally.repaired >= 1000, "control: repairs by the unchanged report (${tally.repaired})")
        assertTrue(tally.lostReads >= 1000, "control: lost authorization reads (${tally.lostReads})")
        assertEquals(emptyList(), tally.broken.take(12), "${tally.broken.size} of ${tally.scenarios} broken")
    }

    /** Two calls with at most one event each, both confines, a release that fails after it took effect. */
    @Test
    fun `a release that fails with an exception or an Error at any position still releases once and calls nothing after`() = runTest(timeout = 300.seconds) {
        val all: List<Step> = steps(maxEvents = 1)
        val tally = Tally()
        for (releaseFault: ReleaseFault in listOf(ReleaseFault.EXCEPTION, ReleaseFault.ERROR)) {
            for (deferred: Boolean in listOf(false, true)) {
                for (authorizedAtOpen: Boolean in listOf(false, true)) {
                    for (a: Step in all) for (b: Step in all) {
                        runScenario(listOf(a, b), authorizedAtOpen, deferred, releaseFault, tally)
                    }
                }
            }
        }
        assertTrue(tally.ended >= 500, "control: collections released inside the script (${tally.ended})")
        assertEquals(emptyList(), tally.broken.take(12), "${tally.broken.size} of ${tally.scenarios} broken")
    }

    /**
     * Livelock and storm: a start that reports in place (nothing changed) and then fails, on every call. The claim is
     * what stops the nested report from starting again inside the failing start; afterwards each report is one call.
     * Turns red if the flag is written after the native call, or set to unknown before it (the nested report would
     * re-issue the start from inside itself, as deep as the manager lets it).
     */
    @Test
    fun `a start that reports in place and fails is issued exactly once per report and never from inside itself`() = runTest(timeout = 300.seconds) {
        val device = ModelDevice(authorizedAtOpen = false, releaseFault = ReleaseFault.NONE)
        val collection: Job = collect(device, inlineConfine)
        val manager: ModelDevice.ModelManager = device.managers.single()
        repeat(REPORTS + 1) { device.script.addLast(Step(listOf(Event.SAME), Outcome.FAIL)) }

        runCatching {
            manager.authorized = true
            manager.events.onAuthorizationChanged()
        }
        assertEquals(1, manager.startCalls, "the grant starts once; the report inside the failing start starts nothing")
        assertEquals(1, device.failedCalls, "control: the start failed")
        repeat(REPORTS) { manager.events.onAuthorizationChanged() }
        assertEquals(REPORTS + 1, manager.startCalls, "one start per report while the flag is unknown, none from inside a call")
        assertEquals(REPORTS + 1, device.failedCalls, "control: every start failed")
        assertTrue(device.script.isEmpty(), "control: every scripted step was used")
        manager.events.onAuthorizationChanged()
        assertTrue(manager.location, "the first start that works starts location")
        manager.events.onAuthorizationChanged()
        assertEquals(REPORTS + 2, manager.startCalls, "a known flag is not re-issued")
        assertEquals(emptyList(), device.problems)
        collection.cancel()
        collection.join()
        assertEquals(1, manager.releases)
    }

    /** The mirror: a stop that reports in place and fails, while unauthorized. */
    @Test
    fun `a stop that reports in place and fails is issued exactly once per report and never from inside itself`() = runTest(timeout = 300.seconds) {
        val device = ModelDevice(authorizedAtOpen = true, releaseFault = ReleaseFault.NONE)
        val collection: Job = collect(device, inlineConfine)
        val manager: ModelDevice.ModelManager = device.managers.single()
        assertTrue(manager.location, "control: the opening started location")
        repeat(REPORTS + 1) { device.script.addLast(Step(listOf(Event.SAME), Outcome.FAIL)) }

        manager.authorized = false
        manager.events.onAuthorizationChanged()
        assertEquals(1, manager.stopCalls, "the revocation stops once; the report inside the failing stop stops nothing")
        repeat(REPORTS) { manager.events.onAuthorizationChanged() }
        assertEquals(REPORTS + 1, manager.stopCalls, "one stop per report while the flag is unknown, none from inside a call")
        assertTrue(manager.location, "control: no stop took effect")
        manager.events.onAuthorizationChanged()
        assertFalse(manager.location, "the first stop that works stops location")
        manager.events.onAuthorizationChanged()
        assertEquals(REPORTS + 2, manager.stopCalls, "a known flag is not re-issued")
        assertEquals(1, manager.startCalls, "control: only the opening started")
        assertEquals(emptyList(), device.problems)
        collection.cancel()
        collection.join()
        assertEquals(1, manager.releases)
    }

    /**
     * An unknown flag is neither true nor false: after a failed start a revocation still stops (once), after a failed
     * stop a grant still starts (once), and a collection that ends while the flag is unknown is released once with no
     * extra native call (the release stops both updates itself).
     */
    @Test
    fun `an unknown flag is acted on in both directions and the end of the collection calls only release`() = runTest(timeout = 300.seconds) {
        for (startFailed: Boolean in listOf(true, false)) {
            val device = ModelDevice(authorizedAtOpen = !startFailed, releaseFault = ReleaseFault.NONE)
            val collection: Job = collect(device, inlineConfine)
            val manager: ModelDevice.ModelManager = device.managers.single()
            device.script.addLast(Step(emptyList(), Outcome.FAIL_AFTER_EFFECT))
            manager.authorized = startFailed
            manager.events.onAuthorizationChanged()
            assertEquals(1, device.failedCalls, "control: the call failed after it took effect; startFailed=$startFailed")
            assertEquals(startFailed, manager.location, "control: the effect happened; startFailed=$startFailed")

            manager.authorized = !startFailed
            manager.events.onAuthorizationChanged()
            assertEquals(!startFailed, manager.location, "the unknown flag is acted on; startFailed=$startFailed")

            // Unknown again, then the end.
            device.script.addLast(Step(emptyList(), Outcome.FAIL))
            manager.authorized = startFailed
            manager.events.onAuthorizationChanged()
            val callsBeforeEnd: Int = manager.locationCalls
            collection.cancel()
            collection.join()
            assertEquals(callsBeforeEnd, manager.locationCalls, "the end makes no start or stop; startFailed=$startFailed")
            assertEquals(1, manager.releases, "released once; startFailed=$startFailed")
            manager.authorized = !startFailed
            manager.events.onAuthorizationChanged()
            assertEquals(callsBeforeEnd, manager.locationCalls, "a late report calls nothing; startFailed=$startFailed")
            assertEquals(emptyList(), device.problems, "startFailed=$startFailed")
        }
    }

    private companion object {
        const val REPORTS: Int = 50
    }
}
