package io.github.jamal_wia.kmptoolkit.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Shared helpers of the geo-math tests. Every random choice in these tests comes from
 * `Random(GEO_TEST_SEED)`-derived generators, and the seed is part of every failure message.
 */

/** The fixed default seed of every seeded geo test; printed by [seedNote] in each failure message. */
internal const val GEO_TEST_SEED: Int = 20_261_115

/** The sphere radius the library works on, restated here so a changed constant fails the reference tests. */
internal const val SPHERE_RADIUS_METERS: Double = 6_371_008.8

/** `" [seed=...]"` for failure messages. */
internal fun seedNote(seed: Int = GEO_TEST_SEED): String = " [seed=$seed]"

/** The oracle tolerance for a distance (PLAN section 6): `max(1e-9 * expected, 1e-6 m)`. */
internal fun distanceTolerance(expected: Double): Double = max(1e-9 * expected, 1e-6)

/** Asserts `|actual - expected| <= max(1e-9 * expected, 1e-6 m)`. */
internal fun assertDistanceClose(expected: Double, actual: Double, message: String) {
    val tolerance: Double = distanceTolerance(expected)
    assertTrue(
        abs(actual - expected) <= tolerance,
        "$message: expected $expected m, got $actual m (difference ${abs(actual - expected)} > $tolerance)",
    )
}

/** Asserts a plain tolerance on any value. */
internal fun assertClose(expected: Double, actual: Double, tolerance: Double, message: String) {
    assertTrue(
        abs(actual - expected) <= tolerance,
        "$message: expected $expected, got $actual (difference ${abs(actual - expected)} > $tolerance)",
    )
}

/** Asserts two angles in degrees are the same direction within [toleranceDegrees], across the 0/360 seam. */
internal fun assertAngleClose(expectedDegrees: Double, actualDegrees: Double, toleranceDegrees: Double, message: String) {
    val difference: Double = abs(angleDifference(expectedDegrees, actualDegrees))
    assertTrue(
        difference <= toleranceDegrees,
        "$message: expected $expectedDegrees deg, got $actualDegrees deg (difference $difference > $toleranceDegrees)",
    )
}

/** A random valid position; about one in eight draws is exactly a pole or on the +-180 meridian. */
internal fun randomCoordinates(rng: Random): GeoCoordinates {
    val latitude: Double = when (rng.nextInt(8)) {
        0 -> if (rng.nextBoolean()) 90.0 else -90.0
        1 -> (if (rng.nextBoolean()) 1.0 else -1.0) * (90.0 - rng.nextDouble() * 1e-3)
        else -> rng.nextDouble(-90.0, 90.0)
    }
    val longitude: Double = when (rng.nextInt(8)) {
        0 -> if (rng.nextBoolean()) 180.0 else -180.0
        else -> rng.nextDouble(-180.0, 180.0)
    }
    return GeoCoordinates(latitude, longitude)
}

/** Half the circumference of the sphere: the largest possible great-circle distance. */
internal const val HALF_CIRCUMFERENCE_METERS: Double = PI * SPHERE_RADIUS_METERS
