@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 4 of group loc-heading (LOC-11, decision D-23): the iOS heading provider through its seams.
 *
 * - Re-entrancy with a synchronous collector (the loc-ios class of defect: native work after an emission on a
 *   released session). With an inline confine (the main queue, as CoreLocation calls back on it) and a collector
 *   that resumes inline (`Dispatchers.Main.immediate` on the main thread, modelled by an unconfined dispatcher),
 *   publishing a new state inside a delegate callback can run the collector to its end, and its release, before
 *   the callback continues. Contract: `IosHeadingManager.release` ("nothing is delivered afterwards"), the provider
 *   KDoc ("from the moment close returns no event reaches the collection ... the confine stops both updates") and
 *   "Location updates run on the same manager only while authorized ... never without authorization": a released
 *   manager must not be told to start (or stop) location updates afterwards.
 * - The full Throwable matrix of `Session.open()` (round-4 fix): every start failure (none, exception,
 *   `CancellationException`, `Error`) x every release behaviour (fine, exception, `Error`, the start failure's own
 *   instance) x every logger (fine, throws an exception, throws an `Error`) x both confines (inline, queued). The
 *   oracle comes from the provider KDoc: the manager is released exactly once on every exit, nothing is started
 *   after the release, an `Error` is never absorbed (it reaches the collector or the confine), and every other
 *   failure is either handed to the logger or reachable from what surfaced. Exhaustive, no randomness.
 *
 * Single-threaded and deterministic: inline or drained confine, unconfined collector, no sleeps, no wall clock.
 */
class HeadingIosBreakerR4Test {

    // region re-entrancy: the collector ends inside the authorization callback

    @Test
    fun `a collector that ends on the heading published by a grant does not get location started on its released manager`() = runTest {
        val device = FakeHeadingDevice()
        device.authorizedAtOpen = false
        val provider: HeadingProvider = IosHeadingProvider(device, false, RecordingLogger(), inlineConfine)
        val received: MutableList<Heading> = mutableListOf()
        // minChangeDegrees = 0.0 (documented: "0 asks for every update"): every new state passes the filter, so the
        // grant's own state change is emitted to the collector inside setAuthorized.
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeHeading(minChangeDegrees = 0.0).collect { value: Heading ->
                received += value
                if (received.size == 2) cancel()
            }
        }
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(10.0, -1.0, 5.0)
        assertEquals(1, received.size, "control: the first heading is served")
        assertEquals(0, manager.locationStarts.load(), "control: no location updates without authorization")

        manager.authorized = true
        manager.events.onAuthorizationChanged()

        assertEquals(2, received.size, "control: the grant's state change reached the collector inside the callback")
        assertTrue(collection.isCancelled, "control: the collector ended inside the callback")
        assertEquals(1, manager.releases.load(), "control: the manager was released inside the callback")
        assertEquals(
            emptyList(),
            device.problemList,
            "the authorization callback went on with its released manager after the collector ended " +
                "(locationStarts=${manager.locationStarts.load()}, locationRunning=${manager.locationRunning})",
        )
        assertEquals(0, manager.locationStarts.load(), "a released manager must not be told to start location updates")
        assertFalse(manager.locationRunning, "nothing may run on a released manager")
        collection.cancelAndJoin()
    }

    @Test
    fun `a collector that completes with take on the grant's heading does not get location started on its released manager`() = runTest {
        val device = FakeHeadingDevice()
        val provider: HeadingProvider = IosHeadingProvider(device, false, RecordingLogger(), inlineConfine)
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeHeading(minChangeDegrees = 0.0).take(2).collect { value: Heading -> received += value }
        }
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(200.0, -1.0, 5.0)
        manager.authorized = true
        manager.events.onAuthorizationChanged()

        assertEquals(2, received.size, "control: take(2) was satisfied by the grant")
        assertTrue(collection.isCompleted, "control: the collection completed inside the callback")
        assertEquals(1, manager.releases.load(), "control: released once")
        assertEquals(emptyList(), device.problemList, "nothing may be started on the released manager")
        assertEquals(0, manager.locationStarts.load(), "a released manager must not be told to start location updates")
    }

    @Test
    fun `a collector that ends on the heading published by a revocation leaves no call on its released manager`() = runTest {
        val device = FakeHeadingDevice()
        device.authorizedAtOpen = true
        val provider: HeadingProvider = IosHeadingProvider(device, false, RecordingLogger(), inlineConfine)
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeHeading().collect { value: Heading ->
                received += value
                if (received.size == 2) cancel()
            }
        }
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(10.0, 14.0, 5.0)
        assertEquals(1, manager.locationStarts.load(), "control: authorized at open, location runs")

        manager.authorized = false
        manager.events.onAuthorizationChanged()

        assertEquals(2, received.size, "control: true north disappearing is emitted inside the callback")
        assertTrue(collection.isCancelled, "control: the collector ended inside the callback")
        assertEquals(1, manager.releases.load(), "released once")
        assertEquals(0, manager.locationStops.load(), "a released manager is not told to stop location updates afterwards")
        assertEquals(emptyList(), device.problemList)
        collection.cancelAndJoin()
    }

    // endregion

    // region the Throwable matrix of Session.open()

    private enum class Start { NONE, EXCEPTION, CANCELLATION, ERROR }

    private enum class Release { FINE, EXCEPTION, ERROR, SAME_AS_START }

    private enum class Log { FINE, THROWS_EXCEPTION, THROWS_ERROR }

    /** Records every throwable it is handed; throws a fresh instance per call when told to. */
    private class MatrixLogger(private val mode: Log) : Logger {
        val handed: MutableList<Throwable> = mutableListOf()
        val thrownErrors: MutableList<Throwable> = mutableListOf()

        override val tag: String = "MatrixLogger"

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            message()
            if (throwable != null) handed += throwable
            when (mode) {
                Log.FINE -> Unit
                Log.THROWS_EXCEPTION -> throw IllegalStateException("matrix logger refuses $level")
                Log.THROWS_ERROR -> throw AssertionError("matrix logger Error at $level").also { error: AssertionError -> thrownErrors += error }
            }
        }
    }

    private fun Throwable.graph(seen: MutableSet<Throwable> = mutableSetOf()): Set<Throwable> {
        if (!seen.add(this)) return seen
        cause?.graph(seen)
        suppressedExceptions.forEach { suppressed: Throwable -> suppressed.graph(seen) }
        return seen
    }

    @Test
    fun `every start failure release failure logger failure and confine releases once and loses nothing`() = runTest {
        for (start: Start in Start.entries) {
            for (release: Release in Release.entries) {
                if (start == Start.NONE && release == Release.SAME_AS_START) continue
                for (log: Log in Log.entries) {
                    for (queued: Boolean in listOf(false, true)) {
                        val combination = "start=$start release=$release log=$log queued=$queued"
                        val startFailure: Throwable? = when (start) {
                            Start.NONE -> null
                            Start.EXCEPTION -> IllegalStateException("startHeading exception")
                            Start.CANCELLATION -> CancellationException("startHeading cancellation")
                            Start.ERROR -> AssertionError("startHeading Error")
                        }
                        val releaseFailure: Throwable? = when (release) {
                            Release.FINE -> null
                            Release.EXCEPTION -> IllegalArgumentException("release exception")
                            Release.ERROR -> AssertionError("release Error")
                            Release.SAME_AS_START -> startFailure
                        }
                        val device = FakeHeadingDevice()
                        device.authorizedAtOpen = true
                        device.startHeadingFailure = startFailure
                        device.releaseFailure = releaseFailure
                        val logger = MatrixLogger(log)
                        val deferred = DeferredConfine()
                        val provider: HeadingProvider =
                            IosHeadingProvider(device, false, logger, if (queued) deferred.confine else inlineConfine)
                        val surfaced: MutableList<Throwable> = mutableListOf()
                        fun drainAll() {
                            while (deferred.pending > 0) {
                                try {
                                    deferred.drain()
                                } catch (thrown: Throwable) {
                                    surfaced += thrown
                                }
                            }
                        }
                        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                            try {
                                provider.observeHeading().collect { }
                            } catch (thrown: Throwable) {
                                surfaced += thrown
                            }
                        }
                        drainAll()
                        assertEquals(1, device.openCalls.load(), "control: a manager was opened; $combination")
                        collection.cancel()
                        drainAll()
                        testScheduler.runCurrent()
                        drainAll()

                        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
                        assertEquals(1, manager.releases.load(), "the manager is released exactly once; $combination; surfaced=$surfaced")
                        assertTrue(device.liveManagers.isEmpty(), "no manager stays live; $combination")
                        assertFalse(manager.headingRunning || manager.locationRunning, "nothing runs after the end; $combination")
                        assertEquals(emptyList(), device.problemList, "no call on a released manager; $combination")

                        val reachable: Set<Throwable> = surfaced.flatMap { thrown: Throwable -> thrown.graph() }.toSet()
                        val errors: List<Throwable> = listOfNotNull(startFailure, releaseFailure).filter { failure: Throwable -> failure is Error } +
                            logger.thrownErrors
                        for (error: Throwable in errors) {
                            assertTrue(error in reachable, "an Error is never absorbed: $error must surface; $combination; surfaced=$surfaced")
                        }
                        for (failure: Throwable in listOfNotNull(startFailure, releaseFailure)) {
                            assertTrue(
                                failure in reachable || logger.handed.any { handed: Throwable -> handed === failure },
                                "$failure is neither logged nor reachable from what surfaced; $combination; surfaced=$surfaced",
                            )
                        }
                    }
                }
            }
        }
    }

    // endregion
}
