@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
 * Breaker round 2 of group loc-heading (decision D-23, ticket item LOC-11): attacks on the iOS true-north gating
 * of round 2.
 *
 * Contract (`createHeadingProvider` KDoc on iOS, `Heading.trueDegrees`, plan 4.4): without authorization
 * `trueDegrees` is `null`, also when CoreLocation still hands over a `trueHeading` it computed from a location cached
 * before a revocation; losing authorization forgets the last true north and regaining it shows true north again with
 * the NEXT heading from CoreLocation (executor round-2 hand-off). So a `trueHeading` that arrived while the collection
 * was not authorized must never surface later, when authorization comes (back) and before a fresh heading.
 *
 * Also: the order of heading and authorization callbacks, and a revocation delivered re-entrantly while a heading is
 * being delivered to the collector.
 *
 * Single-threaded and deterministic: inline confine, unconfined collector. No sleeps, no wall clock.
 */
class HeadingIosBreakerR2Test {

    private val device = FakeHeadingDevice()
    private val logger = RecordingLogger()

    private fun provider(): HeadingProvider = IosHeadingProvider(device, false, logger, inlineConfine)

    private fun TestScope.collect(flow: Flow<Heading>, into: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { heading: Heading -> into += heading } }

    @Test
    fun `a trueHeading received while revoked never surfaces when authorization comes back before a fresh heading`() = runTest {
        device.authorizedAtOpen = true
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 45.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(100.0, 104.0, 5.0)
        assertEquals(104.0, received.single().trueDegrees, "control: authorized, true north is served")

        manager.authorized = false
        manager.events.onAuthorizationChanged()
        assertNull(received.last().trueDegrees, "control: revoked, true north is gone")
        // CoreLocation still computes a trueHeading from the location it cached before the revocation.
        manager.events.onHeading(100.2, 104.2, 5.0)
        assertNull(received.last().trueDegrees, "control: not reported while revoked")
        val countBeforeRegrant: Int = received.size

        manager.authorized = true
        manager.events.onAuthorizationChanged()

        val surfaced: List<Double?> = received.drop(countBeforeRegrant).map { heading: Heading -> heading.trueDegrees }
        assertTrue(
            surfaced.all { trueDegrees: Double? -> trueDegrees == null },
            "the 104.2 CoreLocation handed over while revoked must not appear at the re-grant, only with the next heading; got $surfaced",
        )
        manager.events.onHeading(100.3, 104.3, 5.0)
        assertEquals(104.3, received.last().trueDegrees, "control: the next heading carries true north")
        collection.cancelAndJoin()
    }

    @Test
    fun `a trueHeading received before the first authorization never surfaces at the grant before a fresh heading`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 45.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        // A cached trueHeading from before this collection (another app session) while not authorized.
        manager.events.onHeading(10.0, 14.0, 5.0)
        assertNull(received.single().trueDegrees, "control: not authorized, not reported")

        manager.authorized = true
        manager.events.onAuthorizationChanged()

        assertEquals(
            listOf<Double?>(null),
            received.map { heading: Heading -> heading.trueDegrees },
            "the grant alone must not publish the 14.0 that arrived without authorization",
        )
        assertEquals(1, manager.locationStarts.load(), "control: location updates start at the grant")
        manager.events.onHeading(10.1, 14.1, 5.0)
        assertEquals(14.1, received.last().trueDegrees, "control: the next heading carries true north")
        collection.cancelAndJoin()
    }

    @Test
    fun `an authorization callback before any heading then a heading gives true north at once`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 45.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.authorized = true
        manager.events.onAuthorizationChanged()
        assertTrue(received.isEmpty(), "no heading yet, nothing to emit")

        manager.events.onHeading(200.0, 203.0, 5.0)

        assertEquals(listOf<Double?>(203.0), received.map { heading: Heading -> heading.trueDegrees })
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }

    @Test
    fun `a revocation delivered while a heading is being delivered to the collector wins over the heading's trueHeading`() = runTest {
        device.authorizedAtOpen = true
        val received: MutableList<Heading> = mutableListOf()
        lateinit var manager: FakeHeadingDevice.FakeManager
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider().observeHeading(minChangeDegrees = 45.0).collect { heading: Heading ->
                received += heading
                if (received.size == 1) {
                    // Re-entrant: CoreLocation reports the revocation, then the next heading, while the first one is
                    // still inside the collector.
                    manager.authorized = false
                    manager.events.onAuthorizationChanged()
                    manager.events.onHeading(50.5, 55.5, 5.0)
                }
            }
        }
        manager = device.liveManagers.single()

        manager.events.onHeading(50.0, 55.0, 5.0)

        assertEquals(listOf<Double?>(55.0, null), received.map { heading: Heading -> heading.trueDegrees })
        assertTrue(!manager.locationRunning, "location updates stopped at the revocation")
        assertTrue(device.problemList.isEmpty(), "problems: ${device.problemList}")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load())
    }
}
