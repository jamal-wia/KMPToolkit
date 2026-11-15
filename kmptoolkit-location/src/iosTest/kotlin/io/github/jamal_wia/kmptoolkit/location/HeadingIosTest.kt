@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
 * The iOS heading provider through its seams (PLAN section 6.4 `HeadingIosTest`, group loc-heading): what a
 * collection asks of its manager (orientation, filter, calibration overlay), when location updates run for true
 * north, how raw CoreLocation values become a [Heading], the minimum-change filter, two independent collectors,
 * and the release of the manager. The simulator has no compass and a headless run cannot be authorized, so the
 * manager is scripted; the CoreLocation half itself is in [HeadingIosCoreLocationTest].
 *
 * The lifecycle attacks (cancel racing creation, failing open, storm) are in [HeadingIosAdversarialTest].
 */
class HeadingIosTest {

    private val device = FakeHeadingDevice()
    private val logger = RecordingLogger()

    private fun provider(showsSystemCalibration: Boolean = false): HeadingProvider =
        IosHeadingProvider(device, showsSystemCalibration, logger, inlineConfine)

    private fun TestScope.collect(flow: Flow<Heading>, into: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { heading: Heading -> into += heading } }

    /** One CoreLocation heading update delivered to every live manager. */
    private fun heading(magnetic: Double, trueHeading: Double = -1.0, accuracy: Double = 5.0) {
        device.liveManagers.forEach { manager -> manager.events.onHeading(magnetic, trueHeading, accuracy) }
    }

    // region availability and manager settings

    @Test
    fun `availability is the device's and an unavailable compass stays open and silent without a manager`() = runTest {
        val unavailable = FakeHeadingDevice(isHeadingAvailable = false)
        val provider: HeadingProvider = IosHeadingProvider(unavailable, false, logger, inlineConfine)
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = collect(provider.observeHeading(), received)

        assertFalse(provider.isAvailable)
        assertTrue(collection.isActive, "no compass: open, never completed")
        assertTrue(received.isEmpty())
        assertEquals(0, unavailable.openCalls.load(), "and no manager was created")
        assertTrue(provider(showsSystemCalibration = false).isAvailable, "control: an available device")
        collection.cancelAndJoin()
    }

    @Test
    fun `a collection opens one manager with its orientation and filter and starts heading`() = runTest {
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = collect(
            provider().observeHeading(minChangeDegrees = 2.5, screenRotation = ScreenRotation.ROTATION_90),
            received,
        )

        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        assertEquals(ScreenRotation.ROTATION_90, manager.orientation)
        assertEquals(2.5, manager.headingFilterDegrees)
        assertEquals(1, manager.headingStarts.load())
        assertFalse(manager.locationRunning, "not authorized: no location updates")
        collection.cancelAndJoin()
    }

    @Test
    fun `the overlay answer is the flag and a request is also reported through needsCalibration`() = runTest {
        for (flag: Boolean in listOf(true, false)) {
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collect(provider(showsSystemCalibration = flag).observeHeading(minChangeDegrees = 90.0), received)
            val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
            heading(100.0)

            val answer: Boolean = manager.events.onCalibrationRequested()

            assertEquals(flag, answer, "locationManagerShouldDisplayHeadingCalibration answers showsSystemCalibration")
            assertEquals(listOf(false, true), received.map { it.needsCalibration }, "a request flips needsCalibration without movement")
            heading(100.5, accuracy = 5.0)
            assertEquals(listOf(false, true, false), received.map { it.needsCalibration }, "a valid heading ends the request")
            collection.cancelAndJoin()
        }
    }

    // endregion

    // region values

    @Test
    fun `true north is null while CoreLocation says it is invalid and the heading is normalized`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 0.0), received)

        heading(magnetic = 359.9, trueHeading = -1.0)
        heading(magnetic = 10.0, trueHeading = 14.0)
        heading(magnetic = 360.0, trueHeading = 360.0)
        heading(magnetic = 20.0, trueHeading = Double.NaN)

        assertEquals(listOf(null, 14.0, 0.0, null), received.map { it.trueDegrees })
        assertEquals(0.0, received[2].magneticDegrees, "a full turn reads 0, never 360")
        assertTrue(received.all { it.magneticDegrees >= 0.0 && it.magneticDegrees < 360.0 })
        collection.cancelAndJoin()
    }

    @Test
    fun `a negative accuracy needs calibration and reports no accuracy a valid one is passed through`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 90.0), received)

        heading(100.0, accuracy = 12.5)
        heading(100.0, accuracy = -1.0)
        heading(100.0, accuracy = Double.NaN)
        heading(100.0, accuracy = 0.0)

        assertEquals(listOf(12.5, null, 0.0), received.map { it.accuracyDegrees }, "NaN is the same state as -1: no change, nothing new")
        assertEquals(listOf(false, true, false), received.map { it.needsCalibration })
        collection.cancelAndJoin()
    }

    @Test
    fun `a heading without a valid magnetic direction is not a heading`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 0.0), received)

        heading(-1.0)
        heading(Double.NaN)
        heading(Double.POSITIVE_INFINITY)
        assertTrue(received.isEmpty(), "dropped: $received")
        heading(77.0)

        assertEquals(listOf(77.0), received.map { it.magneticDegrees })
        collection.cancelAndJoin()
    }

    // endregion

    // region location updates for true north

    @Test
    fun `an authorized collection runs location updates at once and an unauthorized one never does`() = runTest {
        device.authorizedAtOpen = true
        val authorizedJob: Job = collect(provider().observeHeading(), mutableListOf())
        val authorized: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        assertEquals(1, authorized.locationStarts.load())
        assertTrue(authorized.locationRunning)
        authorizedJob.cancelAndJoin()

        device.authorizedAtOpen = false
        val deniedJob: Job = collect(provider().observeHeading(), mutableListOf())
        val denied: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        assertEquals(0, denied.locationStarts.load(), "never without authorization")
        assertEquals(1, denied.headingStarts.load(), "control: the heading itself runs")
        deniedJob.cancelAndJoin()
        assertTrue(device.problemList.isEmpty(), device.problemList.toString())
    }

    @Test
    fun `authorization arriving mid collection starts location updates once and revoking stops them`() = runTest {
        val collection: Job = collect(provider().observeHeading(), mutableListOf())
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        assertEquals(0, manager.locationStarts.load(), "control: not running yet")

        manager.authorized = true
        manager.events.onAuthorizationChanged()
        manager.events.onAuthorizationChanged()
        assertEquals(1, manager.locationStarts.load(), "started once, the repeat is no news")
        assertTrue(manager.locationRunning)

        manager.authorized = false
        manager.events.onAuthorizationChanged()
        manager.events.onAuthorizationChanged()
        assertEquals(1, manager.locationStops.load(), "stopped once on revocation")
        assertFalse(manager.locationRunning)

        manager.authorized = true
        manager.events.onAuthorizationChanged()
        assertEquals(2, manager.locationStarts.load(), "granted again: running again")
        collection.cancelAndJoin()
        assertFalse(manager.locationRunning, "and stopped with the collection")
        assertTrue(device.problemList.isEmpty(), device.problemList.toString())
    }

    @Test
    fun `true north appears in the heading once it is valid without the device moving`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 45.0), received)
        heading(100.0, trueHeading = -1.0)
        assertNull(received.single().trueDegrees, "control: not valid yet")

        heading(100.2, trueHeading = 104.0)

        assertEquals(2, received.size, "null to non-null passes the filter without movement")
        assertEquals(104.0, received.last().trueDegrees)
        collection.cancelAndJoin()
    }

    @Test
    fun `declinationAt cannot be used on iOS and is ignored without creating anything`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(
            provider().observeHeading(minChangeDegrees = 0.0, declinationAt = GeoCoordinates(21.4, 39.8)),
            received,
        )

        heading(50.0, trueHeading = -1.0)

        assertNull(received.single().trueDegrees, "no authorization, no true north, whatever declinationAt says")
        assertEquals(1, device.openCalls.load())
        collection.cancelAndJoin()
    }

    // endregion

    // region minimum change

    @Test
    fun `the first reading is emitted at once and steps of 0_4 degrees add up from the last emitted heading`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 1.0), received)

        for (index: Int in 0..6) heading(10.0 + index * 0.4)

        assertEquals(3, received.size, "10.0, 11.2 and 12.4: ${received.map { it.magneticDegrees }}")
        assertEquals(10.0, received[0].magneticDegrees)
        collection.cancelAndJoin()
    }

    @Test
    fun `a turn across north counts as the short way`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 1.0), received)

        heading(359.5)
        heading(0.5)
        heading(0.4)

        assertEquals(2, received.size, "359.5 to 0.5 is one degree, 0.5 to 0.4 is 0.1")
        collection.cancelAndJoin()
    }

    @Test
    fun `a calibration flip is emitted without movement`() = runTest {
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider().observeHeading(minChangeDegrees = 45.0), received)

        heading(100.0, accuracy = 3.0)
        heading(100.0, accuracy = -1.0)

        assertEquals(listOf(false, true), received.map { it.needsCalibration })
        collection.cancelAndJoin()
    }

    // endregion

    // region concurrent collectors and release

    @Test
    fun `two concurrent collectors have their own manager orientation filter and rotation`() = runTest {
        val first: MutableList<Heading> = mutableListOf()
        val second: MutableList<Heading> = mutableListOf()
        val provider: HeadingProvider = provider()
        val firstJob: Job = collect(provider.observeHeading(minChangeDegrees = 1.0, screenRotation = ScreenRotation.NATURAL), first)
        val secondJob: Job = collect(provider.observeHeading(minChangeDegrees = 20.0, screenRotation = ScreenRotation.ROTATION_270), second)

        val managers: List<FakeHeadingDevice.FakeManager> = device.liveManagers
        assertEquals(2, managers.size, "one manager each, nothing shared")
        assertEquals(listOf(ScreenRotation.NATURAL, ScreenRotation.ROTATION_270), managers.map { it.orientation })
        assertEquals(listOf(1.0, 20.0), managers.map { it.headingFilterDegrees })

        managers[0].events.onHeading(10.0, -1.0, 5.0)
        managers[1].events.onHeading(100.0, -1.0, 5.0)
        managers[0].events.onHeading(15.0, -1.0, 5.0)
        managers[1].events.onHeading(105.0, -1.0, 5.0)

        assertEquals(listOf(10.0, 15.0), first.map { it.magneticDegrees })
        assertEquals(listOf(100.0), second.map { it.magneticDegrees }, "its own 20 degree filter: 105 is 5 from 100")

        firstJob.cancelAndJoin()
        assertEquals(1, device.liveManagers.size, "cancelling one releases only its own manager")
        assertTrue(managers[0].isReleased)
        assertFalse(managers[1].isReleased)
        secondJob.cancelAndJoin()
        assertEquals(0, device.liveManagers.size)
    }

    @Test
    fun `cancelling stops heading and location nils the delegate and releases once`() = runTest {
        device.authorizedAtOpen = true
        val collection: Job = collect(provider().observeHeading(), mutableListOf())
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        assertTrue(manager.headingRunning && manager.locationRunning, "control: both updates run")
        assertFalse(manager.isReleased)

        collection.cancelAndJoin()

        assertFalse(manager.headingRunning)
        assertFalse(manager.locationRunning)
        assertTrue(manager.isReleased, "the delegate was set to nil")
        assertEquals(1, manager.releases.load(), "released exactly once")
        assertEquals(0, device.liveManagers.size)
    }

    @Test
    fun `a second provider works after the first was released and so does a second collection`() = runTest {
        val firstProvider: HeadingProvider = provider()
        val firstJob: Job = collect(firstProvider.observeHeading(), mutableListOf())
        firstJob.cancelAndJoin()
        assertEquals(0, device.liveManagers.size, "control: the first one is gone")

        val received: MutableList<Heading> = mutableListOf()
        val secondJob: Job = collect(provider().observeHeading(minChangeDegrees = 0.0), received)
        heading(42.0)
        assertEquals(listOf(42.0), received.map { it.magneticDegrees }, "a new provider after release")
        secondJob.cancelAndJoin()

        val again: MutableList<Heading> = mutableListOf()
        val thirdJob: Job = collect(firstProvider.observeHeading(minChangeDegrees = 0.0), again)
        heading(43.0)
        assertEquals(listOf(43.0), again.map { it.magneticDegrees }, "and the released provider's flow can be collected again")
        thirdJob.cancelAndJoin()
        assertEquals(3, device.openCalls.load())
        assertEquals(3, device.allManagers.count { it.isReleased })
    }

    @Test
    fun `the argument check happens at the call and creates nothing`() {
        val provider: HeadingProvider = provider()

        assertFailsWith<IllegalArgumentException> { provider.observeHeading(minChangeDegrees = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { provider.observeHeading(minChangeDegrees = -0.5) }
        assertFailsWith<IllegalArgumentException> { provider.observeHeading(minChangeDegrees = Double.NEGATIVE_INFINITY) }
        provider.observeHeading(minChangeDegrees = 0.0)
        assertEquals(0, device.openCalls.load())
    }

    // endregion
}
