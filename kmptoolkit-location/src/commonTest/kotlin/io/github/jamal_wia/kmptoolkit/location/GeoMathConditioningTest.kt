package io.github.jamal_wia.kmptoolkit.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Group loc-core, round 4 (breaker findings m1 and m2 of round 1, decision D-23): the places where
 * the geo math is ill-conditioned or overflows.
 *
 * - [boundingBox] of a circle that almost touches a pole, at every centre latitude and circle size
 *   (not only near a pole: a circle of 10 000 km around the equator is nearly tangent to the pole
 *   meridian-wise as well). `asin` of a ratio near 1 amplifies rounding by up to 1e8, so a fixed
 *   padding of the longitude cannot work; the contract of the box is "a point whose distance from the
 *   centre is within the radius is inside it".
 * - [destinationPoint] for a finite bearing of any magnitude.
 *
 * Every random choice comes from `Random(seed)`; the seed is in every failure message.
 */
class GeoMathConditioningTest {

    private fun radians(degrees: Double): Double = degrees * PI / 180.0

    private fun degrees(radians: Double): Double = radians * 180.0 / PI

    /**
     * The circle's tangent bearing: the bearing of the points with the extreme longitude,
     * `cos(bearing) = tan(rho) * tan(phi)` (north) from the pole-centre-tangent right triangle.
     */
    private fun tangentBearing(latitude: Double, angularRadius: Double): Double? {
        val tangent: Double = tan(angularRadius) * tan(radians(abs(latitude)))
        if (tangent > 1.0) return null
        val toTangent: Double = degrees(acos(tangent))
        return if (latitude >= 0.0) toTangent else 180.0 - toTangent
    }

    /** Circles whose gap to the pole is `10^u` radians, `u` in [-13, -0.5], at centres anywhere from the equator to the pole. */
    @Test
    fun `a circle that almost touches a pole keeps every point within the radius inside its box at any size`() {
        val seed: Int = GEO_TEST_SEED + 800
        val rng = Random(seed)
        var tested = 0
        repeat(6_000) { index: Int ->
            val north: Boolean = rng.nextBoolean()
            // Centre colatitude from 1e-3 to 90 degrees: small and large circles alike.
            val colatitude: Double = 10.0.pow(rng.nextDouble(-3.0, 1.9544))
            val latitude: Double = (if (north) 90.0 else -90.0) - (if (north) colatitude else -colatitude)
            if (abs(latitude) >= 90.0) return@repeat
            val centre = GeoCoordinates(latitude, rng.nextDouble(-180.0, 180.0))
            val colatitudeRadians: Double = radians(90.0 - abs(latitude))
            val gap: Double = 10.0.pow(rng.nextDouble(-13.0, -0.5))
            val angularRadius: Double = colatitudeRadians - gap
            if (angularRadius <= 0.0) return@repeat
            val radius: Double = angularRadius * SPHERE_RADIUS_METERS
            val box: GeoBounds = centre.boundingBox(radius)
            if (!(box.west == -180.0 && box.east == 180.0)) tested++
            val bearing: Double = tangentBearing(latitude, angularRadius) ?: return@repeat
            for (side: Double in doubleArrayOf(1.0, -1.0)) {
                repeat(10) {
                    val jitter: Double = rng.nextDouble(-1.0, 1.0) * 10.0.pow(rng.nextDouble(-13.0, -2.0))
                    val point: GeoCoordinates = centre.destinationPoint(radius, side * bearing + jitter)
                    if (centre.distanceInMetersTo(point) <= radius && point !in box) {
                        fail(
                            "circle $index: $point is within the radius $radius m of $centre (gap $gap rad) " +
                                "but outside $box${seedNote(seed)}",
                        )
                    }
                }
            }
        }
        assertTrue(tested > 1_000, "control: enough circles kept a longitude-limited box (tested=$tested)${seedNote(seed)}")
    }

    /** Large circles around the equator are the worst case: the tangency is a double root, so the conditioning is the worst. */
    @Test
    fun `a circle of a quarter of the Earth around the equator is limited only when it clearly misses the pole`() {
        val seed: Int = GEO_TEST_SEED + 803
        val rng = Random(seed)
        val equator = GeoCoordinates(0.0, 30.0)
        // 1 mm short of the pole: the pole cannot be ruled out, every longitude.
        val almost: GeoBounds = equator.boundingBox(SPHERE_RADIUS_METERS * (PI / 2.0 - 1e-9))
        assertEquals(-180.0, almost.west, "almost touching, full longitude")
        assertEquals(180.0, almost.east, "almost touching, full longitude")
        // 6 km short of it: a real limit, narrower than the full circle and holding the centre.
        val clear: GeoBounds = equator.boundingBox(SPHERE_RADIUS_METERS * (PI / 2.0 - 1e-3))
        assertTrue(clear.west > -180.0 && clear.east < 180.0 && equator in clear, "clear of the pole: $clear")
        // Points of that circle, found without the box: every one inside.
        var inside = 0
        repeat(2_000) { index: Int ->
            val radius: Double = SPHERE_RADIUS_METERS * (PI / 2.0 - 10.0.pow(rng.nextDouble(-9.0, -2.0)))
            val box: GeoBounds = equator.boundingBox(radius)
            val point: GeoCoordinates = equator.destinationPoint(radius, rng.nextDouble(0.0, 360.0))
            if (equator.distanceInMetersTo(point) <= radius) {
                inside++
                assertTrue(point in box, "circle $index: $point on the circle of $radius m around $equator is outside $box${seedNote(seed)}")
            }
        }
        assertTrue(inside > 500, "control: points were within the radius (inside=$inside)${seedNote(seed)}")
    }

    /** Points sampled directly near the pole, independent of destinationPoint, against the same contract. */
    @Test
    fun `points sampled near a pole are inside the box whenever they are within the radius`() {
        val seed: Int = GEO_TEST_SEED + 801
        val rng = Random(seed)
        var within = 0
        repeat(4_000) { index: Int ->
            val centre = GeoCoordinates(90.0 - 10.0.pow(rng.nextDouble(-6.0, 1.5)), rng.nextDouble(-180.0, 180.0))
            val pole = GeoCoordinates(90.0, 0.0)
            val radius: Double = centre.distanceInMetersTo(pole) * (1.0 - 10.0.pow(rng.nextDouble(-12.0, -1.0)))
            val box: GeoBounds = centre.boundingBox(radius)
            repeat(40) {
                val point = GeoCoordinates(90.0 - 10.0.pow(rng.nextDouble(-12.0, 1.5)), rng.nextDouble(-180.0, 180.0))
                if (centre.distanceInMetersTo(point) <= radius) {
                    within++
                    if (point !in box) fail("circle $index: $point is within $radius m of $centre but outside $box${seedNote(seed)}")
                }
            }
        }
        assertTrue(within > 200, "control: some sampled points were within a radius (within=$within)${seedNote(seed)}")
    }

    /** Well away from tangency the longitude is as tight as the plain formula: the fix must not blur ordinary boxes. */
    @Test
    fun `a circle well away from a pole keeps a box as tight as the plain formula`() {
        val seed: Int = GEO_TEST_SEED + 802
        val rng = Random(seed)
        repeat(5_000) { index: Int ->
            val latitude: Double = rng.nextDouble(-80.0, 80.0)
            val centre = GeoCoordinates(latitude, rng.nextDouble(-90.0, 90.0))
            val rho: Double = rng.nextDouble(1e-7, radians(90.0 - abs(latitude)) - 1e-3)
            val box: GeoBounds = centre.boundingBox(rho * SPHERE_RADIUS_METERS)
            val plain: Double = degrees(asin(sin(rho) / cos(radians(latitude))))
            val allowed = 5.0e-10 + 1e-8
            assertTrue(
                abs((centre.longitude - plain) - box.west) <= allowed && abs((centre.longitude + plain) - box.east) <= allowed,
                "circle $index: $box is not within $allowed deg of lon ${centre.longitude} +- $plain (centre $centre, rho $rho)${seedNote(seed)}",
            )
        }
    }

    @Test
    fun `a finite bearing of any magnitude gives the same point as the bearing reduced to a turn`() {
        val from = GeoCoordinates(10.0, 20.0)
        for (bearing: Double in doubleArrayOf(1e306, 5.8e306, 1e307, Double.MAX_VALUE, -5.8e306, -Double.MAX_VALUE)) {
            val to: GeoCoordinates = from.destinationPoint(1_000.0, bearing)
            val reduced: GeoCoordinates = from.destinationPoint(1_000.0, bearing % 360.0)
            assertEquals(reduced, to, "bearing $bearing")
        }
        // Ordinary bearings are untouched by the reduction, including a full turn and a negative one.
        assertEquals(from.destinationPoint(1_000.0, 0.0), from.destinationPoint(1_000.0, 360.0))
        assertEquals(from.destinationPoint(1_000.0, 90.0), from.destinationPoint(1_000.0, -270.0))
        assertFailsWith<IllegalArgumentException> { from.destinationPoint(1_000.0, Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { from.destinationPoint(1_000.0, Double.NaN) }
    }
}
