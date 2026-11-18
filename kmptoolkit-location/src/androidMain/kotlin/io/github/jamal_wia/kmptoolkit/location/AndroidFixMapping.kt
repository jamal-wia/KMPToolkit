package io.github.jamal_wia.kmptoolkit.location

import android.location.Location
import android.os.Build
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Maps a platform [Location] to a [LocationFix] (PLAN section 4.1), or returns `null` when the
 * coordinates are invalid (NaN or out of range): an invalid platform fix is dropped, never thrown and
 * never emitted (PLAN D6, D16). Every optional field is `null` unless the platform says it has a
 * valid value (`has*()` and a finite, non-negative accuracy or speed).
 *
 * @param coarseOnly whether only `ACCESS_COARSE_LOCATION` is held at delivery, which makes the fix
 * approximate.
 */
internal fun Location.toLocationFix(coarseOnly: Boolean): LocationFix? {
    val where: GeoCoordinates = GeoCoordinates.orNull(latitude, longitude) ?: return null
    val simulated: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        isMock
    } else {
        @Suppress("DEPRECATION")
        isFromMockProvider
    }
    val api26: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    // A negative (or non-finite) vertical accuracy marks the altitudes as unusable: all three fields are
    // nulled together (LocationFix.verticalAccuracyMeters), the same rule as on iOS.
    val verticalAccuracyInvalid: Boolean =
        api26 && hasVerticalAccuracy() && !(verticalAccuracyMeters.isFinite() && verticalAccuracyMeters >= 0f)
    return LocationFix(
        coordinates = where,
        timestampEpochMillis = time,
        horizontalAccuracyMeters = if (hasAccuracy()) accuracy.nonNegative() else null,
        ellipsoidalAltitudeMeters = if (hasAltitude() && !verticalAccuracyInvalid) altitude.finiteOrNull() else null,
        mslAltitudeMeters = if (Build.VERSION.SDK_INT >= MSL_ALTITUDE_API && hasMslAltitude() && !verticalAccuracyInvalid) {
            mslAltitudeMeters.finiteOrNull()
        } else {
            null
        },
        verticalAccuracyMeters = if (api26 && hasVerticalAccuracy()) verticalAccuracyMeters.nonNegative() else null,
        speedMetersPerSecond = if (hasSpeed()) speed.nonNegative() else null,
        speedAccuracyMetersPerSecond = if (api26 && hasSpeedAccuracy()) speedAccuracyMetersPerSecond.nonNegative() else null,
        courseDegrees = if (hasBearing()) bearing.course() else null,
        courseAccuracyDegrees = if (api26 && hasBearingAccuracy()) bearingAccuracyDegrees.nonNegative() else null,
        isSimulated = simulated,
        isApproximate = coarseOnly,
        platformDetails = AndroidFixDetails(provider = provider ?: "", elapsedRealtimeNanos = elapsedRealtimeNanos),
    )
}

/**
 * The age of a fix that the platform already held when the request began, on the boot clock
 * (PLAN D10). A fix with no boot-clock stamp (`0`: only a hand-built `Location`) has an unknown
 * age, which no finite `maxAge` accepts.
 */
internal fun Location.cachedAge(nowElapsedNanos: Long): Duration =
    if (elapsedRealtimeNanos > 0L) (nowElapsedNanos - elapsedRealtimeNanos).nanoseconds else Duration.INFINITE

/**
 * The age of a fix the platform just delivered, on the boot clock. A delivered fix with no boot-clock
 * stamp is taken as fresh at delivery: it was handed over a moment ago and its age cannot be known.
 */
internal fun Location.deliveredAge(nowElapsedNanos: Long): Duration =
    if (elapsedRealtimeNanos > 0L) (nowElapsedNanos - elapsedRealtimeNanos).nanoseconds else Duration.ZERO

private const val MSL_ALTITUDE_API: Int = 34

private fun Float.nonNegative(): Double? = if (isFinite() && this >= 0f) toDouble() else null

private fun Double.finiteOrNull(): Double? = if (isFinite()) this else null

private fun Float.course(): Double? = if (isFinite() && this >= 0f && this < 360f) toDouble() else null
