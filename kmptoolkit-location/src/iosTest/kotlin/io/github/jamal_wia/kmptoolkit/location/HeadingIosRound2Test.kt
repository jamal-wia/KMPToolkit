@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Group loc-heading, round 2: the fixes for breaker round 1 on iOS (findings m2 and m3), through the seams.
 *
 * - m2: `trueDegrees` is reported only while the collection knows location is authorized, which it reads inside
 *   the confine when it acts (after opening and on each authorization callback); losing authorization forgets
 *   the true north of the last heading, so regaining it never replays a stale one.
 * - m3: a logger that throws never keeps a manager running, never escapes into a callback, and a manager whose
 *   start, release or location switch throws is still released exactly once.
 *
 * Single-threaded and deterministic: inline confine, unconfined collector, one seeded sequence with `seed=` in
 * its messages. No sleeps, no wall clock.
 */
class HeadingIosRound2Test {

    private val device = FakeHeadingDevice()
    private val logger = RecordingLogger()

    private fun provider(onDevice: IosHeadingDevice = device, log: Logger = logger): HeadingProvider =
        IosHeadingProvider(onDevice, false, log, inlineConfine)

    private fun TestScope.collect(flow: Flow<Heading>, into: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { heading: Heading -> into += heading } }

    private fun TestScope.collectRecordingFailure(flow: Flow<Heading>, failure: MutableList<Throwable>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                flow.collect { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                failure += thrown
            }
        }

    /** A device whose managers can fail on release or on the location switch, over the recording [FakeHeadingDevice]. */
    private class FaultyDevice(private val inner: FakeHeadingDevice) : IosHeadingDevice {
        var releaseFails: Boolean = false
        var locationFails: Boolean = false

        override val isHeadingAvailable: Boolean get() = inner.isHeadingAvailable

        override fun openManager(events: IosHeadingEvents, orientation: ScreenRotation, headingFilterDegrees: Double): IosHeadingManager {
            val real: IosHeadingManager = inner.openManager(events, orientation, headingFilterDegrees)
            return object : IosHeadingManager by real {
                override fun startLocation() {
                    if (locationFails) throw IllegalStateException("simulated startUpdatingLocation failure")
                    real.startLocation()
                }

                override fun stopLocation() {
                    if (locationFails) throw IllegalStateException("simulated stopUpdatingLocation failure")
                    real.stopLocation()
                }

                override fun release() {
                    real.release()
                    if (releaseFails) throw IllegalStateException("simulated release failure")
                }
            }
        }
    }

    private class ThrowingLogger : Logger {
        override val tag: String = "ThrowingLogger"

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            throw IllegalStateException("logger failure at $level")
        }
    }

    // region m2: true north follows the authorization the collection last read

    @Test
    fun `regaining authorization never replays the true north of a heading from before the revocation`() = runTest {
        device.authorizedAtOpen = true
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 45.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(100.0, 104.0, 5.0)
        assertEquals(104.0, received.single().trueDegrees, "control: authorized, true north is served")

        manager.authorized = false
        manager.events.onAuthorizationChanged()
        assertEquals(2, received.size, "the loss of true north is emitted without movement")
        assertNull(received.last().trueDegrees)

        manager.authorized = true
        manager.events.onAuthorizationChanged()
        assertEquals(2, received.size, "nothing is replayed: the stale 104 is gone until CoreLocation delivers a fresh heading")
        manager.events.onHeading(100.1, 104.1, 5.0)
        assertEquals(3, received.size, "null to non-null passes without movement")
        assertEquals(104.1, received.last().trueDegrees)
        collection.cancelAndJoin()
    }

    @Test
    fun `true north appears when authorization is granted in the middle of a collection and the next heading carries it`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 0.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(10.0, 14.0, 5.0)
        assertNull(received.single().trueDegrees, "control: not authorized, whatever CoreLocation hands over")

        manager.authorized = true
        manager.events.onAuthorizationChanged()
        manager.events.onHeading(20.0, 24.0, 5.0)

        assertEquals(24.0, received.last().trueDegrees)
        collection.cancelAndJoin()
    }

    // endregion

    // region m3: a throwing logger and a throwing manager

    @Test
    fun `a manager that cannot be opened with a throwing logger leaves the stream silent and nothing running`() = runTest {
        device.openFailure = IllegalStateException("simulated open failure")
        val failure: MutableList<Throwable> = mutableListOf()

        val collection: Job = collectRecordingFailure(provider(log = ThrowingLogger()).observeHeading(), failure)

        assertEquals(1, device.openCalls.load(), "control: the opening was attempted")
        assertTrue(failure.isEmpty(), "a throwing logger is not the collector's problem: $failure")
        assertTrue(collection.isActive, "open and silent")
        assertEquals(0, device.liveManagers.size)
        collection.cancelAndJoin()
    }

    @Test
    fun `a start failure with a manager that also fails to release and a throwing logger releases once and throws nowhere`() = runTest {
        device.startHeadingFailure = IllegalStateException("simulated startUpdatingHeading failure")
        val faulty = FaultyDevice(device).apply { releaseFails = true }
        val failure: MutableList<Throwable> = mutableListOf()

        val collection: Job = collectRecordingFailure(provider(onDevice = faulty, log = ThrowingLogger()).observeHeading(), failure)
        collection.cancelAndJoin()

        assertEquals(1, device.openCalls.load(), "control: a manager was opened")
        assertTrue(failure.isEmpty(), "nothing reaches the collector: $failure")
        assertEquals(0, device.liveManagers.size)
        assertEquals(listOf(1), device.allManagers.map { manager -> manager.releases.load() }, "released exactly once")
    }

    @Test
    fun `a failing release with a throwing logger neither throws from close nor releases twice`() = runTest {
        device.authorizedAtOpen = true
        val faulty = FaultyDevice(device).apply { releaseFails = true }
        val failure: MutableList<Throwable> = mutableListOf()
        val collection: Job = collectRecordingFailure(provider(onDevice = faulty, log = ThrowingLogger()).observeHeading(), failure)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        assertTrue(manager.locationRunning, "control: running before the cancel")

        collection.cancelAndJoin()

        assertTrue(failure.isEmpty(), "$failure")
        assertEquals(1, manager.releases.load())
        assertEquals(0, device.liveManagers.size)
    }

    @Test
    fun `an authorization callback whose location switch fails with a throwing logger throws nothing into CoreLocation and the compass goes on`() = runTest {
        val faulty = FaultyDevice(device).apply { locationFails = true }
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider(onDevice = faulty, log = ThrowingLogger()).observeHeading(minChangeDegrees = 0.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()

        manager.authorized = true
        val thrown: Throwable? = runCatching { manager.events.onAuthorizationChanged() }.exceptionOrNull()
        manager.events.onHeading(50.0, 54.0, 5.0)

        assertNull(thrown, "CoreLocation's thread never sees an exception: $thrown")
        assertEquals(1, received.size, "the heading is served")
        assertEquals(0, manager.locationStarts.load(), "control: the location switch failed")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    @Test
    fun `a seeded run of failing starts releases flaps and cancels always ends with every manager released once and true north only while authorized`() = runTest {
        val seed: Int = HEADING_TEST_SEED + 2
        val random = Random(seed)
        repeat(150) { round: Int ->
            val roundDevice = FakeHeadingDevice()
            val faulty = FaultyDevice(roundDevice)
            val authorizedAtOpen: Boolean = random.nextBoolean()
            val startFails: Boolean = random.nextInt(6) == 0
            faulty.releaseFails = random.nextBoolean()
            faulty.locationFails = random.nextInt(6) == 0
            val log: Logger = if (random.nextBoolean()) ThrowingLogger() else logger
            roundDevice.authorizedAtOpen = authorizedAtOpen
            if (startFails) roundDevice.startHeadingFailure = IllegalStateException("start failure")
            val note = "round=$round authorizedAtOpen=$authorizedAtOpen startFails=$startFails releaseFails=${faulty.releaseFails} " +
                "locationFails=${faulty.locationFails} seed=$seed"
            val received: MutableList<Heading> = mutableListOf()
            val failure: MutableList<Throwable> = mutableListOf()
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                try {
                    provider(onDevice = faulty, log = log).observeHeading(minChangeDegrees = 0.0).collect { heading: Heading -> received += heading }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (thrown: Throwable) {
                    failure += thrown
                }
            }
            assertEquals(1, roundDevice.openCalls.load(), "control: opened: $note")

            var known: Boolean = authorizedAtOpen
            val manager: FakeHeadingDevice.FakeManager? = roundDevice.allManagers.singleOrNull()
            // After a failed start or location switch the manager is released and, in reality, delivers nothing.
            if (manager != null && !startFails && !faulty.locationFails) {
                repeat(20) { step: Int ->
                    if (random.nextInt(3) == 0) {
                        val granted: Boolean = random.nextBoolean()
                        manager.authorized = granted
                        manager.events.onAuthorizationChanged()
                        known = granted
                    } else {
                        val before: Int = received.size
                        manager.events.onHeading(random.nextDouble() * 360.0, random.nextDouble() * 360.0, 5.0)
                        assertEquals(before + 1, received.size, "a heading is served: step=$step $note")
                        if (!known) assertNull(received.last().trueDegrees, "no true north while unauthorized: step=$step $note")
                    }
                }
            }
            collection.cancelAndJoin()

            assertTrue(failure.isEmpty(), "$failure $note")
            assertEquals(0, roundDevice.liveManagers.size, "every manager released: $note")
            assertTrue(roundDevice.allManagers.all { opened -> opened.releases.load() == 1 }, "released exactly once: $note")
        }
    }

    // endregion
}
