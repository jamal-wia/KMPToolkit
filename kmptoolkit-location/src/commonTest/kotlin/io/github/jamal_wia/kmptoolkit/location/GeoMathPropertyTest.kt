package io.github.jamal_wia.kmptoolkit.location

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Seeded fuzz of the geo-math invariants (PLAN section 6.1, "Properties"). Every draw comes from
 * `Random(GEO_TEST_SEED)` or a fixed offset of it, and every failure message carries the seed, the
 * sample index and the inputs, so a failure replays exactly. None of these tests waits on time.
 */
class GeoMathPropertyTest {

    @Test
    fun `distance is symmetric`() {
        val rng = Random(GEO_TEST_SEED)
        repeat(50_000) { index: Int ->
            val a: GeoCoordinates = randomCoordinates(rng)
            val b: GeoCoordinates = randomCoordinates(rng)
            val forward: Double = a.distanceInMetersTo(b)
            val backward: Double = b.distanceInMetersTo(a)
            assertTrue(
                abs(forward - backward) <= distanceTolerance(forward),
                "sample $index: $a <-> $b gave $forward and $backward${seedNote()}",
            )
        }
    }

    @Test
    fun `distance lies between zero and half the circumference`() {
        val rng = Random(GEO_TEST_SEED + 1)
        repeat(50_000) { index: Int ->
            val a: GeoCoordinates = randomCoordinates(rng)
            val b: GeoCoordinates = randomCoordinates(rng)
            val distance: Double = a.distanceInMetersTo(b)
            assertTrue(
                distance >= 0.0 && distance <= HALF_CIRCUMFERENCE_METERS,
                "sample $index: $a to $b gave $distance${seedNote(GEO_TEST_SEED + 1)}",
            )
        }
    }

    @Test
    fun `the triangle inequality holds`() {
        val rng = Random(GEO_TEST_SEED + 2)
        repeat(50_000) { index: Int ->
            val a: GeoCoordinates = randomCoordinates(rng)
            val b: GeoCoordinates = randomCoordinates(rng)
            val c: GeoCoordinates = randomCoordinates(rng)
            val direct: Double = a.distanceInMetersTo(c)
            val viaB: Double = a.distanceInMetersTo(b) + b.distanceInMetersTo(c)
            assertTrue(
                direct <= viaB + max(distanceTolerance(direct), 1e-6),
                "sample $index: $a, $b, $c gave $direct against $viaB${seedNote(GEO_TEST_SEED + 2)}",
            )
        }
    }

    @Test
    fun `a destination is exactly the requested distance away`() {
        val rng = Random(GEO_TEST_SEED + 3)
        repeat(50_000) { index: Int ->
            val start: GeoCoordinates = randomCoordinates(rng)
            val distance: Double = randomTripLength(rng)
            val bearing: Double = rng.nextDouble(0.0, 360.0)
            val end: GeoCoordinates = start.destinationPoint(distance, bearing)
            val back: Double = start.distanceInMetersTo(end)
            val tolerance: Double = max(1e-8 * distance, 1e-6)
            assertTrue(
                abs(back - distance) <= tolerance,
                "sample $index: $start + $distance m at $bearing deg ended at $end, ${back} m back (tolerance $tolerance)${seedNote(GEO_TEST_SEED + 3)}",
            )
        }
    }

    @Test
    fun `the bearing back to the start of a trip is the bearing it left on`() {
        val rng = Random(GEO_TEST_SEED + 4)
        repeat(20_000) { index: Int ->
            // Away from the poles and the antipode, where a direction is ill-conditioned by nature.
            val start = GeoCoordinates(rng.nextDouble(-80.0, 80.0), rng.nextDouble(-180.0, 180.0))
            val distance: Double = rng.nextDouble(1_000.0, 0.8 * HALF_CIRCUMFERENCE_METERS)
            val bearing: Double = rng.nextDouble(0.0, 360.0)
            val end: GeoCoordinates = start.destinationPoint(distance, bearing)
            val measured: Double? = start.initialBearingTo(end)
            assertTrue(measured != null, "sample $index: no bearing from $start to $end${seedNote(GEO_TEST_SEED + 4)}")
            assertAngleClose(
                bearing,
                measured,
                1e-5,
                "sample $index: $start + $distance m at $bearing deg ended at $end${seedNote(GEO_TEST_SEED + 4)}",
            )
        }
    }

    @Test
    fun `bearings stay in zero to 360 and never reach 360`() {
        val rng = Random(GEO_TEST_SEED + 5)
        repeat(50_000) { index: Int ->
            val a: GeoCoordinates = randomCoordinates(rng)
            val b: GeoCoordinates = randomCoordinates(rng)
            val bearing: Double? = a.initialBearingTo(b)
            if (bearing != null) {
                assertTrue(
                    bearing >= 0.0 && bearing < 360.0 && !bearing.isNaN(),
                    "sample $index: $a to $b gave $bearing${seedNote(GEO_TEST_SEED + 5)}",
                )
            }
        }
    }

    @Test
    fun `two million trips biased to reach a pole never throw and never produce NaN`() {
        val seed: Int = GEO_TEST_SEED + 6
        val rng = Random(seed)
        repeat(2_000_000) { index: Int ->
            val start: GeoCoordinates = poleBiasedStart(rng)
            val distance: Double = poleReachingDistance(rng, start)
            val bearing: Double = poleBiasedBearing(rng, start)
            val end: GeoCoordinates = try {
                start.destinationPoint(distance, bearing)
            } catch (failure: Throwable) {
                throw AssertionError("sample $index: $start + $distance m at $bearing deg threw $failure${seedNote(seed)}", failure)
            }
            // The message is built only on failure: two million eager string templates would dominate the run.
            val valid: Boolean = !end.latitude.isNaN() && !end.longitude.isNaN() &&
                end.latitude >= -90.0 && end.latitude <= 90.0 &&
                end.longitude >= -180.0 && end.longitude < 180.0
            if (!valid) fail("sample $index: $start + $distance m at $bearing deg gave $end${seedNote(seed)}")
        }
    }

    /** Trip lengths from one centimetre to half the circumference, log-uniform, with an exact zero now and then. */
    private fun randomTripLength(rng: Random): Double = when (rng.nextInt(20)) {
        0 -> 0.0
        1 -> HALF_CIRCUMFERENCE_METERS
        else -> {
            val exponent: Double = rng.nextDouble(-2.0, log10(HALF_CIRCUMFERENCE_METERS))
            min(HALF_CIRCUMFERENCE_METERS, 10.0.pow(exponent))
        }
    }

    private fun poleBiasedStart(rng: Random): GeoCoordinates {
        val latitude: Double = when (rng.nextInt(4)) {
            0 -> if (rng.nextBoolean()) 90.0 else -90.0
            1 -> (if (rng.nextBoolean()) 1.0 else -1.0) * (90.0 - rng.nextDouble(0.0, 1e-9))
            2 -> (if (rng.nextBoolean()) 1.0 else -1.0) * rng.nextDouble(60.0, 90.0)
            else -> rng.nextDouble(-90.0, 90.0)
        }
        return GeoCoordinates(latitude, rng.nextDouble(-180.0, 180.0))
    }

    /** The distance that lands exactly on the nearer pole going straight at it, plus a jitter of a few ulps or a few meters. */
    private fun poleReachingDistance(rng: Random, start: GeoCoordinates): Double {
        val toPole: Double = (90.0 - abs(start.latitude)) * HALF_CIRCUMFERENCE_METERS / 180.0
        return when (rng.nextInt(5)) {
            0 -> toPole
            1 -> toPole + rng.nextDouble(-1.0, 1.0)
            2 -> toPole * 2.0
            3 -> toPole + HALF_CIRCUMFERENCE_METERS * rng.nextDouble(0.0, 0.5)
            else -> rng.nextDouble(0.0, 2.0 * HALF_CIRCUMFERENCE_METERS)
        }
    }

    /** Mostly straight at the nearer pole (0 in the north, 180 in the south), sometimes jittered, sometimes anything. */
    private fun poleBiasedBearing(rng: Random, start: GeoCoordinates): Double {
        val straight: Double = if (start.latitude >= 0.0) 0.0 else 180.0
        return when (rng.nextInt(4)) {
            0 -> straight
            1 -> straight + rng.nextDouble(-1e-9, 1e-9)
            2 -> straight + rng.nextDouble(-1.0, 1.0)
            else -> rng.nextDouble(-720.0, 720.0)
        }
    }
}
