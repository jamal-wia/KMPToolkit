package io.github.jamal_wia.kmptoolkit.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The IUGG mean Earth radius in meters: the sphere every function of this file works on. */
internal const val EARTH_MEAN_RADIUS_METERS: Double = 6_371_008.8

/**
 * Below this magnitude of the atan2 terms the direction between two points is undefined: coincident
 * points, exact antipodes. About 6 micrometers of arc; the rounding noise of the terms is about
 * 1e-16, so the margin is four orders of magnitude.
 */
private const val UNDEFINED_BEARING_THRESHOLD: Double = 1e-12

/**
 * Great-circle distance in meters on a sphere of radius 6 371 008.8 m, in the atan2 form (spherical
 * Vincenty), which stays well-conditioned at every distance: the law of cosines loses meters at
 * short range and haversine loses precision near antipodes.
 *
 * - Within 0.6 % of the WGS-84 geodesic (measured -0.45 % to +0.56 %).
 * - May differ from Android's `Location.distanceBetween` by up to about 0.56 %, and more near
 *   antipodes, where Android's iteration can stop early.
 * - About 0 (below 1e-8 m) for the same ground point written differently (a pole at any longitude,
 *   longitude 180 against -180); exactly `0.0` for equal values.
 *
 * Throws nothing: [GeoCoordinates] is already valid.
 */
public fun GeoCoordinates.distanceInMetersTo(other: GeoCoordinates): Double {
    val terms: AtanTerms = atanTerms(this, other)
    return EARTH_MEAN_RADIUS_METERS * atan2(hypot(terms.y, terms.x), terms.cosAngle)
}

/**
 * Initial great-circle bearing in degrees clockwise from true north, in `[0, 360)` and never
 * `360.0`, within 0.19 degrees of the geodesic below 10 000 km.
 *
 * `null` when the direction is undefined: coincident points, exact antipodes, or an origin at a
 * pole. A number there would be a lie that turns a Qibla arrow to north.
 */
public fun GeoCoordinates.initialBearingTo(other: GeoCoordinates): Double? {
    if (abs(latitude) == 90.0) return null
    val terms: AtanTerms = atanTerms(this, other)
    if (hypot(terms.y, terms.x) < UNDEFINED_BEARING_THRESHOLD) return null
    return normalizeDegrees360(atan2(terms.y, terms.x).toDegreesValue())
}

/**
 * The point [distanceMeters] along the great circle leaving at [bearingDegrees] clockwise from true
 * north. A negative distance goes backwards. The longitude of the result is in `[-180, 180)`, and
 * the result is never NaN at or across a pole.
 *
 * Throws [IllegalArgumentException] for a non-finite argument.
 */
public fun GeoCoordinates.destinationPoint(distanceMeters: Double, bearingDegrees: Double): GeoCoordinates {
    require(distanceMeters.isFinite()) { "distanceMeters must be finite: $distanceMeters" }
    require(bearingDegrees.isFinite()) { "bearingDegrees must be finite: $bearingDegrees" }

    val phi1: Double = latitude.toRadiansValue()
    val lambda1: Double = longitude.toRadiansValue()
    val theta: Double = bearingDegrees.toRadiansValue()
    val delta: Double = distanceMeters / EARTH_MEAN_RADIUS_METERS

    val sinPhi1: Double = sin(phi1)
    val cosPhi1: Double = cos(phi1)
    val sinDelta: Double = sin(delta)
    val cosDelta: Double = cos(delta)

    // The end point as a vector: z is its height, (east, north) the horizontal part. Taking the
    // latitude as atan2(z, hypot(east, north)) instead of asin(z) matters near a pole: asin(1 - e)
    // amplifies the rounding of z by 1 / sqrt(e), which made a 0.7 m trip from a pole 2 mm wrong and a
    // trip ending exactly at a pole 0.1 m short. atan2 never leaves [-pi/2, pi/2], so the one-ulp
    // overshoot of asin's argument (619 of 2 000 000 sampled trips, PLAN D3) cannot occur and the
    // validating constructor below cannot throw for a valid request; the final coerceIn is a belt.
    val east: Double = sin(theta) * sinDelta
    val north: Double = cosPhi1 * cosDelta - sinPhi1 * sinDelta * cos(theta)
    val height: Double = sinPhi1 * cosDelta + cosPhi1 * sinDelta * cos(theta)
    val phi2: Double = atan2(height, hypot(east, north))
    val lambda2: Double = lambda1 + atan2(east, north)

    // lambda1 is in [-pi, pi] and the atan2 term in [-pi, pi]: at most one wrap is needed, and
    // adjusting only outside the range keeps the full precision of values already inside it.
    var longitude2: Double = lambda2.toDegreesValue()
    if (longitude2 >= 180.0) longitude2 -= 360.0 else if (longitude2 < -180.0) longitude2 += 360.0
    return GeoCoordinates(phi2.toDegreesValue().coerceIn(-90.0, 90.0), longitude2)
}

/** The outward rounding of every [boundingBox] edge: far below any real measurement, far above rounding error. */
private const val BOX_EDGE_PADDING_DEGREES: Double = 5.0e-10

/**
 * The smallest box holding every point within [radiusMeters] of this position on the same sphere,
 * with each edge rounded outward by 5e-10 degrees (about 0.05 mm) so
 * that floating-point rounding can never leave a point that is exactly on the circle outside it.
 * It is a prefilter before [distanceInMetersTo]: a point outside the box is outside the radius, a
 * point inside still needs the exact distance. A pole inside the circle gives the full longitude
 * span. `west > east` in the result means the box crosses the antimeridian, so build a SQL
 * `BETWEEN` clause from [GeoBounds.longitudeRanges], never from `west..east`.
 *
 * Throws [IllegalArgumentException] for a negative or non-finite radius.
 */
public fun GeoCoordinates.boundingBox(radiusMeters: Double): GeoBounds {
    require(radiusMeters.isFinite() && radiusMeters >= 0.0) {
        "radiusMeters must be finite and not negative: $radiusMeters"
    }
    val angularRadius: Double = radiusMeters / EARTH_MEAN_RADIUS_METERS
    val angularRadiusDegrees: Double = angularRadius.toDegreesValue()
    val rawSouth: Double = latitude - angularRadiusDegrees
    val rawNorth: Double = latitude + angularRadiusDegrees

    // A pole inside the circle: the box reaches the pole and spans every longitude.
    if (rawNorth >= 90.0 || rawSouth <= -90.0) {
        return GeoBounds(
            south = (rawSouth - BOX_EDGE_PADDING_DEGREES).coerceAtLeast(-90.0),
            west = -180.0,
            north = (rawNorth + BOX_EDGE_PADDING_DEGREES).coerceAtMost(90.0),
            east = 180.0,
        )
    }

    // The circle's extreme longitudes: sin(delta-lambda) = sin(rho) / cos(phi). Neither pole is inside,
    // so the quotient is below 1; the clamp only absorbs rounding at the border.
    val ratio: Double = (sin(angularRadius) / cos(latitude.toRadiansValue())).coerceAtMost(1.0)
    val halfSpanDegrees: Double = asin(ratio).toDegreesValue()
    var west: Double = longitude - halfSpanDegrees - BOX_EDGE_PADDING_DEGREES
    var east: Double = longitude + halfSpanDegrees + BOX_EDGE_PADDING_DEGREES
    // An edge exactly at +-180 stays: only a value past it wraps, so east == 180 is not a crossing.
    if (west < -180.0) west += 360.0
    if (east > 180.0) east -= 360.0
    return GeoBounds(
        south = (rawSouth - BOX_EDGE_PADDING_DEGREES).coerceAtLeast(-90.0),
        west = west,
        north = (rawNorth + BOX_EDGE_PADDING_DEGREES).coerceAtMost(90.0),
        east = east,
    )
}

/**
 * Signed shortest turn from [fromDegrees] to [toDegrees], in `(-180, 180]`, positive clockwise. A
 * half turn is `180.0` in both directions. Inputs of any magnitude are accepted.
 *
 * Throws [IllegalArgumentException] for non-finite input.
 */
public fun angleDifference(fromDegrees: Double, toDegrees: Double): Double {
    require(fromDegrees.isFinite()) { "fromDegrees must be finite: $fromDegrees" }
    require(toDegrees.isFinite()) { "toDegrees must be finite: $toDegrees" }
    // Reduce each angle first so a huge input does not lose the fraction in the subtraction.
    val raw: Double = (toDegrees % 360.0) - (fromDegrees % 360.0)
    var turn: Double = raw % 360.0
    if (turn > 180.0) turn -= 360.0 else if (turn <= -180.0) turn += 360.0
    return if (turn == 0.0) 0.0 else turn
}

/** The three terms of the atan2 (spherical Vincenty) form: sin and cos of the central angle, scaled. */
private class AtanTerms(
    /** `cos(phi2) * sin(dLambda)`: the east component. */
    val y: Double,
    /** `cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)`: the north component. */
    val x: Double,
    /** `sin(phi1) * sin(phi2) + cos(phi1) * cos(phi2) * cos(dLambda)`: cosine of the central angle. */
    val cosAngle: Double,
)

private fun atanTerms(from: GeoCoordinates, to: GeoCoordinates): AtanTerms {
    val phi1: Double = from.latitude.toRadiansValue()
    val phi2: Double = to.latitude.toRadiansValue()
    // Wrapped in degrees first: 180 against -180 is exactly 0, not a 2.4e-16 sin residue.
    val dLambda: Double = wrapLongitudeDifference(to.longitude - from.longitude).toRadiansValue()

    val sinPhi1: Double = sin(phi1)
    val cosPhi1: Double = cos(phi1)
    val sinPhi2: Double = sin(phi2)
    val cosPhi2: Double = cos(phi2)
    val sinDLambda: Double = sin(dLambda)
    val cosDLambda: Double = cos(dLambda)
    return AtanTerms(
        y = cosPhi2 * sinDLambda,
        x = cosPhi1 * sinPhi2 - sinPhi1 * cosPhi2 * cosDLambda,
        cosAngle = sinPhi1 * sinPhi2 + cosPhi1 * cosPhi2 * cosDLambda,
    )
}

/** A longitude difference of two valid longitudes lies in [-360, 360]: one adjustment brings it into [-180, 180]. */
private fun wrapLongitudeDifference(differenceDegrees: Double): Double = when {
    differenceDegrees > 180.0 -> differenceDegrees - 360.0
    differenceDegrees < -180.0 -> differenceDegrees + 360.0
    else -> differenceDegrees
}

/** [degrees] into `[0, 360)`; `(x + 360) % 360` cannot return 360.0, a tiny negative x rounds to exactly 360 and wraps to 0. */
private fun normalizeDegrees360(degrees: Double): Double = (degrees + 360.0) % 360.0

private fun Double.toRadiansValue(): Double = this * PI / 180.0

private fun Double.toDegreesValue(): Double = this * 180.0 / PI
