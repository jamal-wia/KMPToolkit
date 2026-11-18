package io.github.jamal_wia.kmptoolkit.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.pow
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Breaker round 1 of group loc-core (LOC-2, LOC-3; decision D-23): attacks on the geo math at the
 * places the executor's sampling does not reach. The KDoc promises are taken literally:
 * - `boundingBox`: "floating-point rounding can never leave a point that is exactly on the circle
 *   outside it" - attacked at the circle's extreme longitudes when the circle almost touches a pole,
 *   where `asin(sin(rho) / cos(phi))` is ill-conditioned;
 * - `distanceInMetersTo`: a metric on the sphere (symmetry, triangle inequality, `[0, pi R]`), ~0 for
 *   the same ground point written differently;
 * - `initialBearingTo`: `[0, 360)` and never `360.0`, `null` exactly where undefined;
 * - `destinationPoint`: longitude in `[-180, 180)`, never NaN, valid for huge finite arguments, and a
 *   negative distance goes backwards;
 * - `angleDifference`: `(-180, 180]`, antisymmetric except the half turn, any magnitude.
 *
 * Every random choice comes from `Random(seed)`; the seed is in every failure message.
 */
class GeoMathBreakerR1Test {

    private fun radians(degrees: Double): Double = degrees * PI / 180.0

    private fun degrees(radians: Double): Double = radians * 180.0 / PI

    // region boundingBox near a pole

    /**
     * Circles that almost touch a pole without containing it (so the box is not the full polar cap) and
     * points on them at the bearing of the extreme longitude, where the meridian is tangent to the circle:
     * `cos(bearing) = tan(rho) * tan(phi)` (right spherical triangle pole-centre-tangent point).
     */
    @Test
    fun `no point on a circle that almost touches a pole lies outside its bounding box`() {
        val seed: Int = GEO_TEST_SEED + 700
        val rng = Random(seed)
        var cases = 0
        var violations = 0
        var example: String? = null
        repeat(4_000) {
            val fromPole: Double = 10.0.pow(rng.nextDouble(-9.0, 1.5))
            val north: Boolean = rng.nextBoolean()
            val latitude: Double = if (north) 90.0 - fromPole else -90.0 + fromPole
            if (abs(latitude) >= 90.0) return@repeat
            val centre = GeoCoordinates(latitude, rng.nextDouble(-180.0, 180.0))
            val pole = GeoCoordinates(if (north) 90.0 else -90.0, 0.0)
            val radius: Double = centre.distanceInMetersTo(pole) * (1.0 - 10.0.pow(rng.nextDouble(-16.0, -1.0)))
            val box: GeoBounds = centre.boundingBox(radius)
            if (box.west == -180.0 && box.east == 180.0) return@repeat
            cases++
            val tangent: Double = tan(radius / SPHERE_RADIUS_METERS) * tan(radians(abs(latitude)))
            if (tangent > 1.0) return@repeat
            val bearingToTangent: Double = degrees(acos(tangent)).let { if (north) it else 180.0 - it }
            for (side: Double in doubleArrayOf(1.0, -1.0)) {
                repeat(20) {
                    val bearing: Double = side * bearingToTangent + rng.nextDouble(-1.0, 1.0) * 10.0.pow(rng.nextDouble(-13.0, -2.0))
                    val point: GeoCoordinates = centre.destinationPoint(radius, bearing)
                    if (centre.distanceInMetersTo(point) <= radius && point !in box) {
                        violations++
                        if (example == null) {
                            example = "centre=$centre radius=$radius m bearing=$bearing point=$point " +
                                "(distance ${centre.distanceInMetersTo(point)} m <= radius) box=$box"
                        }
                    }
                }
            }
        }
        assertTrue(cases > 1_000, "control: enough near-pole circles were tried (cases=$cases) seed=$seed")
        assertEquals(0, violations, "seed=$seed: $violations points within the radius fell outside the box over $cases circles; first: $example")
    }

    // endregion

    // region distance is a metric

    @Test
    fun `distance is symmetric bounded by half the circumference and obeys the triangle inequality`() {
        val seed: Int = GEO_TEST_SEED + 701
        val rng = Random(seed)
        repeat(20_000) { index: Int ->
            val a: GeoCoordinates = randomCoordinates(rng)
            val b: GeoCoordinates = randomCoordinates(rng)
            val c: GeoCoordinates = randomCoordinates(rng)
            val ab: Double = a.distanceInMetersTo(b)
            val ba: Double = b.distanceInMetersTo(a)
            val bc: Double = b.distanceInMetersTo(c)
            val ac: Double = a.distanceInMetersTo(c)
            assertTrue(ab >= 0.0 && ab <= HALF_CIRCUMFERENCE_METERS, "seed=$seed #$index: $a -> $b = $ab m outside [0, pi R]")
            assertTrue(abs(ab - ba) <= 1e-6, "seed=$seed #$index: asymmetric $a <-> $b: $ab against $ba")
            assertTrue(ac <= ab + bc + 1e-6, "seed=$seed #$index: triangle inequality broken: d($a,$c)=$ac > $ab + $bc")
        }
    }

    @Test
    fun `the same ground point written differently is about zero apart and equal values are exactly zero`() {
        val seed: Int = GEO_TEST_SEED + 702
        val rng = Random(seed)
        repeat(5_000) { index: Int ->
            val pole: Double = if (rng.nextBoolean()) 90.0 else -90.0
            val polar: Double = GeoCoordinates(pole, rng.nextDouble(-180.0, 180.0)).distanceInMetersTo(GeoCoordinates(pole, rng.nextDouble(-180.0, 180.0)))
            assertTrue(polar < 1e-8, "seed=$seed #$index: a pole at two longitudes is $polar m from itself")
            val latitude: Double = rng.nextDouble(-90.0, 90.0)
            val seam: Double = GeoCoordinates(latitude, 180.0).distanceInMetersTo(GeoCoordinates(latitude, -180.0))
            assertTrue(seam < 1e-8, "seed=$seed #$index: 180 against -180 at latitude $latitude is $seam m")
            val zero: Double = GeoCoordinates(latitude, 0.0).distanceInMetersTo(GeoCoordinates(latitude, -0.0))
            assertTrue(zero < 1e-8, "seed=$seed #$index: 0.0 against -0.0 is $zero m")
            val same: GeoCoordinates = randomCoordinates(rng)
            assertEquals(0.0, same.distanceInMetersTo(GeoCoordinates(same.latitude, same.longitude)), "seed=$seed #$index: $same")
        }
    }

    // endregion

    // region bearing

    @Test
    fun `the bearing is in 0 until 360 even for a direction a hair west of north`() {
        val seed: Int = GEO_TEST_SEED + 703
        val rng = Random(seed)
        repeat(10_000) { index: Int ->
            val latitude: Double = rng.nextDouble(-80.0, 80.0)
            val longitude: Double = rng.nextDouble(-170.0, 170.0)
            val from = GeoCoordinates(latitude, longitude)
            // A target due north, nudged west by a few ulps: the raw atan2 is a tiny negative angle.
            val nudge: Double = 10.0.pow(rng.nextDouble(-16.0, -9.0))
            val to = GeoCoordinates((latitude + rng.nextDouble(0.001, 9.0)).coerceAtMost(89.0), longitude - nudge)
            val bearing: Double? = from.initialBearingTo(to)
            assertNotNull(bearing, "seed=$seed #$index: $from -> $to")
            assertTrue(bearing >= 0.0 && bearing < 360.0, "seed=$seed #$index: $from -> $to gave $bearing")
            val toPole: Double? = from.initialBearingTo(GeoCoordinates(90.0, longitude - nudge))
            assertTrue(toPole != null && toPole >= 0.0 && toPole < 360.0, "seed=$seed #$index: to the north pole gave $toPole")
        }
    }

    @Test
    fun `the bearing is null from either pole and at exact antipodes but defined a micro-degree away`() {
        val seed: Int = GEO_TEST_SEED + 704
        val rng = Random(seed)
        repeat(2_000) { index: Int ->
            val target: GeoCoordinates = randomCoordinates(rng)
            assertNull(GeoCoordinates(90.0, rng.nextDouble(-180.0, 180.0)).initialBearingTo(target), "seed=$seed #$index: from the north pole")
            assertNull(GeoCoordinates(-90.0, rng.nextDouble(-180.0, 180.0)).initialBearingTo(target), "seed=$seed #$index: from the south pole")
            val latitude: Double = rng.nextDouble(-80.0, 80.0)
            val longitude: Double = rng.nextDouble(-179.0, 0.0)
            val from = GeoCoordinates(latitude, longitude)
            assertNull(from.initialBearingTo(GeoCoordinates(-latitude, longitude + 180.0)), "seed=$seed #$index: exact antipode of $from")
            assertNotNull(from.initialBearingTo(GeoCoordinates(-latitude + 1e-3, longitude + 180.0)), "seed=$seed #$index: near antipode of $from")
        }
    }

    // endregion

    // region destinationPoint

    @Test
    fun `destination longitudes stay in -180 until 180 and nothing is NaN for any finite argument`() {
        val seed: Int = GEO_TEST_SEED + 705
        val rng = Random(seed)
        val distances: DoubleArray = doubleArrayOf(0.0, -0.0, Double.MIN_VALUE, 1e-300, 1.0, 1e7, HALF_CIRCUMFERENCE_METERS, 2 * HALF_CIRCUMFERENCE_METERS, 1e15, 1e300, Double.MAX_VALUE, -Double.MAX_VALUE)
        val bearings: DoubleArray = doubleArrayOf(0.0, -0.0, 90.0, 180.0, 270.0, 360.0, -360.0, 1e300, -1e300, Double.MIN_VALUE)
        repeat(1_000) { index: Int ->
            val from: GeoCoordinates = when (index % 4) {
                0 -> GeoCoordinates(rng.nextDouble(-90.0, 90.0), 180.0)
                1 -> GeoCoordinates(rng.nextDouble(-90.0, 90.0), -180.0)
                2 -> GeoCoordinates(if (rng.nextBoolean()) 90.0 else -90.0, rng.nextDouble(-180.0, 180.0))
                else -> randomCoordinates(rng)
            }
            for (distance: Double in distances) {
                for (bearing: Double in bearings) {
                    val to: GeoCoordinates = from.destinationPoint(distance, bearing)
                    assertTrue(!to.latitude.isNaN() && !to.longitude.isNaN(), "seed=$seed #$index: $from, $distance m, $bearing deg -> $to")
                    assertTrue(to.longitude >= -180.0 && to.longitude < 180.0, "seed=$seed #$index: $from, $distance m, $bearing deg -> longitude ${to.longitude}")
                }
            }
        }
    }

    /**
     * The KDoc throws only "for a non-finite argument", so every finite bearing is a valid request. A bearing
     * above about 5.7e306 in magnitude overflows `bearing * PI` to infinity before the trigonometry.
     */
    @Test
    fun `a finite bearing of any magnitude is accepted and gives a valid point`() {
        val from = GeoCoordinates(10.0, 20.0)
        for (bearing: Double in doubleArrayOf(1e306, 1e307, -1e307, Double.MAX_VALUE, -Double.MAX_VALUE)) {
            val to: GeoCoordinates = runCatching { from.destinationPoint(1_000.0, bearing) }.getOrElse { failure: Throwable ->
                throw AssertionError("a finite bearing $bearing was refused: $failure", failure)
            }
            assertTrue(abs(from.distanceInMetersTo(to) - 1_000.0) <= 1e-6, "bearing $bearing: $to is not 1 km away")
        }
    }

    @Test
    fun `a negative distance is the same trip backwards`() {
        val seed: Int = GEO_TEST_SEED + 706
        val rng = Random(seed)
        repeat(10_000) { index: Int ->
            val from = GeoCoordinates(rng.nextDouble(-89.0, 89.0), rng.nextDouble(-180.0, 180.0))
            val distance: Double = rng.nextDouble(0.0, 1.9e7)
            val bearing: Double = rng.nextDouble(0.0, 360.0)
            val backwards: GeoCoordinates = from.destinationPoint(-distance, bearing)
            val reversed: GeoCoordinates = from.destinationPoint(distance, bearing + 180.0)
            val gap: Double = backwards.distanceInMetersTo(reversed)
            assertTrue(gap <= 1e-6, "seed=$seed #$index: $from, -$distance m at $bearing: $backwards against $reversed ($gap m)")
        }
    }

    // endregion

    // region angleDifference

    @Test
    fun `angle difference is in the half-open range antisymmetric except the half turn and exact for huge inputs`() {
        val seed: Int = GEO_TEST_SEED + 707
        val rng = Random(seed)
        repeat(50_000) { index: Int ->
            val magnitude: Double = 10.0.pow(rng.nextDouble(-3.0, 300.0))
            val from: Double = (if (rng.nextBoolean()) 1.0 else -1.0) * rng.nextDouble() * magnitude
            val to: Double = when (rng.nextInt(4)) {
                0 -> from
                1 -> from + 180.0
                2 -> from - 360.0 * rng.nextInt(1, 1_000)
                else -> (if (rng.nextBoolean()) 1.0 else -1.0) * rng.nextDouble() * magnitude
            }
            val turn: Double = angleDifference(from, to)
            val back: Double = angleDifference(to, from)
            assertTrue(turn > -180.0 && turn <= 180.0, "seed=$seed #$index: angleDifference($from, $to) = $turn")
            assertTrue(back > -180.0 && back <= 180.0, "seed=$seed #$index: angleDifference($to, $from) = $back")
            if (turn != 180.0) assertTrue(-turn == back, "seed=$seed #$index: not antisymmetric for $from, $to: $turn against $back")
            if (to == from) assertTrue(turn == 0.0, "seed=$seed #$index: no turn from an angle to itself: $turn")
        }
        assertEquals(180.0, angleDifference(0.0, -180.0))
        assertEquals(180.0, angleDifference(-180.0, 0.0))
        assertTrue(angleDifference(Double.MAX_VALUE, Double.MAX_VALUE) == 0.0)
        assertTrue(angleDifference(-Double.MAX_VALUE, -Double.MAX_VALUE) == 0.0)
        assertTrue(angleDifference(Double.MIN_VALUE, -Double.MIN_VALUE).let { it > -180.0 && it <= 180.0 })
    }

    // endregion
}
