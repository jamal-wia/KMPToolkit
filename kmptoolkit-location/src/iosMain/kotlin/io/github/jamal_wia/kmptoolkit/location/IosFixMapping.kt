package io.github.jamal_wia.kmptoolkit.location

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import platform.CoreLocation.CLActivityTypeAirborne
import platform.CoreLocation.CLActivityTypeAutomotiveNavigation
import platform.CoreLocation.CLActivityTypeFitness
import platform.CoreLocation.CLActivityTypeOther
import platform.CoreLocation.CLActivityTypeOtherNavigation
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.CoreLocation.kCLLocationAccuracyNearestTenMeters
import platform.CoreLocation.kCLLocationAccuracyReduced
import platform.CoreLocation.kCLLocationAccuracyThreeKilometers

// The pure half of the iOS provider: raw CoreLocation numbers in, library types out. Nothing here
// touches a CLLocationManager, so every rule of PLAN section 4.1 / 4.2 is tested without one.

/**
 * A fix as CoreLocation delivered it: raw platform numbers with no policy applied (a negative
 * accuracy is still negative here). Values that exist only on newer systems are `null` below that
 * system; [IosRawFix.toFixOrNull] applies every rule of PLAN section 4.1.
 */
internal class IosRawFix(
    val latitude: Double,
    val longitude: Double,
    /** `CLLocation.timestamp` as Unix epoch milliseconds: a wall-clock stamp, never used for age on its own. */
    val timestampEpochMillis: Long,
    val horizontalAccuracy: Double,
    /** `CLLocation.altitude`: metres above mean sea level. */
    val altitude: Double,
    /** `CLLocation.ellipsoidalAltitude` (iOS 15+); `null` below. */
    val ellipsoidalAltitude: Double?,
    val verticalAccuracy: Double,
    val speed: Double,
    val speedAccuracy: Double,
    val course: Double,
    /** `CLLocation.courseAccuracy` (iOS 13.4+); `null` below. */
    val courseAccuracy: Double?,
    /** `CLFloor.level`, `null` when the venue provides no floor. */
    val floorLevel: Int?,
    /** `sourceInformation.isSimulatedBySoftware` (iOS 15+); `null` below or without source information. */
    val isSimulated: Boolean?,
    /** `sourceInformation.isProducedByAccessory` (iOS 15+); `null` below or without source information. */
    val isProducedByAccessory: Boolean?,
)

/** `null` when [value] is negative (CoreLocation's "unknown") or not finite. */
private fun nonNegativeOrNull(value: Double): Double? = if (value.isFinite() && value >= 0.0) value else null

/**
 * PLAN section 4.1: the common [LocationFix] of this raw fix, or `null` when the fix is invalid and
 * must be dropped (a negative or non-finite `horizontalAccuracy`, or coordinates that [GeoCoordinates]
 * rejects). [isApproximate] is the grade the fix was delivered under, decided by the caller.
 *
 * A negative `verticalAccuracy` nulls itself and both altitudes; a negative speed, course or accuracy
 * is `null`; the version-gated values arrive as `null` already.
 */
internal fun IosRawFix.toFixOrNull(isApproximate: Boolean?): LocationFix? {
    if (!horizontalAccuracy.isFinite() || horizontalAccuracy < 0.0) return null
    val coordinates: GeoCoordinates = GeoCoordinates.orNull(latitude, longitude) ?: return null
    val hasVertical: Boolean = nonNegativeOrNull(verticalAccuracy) != null
    return LocationFix(
        coordinates = coordinates,
        timestampEpochMillis = timestampEpochMillis,
        horizontalAccuracyMeters = horizontalAccuracy,
        ellipsoidalAltitudeMeters = if (hasVertical) ellipsoidalAltitude?.takeIf { it.isFinite() } else null,
        mslAltitudeMeters = if (hasVertical) altitude.takeIf { it.isFinite() } else null,
        verticalAccuracyMeters = nonNegativeOrNull(verticalAccuracy),
        speedMetersPerSecond = nonNegativeOrNull(speed),
        speedAccuracyMetersPerSecond = nonNegativeOrNull(speedAccuracy),
        courseDegrees = nonNegativeOrNull(course)?.let { degrees: Double -> if (degrees >= 360.0) degrees % 360.0 else degrees },
        courseAccuracyDegrees = courseAccuracy?.let { value: Double -> nonNegativeOrNull(value) },
        isSimulated = isSimulated,
        isApproximate = isApproximate,
        platformDetails = IosFixDetails(floorLevel = floorLevel, isProducedByAccessory = isProducedByAccessory),
    )
}

/**
 * A fix the provider received itself, with what it needs to judge its age later without trusting the
 * wall clock (PLAN section 10, item 7): the age at the moment of delivery and the system uptime at
 * that moment. The age at a later decision is `ageAtDelivery + (uptimeNow - uptimeAtDelivery)`, so a
 * wall-clock correction between delivery and decision changes nothing.
 */
internal class IosDelivery(val fix: LocationFix, val ageAtDelivery: Duration, val uptimeAtDelivery: Duration) {

    /** The age of this fix at [uptimeNow]; an uptime that stepped back counts as no time passed. */
    fun ageAt(uptimeNow: Duration): Duration =
        ageAtDelivery + (uptimeNow - uptimeAtDelivery).coerceAtLeast(Duration.ZERO)
}

/**
 * `deliveryDate - location.timestamp`, the short wall-clock difference captured at delivery (or, for a
 * cached `manager.location`, with "now" as the delivery). A stamp from the future counts as zero.
 */
internal fun IosClock.wallAgeOf(raw: IosRawFix): Duration =
    (epochMillis() - raw.timestampEpochMillis).coerceAtLeast(0L).milliseconds

/**
 * Which reason, if any, stops a request given the authorization [status] and whether Location
 * Services are on (PLAN section 4.2). `notDetermined` and `restricted` are `PERMISSION_MISSING`;
 * `denied` is `PERMISSION_MISSING` with the services on and `SERVICE_DISABLED` with them off (iOS
 * reports `denied` while the services are off, so the switch is the reason worth naming); an
 * authorized app is `SERVICE_DISABLED` while the services are off. `null` means the request may run.
 */
internal fun authorizationReason(status: Int, servicesEnabled: Boolean): LocationUnavailability? = when (status) {
    kCLAuthorizationStatusNotDetermined, kCLAuthorizationStatusRestricted -> LocationUnavailability.PERMISSION_MISSING
    kCLAuthorizationStatusDenied ->
        if (servicesEnabled) LocationUnavailability.PERMISSION_MISSING else LocationUnavailability.SERVICE_DISABLED
    else -> if (servicesEnabled) null else LocationUnavailability.SERVICE_DISABLED
}

/** Whether [status] can be answered without reading the services switch: no prompt answer depends on it. */
internal fun decidesWithoutServices(status: Int): Boolean =
    status == kCLAuthorizationStatusNotDetermined || status == kCLAuthorizationStatusRestricted

/** What a `didFailWithError` means to the provider (PLAN D14, section 4.2). */
internal enum class IosFailureKind {
    /** `kCLErrorLocationUnknown`: transient, Apple says to ignore it and wait. Nothing is emitted. */
    LOCATION_UNKNOWN,

    /** `kCLErrorDenied`: the user denied or revoked; Apple says to stop the service. */
    DENIED,

    /** Anything else: logged, never thrown. */
    OTHER,
}

internal fun classifyLocationFailure(domain: String?, code: Long): IosFailureKind = when {
    domain != kCLErrorDomain -> IosFailureKind.OTHER
    code == kCLErrorLocationUnknown -> IosFailureKind.LOCATION_UNKNOWN
    code == kCLErrorDenied -> IosFailureKind.DENIED
    else -> IosFailureKind.OTHER
}

/** PLAN section 4.2: the `desiredAccuracy` of each common level (F4: the same on both calls). */
internal fun LocationAccuracy.toDesiredAccuracy(): Double = when (this) {
    LocationAccuracy.HIGH_ACCURACY -> kCLLocationAccuracyBest
    LocationAccuracy.BALANCED -> kCLLocationAccuracyHundredMeters
    LocationAccuracy.LOW_POWER -> kCLLocationAccuracyKilometer
}

/** The exact `kCLLocationAccuracy*` constant of an iOS override. */
internal fun IosDesiredAccuracy.toDesiredAccuracy(): Double = when (this) {
    IosDesiredAccuracy.BEST_FOR_NAVIGATION -> kCLLocationAccuracyBestForNavigation
    IosDesiredAccuracy.BEST -> kCLLocationAccuracyBest
    IosDesiredAccuracy.NEAREST_TEN_METERS -> kCLLocationAccuracyNearestTenMeters
    IosDesiredAccuracy.HUNDRED_METERS -> kCLLocationAccuracyHundredMeters
    IosDesiredAccuracy.KILOMETER -> kCLLocationAccuracyKilometer
    IosDesiredAccuracy.THREE_KILOMETERS -> kCLLocationAccuracyThreeKilometers
    IosDesiredAccuracy.REDUCED -> kCLLocationAccuracyReduced
}

/** `CLActivityType` of an [IosActivityType]. */
internal fun IosActivityType.toCoreLocation(): Long = when (this) {
    IosActivityType.OTHER -> CLActivityTypeOther
    IosActivityType.AUTOMOTIVE_NAVIGATION -> CLActivityTypeAutomotiveNavigation
    IosActivityType.FITNESS -> CLActivityTypeFitness
    IosActivityType.OTHER_NAVIGATION -> CLActivityTypeOtherNavigation
    IosActivityType.AIRBORNE -> CLActivityTypeAirborne
}

/** Whether [desiredAccuracy] is `kCLLocationAccuracyReduced`: such fixes read `isApproximate == true` (R6-3). */
internal fun isReducedRequest(desiredAccuracy: Double): Boolean = desiredAccuracy == kCLLocationAccuracyReduced
