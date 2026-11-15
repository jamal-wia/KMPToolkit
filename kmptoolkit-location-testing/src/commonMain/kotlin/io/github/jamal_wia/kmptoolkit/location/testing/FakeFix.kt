package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.GeoCoordinates
import io.github.jamal_wia.kmptoolkit.location.LocationFix
import io.github.jamal_wia.kmptoolkit.location.PlatformFixDetails

/**
 * Builds a [LocationFix] with test defaults, so a test names only what it cares about:
 * `fakeFix(21.4225, 39.8262)` is a precise (5 m), non-simulated, non-approximate fix at that point.
 *
 * Every parameter maps one to one onto the [LocationFix] field of the same name. Only the defaults
 * differ from the data class: a 5 m horizontal accuracy, `isSimulated = false` and
 * `isApproximate = false` (the data class defaults every optional fact to `null`, "the platform
 * reported none"), and a timestamp of `0`.
 *
 * Throws [IllegalArgumentException] for coordinates [GeoCoordinates] rejects (non-finite, latitude
 * outside [-90, 90], longitude outside [-180, 180]); a test that needs an invalid platform fix
 * builds its own input, because no provider ever emits one.
 */
public fun fakeFix(
    latitude: Double,
    longitude: Double,
    timestampEpochMillis: Long = 0L,
    horizontalAccuracyMeters: Double? = 5.0,
    ellipsoidalAltitudeMeters: Double? = null,
    mslAltitudeMeters: Double? = null,
    verticalAccuracyMeters: Double? = null,
    speedMetersPerSecond: Double? = null,
    speedAccuracyMetersPerSecond: Double? = null,
    courseDegrees: Double? = null,
    courseAccuracyDegrees: Double? = null,
    isSimulated: Boolean? = false,
    isApproximate: Boolean? = false,
    platformDetails: PlatformFixDetails? = null,
): LocationFix = LocationFix(
    coordinates = GeoCoordinates(latitude = latitude, longitude = longitude),
    timestampEpochMillis = timestampEpochMillis,
    horizontalAccuracyMeters = horizontalAccuracyMeters,
    ellipsoidalAltitudeMeters = ellipsoidalAltitudeMeters,
    mslAltitudeMeters = mslAltitudeMeters,
    verticalAccuracyMeters = verticalAccuracyMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
    courseDegrees = courseDegrees,
    courseAccuracyDegrees = courseAccuracyDegrees,
    isSimulated = isSimulated,
    isApproximate = isApproximate,
    platformDetails = platformDetails,
)
