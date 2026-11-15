@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 1 of group loc-heading (decision D-23): tests that try to break the iOS heading provider through
 * its seams, where the executor's suite does not look.
 *
 * - `trueDegrees` is `null` without location authorization (`HeadingProvider.observeHeading` KDoc: "iOS ...
 *   reports trueDegrees == null without authorization"; `Heading.trueDegrees` KDoc), also when CoreLocation still
 *   hands over a `trueHeading` it computed from a location cached before the revocation.
 * - A failure on the start path must not leave a manager running when the app's logger throws.
 * - A collector that cancels itself from inside its own collect (the delegate callback's stack, on the main
 *   thread) releases its manager exactly once and the provider works again afterwards.
 * - Delegate callbacks that arrive while the manager is still being opened do not start location twice or
 *   lose the first heading.
 * - The eager argument check holds on an unavailable device and opens nothing.
 *
 * Single-threaded and deterministic: inline confine, unconfined collector, no sleeps, no wall clock.
 */
class HeadingIosBreakerR1Test {

    private val device = FakeHeadingDevice()
    private val logger = RecordingLogger()

    private fun provider(log: Logger = logger, onDevice: IosHeadingDevice = device): HeadingProvider =
        IosHeadingProvider(onDevice, false, log, inlineConfine)

    private fun TestScope.collect(flow: Flow<Heading>, into: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { heading: Heading -> into += heading } }

    // region true north needs authorization

    @Test
    fun `after a revocation true north is null even when CoreLocation still reports a cached trueHeading`() = runTest {
        device.authorizedAtOpen = true
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 0.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(10.0, 15.0, 5.0)
        assertEquals(15.0, received.last().trueDegrees, "control: authorized, true north is served")

        manager.authorized = false
        manager.events.onAuthorizationChanged()
        assertFalse(manager.locationRunning, "control: location updates stopped on revocation")
        manager.events.onHeading(30.0, 35.0, 5.0)

        assertEquals(30.0, received.last().magneticDegrees, "control: the heading itself is served")
        assertNull(received.last().trueDegrees, "not authorized any more: trueDegrees must be null")

        manager.authorized = true
        manager.events.onAuthorizationChanged()
        manager.events.onHeading(40.0, 45.0, 5.0)
        assertEquals(45.0, received.last().trueDegrees, "control: authorized again, true north is back")
        collection.cancelAndJoin()
    }

    @Test
    fun `a collection that was never authorized never reports true north`() = runTest {
        device.authorizedAtOpen = false
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 0.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        assertFalse(manager.locationRunning, "control: no location updates without authorization")

        manager.events.onHeading(100.0, 104.0, 5.0)

        assertEquals(1, received.size, "control: the heading is served")
        assertNull(received.single().trueDegrees, "without authorization trueDegrees must be null")
        collection.cancelAndJoin()
    }

    // endregion

    // region failure paths

    @Test
    fun `a failed start with a throwing logger leaves no manager running`() = runTest {
        device.startHeadingFailure = IllegalStateException("simulated startUpdatingHeading failure")
        val failure: MutableList<Throwable> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                provider(log = ThrowingLogger()).observeHeading().collect { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                failure += thrown
            }
        }
        collection.cancelAndJoin()

        assertEquals(1, device.openCalls.load(), "control: a manager was opened")
        assertEquals(0, device.liveManagers.size, "the opened manager must be released (failure=$failure)")
        assertTrue(device.allManagers.all { manager -> manager.releases.load() == 1 }, "released exactly once")
    }

    // endregion

    // region re-entrancy

    @Test
    fun `a collector that cancels itself inside the delegate callback releases its manager once and the provider works again`() = runTest {
        val heading: HeadingProvider = provider()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            heading.observeHeading(minChangeDegrees = 0.0).collect { value: Heading ->
                received += value
                // Runs on the stack of CoreLocation's didUpdateHeading (unconfined collector, inline confine).
                cancel()
            }
        }
        val first: FakeHeadingDevice.FakeManager = device.liveManagers.single()

        first.events.onHeading(10.0, -1.0, 5.0)
        testScheduler.runCurrent()

        assertEquals(1, received.size, "control: delivered once")
        assertTrue(collection.isCancelled)
        assertEquals(1, first.releases.load(), "released exactly once, from inside its own callback")
        assertTrue(first.isReleased)
        first.events.onHeading(20.0, -1.0, 5.0)
        first.events.onAuthorizationChanged()
        assertFalse(first.events.onCalibrationRequested(), "a stale overlay question gets the configured answer")
        assertEquals(1, received.size, "a released manager's late callbacks reach nobody")
        assertEquals(1, first.releases.load())

        val again: MutableList<Heading> = mutableListOf()
        val second: Job = collect(heading.observeHeading(minChangeDegrees = 0.0), again)
        val next: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        assertTrue(next !== first, "a fresh manager for the new collection")
        next.events.onHeading(30.0, -1.0, 5.0)
        assertEquals(1, again.size)
        second.cancelAndJoin()
        assertEquals(0, device.liveManagers.size)
    }

    @Test
    fun `callbacks that arrive while the manager is being opened neither start location twice nor lose the first heading`() = runTest {
        device.authorizedAtOpen = true
        device.afterOpen = { count: Int ->
            val events: IosHeadingEvents = device.allManagers[count - 1].events
            // CoreLocation reports the authorization once the delegate is installed, and may deliver a heading.
            events.onAuthorizationChanged()
            events.onHeading(50.0, 55.0, 4.0)
        }
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 0.0), received)

        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        assertEquals(1, manager.locationStarts.load(), "location started exactly once")
        assertTrue(manager.locationRunning)
        assertEquals(1, manager.headingStarts.load())
        assertEquals(1, received.size, "the heading delivered during the opening is served")
        assertEquals(50.0, received.single().magneticDegrees)
        assertTrue(device.problemList.isEmpty(), "problems: ${device.problemList}")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    // endregion

    // region argument check

    @Test
    fun `the argument check is eager on an unavailable device and opens nothing`() {
        val unavailable = FakeHeadingDevice(isHeadingAvailable = false)
        val onUnavailable: HeadingProvider = provider(onDevice = unavailable)
        for (bad: Double in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1e-300, -1.0)) {
            assertFailsWith<IllegalArgumentException>("minChangeDegrees=$bad must throw at the call") { onUnavailable.observeHeading(minChangeDegrees = bad) }
            assertFailsWith<IllegalArgumentException>("minChangeDegrees=$bad must throw at the call") { provider().observeHeading(minChangeDegrees = bad) }
        }
        provider().observeHeading(minChangeDegrees = -0.0)
        assertEquals(0, unavailable.openCalls.load())
        assertEquals(0, device.openCalls.load(), "nothing is opened before collection")
    }

    // endregion

    /** A logger that throws on every call, as a misbehaving app logger may. */
    private class ThrowingLogger : Logger {
        override val tag: String = "ThrowingLogger"

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            throw IllegalStateException("logger failure at $level")
        }
    }
}
