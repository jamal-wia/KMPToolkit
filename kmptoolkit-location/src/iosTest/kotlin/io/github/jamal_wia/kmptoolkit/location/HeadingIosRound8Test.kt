@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Round 8 of group loc-heading (LOC-11), the executor's own tests for the fix of the breaker round 7 finding m1: the
 * iOS heading session's `locationRunning` flag is `Boolean?` (`true`, `false`, or `null` = unknown). A native
 * `startLocation` / `stopLocation` that throws leaves the flag unknown, whatever calls were nested inside it, and the
 * next report that needs location issues the native call again (starting or stopping twice is harmless). There is no
 * write counter and no claim compared with anything, so no nesting depth, throw position or release inside a call
 * can leave the flag disagreeing with the device for good.
 *
 * The model is wider than the breaker's: a location call may succeed (takes effect, then delivers in place), fail
 * without effect (delivers, then throws), or fail after it took effect (takes effect, delivers, then throws); the
 * collection may end inside any call. The oracle has two forms: after the script the fault-free flips follow the
 * authorization at once (the first revocation or grant is a real decision on the flag), and ONE unchanged report
 * (nothing flipped) already leaves location running exactly while authorized, because the unknown flag makes every
 * report repair the manager. Deterministic: exhaustive loops, an inline confine (the main queue), an unconfined collector, no
 * randomness, no sleeps, no wall clock.
 */
class HeadingIosRound8Test {

    private class PlatformError(message: String) : Error(message)

    private enum class Nested { NONE, GRANT, REVOKE, END }

    private enum class Outcome { OK, FAIL, FAIL_AFTER_EFFECT }

    /** What one location call does: how it ends, and which event it delivers in place before it ends. */
    private data class Step(val nested: Nested, val outcome: Outcome)

    private class ScriptDevice : IosHeadingDevice {
        override val isHeadingAvailable: Boolean = true
        var authorizedAtOpen: Boolean = false
        val managers: MutableList<ScriptManager> = mutableListOf()
        val problems: MutableList<String> = mutableListOf()
        var script: ArrayDeque<Step>? = null
        var failure: () -> Throwable = { IllegalStateException("location call failed") }
        var onEnd: () -> Unit = { }
        var failedCalls: Int = 0
        var scriptedCalls: Int = 0

        override fun openManager(events: IosHeadingEvents, orientation: ScreenRotation, headingFilterDegrees: Double): IosHeadingManager =
            ScriptManager(events, authorizedAtOpen).also { manager: ScriptManager -> managers += manager }

        inner class ScriptManager(val events: IosHeadingEvents, var authorized: Boolean) : IosHeadingManager {
            var heading: Boolean = false
            var location: Boolean = false
            var released: Boolean = false
            var releases: Int = 0
            var startCalls: Int = 0
            var stopCalls: Int = 0

            override val isAuthorized: Boolean
                get() {
                    if (released) problems += "isAuthorized after release"
                    return authorized
                }

            override fun startHeading() {
                if (released) problems += "startHeading after release"
                heading = true
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
                heading = false
                location = false
                released = true
            }

            private fun scripted(effect: () -> Unit) {
                val step: Step = script?.removeFirstOrNull() ?: return effect()
                scriptedCalls++
                if (step.outcome != Outcome.FAIL) effect()
                when (step.nested) {
                    Nested.NONE -> Unit
                    Nested.GRANT -> deliver { authorized = true }
                    Nested.REVOKE -> deliver { authorized = false }
                    Nested.END -> onEnd()
                }
                if (step.outcome != Outcome.OK) {
                    failedCalls++
                    throw failure()
                }
            }

            private fun deliver(change: () -> Unit) {
                change()
                events.onAuthorizationChanged()
            }
        }
    }

    private fun TestScope.collect(device: ScriptDevice): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                IosHeadingProvider(device, false, RecordingLogger(), inlineConfine).observeHeading(minChangeDegrees = 0.0).collect { }
            } catch (thrown: Throwable) {
                // An Error that ends the collection is the test's to judge, not the scope's to crash on.
                if (thrown is CancellationException) throw thrown
            }
        }

    private fun ScriptDevice.ScriptManager.flip(authorized: Boolean) {
        this.authorized = authorized
        events.onAuthorizationChanged()
    }

    /** A report that changes nothing: the manager is asked again what it is and the session decides again. */
    private fun ScriptDevice.ScriptManager.unchangedReport() {
        events.onAuthorizationChanged()
    }

    /** Two fault-free reports, each flipping the authorization; `null` when location followed both, else the miss. */
    private fun ScriptDevice.ScriptManager.settle(): String? {
        for (round: Int in 1..2) {
            val target: Boolean = !authorized
            flip(target)
            if (location != target) return "after flip $round to authorized=$target location=$location"
        }
        return null
    }

    // region directed shapes

    /**
     * A failing start, nothing nested: the flag is unknown, so the next UNCHANGED report starts location again (one
     * call, no spin), and once it took effect further reports start nothing.
     */
    @Test
    fun `a failing start is issued again by the next unchanged report and only once`() = runTest {
        val device = ScriptDevice()
        val collection: Job = collect(device)
        val manager: ScriptDevice.ScriptManager = device.managers.single()
        assertFalse(manager.location, "control: nothing runs before the grant")
        device.script = ArrayDeque(listOf(Step(Nested.NONE, Outcome.FAIL)))

        manager.flip(authorized = true)

        assertEquals(1, device.failedCalls, "control: the start failed")
        assertEquals(1, manager.startCalls, "control: one start was made")
        assertFalse(manager.location, "control: the failed start took no effect")
        device.script = null

        manager.unchangedReport()
        assertTrue(manager.location, "the next report starts location again")
        assertEquals(2, manager.startCalls, "one repeat, no spin")
        manager.unchangedReport()
        manager.unchangedReport()
        assertEquals(2, manager.startCalls, "a started location is known: further reports start nothing")
        assertEquals(emptyList(), device.problems)
        collection.cancelAndJoin()
        assertEquals(1, manager.releases)
        assertFalse(manager.location, "nothing runs after the end")
    }

    /**
     * Round-6 shape: a revocation makes the stop A; inside A a grant makes the start B, which succeeds; then A fails.
     * Location runs and the manager is authorized, and the flag is unknown, not "stopped": the next report starts once
     * more (idempotent), and the revocation after it stops location.
     */
    @Test
    fun `a failing stop whose nested start succeeded leaves a manager that the next reports repair`() = runTest {
        val device = ScriptDevice()
        device.authorizedAtOpen = true
        val collection: Job = collect(device)
        val manager: ScriptDevice.ScriptManager = device.managers.single()
        assertTrue(manager.location, "control: the opening started location")
        assertEquals(1, manager.startCalls, "control: one start at the opening")
        device.script = ArrayDeque(listOf(Step(Nested.GRANT, Outcome.FAIL), Step(Nested.NONE, Outcome.OK)))

        manager.flip(authorized = false)

        assertEquals(1, device.failedCalls, "control: only the stop failed")
        assertEquals(1, manager.stopCalls, "control: the stop ran once")
        assertEquals(2, manager.startCalls, "control: the nested start ran")
        assertTrue(manager.authorized && manager.location, "control: authorized again and running")
        device.script = null

        manager.unchangedReport()
        assertEquals(3, manager.startCalls, "the unknown flag makes one repeated start")
        manager.unchangedReport()
        assertEquals(3, manager.startCalls, "and no more")

        manager.flip(authorized = false)
        assertFalse(manager.location, "a revocation stops location")
        assertEquals(2, manager.stopCalls, "the revocation stopped once more")
        assertEquals(emptyList(), device.problems)
        collection.cancelAndJoin()
        assertEquals(1, manager.releases)
    }

    /**
     * Four failing calls, each nested in the one before, alternating start / stop (a grant, a revocation, a grant, a
     * revocation delivered in place). Nothing took effect anywhere; the manager ends unauthorized with location
     * stopped. One unchanged report must leave it right, and so must the next grant.
     */
    @Test
    fun `four failing calls nested in each other leave a manager that the next report repairs`() = runTest {
        val device = ScriptDevice()
        val collection: Job = collect(device)
        val manager: ScriptDevice.ScriptManager = device.managers.single()
        device.script = ArrayDeque(
            listOf(
                Step(Nested.REVOKE, Outcome.FAIL),
                Step(Nested.GRANT, Outcome.FAIL),
                Step(Nested.REVOKE, Outcome.FAIL),
                Step(Nested.NONE, Outcome.FAIL),
            ),
        )

        manager.flip(authorized = true)

        assertEquals(4, device.failedCalls, "control: all four calls failed")
        assertEquals(2, manager.startCalls, "control: two starts ran")
        assertEquals(2, manager.stopCalls, "control: two stops ran")
        assertFalse(manager.authorized || manager.location, "control: unauthorized and stopped")
        device.script = null

        manager.unchangedReport()
        assertFalse(manager.location)
        manager.flip(authorized = true)
        assertTrue(manager.location, "a grant after the chain of failures starts location")
        manager.flip(authorized = false)
        assertFalse(manager.location, "a revocation stops it")
        assertEquals(emptyList(), device.problems)
        collection.cancelAndJoin()
        assertEquals(1, manager.releases)
    }

    /** The collection ends inside a failing call: released once, no call after it, nothing runs, the failure is logged. */
    @Test
    fun `a collection that ends inside a failing call releases once and calls nothing more`() = runTest {
        val device = ScriptDevice()
        device.authorizedAtOpen = true
        val collection: Job = collect(device)
        device.onEnd = { collection.cancel() }
        val manager: ScriptDevice.ScriptManager = device.managers.single()
        device.script = ArrayDeque(listOf(Step(Nested.END, Outcome.FAIL_AFTER_EFFECT)))

        manager.flip(authorized = false)

        assertEquals(1, device.failedCalls, "control: the stop failed after it took effect")
        assertTrue(manager.released, "the collection ended inside the call and released the manager")
        assertEquals(1, manager.releases, "released once")
        assertEquals(1, manager.stopCalls, "no stop after the failed one")
        assertFalse(manager.heading || manager.location, "nothing runs after the end")
        manager.unchangedReport()
        manager.flip(authorized = true)
        assertEquals(1, manager.stopCalls, "late reports call nothing")
        assertEquals(1, manager.startCalls, "late reports start nothing (the one start is the opening's)")
        assertEquals(emptyList(), device.problems)
        collection.cancelAndJoin()
        assertEquals(1, manager.releases, "still released once")
    }

    // endregion

    // region the exhaustive script model

    private class Tally {
        var scenarios: Int = 0
        var nestedFailures: Int = 0
        var failedAfterEffect: Int = 0
        var ended: Int = 0
        var repaired: Int = 0
        val broken: MutableList<String> = mutableListOf()
    }

    private suspend fun TestScope.runScenario(script: List<Step>, authorizedAtOpen: Boolean, error: Boolean, repairFirst: Boolean, tally: Tally) {
        tally.scenarios++
        val combination = "open=$authorizedAtOpen error=$error repairFirst=$repairFirst script=$script"
        val device = ScriptDevice()
        device.authorizedAtOpen = authorizedAtOpen
        device.failure = { if (error) PlatformError("location call failed") else IllegalStateException("location call failed") }
        val collection: Job = collect(device)
        device.onEnd = { collection.cancel() }
        val manager: ScriptDevice.ScriptManager = device.managers.single()
        assertEquals(authorizedAtOpen, manager.location, "control: the opening follows the authorization; $combination")
        device.script = ArrayDeque(script)

        runCatching { manager.flip(authorized = !authorizedAtOpen) }

        device.script = null
        // An Error of a nested call leaves through the outer call's delivery, so the outer call never counts as failed.
        if (device.failedCalls >= 2 || (error && device.failedCalls >= 1 && device.scriptedCalls >= 2)) tally.nestedFailures++
        if (script.any { step: Step -> step.outcome == Outcome.FAIL_AFTER_EFFECT }) tally.failedAfterEffect++
        assertEquals(emptyList(), device.problems, "no call on a released manager, no start while unauthorized; $combination")
        if (manager.released) {
            tally.ended++
        } else {
            if (repairFirst) {
                val callsBefore: Int = manager.startCalls + manager.stopCalls
                manager.unchangedReport()
                if (manager.startCalls + manager.stopCalls > callsBefore) tally.repaired++
                if (manager.location != manager.authorized) {
                    tally.broken += "$combination: one unchanged report left authorized=${manager.authorized} location=${manager.location}"
                }
            }
            manager.settle()?.let { miss: String -> tally.broken += "$combination: $miss" }
            assertEquals(emptyList(), device.problems, "settle; $combination")
        }
        collection.cancelAndJoin()
        assertEquals(1, manager.releases, "released exactly once; $combination")
        assertFalse(manager.heading || manager.location, "nothing runs after the end; $combination")
    }

    private fun steps(): List<Step> = Nested.entries.flatMap { nested: Nested -> Outcome.entries.map { outcome: Outcome -> Step(nested, outcome) } }

    /**
     * Each script runs twice from the same start: once with ONE unchanged report first (the unknown flag must make
     * it repair the manager), and once with the fault-free flips right away (the breaker's oracle: the first
     * revocation or grant after the failures must already find the flag right, so the first flip is a real decision).
     * Every script of four steps (grant, revocation, end of the collection or nothing delivered in place; success,
     * failure without effect, failure after effect) for the location calls a report makes, nested as deep as the
     * script goes, from both starting states: every throw position and every depth up to four, the end inside any
     * call included. 2 x 2 x 12^4 scenarios.
     */
    @Test
    fun `every script of nested deliveries and failing calls leaves location on what the device does`() = runTest {
        val all: List<Step> = steps()
        val tally = Tally()
        for (authorizedAtOpen: Boolean in listOf(false, true)) {
            for (a: Step in all) for (b: Step in all) for (c: Step in all) for (d: Step in all) {
                for (repairFirst: Boolean in listOf(true, false)) {
                    runScenario(listOf(a, b, c, d), authorizedAtOpen, error = false, repairFirst = repairFirst, tally = tally)
                }
            }
        }
        assertTrue(tally.nestedFailures >= 1000, "control: many scenarios had a failing call inside a failing call (${tally.nestedFailures})")
        assertTrue(tally.failedAfterEffect >= 1000, "control: many scenarios had a call that failed after it took effect (${tally.failedAfterEffect})")
        assertTrue(tally.ended >= 1000, "control: many collections ended inside a call (${tally.ended})")
        assertTrue(tally.repaired >= 1000, "control: many scenarios were repaired by the unchanged report (${tally.repaired})")
        assertEquals(emptyList(), tally.broken.take(12), "location must follow the authorization after failed calls (${tally.broken.size} of ${tally.scenarios} broken)")
    }

    /** The same model with an `Error` as the failure (it leaves through the nesting instead of being logged), depth three. */
    @Test
    fun `every script of three nested steps that fail with an Error leaves location on what the device does`() = runTest {
        val all: List<Step> = steps()
        val tally = Tally()
        for (authorizedAtOpen: Boolean in listOf(false, true)) {
            for (a: Step in all) for (b: Step in all) for (c: Step in all) {
                for (repairFirst: Boolean in listOf(true, false)) {
                    runScenario(listOf(a, b, c), authorizedAtOpen, error = true, repairFirst = repairFirst, tally = tally)
                }
            }
        }
        assertTrue(tally.nestedFailures >= 100, "control: failing calls inside failing calls (${tally.nestedFailures})")
        assertTrue(tally.repaired >= 100, "control: repairs by the unchanged report (${tally.repaired})")
        assertEquals(emptyList(), tally.broken.take(12), "location must follow the authorization after failed calls (${tally.broken.size} of ${tally.scenarios} broken)")
    }

    // endregion
}
