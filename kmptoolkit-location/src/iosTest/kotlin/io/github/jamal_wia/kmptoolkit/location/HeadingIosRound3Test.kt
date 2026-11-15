@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Group loc-heading, round 3: the fix for breaker round 2 finding m1. True north is kept only while the
 * collection is authorized: a `trueHeading` that CoreLocation hands over while not authorized is dropped on
 * arrival, so no later grant can publish it before a fresh heading.
 *
 * Single-threaded and deterministic: inline confine, unconfined collector, the random run uses
 * `Random(HEADING_TEST_SEED + 3)` and prints `seed=` in its messages. No sleeps, no wall clock.
 */
class HeadingIosRound3Test {

    private val device = FakeHeadingDevice()
    private val logger = RecordingLogger()

    private fun provider(): HeadingProvider = IosHeadingProvider(device, false, logger, inlineConfine)

    private fun TestScope.collect(flow: Flow<Heading>, into: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { heading: Heading -> into += heading } }

    @Test
    fun `several headings with a trueHeading while revoked leave nothing behind for the re-grant`() = runTest {
        device.authorizedAtOpen = true
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 45.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(100.0, 104.0, 5.0)
        assertEquals(104.0, received.last().trueDegrees, "control: authorized, true north is served")

        manager.authorized = false
        manager.events.onAuthorizationChanged()
        manager.events.onHeading(150.0, 154.0, 5.0)
        manager.events.onHeading(200.0, 204.0, 5.0)
        assertNull(received.last().trueDegrees, "control: nothing reported while revoked")
        val countBefore: Int = received.size

        manager.authorized = true
        manager.events.onAuthorizationChanged()

        assertEquals(
            emptyList<Double?>(),
            received.drop(countBefore).mapNotNull { heading: Heading -> heading.trueDegrees },
            "the grant alone publishes no true north",
        )
        assertNull(received.last().trueDegrees, "still none after the grant")
        manager.events.onHeading(200.5, 204.5, 5.0)
        assertEquals(204.5, received.last().trueDegrees, "control: the next heading carries it")
        collection.cancelAndJoin()
    }

    @Test
    fun `a heading whose trueHeading is invalid while authorized clears the true north and a later grant does not bring it back`() = runTest {
        device.authorizedAtOpen = true
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 45.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(100.0, 104.0, 5.0)
        assertEquals(104.0, received.last().trueDegrees, "control: served")

        manager.events.onHeading(100.1, -1.0, 5.0)
        assertNull(received.last().trueDegrees, "an invalid trueHeading means no true north")

        manager.authorized = false
        manager.events.onAuthorizationChanged()
        manager.authorized = true
        manager.events.onAuthorizationChanged()
        assertNull(received.last().trueDegrees, "nothing comes back from before")
        collection.cancelAndJoin()
    }

    @Test
    fun `a seeded run of headings and authorization flaps reports true north only for a heading received while authorized`() = runTest {
        val random = Random(HEADING_TEST_SEED + 3)
        device.authorizedAtOpen = random.nextBoolean()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 45.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        var authorized: Boolean = manager.authorized
        var stored: Boolean = false // a true north received while authorized and not revoked since
        var magnetic: Double = 0.0
        for (step: Int in 0 until 300) {
            val message = "seed=${HEADING_TEST_SEED + 3} step=$step authorized=$authorized"
            if (random.nextInt(3) == 0) {
                authorized = !authorized
                manager.authorized = authorized
                manager.events.onAuthorizationChanged()
                if (!authorized) stored = false
            } else {
                // A magnetic heading that always differs by more than the filter, so every heading is emitted.
                magnetic = (magnetic + 90.0) % 360.0
                val validTrue: Boolean = random.nextInt(4) != 0
                manager.events.onHeading(magnetic, if (validTrue) (magnetic + 3.0) % 360.0 else -1.0, 5.0)
                stored = authorized && validTrue
            }
            val last: Heading? = received.lastOrNull()
            if (last != null) {
                assertEquals(authorized && stored, last.trueDegrees != null, "true north present exactly when authorized and received while authorized, $message")
            }
        }
        collection.cancelAndJoin()
    }
}
