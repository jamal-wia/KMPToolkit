package io.github.jamal_wia.kmptoolkit.location

/**
 * A WGS-84 latitude/longitude pair, in decimal degrees.
 *
 * Platform- and feature-agnostic — the only shape this module returns. It carries no accuracy,
 * altitude, bearing, speed, or timestamp: a consumer that needs those reaches for the platform
 * location APIs directly, since this module's whole point is to stay small enough to depend on
 * without a second thought.
 */
public data class GeoCoordinates(
    public val latitude: Double,
    public val longitude: Double,
)
