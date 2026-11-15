@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// The seams of the iOS provider, in the style of kmptoolkit-permission's IosAuthorizationPort: every
// call the provider makes into the platform goes through IosLocationEnvironment and
// IosLocationManager, so the provider's logic (authorization, seed, age rule, flap, auto-pause,
// release) runs against a scripted fake in a test binary, which has no usable location services.
// SystemIosLocationEnvironment is the only implementation in the library. Internal: not API.

/** Wall time for CoreLocation's stamps and system uptime for ages: a test injects both. */
internal interface IosClock {
    /** The wall clock as Unix epoch milliseconds (`NSDate`): only ever differenced against CoreLocation's own stamps. */
    fun epochMillis(): Long

    /** Time since boot (`NSProcessInfo.systemUptime`): monotonic, so it is what ages advance on. */
    fun uptime(): Duration
}

/**
 * What one `CLLocationManager` reports to the provider. Every method is called on the main queue
 * (CoreLocation delivers on the run loop of the thread that created the manager), and none may
 * throw: they run inside an Objective-C callback.
 */
internal interface IosLocationEvents {
    /** `didUpdateLocations`, in order. */
    fun onLocations(fixes: List<IosRawFix>) = Unit

    /** `didFailWithError`, classified; [description] is for the log only. */
    fun onFailure(kind: IosFailureKind, description: String) = Unit

    /** `locationManagerDidChangeAuthorization`: authorization, accuracy grade or the services switch changed. */
    fun onAuthorizationChanged() = Unit

    /** `locationManagerDidPauseLocationUpdates` (automatic pause, opt-in). */
    fun onPaused() = Unit

    /** `locationManagerDidResumeLocationUpdates`. */
    fun onResumed() = Unit
}

/** Everything the provider sets on a manager, in one value so a test can read what was applied. */
internal data class IosManagerConfig(
    val desiredAccuracy: Double,
    val distanceFilter: Double,
    val allowsBackgroundLocationUpdates: Boolean,
    val showsBackgroundLocationIndicator: Boolean,
    val pausesLocationUpdatesAutomatically: Boolean,
    val activityType: Long,
)

/**
 * One `CLLocationManager` together with the delegate that feeds [IosLocationEvents]. The manager
 * wrapper owns the delegate **strongly** ( `CLLocationManager.delegate` is weak: a delegate nobody
 * else holds is collected and the callbacks silently stop) until [detach], which sets the delegate
 * to nil before the manager is dropped. Used on the main queue only.
 */
internal interface IosLocationManager {
    /** `authorizationStatus` (iOS 14+). */
    val authorizationStatus: Int

    /** `accuracyAuthorization == reducedAccuracy` (iOS 14+). */
    val isAccuracyReduced: Boolean

    /** `manager.location`: the last fix CoreLocation holds, of any age. */
    val lastLocation: IosRawFix?

    /** Sets every property of [config]; may be called again to re-apply it. */
    fun apply(config: IosManagerConfig)

    fun startUpdatingLocation()

    fun stopUpdatingLocation()

    fun requestLocation()

    /** Stops updates, sets the delegate to nil and drops delegate and manager. Idempotent. */
    fun detach()
}

/** The platform behind the provider; [SystemIosLocationEnvironment] is the real one. */
internal interface IosLocationEnvironment {
    /** Where managers are created, started and released: the main queue. */
    val mainDispatcher: CoroutineDispatcher

    /** Where the services switch is read: `locationServicesEnabled()` warns when called on the main thread. */
    val offMainDispatcher: CoroutineDispatcher

    val clock: IosClock

    /** The clock the common throttle measures `minUpdateInterval` on. */
    val timeSource: TimeSource

    /** The authorization status, read on the calling thread (hopping to main would deadlock a caller that blocks it). */
    fun authorizationStatus(): Int

    /** `CLLocationManager.locationServicesEnabled()`; call it off the main thread. */
    fun locationServicesEnabled(): Boolean

    /** Whether `UIBackgroundModes` of the app's Info.plist lists `location`. */
    fun hasLocationBackgroundMode(): Boolean

    /** Creates a manager and its delegate and wires them to [events]; call it on [mainDispatcher]. */
    fun createManager(events: IosLocationEvents): IosLocationManager

    /** Registers [onActive] for every did-become-active notification (main queue); returns the function that unregisters it. */
    fun addBecameActiveListener(onActive: () -> Unit): () -> Unit

    /** Opens the app's own settings page; callable from any thread, never throws. */
    fun openAppSettings()
}

/** The real clock. */
internal object SystemIosClock : IosClock {
    override fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()

    override fun uptime(): Duration = NSProcessInfo.processInfo.systemUptime.seconds

    private const val MILLIS_PER_SECOND: Double = 1000.0
}

/**
 * The real platform behind [IosLocationEnvironment]. Nothing is fetched at construction, so building
 * a provider in a test binary touches no CoreLocation object.
 */
internal object SystemIosLocationEnvironment : IosLocationEnvironment {
    // immediate: a caller already on the main thread (a UI collector) is not re-queued behind its own hop.
    override val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    override val offMainDispatcher: CoroutineDispatcher = Dispatchers.Default
    override val clock: IosClock = SystemIosClock
    override val timeSource: TimeSource = TimeSource.Monotonic

    // Read on the calling thread, like kmptoolkit-permission does: a status read needs no delegate.
    override fun authorizationStatus(): Int = CLLocationManager().authorizationStatus

    override fun locationServicesEnabled(): Boolean = CLLocationManager.locationServicesEnabled()

    override fun hasLocationBackgroundMode(): Boolean {
        val modes: List<*> = NSBundle.mainBundle.objectForInfoDictionaryKey("UIBackgroundModes") as? List<*> ?: return false
        return modes.any { mode: Any? -> mode == "location" }
    }

    override fun createManager(events: IosLocationEvents): IosLocationManager {
        val delegate = CoreLocationDelegate(events)
        val manager: CLLocationManager = CLLocationManager()
        manager.delegate = delegate
        return SystemIosLocationManager(manager, delegate)
    }

    override fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
        val center: NSNotificationCenter = NSNotificationCenter.defaultCenter
        val observer: Any = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? -> onActive() }
        return { center.removeObserver(observer) }
    }

    // iOS exposes no deep link to the Location Services toggle, so the best available fallback is the
    // app's own settings page. UIApplication may only be used on the main thread, and this method is
    // documented as callable from any thread.
    override fun openAppSettings() {
        val url: NSURL = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        val open: () -> Unit = {
            UIApplication.sharedApplication.openURL(url, options = mapOf<Any?, Any?>(), completionHandler = null)
        }
        if (NSThread.isMainThread) open() else dispatch_async(dispatch_get_main_queue(), open)
    }
}

/**
 * Whether this OS is at least [major].[minor]: an API introduced later is reached only behind this,
 * because sending its selector to an older system is a crash.
 */
internal fun isIosAtLeast(major: Long, minor: Long = 0): Boolean =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        majorVersion > major || (majorVersion == major && minorVersion >= minor)
    }

/** `CLLocation` as raw numbers; every version-gated read sits behind [isIosAtLeast]. */
internal fun CLLocation.toRawFix(): IosRawFix {
    var latitudeValue = 0.0
    var longitudeValue = 0.0
    coordinate.useContents {
        latitudeValue = latitude
        longitudeValue = longitude
    }
    val atLeast15: Boolean = isIosAtLeast(15)
    val source = if (atLeast15) sourceInformation else null
    return IosRawFix(
        latitude = latitudeValue,
        longitude = longitudeValue,
        timestampEpochMillis = (timestamp.timeIntervalSince1970 * 1000.0).toLong(),
        horizontalAccuracy = horizontalAccuracy,
        altitude = altitude,
        ellipsoidalAltitude = if (atLeast15) ellipsoidalAltitude else null,
        verticalAccuracy = verticalAccuracy,
        speed = speed,
        speedAccuracy = speedAccuracy,
        course = course,
        courseAccuracy = if (isIosAtLeast(13, 4)) courseAccuracy else null,
        floorLevel = floor?.level?.toInt(),
        isSimulated = source?.isSimulatedBySoftware,
        isProducedByAccessory = source?.isProducedByAccessory,
    )
}

/**
 * The delegate. It only translates: every decision is made by the session that receives the events.
 * Held strongly by [SystemIosLocationManager] because `CLLocationManager.delegate` is weak.
 */
private class CoreLocationDelegate(private val events: IosLocationEvents) : NSObject(), CLLocationManagerDelegateProtocol {

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val fixes: List<IosRawFix> = didUpdateLocations.mapNotNull { location: Any? -> (location as? CLLocation)?.toRawFix() }
        if (fixes.isNotEmpty()) events.onLocations(fixes)
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        events.onFailure(
            kind = classifyLocationFailure(didFailWithError.domain, didFailWithError.code),
            description = didFailWithError.localizedDescription,
        )
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        events.onAuthorizationChanged()
    }

    override fun locationManagerDidPauseLocationUpdates(manager: CLLocationManager) {
        events.onPaused()
    }

    override fun locationManagerDidResumeLocationUpdates(manager: CLLocationManager) {
        events.onResumed()
    }
}

/** One real manager and its strongly held delegate; see [IosLocationManager]. */
internal class SystemIosLocationManager(manager: CLLocationManager, delegate: NSObject) : IosLocationManager {

    private var manager: CLLocationManager? = manager

    /** The strong reference that keeps the weak `CLLocationManager.delegate` alive; cleared only by [detach]. */
    private var delegate: NSObject? = delegate

    /** Whether the manager still has a delegate: `false` once released, and `false` if the weak reference was lost. */
    val hasDelegate: Boolean get() = manager?.delegate != null

    override val authorizationStatus: Int get() = manager?.authorizationStatus ?: kCLAuthorizationStatusNotDetermined

    override val isAccuracyReduced: Boolean
        get() = manager?.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationReducedAccuracy

    override val lastLocation: IosRawFix? get() = manager?.location?.toRawFix()

    override fun apply(config: IosManagerConfig) {
        val target: CLLocationManager = manager ?: return
        target.desiredAccuracy = config.desiredAccuracy
        target.distanceFilter = config.distanceFilter
        target.allowsBackgroundLocationUpdates = config.allowsBackgroundLocationUpdates
        target.showsBackgroundLocationIndicator = config.showsBackgroundLocationIndicator
        target.pausesLocationUpdatesAutomatically = config.pausesLocationUpdatesAutomatically
        target.activityType = config.activityType
    }

    override fun startUpdatingLocation() {
        manager?.startUpdatingLocation()
    }

    override fun stopUpdatingLocation() {
        manager?.stopUpdatingLocation()
    }

    override fun requestLocation() {
        manager?.requestLocation()
    }

    override fun detach() {
        val target: CLLocationManager = manager ?: return
        target.stopUpdatingLocation()
        // Nil before the manager is dropped: a weak delegate that outlives its manager's owner is the bug class.
        target.delegate = null
        manager = null
        delegate = null
    }
}
