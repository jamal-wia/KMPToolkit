@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
 * Breaker round 3 of group loc-heading (decision D-23, ticket item LOC-11): the iOS true north after the round-3 fix
 * ("keeps a `trueHeading` only while [authorization] holds"; the provider "reads the authorization on the main queue
 * each time it changes", i.e. at `locationManagerDidChangeAuthorization`), attacked on the order of the status
 * change, its callback and a heading in between, on CoreLocation's invalid values (`magneticHeading` -1 / NaN /
 * infinite, `trueHeading` -1 / NaN, a negative or NaN `headingAccuracy`), and by a seeded storm of every event kind
 * against an independent oracle (`seed=` in every message). iOS has no declination API (plan D22, 4.4): a dropped
 * `trueHeading` is never replaced by a computed one, whatever `declinationAt` says.
 *
 * Single-threaded and deterministic: inline confine, unconfined collector, `Random(HEADING_TEST_SEED + 33)`. No
 * sleeps, no wall clock.
 */
class HeadingIosBreakerR3Test {

    private val device = FakeHeadingDevice()
    private val logger = RecordingLogger()

    private fun provider(): HeadingProvider = IosHeadingProvider(device, false, logger, inlineConfine)

    private fun TestScope.collect(flow: Flow<Heading>, into: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { heading: Heading -> into += heading } }

    @Test
    fun `a heading between the grant and its callback is not published as true north and the callback alone publishes nothing`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 0.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(10.0, 12.0, 5.0)
        assertNull(received.last().trueDegrees, "control: not authorized, no true north")

        manager.authorized = true // the status changed, the callback has not been delivered yet
        manager.events.onHeading(20.0, 22.0, 5.0)
        assertNull(received.last().trueDegrees, "a heading before the authorization callback carries no true north")
        manager.events.onAuthorizationChanged()
        assertNull(received.last().trueDegrees, "the callback alone has nothing to publish")
        assertTrue(manager.locationRunning, "control: location updates started at the callback")

        manager.events.onHeading(30.0, 32.0, 5.0)
        assertEquals(32.0, received.last().trueDegrees, "the next heading after the callback carries it")
        collection.cancelAndJoin()
    }

    @Test
    fun `a heading between the revocation and its callback does not survive the callback nor a later grant`() = runTest {
        device.authorizedAtOpen = true
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 0.0, declinationAt = GeoCoordinates(55.75, 37.62)), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(10.0, 12.0, 5.0)
        assertEquals(12.0, received.last().trueDegrees, "control: authorized")

        manager.authorized = false // revoked, the callback is still on its way
        manager.events.onHeading(20.0, 22.0, 5.0)
        manager.events.onAuthorizationChanged()
        assertNull(received.last().trueDegrees, "after the revocation callback there is no true north (no declination fallback on iOS)")
        assertTrue(!manager.locationRunning, "control: location updates stopped")

        manager.authorized = true
        manager.events.onAuthorizationChanged()
        assertNull(received.last().trueDegrees, "the 22.0 from the revocation window is not replayed at the grant")
        collection.cancelAndJoin()
    }

    @Test
    fun `an invalid magnetic heading changes nothing and invalid optional values become null and calibration`() = runTest {
        device.authorizedAtOpen = true
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 0.0), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(100.0, 104.0, 5.0)
        val reference: Heading = received.last()
        assertEquals(Heading(100.0, 104.0, 5.0, false), reference, "control")

        for (magnetic: Double in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            manager.events.onHeading(magnetic, 250.0, -1.0)
            assertEquals(1, received.size, "magneticHeading=$magnetic is not a heading: nothing emitted, nothing stored")
        }
        manager.events.onHeading(100.0, 104.0, 5.0)
        assertEquals(reference, received.last(), "the invalid headings left no trace (no 250.0, no calibration)")

        manager.events.onHeading(360.0, Double.NaN, Double.NaN)
        assertEquals(Heading(0.0, null, null, true), received.last(), "360 is 0; NaN true heading and accuracy are absent; NaN accuracy is invalid")
        manager.events.onHeading(-0.0, 360.0, -0.5)
        val last: Heading = received.last()
        assertEquals(Heading(0.0, 0.0, null, true), last, "-0.0 is 0, a true 360 is 0, a negative accuracy is calibration")
        assertTrue(1.0 / last.magneticDegrees > 0.0, "never -0.0")
        collection.cancelAndJoin()
    }

    /** The oracle's own reduction into [0, 360). */
    private fun reduce(degrees: Double): Double {
        var r: Double = degrees % 360.0
        if (r < 0.0) r += 360.0
        if (r >= 360.0) r = 0.0
        return r + 0.0
    }

    @Test
    fun `a seeded storm of headings status changes callbacks and calibration requests follows the documented rules`() = runTest {
        val seed: Int = HEADING_TEST_SEED + 33
        val random = Random(seed)
        device.authorizedAtOpen = random.nextBoolean()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 0.0, declinationAt = GeoCoordinates(21.42, 39.83)), received)
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()

        // The oracle, written from the KDoc and the plan (4.4), not from the code.
        var providerAuthorized: Boolean = device.authorizedAtOpen
        var magnetic: Double? = null
        var trueNorth: Double? = null
        var accuracy: Double? = null
        var accuracyInvalid = false
        var calibrationRequested = false
        val magneticPool: List<Double> = listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -0.0, 360.0, 359.9)
        val truePool: List<Double> = listOf(-1.0, Double.NaN, 360.0, 0.0)
        val accuracyPool: List<Double> = listOf(-1.0, Double.NaN, 0.0)

        for (step: Int in 0 until 3_000) {
            val kind: Int = random.nextInt(10)
            val message = "seed=$seed step=$step kind=$kind"
            when (kind) {
                in 0..4 -> {
                    val m: Double = if (random.nextInt(4) == 0) magneticPool[random.nextInt(magneticPool.size)] else random.nextDouble(0.0, 360.0)
                    val t: Double = if (random.nextInt(3) == 0) truePool[random.nextInt(truePool.size)] else random.nextDouble(0.0, 360.0)
                    val a: Double = if (random.nextInt(3) == 0) accuracyPool[random.nextInt(accuracyPool.size)] else random.nextDouble(0.0, 30.0)
                    manager.events.onHeading(m, t, a)
                    if (m.isFinite() && m >= 0.0) {
                        magnetic = reduce(m)
                        trueNorth = if (providerAuthorized && t.isFinite() && t >= 0.0) reduce(t) else null
                        val valid: Boolean = a.isFinite() && a >= 0.0
                        accuracy = if (valid) a else null
                        accuracyInvalid = !valid
                        if (valid) calibrationRequested = false
                    }
                }
                5, 6 -> {
                    // The status flips; the callback comes now or with a later step (a heading may land in between).
                    manager.authorized = !manager.authorized
                    if (random.nextBoolean()) {
                        manager.events.onAuthorizationChanged()
                        if (providerAuthorized && !manager.authorized) trueNorth = null
                        providerAuthorized = manager.authorized
                    }
                }
                7 -> {
                    // A callback, possibly a spurious one with no change (CoreLocation also reports at install).
                    manager.events.onAuthorizationChanged()
                    if (providerAuthorized && !manager.authorized) trueNorth = null
                    providerAuthorized = manager.authorized
                }
                8 -> {
                    assertEquals(false, manager.events.onCalibrationRequested(), "showsSystemCalibration=false, $message")
                    calibrationRequested = true
                }
                else -> {
                    // Several headings in a row with valid values, so long runs of each authorization state happen.
                    repeat(3) {
                        val m: Double = random.nextDouble(0.0, 360.0)
                        val t: Double = random.nextDouble(0.0, 360.0)
                        manager.events.onHeading(m, t, 4.0)
                        magnetic = reduce(m)
                        trueNorth = if (providerAuthorized) reduce(t) else null
                        accuracy = 4.0
                        accuracyInvalid = false
                        calibrationRequested = false
                    }
                }
            }
            val expectedMagnetic: Double? = magnetic
            if (expectedMagnetic == null) {
                assertTrue(received.isEmpty(), "nothing before the first valid heading, $message")
            } else {
                val expected = Heading(expectedMagnetic, if (providerAuthorized) trueNorth else null, accuracy, accuracyInvalid || calibrationRequested)
                assertEquals(expected, received.lastOrNull(), "$message providerAuthorized=$providerAuthorized status=${manager.authorized}")
                assertTrue(providerAuthorized || received.last().trueDegrees == null, "no true north while not authorized, $message")
            }
            assertEquals(providerAuthorized, manager.locationRunning, "location updates run exactly while authorized, $message")
        }
        assertTrue(device.problemList.isEmpty(), "seed=$seed problems: ${device.problemList}")
        assertEquals(1, device.allManagers.size, "seed=$seed one manager for the whole collection")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load(), "seed=$seed released once")
        assertTrue(manager.isReleased && !manager.locationRunning && !manager.headingRunning, "seed=$seed nothing runs after the release")
    }
}
