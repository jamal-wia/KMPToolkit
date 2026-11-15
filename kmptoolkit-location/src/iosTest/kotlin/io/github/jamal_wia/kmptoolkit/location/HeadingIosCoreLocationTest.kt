@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLDeviceOrientationLandscapeLeft
import platform.CoreLocation.CLDeviceOrientationLandscapeRight
import platform.CoreLocation.CLDeviceOrientationPortrait
import platform.CoreLocation.CLDeviceOrientationPortraitUpsideDown
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLHeadingFilterNone
import platform.CoreLocation.kCLLocationAccuracyThreeKilometers

/**
 * The CoreLocation half of the iOS heading provider (PLAN section 6.4 `HeadingIosTest`, group loc-heading): the
 * `CLDeviceOrientation` of each [ScreenRotation], what the real manager is configured with, its delegate's
 * answer to the calibration overlay, and that releasing it nils the delegate. A `CLLocationManager` can be
 * created and configured in a headless simulator run; no authorization, location or compass is needed because
 * nothing here waits for a delivery.
 *
 * Not observed on a device: real heading deliveries, the calibration overlay itself, and what `trueHeading`
 * reads after location updates start.
 */
class HeadingIosCoreLocationTest {

    /** Records the events a delegate forwards. */
    private class RecordingEvents(private val overlayAnswer: Boolean = true) : IosHeadingEvents {
        var calibrationRequests: Int = 0
        var authorizationChanges: Int = 0

        override fun onHeading(magneticHeading: Double, trueHeading: Double, headingAccuracy: Double) = Unit

        override fun onAuthorizationChanged() {
            authorizationChanges++
        }

        override fun onCalibrationRequested(): Boolean {
            calibrationRequests++
            return overlayAnswer
        }
    }

    private fun open(rotation: ScreenRotation, filter: Double): CoreLocationHeadingManager {
        val manager: IosHeadingManager =
            CoreLocationHeadingDevice(NoopLogger).openManager(RecordingEvents(), rotation, filter)
        return manager as CoreLocationHeadingManager
    }

    @Test
    fun `each screen rotation maps to its device orientation`() {
        // D23: ROTATION_90 is LandscapeLeft (home button on the right), 180 is upside down, 270 LandscapeRight.
        assertEquals(CLDeviceOrientationPortrait, ScreenRotation.NATURAL.toDeviceOrientation())
        assertEquals(CLDeviceOrientationLandscapeLeft, ScreenRotation.ROTATION_90.toDeviceOrientation())
        assertEquals(CLDeviceOrientationPortraitUpsideDown, ScreenRotation.ROTATION_180.toDeviceOrientation())
        assertEquals(CLDeviceOrientationLandscapeRight, ScreenRotation.ROTATION_270.toDeviceOrientation())
        assertEquals(4, ScreenRotation.entries.map { it.toDeviceOrientation() }.toSet().size, "no two rotations share an orientation")
    }

    @Test
    fun `the real manager gets the orientation and the heading filter of the collection`() {
        for (rotation: ScreenRotation in ScreenRotation.entries) {
            val opened: CoreLocationHeadingManager = open(rotation, filter = 2.5)
            val manager: CLLocationManager = assertNotNull(opened.coreLocationManager)

            assertEquals(rotation.toDeviceOrientation(), manager.headingOrientation, "rotation=$rotation")
            assertEquals(2.5, manager.headingFilter)
            opened.release()
        }
    }

    @Test
    fun `a filter of zero asks CoreLocation for every update`() {
        val opened: CoreLocationHeadingManager = open(ScreenRotation.NATURAL, filter = 0.0)

        assertEquals(kCLHeadingFilterNone, assertNotNull(opened.coreLocationManager).headingFilter)
        opened.release()
    }

    @Test
    fun `location updates for true north run at the three kilometer accuracy`() {
        val opened: CoreLocationHeadingManager = open(ScreenRotation.NATURAL, filter = 1.0)
        val manager: CLLocationManager = assertNotNull(opened.coreLocationManager)

        opened.startLocation()

        assertEquals(kCLLocationAccuracyThreeKilometers, manager.desiredAccuracy)
        opened.stopLocation()
        opened.release()
    }

    @Test
    fun `release nils the delegate drops the manager and can be repeated`() {
        val opened: CoreLocationHeadingManager = open(ScreenRotation.NATURAL, filter = 1.0)
        val manager: CLLocationManager = assertNotNull(opened.coreLocationManager)
        assertNotNull(manager.delegate, "control: the delegate is set while the collection runs")

        opened.startHeading()
        opened.release()

        assertNull(manager.delegate, "the delegate was set to nil before the manager was dropped")
        assertNull(opened.coreLocationManager)
        opened.release()
        assertNull(manager.delegate)
    }

    @Test
    fun `the delegate asks the collection whether to show the calibration overlay and answers with its answer`() {
        for (overlay: Boolean in listOf(true, false)) {
            val events = RecordingEvents(overlayAnswer = overlay)
            val delegate = HeadingDelegate(events, logger = NoopLogger)

            val answer: Boolean = delegate.locationManagerShouldDisplayHeadingCalibration(CLLocationManager())

            assertEquals(overlay, answer, "locationManagerShouldDisplayHeadingCalibration answers what the collection says")
            assertEquals(1, events.calibrationRequests, "the request is always reported")
            delegate.locationManagerDidChangeAuthorization(CLLocationManager())
            assertEquals(1, events.authorizationChanges, "an authorization change is forwarded without a value")
        }
    }

    @Test
    fun `releasing a manager whose delegate agreed to the overlay dismisses it once and one that never agreed dismisses nothing`() {
        for (overlay: Boolean in listOf(true, false)) {
            val dismissed: MutableList<CLLocationManager> = mutableListOf()
            val delegate = HeadingDelegate(RecordingEvents(overlayAnswer = overlay), logger = NoopLogger)
            val coreLocation = CLLocationManager()
            val opened = CoreLocationHeadingManager(coreLocation, delegate) { manager: CLLocationManager -> dismissed += manager }
            assertFalse(delegate.calibrationDisplayAgreed, "control: nothing was asked yet (overlay=$overlay)")

            delegate.locationManagerShouldDisplayHeadingCalibration(coreLocation)
            assertEquals(overlay, delegate.calibrationDisplayAgreed, "the delegate remembers that it agreed (overlay=$overlay)")
            assertEquals(0, dismissed.size, "control: nothing is dismissed while the collection runs (overlay=$overlay)")

            opened.release()

            if (overlay) {
                assertEquals(1, dismissed.size, "the overlay that was agreed to is dismissed on release")
                assertTrue(dismissed.single() === coreLocation, "it is dismissed on the manager that asked for it")
            } else {
                assertEquals(0, dismissed.size, "a manager that never agreed to the overlay dismisses nothing")
            }
            opened.release()
            assertEquals(if (overlay) 1 else 0, dismissed.size, "a second release dismisses nothing again (overlay=$overlay)")
        }
    }

    @Test
    fun `the default dismissal of a released manager is the CoreLocation one and does not need an overlay to be up`() {
        val delegate = HeadingDelegate(RecordingEvents(overlayAnswer = true), logger = NoopLogger)
        val coreLocation = CLLocationManager()
        val opened = CoreLocationHeadingManager(coreLocation, delegate)
        delegate.locationManagerShouldDisplayHeadingCalibration(coreLocation)

        opened.release()

        assertNull(coreLocation.delegate, "control: the release ran to its end after the dismissal")
        assertNull(opened.coreLocationManager)
    }

    @Test
    fun `a dismissal that throws still ends the release once and a second release dismisses nothing`() {
        val delegate = HeadingDelegate(RecordingEvents(overlayAnswer = true), logger = NoopLogger)
        val coreLocation = CLLocationManager()
        var dismissals = 0
        val opened = CoreLocationHeadingManager(coreLocation, delegate) { _: CLLocationManager ->
            dismissals++
            throw IllegalStateException("the dismissal failed")
        }
        delegate.locationManagerShouldDisplayHeadingCalibration(coreLocation)
        assertNotNull(coreLocation.delegate, "control: the delegate is set while the collection runs")
        opened.startHeading()

        val failure: Throwable? = runCatching { opened.release() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "the dismissal failure leaves the release once: $failure")
        assertEquals(1, dismissals, "control: the dismissal ran")
        assertNull(coreLocation.delegate, "the delegate was set to nil although the dismissal failed")
        assertNull(opened.coreLocationManager, "the manager was dropped although the dismissal failed")

        opened.release()

        assertEquals(1, dismissals, "a second release dismisses nothing and fails nothing")
    }

    @Test
    fun `the factory builds without a manager and availability is the device's`() {
        val provider: HeadingProvider = createHeadingProvider()

        assertEquals(CLLocationManager.headingAvailable(), provider.isAvailable)
    }
}
