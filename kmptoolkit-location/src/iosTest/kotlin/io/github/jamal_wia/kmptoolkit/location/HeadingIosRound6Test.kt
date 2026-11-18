@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Group loc-heading, round 6: the authorization bookkeeping of an iOS heading session when the manager delivers an
 * authorization change inside one of its own calls (finding m1 of breaker round 5), and the calibration overlay
 * (finding m2).
 *
 * CoreLocation never delivers inside a call; the seam allows it and the session KDoc promises to be robust to it.
 * The model is single-threaded: an inline confine, an unconfined collector, a fake manager whose hooks run after a
 * call took effect and deliver the next event on the spot. The oracle is the fake's problem list (a call on a
 * released manager, a start while unauthorized), exact call counters, and the invariant that a live manager runs
 * location updates exactly while it is authorized. Every test has a control that the nested delivery happened.
 *
 * Seeded where random (`seed=` and `step=` in every message), no sleeps, no wall clock.
 */
class HeadingIosRound6Test {

    /** An `Error`: the platform failure that must never be absorbed. */
    private class PlatformError(message: String) : Error(message)

    private val device = FakeHeadingDevice()
    private val logger = RecordingLogger()

    private fun provider(showsSystemCalibration: Boolean = false): HeadingProvider =
        IosHeadingProvider(device, showsSystemCalibration, logger, inlineConfine)

    private fun TestScope.collectForever(provider: HeadingProvider, received: MutableList<Heading> = mutableListOf()): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeHeading(minChangeDegrees = 0.0).collect { heading: Heading -> received += heading }
        }

    private fun FakeHeadingDevice.FakeManager.flip(authorized: Boolean) {
        this.authorized = authorized
        events.onAuthorizationChanged()
    }

    private fun assertConsistent(manager: FakeHeadingDevice.FakeManager, context: String) {
        assertEquals(emptyList(), device.problemList, "no call on a released manager and no start while unauthorized ($context)")
        assertEquals(manager.authorized, manager.locationRunning, "location updates run exactly while authorized ($context)")
    }

    // region m1: the flag says what the call is doing

    @Test
    fun `a revocation delivered inside the start of a grant stops location once and leaves it stopped`() = runTest {
        val collection: Job = collectForever(provider())
        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
        var nested = 0
        device.afterStartLocation = { inside: FakeHeadingDevice.FakeManager ->
            nested++
            inside.flip(authorized = false)
        }

        manager.flip(authorized = true)

        assertEquals(1, nested, "control: the revocation came inside the start")
        assertEquals(1, manager.locationStarts.load(), "started once")
        assertEquals(1, manager.locationStops.load(), "stopped once, by the nested revocation")
        assertConsistent(manager, "grant then nested revocation")
        device.afterStartLocation = { }
        manager.flip(authorized = true)
        assertTrue(manager.locationRunning, "the next grant starts location again")
        assertConsistent(manager, "regrant")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    @Test
    fun `a grant delivered inside the stop of a revocation starts location once and leaves it running`() = runTest {
        device.authorizedAtOpen = true
        val collection: Job = collectForever(provider())
        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
        assertEquals(1, manager.locationStarts.load(), "control: the opening started location updates")
        var nested = 0
        device.afterStopLocation = { inside: FakeHeadingDevice.FakeManager ->
            nested++
            inside.flip(authorized = true)
        }

        manager.flip(authorized = false)

        assertEquals(1, nested, "control: the grant came inside the stop")
        assertEquals(1, manager.locationStops.load(), "stopped once")
        assertEquals(2, manager.locationStarts.load(), "started again once, by the nested grant")
        assertConsistent(manager, "revocation then nested grant")
        device.afterStopLocation = { }
        manager.flip(authorized = false)
        assertFalse(manager.locationRunning, "the next revocation stops location")
        assertConsistent(manager, "re-revocation")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    @Test
    fun `a revocation delivered inside the opening start leaves location stopped`() = runTest {
        device.authorizedAtOpen = true
        var nested = 0
        device.afterStartLocation = { inside: FakeHeadingDevice.FakeManager ->
            nested++
            inside.flip(authorized = false)
        }

        val collection: Job = collectForever(provider())

        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
        assertEquals(1, nested, "control: the revocation came inside the opening start")
        assertEquals(1, manager.locationStarts.load())
        assertEquals(1, manager.locationStops.load())
        assertConsistent(manager, "opening start then nested revocation")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    @Test
    fun `an authorization report delivered inside the opening heading start starts location once and later changes still work`() = runTest {
        device.authorizedAtOpen = true
        var nested = 0
        device.afterStartHeading = { inside: FakeHeadingDevice.FakeManager ->
            nested++
            inside.events.onAuthorizationChanged()
        }

        val collection: Job = collectForever(provider())

        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
        assertEquals(1, nested, "control: the report came inside the heading start")
        assertEquals(1, manager.locationStarts.load(), "location updates were started once")
        assertConsistent(manager, "opening")
        manager.flip(authorized = false)
        assertEquals(1, manager.locationStops.load(), "a revocation stops it once")
        assertConsistent(manager, "revocation")
        manager.flip(authorized = true)
        assertEquals(2, manager.locationStarts.load(), "a grant starts it once")
        assertConsistent(manager, "grant")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    @Test
    fun `a grant delivered inside the opening heading start of an unauthorized manager starts location once`() = runTest {
        var nested = 0
        device.afterStartHeading = { inside: FakeHeadingDevice.FakeManager ->
            nested++
            inside.flip(authorized = true)
        }

        val collection: Job = collectForever(provider())

        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
        assertEquals(1, nested, "control: the grant came inside the heading start")
        assertEquals(1, manager.locationStarts.load(), "location updates were started once")
        assertConsistent(manager, "grant inside the opening")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    @Test
    fun `a start that throws gives the flag back so the next report retries it`() = runTest {
        for (failure: Throwable in listOf(IllegalStateException("start refused"), PlatformError("start refused hard"))) {
            val local = FakeHeadingDevice()
            val rolled: IosHeadingProvider = IosHeadingProvider(local, false, logger, inlineConfine)
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                rolled.observeHeading(minChangeDegrees = 0.0).collect { }
            }
            val manager: FakeHeadingDevice.FakeManager = local.allManagers.single()
            local.startLocationFailure = failure
            manager.authorized = true

            val escaped: Throwable? = runCatching { manager.events.onAuthorizationChanged() }.exceptionOrNull()

            if (failure is Exception) {
                assertEquals(null, escaped, "an exception of the platform never escapes into the callback (${failure::class.simpleName})")
                assertTrue(
                    logger.messages.any { (level: LogLevel, message: String) -> level == LogLevel.WARN && "heading location updates" in message },
                    "the failure is logged (${failure::class.simpleName})",
                )
            } else {
                assertEquals(failure, escaped, "an Error is not absorbed (${failure::class.simpleName})")
            }
            assertEquals(0, manager.locationStarts.load(), "control: the start did not take effect (${failure::class.simpleName})")
            local.startLocationFailure = null

            manager.events.onAuthorizationChanged()

            assertEquals(1, manager.locationStarts.load(), "the next report retries the start (${failure::class.simpleName})")
            assertEquals(emptyList(), local.problemList)
            collection.cancelAndJoin()
            assertEquals(1, manager.releases.load())
        }
    }

    @Test
    fun `a stop that throws gives the flag back so the next report retries it`() = runTest {
        for (failure: Throwable in listOf(IllegalStateException("stop refused"), PlatformError("stop refused hard"))) {
            val local = FakeHeadingDevice()
            local.authorizedAtOpen = true
            val rolled: IosHeadingProvider = IosHeadingProvider(local, false, logger, inlineConfine)
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                rolled.observeHeading(minChangeDegrees = 0.0).collect { }
            }
            val manager: FakeHeadingDevice.FakeManager = local.allManagers.single()
            assertTrue(manager.locationRunning, "control: location runs while authorized (${failure::class.simpleName})")
            local.stopLocationFailure = failure
            manager.authorized = false

            val escaped: Throwable? = runCatching { manager.events.onAuthorizationChanged() }.exceptionOrNull()

            if (failure is Exception) {
                assertEquals(null, escaped, "an exception of the platform never escapes into the callback (${failure::class.simpleName})")
            } else {
                assertEquals(failure, escaped, "an Error is not absorbed (${failure::class.simpleName})")
            }
            assertEquals(0, manager.locationStops.load(), "control: the stop did not take effect (${failure::class.simpleName})")
            local.stopLocationFailure = null

            manager.events.onAuthorizationChanged()

            assertEquals(1, manager.locationStops.load(), "the next report retries the stop (${failure::class.simpleName})")
            assertFalse(manager.locationRunning)
            assertEquals(emptyList(), local.problemList)
            collection.cancelAndJoin()
            assertEquals(1, manager.releases.load())
        }
    }

    @Test
    fun `a grant delivered inside a stop that then throws leaves location running and the flag agreeing`() = runTest {
        device.authorizedAtOpen = true
        val collection: Job = collectForever(provider())
        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
        device.afterStopLocation = { inside: FakeHeadingDevice.FakeManager ->
            device.afterStopLocation = { }
            inside.flip(authorized = true)
            // The platform call fails after it already delivered the grant.
            throw IllegalStateException("stop failed after delivering")
        }

        manager.flip(authorized = false)

        assertTrue(manager.locationRunning, "control: the nested grant started location again")
        assertEquals(2, manager.locationStarts.load(), "control: the nested grant started location")
        assertConsistent(manager, "grant inside a failing stop")
        // The flag agrees with the device: the next revocation stops it exactly once, a repeated report does nothing.
        manager.flip(authorized = false)
        manager.events.onAuthorizationChanged()
        assertEquals(2, manager.locationStops.load(), "the next revocation stops location once")
        assertConsistent(manager, "revocation after the failing stop")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    @Test
    fun `a seeded storm of authorization changes delivered inside the manager's own calls keeps location in step with authorization`() = runTest {
        var nestedDeliveries = 0
        var storms = 0
        for (seed: Int in 1..40) {
            val rng = Random(HEADING_TEST_SEED + 66 + seed)
            val storm = FakeHeadingDevice()
            storm.authorizedAtOpen = rng.nextBoolean()
            var depth = 0

            fun maybeFlipInside(manager: FakeHeadingDevice.FakeManager) {
                if (depth < 3 && rng.nextInt(3) == 0) {
                    nestedDeliveries++
                    depth++
                    try {
                        manager.flip(authorized = rng.nextBoolean())
                    } finally {
                        depth--
                    }
                }
            }
            storm.afterStartHeading = ::maybeFlipInside
            storm.afterStartLocation = ::maybeFlipInside
            storm.afterStopLocation = ::maybeFlipInside
            val stormProvider: HeadingProvider = IosHeadingProvider(storm, false, logger, inlineConfine)
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                stormProvider.observeHeading(minChangeDegrees = 0.0).collect { }
            }
            val manager: FakeHeadingDevice.FakeManager = storm.allManagers.single()
            assertEquals(manager.authorized, manager.locationRunning, "seed=$seed step=open: location in step with authorization")

            repeat(40) { step: Int ->
                manager.flip(authorized = rng.nextBoolean())
                assertEquals(emptyList(), storm.problemList, "seed=$seed step=$step: no call on a released manager, no start while unauthorized")
                assertEquals(manager.authorized, manager.locationRunning, "seed=$seed step=$step: location in step with authorization")
            }
            storms++
            collection.cancelAndJoin()
            assertEquals(1, manager.releases.load(), "seed=$seed: released once")
            assertEquals(emptyList(), storm.problemList, "seed=$seed: nothing after release")
        }
        assertEquals(40, storms)
        assertTrue(nestedDeliveries >= 200, "control: many authorization changes were delivered inside a call ($nestedDeliveries)")
    }

    // endregion

    // region m2: the overlay of a collection that is over

    private enum class End { CANCEL, TAKE, FIRST }

    /** Collects [flow] and ends on the [n]-th heading, the way [how] says. */
    private fun TestScope.collectUntil(flow: Flow<Heading>, n: Int, how: End, received: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            when (how) {
                End.CANCEL -> flow.collect { value: Heading ->
                    received += value
                    if (received.size == n) cancel()
                }
                End.TAKE -> flow.take(n).collect { value: Heading -> received += value }
                End.FIRST -> flow.first { value: Heading ->
                    received += value
                    received.size == n
                }
            }
        }

    @Test
    fun `the overlay is allowed while the collection lives and refused when the collector ended inside the question`() = runTest {
        for (how: End in End.entries) {
            val local = FakeHeadingDevice()
            val received: MutableList<Heading> = mutableListOf()
            val provider: HeadingProvider = IosHeadingProvider(local, true, logger, inlineConfine)
            val collection: Job = collectUntil(provider.observeHeading(minChangeDegrees = 90.0), n = 3, how, received)
            val manager: FakeHeadingDevice.FakeManager = local.allManagers.single()
            manager.events.onHeading(100.0, -1.0, 5.0)
            assertEquals(1, received.size, "control: the first heading is served ($how)")

            val live: Boolean = manager.events.onCalibrationRequested()

            assertEquals(2, received.size, "control: the request was served ($how)")
            assertTrue(live, "the overlay is allowed while the collection runs ($how)")
            assertFalse(collection.isCompleted, "control: the collection is still open ($how)")
            // The next heading ends the collection from inside the callback that publishes it.
            manager.events.onHeading(250.0, -1.0, 5.0)
            assertTrue(collection.isCompleted, "control: the collector ended inside the heading callback ($how)")
            assertEquals(1, manager.releases.load(), "control: released ($how)")

            assertFalse(manager.events.onCalibrationRequested(), "a stale question after the end is refused ($how)")
            assertEquals(emptyList(), local.problemList)
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `a collector that ends on the calibration flip is refused the overlay for every ending and both configurations`() = runTest {
        for (how: End in End.entries) {
            for (flag: Boolean in listOf(false, true)) {
                val local = FakeHeadingDevice()
                val received: MutableList<Heading> = mutableListOf()
                val provider: HeadingProvider = IosHeadingProvider(local, flag, logger, inlineConfine)
                val collection: Job = collectUntil(provider.observeHeading(minChangeDegrees = 90.0), n = 2, how, received)
                val manager: FakeHeadingDevice.FakeManager = local.allManagers.single()
                manager.events.onHeading(100.0, -1.0, 5.0)

                val answer: Boolean = manager.events.onCalibrationRequested()

                assertEquals(listOf(false, true), received.map { it.needsCalibration }, "control: the flip reached the collector inside the callback ($how, flag=$flag)")
                assertTrue(collection.isCompleted, "control: the collector ended inside the callback ($how, flag=$flag)")
                assertEquals(1, manager.releases.load(), "control: released inside the callback ($how, flag=$flag)")
                assertFalse(answer, "no overlay for a collection that ended on its own request ($how, flag=$flag)")
                collection.cancelAndJoin()
            }
        }
    }

    @Test
    fun `a question that arrives between the close and the queued release is refused`() = runTest {
        val local = FakeHeadingDevice()
        val queue = DeferredConfine()
        val provider: HeadingProvider = IosHeadingProvider(local, true, logger, queue.confine)
        val collection: Job = collectUntil(provider.observeHeading(minChangeDegrees = 0.0), n = 99, End.CANCEL, mutableListOf())
        queue.drain()
        val manager: FakeHeadingDevice.FakeManager = local.allManagers.single()
        assertTrue(manager.events.onCalibrationRequested(), "control: the overlay is allowed while the collection lives")

        collection.cancelAndJoin()

        assertEquals(1, queue.pending, "control: the release waits in the queue")
        assertEquals(0, manager.releases.load(), "control: the manager is not released yet")
        assertFalse(manager.events.onCalibrationRequested(), "a question in the window before the release is refused")
        queue.drain()
        assertEquals(1, manager.releases.load())
        assertFalse(manager.events.onCalibrationRequested(), "and after it")
        assertEquals(emptyList(), local.problemList)
    }

    @Test
    fun `a collection started after another one ended on its overlay request is allowed its own overlay`() = runTest {
        val local = FakeHeadingDevice()
        val provider: HeadingProvider = IosHeadingProvider(local, true, logger, inlineConfine)
        val first: Job = collectUntil(provider.observeHeading(minChangeDegrees = 0.0), n = 2, End.CANCEL, mutableListOf())
        val ended: FakeHeadingDevice.FakeManager = local.allManagers.single()
        ended.events.onHeading(100.0, -1.0, 5.0)
        ended.events.onCalibrationRequested()
        assertTrue(first.isCompleted, "control: the first collection ended on its request")

        val second: Job = collectUntil(provider.observeHeading(minChangeDegrees = 0.0), n = 99, End.CANCEL, mutableListOf())

        val fresh: FakeHeadingDevice.FakeManager = local.allManagers.last()
        assertTrue(fresh !== ended, "control: a fresh manager serves the second collection")
        assertTrue(fresh.events.onCalibrationRequested(), "the second collection is allowed its overlay")
        assertFalse(ended.events.onCalibrationRequested(), "the first one still is not")
        second.cancelAndJoin()
        assertEquals(1, fresh.releases.load())
    }

    // endregion
}
