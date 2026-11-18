@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Group loc-heading, round 7: the give-back of a failing `startLocation` / `stopLocation` against the calls nested
 * inside it (finding m1 of breaker round 6). A failing call gives its claim on the location flag back only when
 * nothing wrote the flag since the claim; a call nested inside it (a delivery that started or stopped location
 * itself, or ended the collection) wrote what it knows about the manager, and that is not overwritten.
 *
 * CoreLocation neither delivers inside a call nor throws Kotlin exceptions; the seam allows both and the session KDoc
 * promises to be robust to them. The model is single-threaded: an inline confine, an unconfined collector, a fake
 * manager whose hooks run after a call took effect and deliver the next event on the spot. Every test has a control
 * that the nested deliveries happened, and the oracle is the fake's problem list plus the invariant that a live
 * manager runs location updates exactly while it is authorized (checked after one more report, because a failed call
 * is retried on the next report). Exhaustive and deterministic: no randomness, no sleeps, no wall clock.
 */
class HeadingIosRound7Test {

    /** An `Error`: the platform failure that must never be absorbed. */
    private class PlatformError(message: String) : Error(message)

    private val device = FakeHeadingDevice()
    private val logger = RecordingLogger()

    private fun failure(error: Boolean): Throwable =
        if (error) PlatformError("platform failure") else IllegalStateException("platform failure")

    private fun provider(): HeadingProvider = IosHeadingProvider(device, false, logger, inlineConfine)

    private fun TestScope.collectForever(): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider().observeHeading(minChangeDegrees = 0.0).collect { }
        }

    private fun FakeHeadingDevice.FakeManager.flip(authorized: Boolean) {
        this.authorized = authorized
        events.onAuthorizationChanged()
    }

    private fun assertConsistent(manager: FakeHeadingDevice.FakeManager, context: String) {
        assertEquals(emptyList(), device.problemList, "no call on a released manager and no start while unauthorized ($context)")
        assertEquals(manager.authorized, manager.locationRunning, "location updates run exactly while authorized ($context)")
    }

    @Test
    fun `a failing start with nothing nested gives its claim back and the next report retries it`() = runTest {
        val collection: Job = collectForever()
        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
        device.startLocationFailure = failure(error = false)

        manager.flip(authorized = true)

        assertEquals(0, manager.locationStarts.load(), "control: the failed start took no effect")
        assertTrue(logger.messages.any { (level: LogLevel, _) -> level == LogLevel.WARN }, "control: the failure was reported")
        device.startLocationFailure = null

        manager.events.onAuthorizationChanged()

        assertEquals(1, manager.locationStarts.load(), "the next report started location: the claim had been given back")
        assertConsistent(manager, "start retried")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    @Test
    fun `a failing stop with nothing nested gives its claim back and the next report retries it`() = runTest {
        device.authorizedAtOpen = true
        val collection: Job = collectForever()
        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
        assertTrue(manager.locationRunning, "control: the opening started location")
        device.stopLocationFailure = failure(error = false)

        manager.flip(authorized = false)

        assertEquals(0, manager.locationStops.load(), "control: the failed stop took no effect")
        assertTrue(manager.locationRunning, "control: location still runs after the failed stop")
        device.stopLocationFailure = null

        manager.events.onAuthorizationChanged()

        assertEquals(1, manager.locationStops.load(), "the next report stopped location: the claim had been given back")
        assertConsistent(manager, "stop retried")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    @Test
    fun `a nested start that failed and gave its claim back does not confuse the stop that succeeds around it`() = runTest {
        device.authorizedAtOpen = true
        val collection: Job = collectForever()
        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
        var nested = 0
        device.afterStopLocation = { inside: FakeHeadingDevice.FakeManager ->
            if (nested == 0) {
                nested++
                device.startLocationFailure = failure(error = false)
                inside.flip(authorized = true)
                device.startLocationFailure = null
            }
        }

        manager.flip(authorized = false)

        assertEquals(1, nested, "control: the grant came inside the stop")
        assertEquals(1, manager.locationStarts.load(), "control: only the opening start took effect, the nested one failed")
        assertEquals(1, manager.locationStops.load(), "control: the outer stop took effect")
        assertTrue(manager.authorized && !manager.locationRunning, "control: authorized and stopped, a start is owed")

        manager.events.onAuthorizationChanged()

        assertEquals(2, manager.locationStarts.load(), "the owed start is issued by the next report")
        assertConsistent(manager, "owed start issued")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    /**
     * Four calls deep, the middle one fails after the two below it took effect. A grant starts location (A); inside it
     * a revocation stops it (B); inside that a grant starts it (C); inside that a revocation stops it (D). B then
     * fails. The last call that took effect is D, a stop: location is stopped and the manager unauthorized. B's
     * give-back must not set the flag to "running", or the next grant starts nothing.
     */
    @Test
    fun `a failing call in the middle of four nested calls does not overwrite what the calls below it wrote`() = runTest {
        for (error: Boolean in listOf(false, true)) {
            val deviceUnderTest = FakeHeadingDevice()
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                IosHeadingProvider(deviceUnderTest, false, RecordingLogger(), inlineConfine)
                    .observeHeading(minChangeDegrees = 0.0)
                    .collect { }
            }
            val manager: FakeHeadingDevice.FakeManager = deviceUnderTest.allManagers.single()
            var startStage = 0
            var stopStage = 0
            deviceUnderTest.afterStartLocation = { inside: FakeHeadingDevice.FakeManager ->
                when (startStage++) {
                    0 -> inside.flip(authorized = false)
                    1 -> inside.flip(authorized = false)
                }
            }
            deviceUnderTest.afterStopLocation = { inside: FakeHeadingDevice.FakeManager ->
                if (stopStage++ == 0) {
                    inside.flip(authorized = true)
                    throw failure(error)
                }
            }

            val escaped: Throwable? = runCatching { manager.flip(authorized = true) }.exceptionOrNull()

            assertEquals(2, startStage, "control: A and C started; error=$error")
            assertEquals(2, stopStage, "control: B and D stopped; error=$error")
            assertEquals(error, escaped is PlatformError, "control: only an Error escapes; error=$error escaped=$escaped")
            assertTrue(!manager.authorized && !manager.locationRunning, "control: the last revocation stopped location; error=$error")
            deviceUnderTest.afterStartLocation = { }
            deviceUnderTest.afterStopLocation = { }

            manager.flip(authorized = true)

            assertEquals(3, manager.locationStarts.load(), "a grant after the failure starts location again; error=$error")
            assertEquals(emptyList(), deviceUnderTest.problemList, "error=$error")
            assertTrue(manager.locationRunning, "error=$error")
            collection.cancelAndJoin()
            assertEquals(1, manager.releases.load(), "error=$error")
        }
    }

    @Test
    fun `a collection that ends inside a failing stop is released once and no call follows`() = runTest {
        for (error: Boolean in listOf(false, true)) {
            val deviceUnderTest = FakeHeadingDevice()
            deviceUnderTest.authorizedAtOpen = true
            lateinit var collection: Job
            collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                IosHeadingProvider(deviceUnderTest, false, RecordingLogger(), inlineConfine)
                    .observeHeading(minChangeDegrees = 0.0)
                    .collect { }
            }
            val manager: FakeHeadingDevice.FakeManager = deviceUnderTest.allManagers.single()
            var ended = 0
            deviceUnderTest.afterStopLocation = { _: FakeHeadingDevice.FakeManager ->
                ended++
                collection.cancel()
                throw failure(error)
            }

            val escaped: Throwable? = runCatching { manager.flip(authorized = false) }.exceptionOrNull()

            assertEquals(1, ended, "control: the stop ran and ended the collection inside itself; error=$error")
            assertEquals(error, escaped is PlatformError, "control: only an Error escapes; error=$error escaped=$escaped")
            collection.cancelAndJoin()
            assertEquals(1, manager.releases.load(), "released exactly once; error=$error")
            assertTrue(manager.isReleased, "error=$error")

            manager.events.onAuthorizationChanged()
            manager.events.onAuthorizationChanged()

            assertEquals(1, manager.locationStops.load(), "no stop after the release; error=$error")
            assertEquals(1, manager.locationStarts.load(), "no start after the release; error=$error")
            assertEquals(emptyList(), deviceUnderTest.problemList, "no call on a released manager; error=$error")
        }
    }
}
