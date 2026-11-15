package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi

/**
 * iOS-only options of one [LocationProvider.getCurrentLocation] call: `requestLocation` honours
 * `desiredAccuracy` too, so the one-shot call can ask for an exact CoreLocation accuracy.
 *
 * Built in iOS code and passed through shared code as [CurrentLocationOptions.platform]. The type
 * exists only on iOS and implements [PlatformCurrentLocationOptions], so an iOS stream-only option
 * cannot be passed to the one-shot call.
 *
 * @property desiredAccuracyOverride an exact CoreLocation accuracy (for example
 * [IosDesiredAccuracy.BEST_FOR_NAVIGATION] or [IosDesiredAccuracy.REDUCED]) that replaces the
 * mapping of the common [LocationAccuracy]; `null` keeps the mapping.
 */
@OptIn(ToolkitInternalApi::class)
public data class IosCurrentLocationOptions(
    public val desiredAccuracyOverride: IosDesiredAccuracy? = null,
) : PlatformCurrentLocationOptions

/**
 * iOS-only options of one [LocationProvider.observeLocation] stream.
 *
 * Built in iOS code and passed through shared code as [LocationUpdatesOptions.platform]. The type
 * exists only on iOS and implements [PlatformLocationUpdatesOptions].
 *
 * @property allowsBackgroundLocationUpdates `allowsBackgroundLocationUpdates` of the manager. It
 * needs `location` in the app's `UIBackgroundModes`: without it the stream fails with
 * [IllegalStateException] naming the key, never silently ignoring the option. The check runs when
 * collection starts, before any authorization is read, so it fails in every authorization state.
 * @property showsBackgroundLocationIndicator `showsBackgroundLocationIndicator`: the blue status-bar
 * indicator while the app updates in the background.
 * @property pausesLocationUpdatesAutomatically `pausesLocationUpdatesAutomatically`. The default is
 * `false`, unlike Apple's `true`, because Apple leaves restarting after a pause to the app. When an
 * app opts in, the stream emits [LocationUnavailability.NO_FIX] while updates are paused and the
 * provider restarts them on the next `UIApplicationDidBecomeActiveNotification`, so a pause that
 * began in the background lasts until the app is next active.
 * @property activityType `CLActivityType`: steers Apple's pause heuristics and map matching.
 * @property desiredAccuracyOverride an exact CoreLocation accuracy that replaces the mapping of the
 * common [LocationAccuracy]; `null` keeps the mapping.
 */
@OptIn(ToolkitInternalApi::class)
public data class IosLocationUpdatesOptions(
    public val allowsBackgroundLocationUpdates: Boolean = false,
    public val showsBackgroundLocationIndicator: Boolean = false,
    public val pausesLocationUpdatesAutomatically: Boolean = false,
    public val activityType: IosActivityType = IosActivityType.OTHER,
    public val desiredAccuracyOverride: IosDesiredAccuracy? = null,
) : PlatformLocationUpdatesOptions

/** `CLActivityType`. */
public enum class IosActivityType {
    /** `CLActivityType.other`. */
    OTHER,

    /** `CLActivityType.automotiveNavigation`. */
    AUTOMOTIVE_NAVIGATION,

    /** `CLActivityType.fitness`. */
    FITNESS,

    /** `CLActivityType.otherNavigation`. */
    OTHER_NAVIGATION,

    /** `CLActivityType.airborne`. */
    AIRBORNE,
}

/** The `kCLLocationAccuracy*` constants. */
public enum class IosDesiredAccuracy {
    /** `kCLLocationAccuracyBestForNavigation`. */
    BEST_FOR_NAVIGATION,

    /** `kCLLocationAccuracyBest`. */
    BEST,

    /** `kCLLocationAccuracyNearestTenMeters`. */
    NEAREST_TEN_METERS,

    /** `kCLLocationAccuracyHundredMeters`. */
    HUNDRED_METERS,

    /** `kCLLocationAccuracyKilometer`. */
    KILOMETER,

    /** `kCLLocationAccuracyThreeKilometers`. */
    THREE_KILOMETERS,

    /**
     * `kCLLocationAccuracyReduced`: reduced-grade fixes (about 1-20 km) even under a full grant;
     * such fixes read `isApproximate == true`.
     */
    REDUCED,
}
