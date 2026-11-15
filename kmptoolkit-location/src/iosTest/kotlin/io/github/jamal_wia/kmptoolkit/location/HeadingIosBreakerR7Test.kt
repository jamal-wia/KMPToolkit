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
 * Breaker round 7 of group loc-heading (LOC-11, decision D-23): the iOS heading provider through its seams, after the
 * round-7 fix of round-6 m1 (a write counter `locationWrites`; a failing `startLocation` / `stopLocation` gives its
 * claim on `locationRunning` back only when nothing wrote the flag since the claim).
 *
 * The documented semantics are kept exactly: a call that throws took no effect and "a failed call is retried on the
 * next report" (session KDoc); a call that succeeds took effect. The model below follows them to the letter: a
 * succeeding call takes effect and THEN may deliver an event in place (the shape of rounds 5 to 7); a failing call may
 * deliver an event in place and then throws WITHOUT taking effect. So the truth is never in doubt: the device state is
 * exactly what the succeeding calls did, and the provider's flag must agree with it (or the next report could not
 * retry what a failed call owes). The oracle: after the scripted step, with no fault left, a report that flips the
 * authorization, and one that flips it back, each leave location running exactly while authorized; no call reaches a
 * released manager and no start is made while unauthorized.
 *
 * Attacked here: a failing call whose nested call ALSO failed and gave its claim back. The counter counts that
 * give-back as a write "that knows better", so the outer give-back is skipped, although the nested give-back only
 * restored what the outer call had assumed. Deterministic: exhaustive loops, an inline confine (the main queue), an
 * unconfined collector, no randomness, no sleeps, no wall clock.
 */
class HeadingIosBreakerR7Test {

    private class PlatformError(message: String) : Error(message)

    private enum class Nested { NONE, GRANT, REVOKE, REPORT, END }

    /** What one location call does: [fails] or takes effect, and which event it delivers in place. */
    private data class Step(val nested: Nested, val fails: Boolean)

    /**
     * Managers whose `startLocation` / `stopLocation` follow a script, one [Step] per call in call order while armed.
     * Plain (no script) calls take effect and deliver nothing.
     */
    private class ScriptDevice : IosHeadingDevice {
        override val isHeadingAvailable: Boolean = true
        var authorizedAtOpen: Boolean = false
        val managers: MutableList<ScriptManager> = mutableListOf()
        val problems: MutableList<String> = mutableListOf()
        var script: ArrayDeque<Step>? = null
        var failure: (call: String) -> Throwable = { call: String -> IllegalStateException("$call failed") }
        var onEnd: () -> Unit = { }
        var scriptedCalls: Int = 0
        var failedCalls: Int = 0
        var nestedDeliveries: Int = 0

        override fun openManager(events: IosHeadingEvents, orientation: ScreenRotation, headingFilterDegrees: Double): IosHeadingManager =
            ScriptManager(events, authorizedAtOpen).also { manager: ScriptManager -> managers += manager }

        inner class ScriptManager(val events: IosHeadingEvents, var authorized: Boolean) : IosHeadingManager {
            var heading: Boolean = false
            var location: Boolean = false
            var released: Boolean = false
            var releases: Int = 0

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
                if (released) problems += "startLocation after release"
                if (!authorized) problems += "startLocation while unauthorized"
                scripted("startLocation") { location = true }
            }

            override fun stopLocation() {
                if (released) problems += "stopLocation after release"
                scripted("stopLocation") { location = false }
            }

            override fun release() {
                releases++
                heading = false
                location = false
                released = true
            }

            private fun scripted(call: String, effect: () -> Unit) {
                val step: Step = script?.removeFirstOrNull() ?: return effect()
                scriptedCalls++
                // A succeeding call takes effect before it delivers; a failing one delivers and takes no effect.
                if (!step.fails) effect()
                when (step.nested) {
                    Nested.NONE -> Unit
                    Nested.GRANT -> deliver { authorized = true }
                    Nested.REVOKE -> deliver { authorized = false }
                    Nested.REPORT -> deliver { }
                    Nested.END -> {
                        nestedDeliveries++
                        onEnd()
                    }
                }
                if (step.fails) {
                    failedCalls++
                    throw failure(call)
                }
            }

            private fun deliver(change: () -> Unit) {
                nestedDeliveries++
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

    /** Two fault-free reports, each flipping the authorization; `null` when location followed both, else the miss. */
    private fun ScriptDevice.ScriptManager.settle(): String? {
        for (round: Int in 1..2) {
            val target: Boolean = !authorized
            flip(target)
            if (location != target) return "after flip $round to authorized=$target location=$location"
        }
        return null
    }

    // region a failing call whose nested call failed too

    /**
     * Authorized and running. A revocation stops location (A); inside A the manager delivers a grant, which starts
     * location (B); B fails (nothing nested in it) and gives its claim back; then A fails. Neither call took effect:
     * location still runs, and the manager is authorized again. The flag must say "running", or the next revocation
     * stops nothing and location runs without authorization. A and B fail with an exception or an `Error` each.
     */
    @Test
    fun `a failing stop whose nested start also failed leaves the flag on what the device does`() = runTest {
        val wrong: MutableList<String> = mutableListOf()
        for (outerError: Boolean in listOf(false, true)) {
            for (innerError: Boolean in listOf(false, true)) {
                val combination = "outerError=$outerError innerError=$innerError"
                val device = ScriptDevice()
                device.authorizedAtOpen = true
                val collection: Job = collect(device)
                val manager: ScriptDevice.ScriptManager = device.managers.single()
                assertTrue(manager.location, "control: the opening started location; $combination")
                device.failure = { call: String ->
                    val error: Boolean = if (call == "stopLocation") outerError else innerError
                    if (error) PlatformError("$call failed") else IllegalStateException("$call failed")
                }
                device.script = ArrayDeque(listOf(Step(Nested.GRANT, fails = true), Step(Nested.NONE, fails = true)))

                val escaped: Throwable? = runCatching { manager.flip(authorized = false) }.exceptionOrNull()

                assertEquals(2, device.scriptedCalls, "control: the stop and the nested start both ran; $combination")
                assertEquals(2, device.failedCalls, "control: both failed; $combination")
                assertTrue(manager.authorized && manager.location, "control: authorized again and still running; $combination")
                assertEquals(outerError || innerError, escaped is PlatformError, "control: only an Error escapes; $combination escaped=$escaped")
                device.script = null

                manager.flip(authorized = false)

                if (manager.location) wrong += "$combination: location runs on an unauthorized manager"
                assertEquals(emptyList(), device.problems, combination)
                collection.cancelAndJoin()
                assertEquals(1, manager.releases, "released once; $combination")
            }
        }
        assertEquals(emptyList(), wrong, "a revocation after the failed stop must stop location (a failed call is retried on the next report)")
    }

    /**
     * The mirror: unauthorized and stopped. A grant starts location (A); inside A a revocation is delivered, which
     * stops location (B); B fails and gives its claim back; then A fails. Neither call took effect: location is
     * stopped, the manager unauthorized. The flag must say "stopped", or the next grant starts nothing and true north
     * never comes back.
     */
    @Test
    fun `a failing start whose nested stop also failed leaves the flag on what the device does`() = runTest {
        val wrong: MutableList<String> = mutableListOf()
        for (outerError: Boolean in listOf(false, true)) {
            for (innerError: Boolean in listOf(false, true)) {
                val combination = "outerError=$outerError innerError=$innerError"
                val device = ScriptDevice()
                val collection: Job = collect(device)
                val manager: ScriptDevice.ScriptManager = device.managers.single()
                assertFalse(manager.location, "control: nothing runs before the grant; $combination")
                device.failure = { call: String ->
                    val error: Boolean = if (call == "startLocation") outerError else innerError
                    if (error) PlatformError("$call failed") else IllegalStateException("$call failed")
                }
                device.script = ArrayDeque(listOf(Step(Nested.REVOKE, fails = true), Step(Nested.NONE, fails = true)))

                val escaped: Throwable? = runCatching { manager.flip(authorized = true) }.exceptionOrNull()

                assertEquals(2, device.scriptedCalls, "control: the start and the nested stop both ran; $combination")
                assertEquals(2, device.failedCalls, "control: both failed; $combination")
                assertFalse(manager.authorized || manager.location, "control: unauthorized and stopped; $combination")
                assertEquals(outerError || innerError, escaped is PlatformError, "control: only an Error escapes; $combination escaped=$escaped")
                device.script = null

                manager.flip(authorized = true)

                if (!manager.location) wrong += "$combination: an authorized manager runs no location updates"
                assertEquals(emptyList(), device.problems, combination)
                collection.cancelAndJoin()
                assertEquals(1, manager.releases, "released once; $combination")
            }
        }
        assertEquals(emptyList(), wrong, "a grant after the failed start must start location (true north comes back)")
    }

    // endregion

    // region the exhaustive script model

    /**
     * Every script of four steps (each: one of none / grant / revocation / unchanged report / end of the collection
     * delivered in place, and success or failure) for the location calls a report makes, nested as deep as the script
     * goes, from both starting states. Then the fault-free settle reports. 2 x 10^4 scenarios.
     */
    @Test
    fun `every script of nested deliveries and failing calls leaves the flag on what the device does`() = runTest {
        val steps: List<Step> = Nested.entries.flatMap { nested: Nested -> listOf(Step(nested, false), Step(nested, true)) }
        val broken: MutableList<String> = mutableListOf()
        var scenarios = 0
        var nestedFailures = 0
        var ended = 0
        for (authorizedAtOpen: Boolean in listOf(false, true)) {
            for (a: Step in steps) for (b: Step in steps) for (c: Step in steps) for (d: Step in steps) {
                scenarios++
                val script: List<Step> = listOf(a, b, c, d)
                val combination = "open=$authorizedAtOpen script=$script"
                val device = ScriptDevice()
                device.authorizedAtOpen = authorizedAtOpen
                val collection: Job = collect(device)
                device.onEnd = { collection.cancel() }
                val manager: ScriptDevice.ScriptManager = device.managers.single()
                assertEquals(authorizedAtOpen, manager.location, "control: the opening follows the authorization; $combination")
                device.script = ArrayDeque(script)

                manager.flip(authorized = !authorizedAtOpen)

                val used: Int = device.scriptedCalls
                device.script = null
                if (device.failedCalls >= 2) nestedFailures++
                assertEquals(emptyList(), device.problems, "no call on a released manager, no start while unauthorized; $combination used=$used")
                if (manager.released) {
                    ended++
                } else {
                    manager.settle()?.let { miss: String -> broken += "$combination used=$used: $miss" }
                    assertEquals(emptyList(), device.problems, "settle; $combination")
                }
                collection.cancelAndJoin()
                assertEquals(1, manager.releases, "released exactly once; $combination")
                assertFalse(manager.heading || manager.location, "nothing runs after the end; $combination")
            }
        }
        assertTrue(nestedFailures >= 1000, "control: many scenarios had a failing call inside a failing call ($nestedFailures of $scenarios)")
        assertTrue(ended >= 1000, "control: many collections ended inside a call ($ended of $scenarios)")
        assertEquals(emptyList(), broken.take(12), "location must follow the authorization after a failed call (${broken.size} of $scenarios broken)")
    }

    // endregion

    // region release faults and re-entrancy from the release into the session

    private enum class InRelease { NOTHING, REPORT, HEADING, CALIBRATION }

    private enum class ReleaseFault { NONE, EXCEPTION, ERROR }

    /**
     * The session's side of `CoreLocationHeadingManager.release()` (stops, then the dismissal, then nil in `finally`):
     * a release that delivers an event in place (as a dismissal could) and then fails (exception or `Error`, after it
     * took effect, as the wrapper's `finally` now guarantees), for a collection that ends by cancellation. Nothing
     * reaches the released manager, the overlay is refused, the event reaches no collector, and an `Error` is not
     * absorbed. A second end (cancel again) releases nothing more.
     */
    @Test
    fun `a release that delivers in place and then fails still releases once and answers nothing`() = runTest {
        for (inRelease: InRelease in InRelease.entries) {
            for (fault: ReleaseFault in ReleaseFault.entries) {
                val combination = "inRelease=$inRelease fault=$fault"
                var released = 0
                val problems: MutableList<String> = mutableListOf()
                var overlayAnswer: Boolean? = null
                var events: IosHeadingEvents? = null
                val device = object : IosHeadingDevice {
                    override val isHeadingAvailable: Boolean = true
                    override fun openManager(events: IosHeadingEvents, orientation: ScreenRotation, headingFilterDegrees: Double): IosHeadingManager {
                        val sink: IosHeadingEvents = events
                        return object : IosHeadingManager {
                            override val isAuthorized: Boolean
                                get() {
                                    if (released > 0) problems += "isAuthorized after release"
                                    return true
                                }
                            override fun startHeading() { if (released > 0) problems += "startHeading after release" }
                            override fun startLocation() { if (released > 0) problems += "startLocation after release" }
                            override fun stopLocation() { if (released > 0) problems += "stopLocation after release" }
                            override fun release() {
                                released++
                                when (inRelease) {
                                    InRelease.NOTHING -> Unit
                                    InRelease.REPORT -> sink.onAuthorizationChanged()
                                    InRelease.HEADING -> sink.onHeading(77.0, 80.0, 3.0)
                                    InRelease.CALIBRATION -> overlayAnswer = sink.onCalibrationRequested()
                                }
                                when (fault) {
                                    ReleaseFault.NONE -> Unit
                                    ReleaseFault.EXCEPTION -> throw IllegalStateException("dismissal failed")
                                    ReleaseFault.ERROR -> throw PlatformError("dismissal failed")
                                }
                            }
                        }.also { events = sink }
                    }
                }
                val received: MutableList<Heading> = mutableListOf()
                var ending: Throwable? = null
                val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    try {
                        IosHeadingProvider(device, true, RecordingLogger(), inlineConfine)
                            .observeHeading(minChangeDegrees = 0.0)
                            .collect { heading: Heading -> received += heading }
                    } catch (thrown: Throwable) {
                        ending = thrown
                        if (thrown is CancellationException) throw thrown
                    }
                }
                val sink: IosHeadingEvents = events ?: error("control: a manager was opened; $combination")
                sink.onHeading(10.0, -1.0, 5.0)
                assertEquals(1, received.size, "control: the live collection receives a heading; $combination")

                collection.cancelAndJoin()

                assertEquals(1, released, "released exactly once; $combination")
                assertEquals(1, received.size, "nothing delivered during the release reaches the collector; $combination")
                assertEquals(emptyList(), problems, "no call on the released manager; $combination")
                if (inRelease == InRelease.CALIBRATION) assertEquals(false, overlayAnswer, "the overlay is refused during the release; $combination")
                val graph: Set<Throwable> = generateSequence(ending) { thrown: Throwable -> thrown.cause }.toSet() +
                    (ending?.suppressedExceptions ?: emptyList())
                if (fault == ReleaseFault.ERROR) {
                    assertTrue(ending is PlatformError || graph.any { thrown: Throwable -> thrown is PlatformError }, "an Error of the release is not absorbed; $combination ending=$ending")
                } else {
                    assertTrue(ending == null || ending is CancellationException, "an exception of the release is logged, not thrown; $combination ending=$ending")
                }

                // Late callbacks after the end and a second cancel: nothing more.
                sink.onAuthorizationChanged()
                sink.onHeading(20.0, 25.0, 1.0)
                assertFalse(sink.onCalibrationRequested(), "the overlay is refused after the end; $combination")
                collection.cancelAndJoin()
                assertEquals(1, released, "a second end releases nothing; $combination")
                assertEquals(1, received.size, "late callbacks reach no collector; $combination")
                assertEquals(emptyList(), problems, "late callbacks make no call; $combination")
            }
        }
    }

    // endregion
}
