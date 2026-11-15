@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `the factory builds without a manager and availability is the device's`() {
        val provider: HeadingProvider = createHeadingProvider()

        assertEquals(CLLocationManager.headingAvailable(), provider.isAvailable)
    }
}
