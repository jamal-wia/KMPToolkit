package io.github.jamal_wia.kmptoolkit.location

/**
 * A latitude/longitude box.
 *
 * `west > east` means the box crosses the antimeridian (longitude 180), and a polar cap spans
 * `west = -180`, `east = 180`. The constructor throws [IllegalArgumentException] unless all four
 * values are finite, `-90 <= south <= north <= 90`, and [west] and [east] are in `[-180, 180]`;
 * build a box from untrusted data (a map viewport, a stored value) with [orNull], which applies the
 * same rule and returns `null` instead of throwing.
 *
 * In SQL use [longitudeRanges], never `west..east`: the second form is the classic bug that misses
 * every row across the antimeridian.
 *
 * @property south Southern edge, degrees.
 * @property west Western edge, degrees.
 * @property north Northern edge, degrees.
 * @property east Eastern edge, degrees.
 */
public data class GeoBounds(
    public val south: Double,
    public val west: Double,
    public val north: Double,
    public val east: Double,
) {
    init {
        require(isValidBounds(south, west, north, east)) {
            "Invalid bounds: south=$south, west=$west, north=$north, east=$east " +
                "(all must be finite, -90 <= south <= north <= 90, west and east in [-180, 180])"
        }
    }

    /** `true` when the box wraps across longitude 180 (`west > east`); an east edge exactly at 180 is not a crossing. */
    public val crossesAntimeridian: Boolean
        get() = west > east

    /**
     * One non-wrapping longitude range, or two when the box crosses the antimeridian; each has
     * `start <= endInclusive`, ready for SQL `BETWEEN`. A crossing box gives `west..180` and
     * `-180..east`.
     *
     * A row stored at exactly `-180` is not matched by a range that ends at `180`, and the other way
     * round, although [contains] treats both as the same meridian; store the antimeridian as one
     * value.
     */
    public val longitudeRanges: List<ClosedFloatingPointRange<Double>>
        get() = if (crossesAntimeridian) listOf(west..180.0, -180.0..east) else listOf(west..east)

    /**
     * Whether [point] lies inside the box, edges included, antimeridian-aware. Longitude 180 and
     * -180 are the same meridian, so a box with an edge on one contains a point written on the
     * other.
     */
    public operator fun contains(point: GeoCoordinates): Boolean {
        if (point.latitude < south || point.latitude > north) return false
        val longitude: Double = point.longitude
        return when {
            crossesAntimeridian -> longitude >= west || longitude <= east
            longitude >= west && longitude <= east -> true
            else -> (longitude == -180.0 && east == 180.0) || (longitude == 180.0 && west == -180.0)
        }
    }

    public companion object {

        /**
         * The same validation as the constructor, for untrusted boxes such as map viewports or
         * stored values: `null` instead of throwing.
         */
        public fun orNull(south: Double, west: Double, north: Double, east: Double): GeoBounds? =
            if (isValidBounds(south, west, north, east)) GeoBounds(south, west, north, east) else null
    }
}

/** The one predicate behind the constructor and [GeoBounds.orNull]; NaN fails every comparison. */
private fun isValidBounds(south: Double, west: Double, north: Double, east: Double): Boolean =
    south >= -90.0 && south <= north && north <= 90.0 &&
        west >= -180.0 && west <= 180.0 &&
        east >= -180.0 && east <= 180.0
