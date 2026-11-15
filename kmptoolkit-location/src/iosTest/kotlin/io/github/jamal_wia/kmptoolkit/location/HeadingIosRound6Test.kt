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
import kotlinx.coroutines.cancelAndJoin
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
}
