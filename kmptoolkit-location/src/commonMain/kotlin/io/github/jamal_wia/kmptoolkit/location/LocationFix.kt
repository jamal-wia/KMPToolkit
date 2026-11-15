package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi

/**
 * One position the platform reported.
 *
 * Every optional field is `null` when the platform reported none or cannot tell; the per-platform
 * table is in `docs/kmptoolkit-location/05-platform-notes.md`. A `null` is never a guess.
 *
 * @property coordinates Where.
 * @property timestampEpochMillis Wall-clock time the platform took the fix, Unix epoch
 * milliseconds. For display and storage only, never for age decisions: providers judge age on the
 * platform's monotonic clock, because a wall clock jumps when it is corrected.
 * @property horizontalAccuracyMeters Horizontal uncertainty radius in meters as reported. Android:
 * 68 % confidence, multiply by 1.62 for about 95 %. Apple states no confidence level.
 * @property ellipsoidalAltitudeMeters Height above the WGS-84 ellipsoid in meters: Android when
 * reported, iOS 15 and later.
 * @property mslAltitudeMeters Height above mean sea level in meters: iOS always, Android API 34 and
 * later when the platform reports it. The two altitudes differ by the geoid height, up to about
 * 100 m, which is why the datum is part of the name.
 * @property verticalAccuracyMeters Uncertainty of both altitudes in meters (Android API 26 and later,
 * iOS). A negative platform value nulls all three altitude fields.
 * @property speedMetersPerSecond Ground speed in meters per second.
 * @property speedAccuracyMetersPerSecond Uncertainty of [speedMetersPerSecond] (Android API 26 and
 * later, iOS 10 and later).
 * @property courseDegrees Direction of travel in `[0, 360)` from true north. It is not the device
 * heading, and it is unreliable when nearly stationary.
 * @property courseAccuracyDegrees Uncertainty of [courseDegrees] in degrees (Android API 26 and
 * later, iOS 13.4 and later).
 * @property isSimulated Whether the OS marks the fix as mocked or simulated by software; `null`
 * where it cannot tell (iOS below 15).
 * @property isApproximate `true` when the fix was delivered approximate: under an approximate-only
 * grant (Android coarse only, iOS reduced accuracy) or for an iOS request of
 * `IosDesiredAccuracy.REDUCED`; `false` when it was delivered precise; `null` where unknown. Use
 * [horizontalAccuracyMeters] for any distance decision. To widen a grant, request
 * `CommonPermission.Location` again from shared code: Android offers the precise upgrade, and iOS
 * does too when `IosPermissionOptions.temporaryFullAccuracyPurposeKey` is set. iOS platform code can
 * also call `requestTemporaryFullAccuracy(purposeKey)`.
 * @property platformDetails Facts only one platform reports; read them through `androidDetails` /
 * `iosDetails` in platform code. `null` from app-written providers unless they set it.
 */
public data class LocationFix(
    public val coordinates: GeoCoordinates,
    public val timestampEpochMillis: Long,
    public val horizontalAccuracyMeters: Double? = null,
    public val ellipsoidalAltitudeMeters: Double? = null,
    public val mslAltitudeMeters: Double? = null,
    public val verticalAccuracyMeters: Double? = null,
    public val speedMetersPerSecond: Double? = null,
    public val speedAccuracyMetersPerSecond: Double? = null,
    public val courseDegrees: Double? = null,
    public val courseAccuracyDegrees: Double? = null,
    public val isSimulated: Boolean? = null,
    public val isApproximate: Boolean? = null,
    public val platformDetails: PlatformFixDetails? = null,
)

/**
 * Platform-only facts about a fix. Implemented only by this library (`AndroidFixDetails` on
 * Android, `IosFixDetails` on iOS): the interface carries
 * `@SubclassOptInRequired(ToolkitInternalApi::class)`, so an app cannot subclass it by accident.
 */
@SubclassOptInRequired(ToolkitInternalApi::class)
public interface PlatformFixDetails
