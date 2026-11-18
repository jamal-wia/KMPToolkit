package io.github.jamal_wia.kmptoolkit.location

/**
 * A WGS-84 position in decimal degrees.
 *
 * The constructor throws [IllegalArgumentException] unless both values are finite, [latitude] is in
 * `[-90, 90]` and [longitude] is in `[-180, 180]`. There is no silent normalisation: a longitude of
 * 190 is rejected, not turned into -170. [copy] validates too, because it goes through the same
 * constructor.
 *
 * This is a runtime check the compiler cannot show. Build coordinates from data you do not trust
 * (storage, a network response, a catalogue, user input) with [orNull], which applies the same rule
 * and returns `null` instead of throwing. A literal or a value that is already known to be valid
 * keeps using the constructor.
 *
 * Both `-180` and `180` are accepted and name the same meridian: the distance between them is zero.
 *
 * @property latitude Degrees north of the equator, `-90..90`.
 * @property longitude Degrees east of the prime meridian, `-180..180`.
 */
public data class GeoCoordinates(
    public val latitude: Double,
    public val longitude: Double,
) {
    init {
        require(isValidCoordinatePair(latitude, longitude)) {
            "Invalid coordinates: latitude=$latitude, longitude=$longitude " +
                "(both must be finite, latitude in [-90, 90], longitude in [-180, 180])"
        }
    }

    public companion object {

        /**
         * The same validation as the constructor, for untrusted data such as storage, network or
         * catalogues: `null` instead of throwing.
         *
         * @return the coordinates, or `null` when either value is NaN or infinite, [latitude] is
         * outside `[-90, 90]`, or [longitude] is outside `[-180, 180]`.
         */
        public fun orNull(latitude: Double, longitude: Double): GeoCoordinates? =
            if (isValidCoordinatePair(latitude, longitude)) GeoCoordinates(latitude, longitude) else null
    }
}

/** The one predicate behind the constructor and [GeoCoordinates.orNull]; NaN fails every comparison. */
private fun isValidCoordinatePair(latitude: Double, longitude: Double): Boolean =
    latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0
