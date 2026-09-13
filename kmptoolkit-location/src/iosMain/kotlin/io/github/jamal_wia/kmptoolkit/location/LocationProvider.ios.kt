package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSError
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Creates the iOS [LocationProvider], on top of `CLLocationManager`.
 *
 * No permission request happens here — the app must declare
 * `NSLocationWhenInUseUsageDescription` (and/or `NSLocationAlwaysAndWhenInUseUsageDescription`)
 * with its own usage-description string in `Info.plist` and request authorization itself; see
 * `docs/kmptoolkit-location/05-platform-notes.md`. Without authorization, [getCurrentLocation]
 * resolves to `null` and [observeLocation] emits `null`, instead of either throwing.
 *
 * The `CLLocationManager` and its delegate are retained for the duration of a request — otherwise
 * CoreLocation silently drops the callbacks.
 *
 * Every manager is created and started on the **main queue**: CoreLocation delivers its delegate
 * callbacks on the run loop of the thread that created the manager, and a caller reaching this from
 * `Dispatchers.Default` — a Kotlin/Native worker with no run loop — would otherwise see the fix and
 * the failure alike simply never arrive, suspending forever rather than timing out or erroring.
 *
 * @param config tuning for update throttling and the single-fix timeout; see
 *   [LocationProviderConfig]. `minUpdateIntervalMillis` is ignored on iOS — CoreLocation has no
 *   time-based throttle, only [LocationProviderConfig.minUpdateDistanceMeters].
 * @param logger where an unexpected `CLLocationManager` failure is reported.
 */
public fun createLocationProvider(
    config: LocationProviderConfig = LocationProviderConfig(),
    logger: Logger = NoopLogger,
): LocationProvider = IosLocationProvider(config, logger)

private class IosLocationProvider(
    private val config: LocationProviderConfig,
    private val logger: Logger,
) : LocationProvider {

    override suspend fun getCurrentLocation(): GeoCoordinates? =
        withTimeoutOrNull(config.singleFixTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val delegate = SingleShotDelegate(
                    onLocation = { coordinates: GeoCoordinates? ->
                        if (continuation.isActive) continuation.resume(coordinates)
                    },
                    onError = { error: NSError ->
                        logger.w { "getCurrentLocation failed: ${error.localizedDescription}" }
                        if (continuation.isActive) continuation.resume(null)
                    },
                )

                dispatch_async(dispatch_get_main_queue()) {
                    val manager: CLLocationManager = CLLocationManager().apply {
                        desiredAccuracy = kCLLocationAccuracyHundredMeters
                        this.delegate = delegate
                    }
                    // Hold a strong reference until the callback fires.
                    delegate.manager = manager
                    manager.requestLocation()
                }

                continuation.invokeOnCancellation {
                    dispatch_async(dispatch_get_main_queue()) {
                        delegate.manager?.stopUpdatingLocation()
                        delegate.manager?.delegate = null
                        delegate.manager = null
                    }
                }
            }
        }

    override fun observeLocation(): Flow<GeoCoordinates?> = callbackFlow {
        val delegate = StreamingDelegate(
            onLocation = { coordinates: GeoCoordinates? -> trySend(coordinates) },
            onError = { error: NSError ->
                logger.w { "observeLocation failed: ${error.localizedDescription}" }
                trySend(null)
            },
        )
        var manager: CLLocationManager? = null

        dispatch_async(dispatch_get_main_queue()) {
            val started: CLLocationManager = CLLocationManager().apply {
                desiredAccuracy = kCLLocationAccuracyHundredMeters
                distanceFilter = config.minUpdateDistanceMeters.toDouble()
                this.delegate = delegate
            }
            manager = started
            started.startUpdatingLocation()

            // Seed with the last known location (or null) so the flow is never silent on
            // subscribe. If no update ever arrives (service on but no fix / no signal), without
            // this the flow would emit nothing and downstream combines would hang on their
            // initial value.
            trySend(started.location?.toGeoCoordinates())
        }

        awaitClose {
            dispatch_async(dispatch_get_main_queue()) {
                manager?.stopUpdatingLocation()
                manager?.delegate = null
                manager = null
            }
        }
    }

    // Off the main thread: locationServicesEnabled() logs a runtime warning when called on the
    // main thread while authorization is still being determined.
    override suspend fun isLocationEnabled(): Boolean = withContext(Dispatchers.Default) {
        CLLocationManager.locationServicesEnabled()
    }

    // iOS exposes no deep link to the system Location Services toggle, so the best available
    // fallback is the app's own settings page. UIApplication may only be used on the main thread,
    // and this method is documented as callable from any thread.
    override fun openLocationSettings() {
        val url: NSURL = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        val open: () -> Unit = {
            UIApplication.sharedApplication.openURL(
                url,
                options = mapOf<Any?, Any?>(),
                completionHandler = null,
            )
        }
        if (NSThread.isMainThread) open() else dispatch_async(dispatch_get_main_queue(), open)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CLLocation.toGeoCoordinates(): GeoCoordinates = coordinate.useContents {
    GeoCoordinates(latitude = latitude, longitude = longitude)
}

private class SingleShotDelegate(
    private val onLocation: (GeoCoordinates?) -> Unit,
    private val onError: (NSError) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {

    var manager: CLLocationManager? = null

    @OptIn(ExperimentalForeignApi::class)
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location: CLLocation = didUpdateLocations.lastOrNull() as? CLLocation ?: run {
            onLocation(null)
            return
        }
        onLocation(location.toGeoCoordinates())
        manager.stopUpdatingLocation()
        this.manager = null
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        onError(didFailWithError)
        manager.stopUpdatingLocation()
        this.manager = null
    }
}

private class StreamingDelegate(
    private val onLocation: (GeoCoordinates?) -> Unit,
    private val onError: (NSError) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {

    @OptIn(ExperimentalForeignApi::class)
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location: CLLocation = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        onLocation(location.toGeoCoordinates())
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        onError(didFailWithError)
    }
}
