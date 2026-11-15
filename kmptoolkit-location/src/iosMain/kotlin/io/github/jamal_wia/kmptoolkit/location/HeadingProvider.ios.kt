package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLDeviceOrientation
import platform.CoreLocation.CLDeviceOrientationLandscapeLeft
import platform.CoreLocation.CLDeviceOrientationLandscapeRight
import platform.CoreLocation.CLDeviceOrientationPortrait
import platform.CoreLocation.CLDeviceOrientationPortraitUpsideDown
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLHeadingFilterNone
import platform.CoreLocation.kCLLocationAccuracyThreeKilometers
import platform.Foundation.NSError
import platform.Foundation.NSThread
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Creates the iOS [HeadingProvider], over `CLLocationManager` heading updates.
 *
 * Every collection of [HeadingProvider.observeHeading] owns its own `CLLocationManager`, created on the main
 * queue, because `headingOrientation` and `headingFilter` are per manager; concurrent collectors are therefore
 * independent, each with its own filter and screen rotation. When the collection ends, the manager's heading
 * and location updates are stopped, its delegate is set to `nil` and it is dropped. Nothing is held between
 * collections.
 *
 * ### True north
 *
 * CoreLocation reports `trueHeading` only while location updates run on the same manager. So while the app is
 * authorized for location, the provider also runs `startUpdatingLocation` on that manager at
 * `kCLLocationAccuracyThreeKilometers` (a declination changes by about one degree over hundreds of kilometres,
 * so no better accuracy is worth the power), starts them when authorization arrives in the middle of a
 * collection and stops them on revocation. It never starts them without authorization. Without authorization
 * [Heading.trueDegrees] is `null`, also when CoreLocation still hands over a `trueHeading` it computed from a
 * location cached before a revocation: the provider reads the authorization on the main queue each time it
 * changes and keeps a `trueHeading` only while it holds, so one that arrives while unauthorized is dropped
 * and never shown when authorization comes back. iOS has no declination API, so `declinationAt` of
 * [HeadingProvider.observeHeading] cannot be used here and is ignored. The app must declare the location usage
 * description and request authorization itself (see `kmptoolkit-permission`); this library requests nothing.
 *
 * ### Screen rotation, filter, accuracy
 *
 * `screenRotation` maps to `headingOrientation` as `ROTATION_90` to `CLDeviceOrientationLandscapeLeft`,
 * `ROTATION_180` to `PortraitUpsideDown`, `ROTATION_270` to `LandscapeRight`, `NATURAL` to `Portrait`.
 * `minChangeDegrees` is set as the manager's `headingFilter` (0 asks for every update) and, on top, the common
 * filter of this library applies, measured from the last heading it emitted. [Heading.accuracyDegrees] is
 * `headingAccuracy` when it is not negative, else `null`; [Heading.needsCalibration] is `true` while
 * `headingAccuracy` is negative or CoreLocation has asked to show its calibration overlay and no valid heading
 * has followed.
 *
 * Because the manager's own `headingFilter` holds back updates while the device is still, a change that needs no
 * movement (true north appearing after authorization, a calibration request) is emitted when CoreLocation next
 * delivers a heading, not before.
 *
 * `isAvailable` is `CLLocationManager.headingAvailable()`; when it is `false` the stream stays open and silent.
 *
 * @param showsSystemCalibration whether iOS may show its own calibration overlay
 *   (`locationManagerShouldDisplayHeadingCalibration`); a request for it is reported through
 *   [Heading.needsCalibration] either way.
 * @param logger where a failing manager is reported. A logger that throws an exception loses its own event
 *   and nothing else: the exception never reaches CoreLocation's callback and never keeps a manager from
 *   being released. An `Error` is not absorbed (a logger that throws one is a defect of the app), but the
 *   manager is released before anything is logged.
 */
public fun createHeadingProvider(
    showsSystemCalibration: Boolean = false,
    logger: Logger = NoopLogger,
): HeadingProvider = IosHeadingProvider(
    device = CoreLocationHeadingDevice(logger),
    showsSystemCalibration = showsSystemCalibration,
    logger = logger,
    confine = ::onMainQueue,
)

/** Runs [block] now when already on the main thread, else queues it on the main queue (FIFO with every other queued block). */
private fun onMainQueue(block: () -> Unit) {
    if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
}

/** The `CLDeviceOrientation` a [ScreenRotation] is applied as (decision D23). */
internal fun ScreenRotation.toDeviceOrientation(): CLDeviceOrientation = when (this) {
    ScreenRotation.NATURAL -> CLDeviceOrientationPortrait
    ScreenRotation.ROTATION_90 -> CLDeviceOrientationLandscapeLeft
    ScreenRotation.ROTATION_180 -> CLDeviceOrientationPortraitUpsideDown
    ScreenRotation.ROTATION_270 -> CLDeviceOrientationLandscapeRight
}

@OptIn(ExperimentalForeignApi::class)
internal class CoreLocationHeadingDevice(private val logger: Logger) : IosHeadingDevice {

    override val isHeadingAvailable: Boolean get() = CLLocationManager.headingAvailable()

    override fun openManager(
        events: IosHeadingEvents,
        orientation: ScreenRotation,
        headingFilterDegrees: Double,
    ): IosHeadingManager {
        val delegate = HeadingDelegate(events, logger)
        val manager = CLLocationManager()
        manager.delegate = delegate
        manager.headingOrientation = orientation.toDeviceOrientation()
        manager.headingFilter = if (headingFilterDegrees > 0.0) headingFilterDegrees else kCLHeadingFilterNone
        return CoreLocationHeadingManager(manager, delegate)
    }
}

/**
 * The manager and its delegate, held strongly until [release]: `CLLocationManager.delegate` is weak, so this
 * reference is what keeps the delegate alive while the collection runs, and [release] sets the delegate to `nil`
 * before both are dropped.
 */
@OptIn(ExperimentalForeignApi::class)
internal class CoreLocationHeadingManager(
    manager: CLLocationManager,
    delegate: HeadingDelegate,
) : IosHeadingManager {

    private var held: CLLocationManager? = manager
    private var strongDelegate: HeadingDelegate? = delegate

    /** The wrapped manager, for tests; `null` after [release]. */
    internal val coreLocationManager: CLLocationManager? get() = held

    override val isAuthorized: Boolean
        get() = held?.authorizationStatus.let { status ->
            status == kCLAuthorizationStatusAuthorizedAlways || status == kCLAuthorizationStatusAuthorizedWhenInUse
        }

    override fun startHeading() {
        held?.startUpdatingHeading()
    }

    override fun startLocation() {
        held?.let { manager: CLLocationManager ->
            manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
            manager.startUpdatingLocation()
        }
    }

    override fun stopLocation() {
        held?.stopUpdatingLocation()
    }

    override fun release() {
        val manager: CLLocationManager = held ?: return
        manager.stopUpdatingHeading()
        manager.stopUpdatingLocation()
        manager.delegate = null
        held = null
        strongDelegate = null
    }
}

/**
 * The delegate of one heading manager: forwards three callbacks to the collection (which answers the overlay
 * question with `showsSystemCalibration`) and ignores the rest.
 */
@OptIn(ExperimentalForeignApi::class)
internal class HeadingDelegate(
    private val events: IosHeadingEvents,
    private val logger: Logger,
) : NSObject(), CLLocationManagerDelegateProtocol {

    override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
        events.onHeading(
            magneticHeading = didUpdateHeading.magneticHeading,
            trueHeading = didUpdateHeading.trueHeading,
            headingAccuracy = didUpdateHeading.headingAccuracy,
        )
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        events.onAuthorizationChanged()
    }

    override fun locationManagerShouldDisplayHeadingCalibration(manager: CLLocationManager): Boolean =
        events.onCalibrationRequested()

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        // Only here so that the location updates have a receiver: their point is that trueHeading becomes valid.
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        // A transient failure (kCLErrorHeadingFailure, kCLErrorLocationUnknown) ends nothing: the heading
        // stream has no error channel and CoreLocation keeps trying. Only reported.
        // A logger that throws must not escape into CoreLocation's callback.
        guardLogger { logger.w { "Heading manager reported: ${didFailWithError.localizedDescription}" } }
    }
}
