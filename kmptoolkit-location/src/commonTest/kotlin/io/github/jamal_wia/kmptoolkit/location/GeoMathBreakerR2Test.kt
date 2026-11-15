package io.github.jamal_wia.kmptoolkit.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Breaker round 2 of group loc-core (LOC-1, LOC-2, LOC-3, LOC-5, LOC-6; decision D-23): tests that try
 * to break the conditioned `boundingBox` and the reduced bearing of `destinationPoint` added in round 4
 * against their KDoc ("rounding can never leave a point that is exactly on the circle outside it", "a
 * point outside the box is outside the radius", "the smallest box", "any finite argument").
 *
 * Unlike the round-1 attack and the executor's conditioning test, which walk ON the circle with
 * `destinationPoint`, these tests take the contrapositive and build the probe points directly from the box:
 * a point a hair outside any edge (longitude edges at the analytic tangent latitude, latitude edges on the
 * centre meridian) must be farther than the radius by the module's own `distanceInMetersTo`. Centres
 * cover both hemispheres (the south pole branch goes through `abs(latitude)`), the seam (+-180), the
 * equator with circles almost touching both poles, and radii from 0 to beyond half the Earth.
 *
 * Every random choice comes from `Random(seed)`; the seed is in every failure message.
 */
class GeoMathBreakerR2Test {

    private fun Double.toRadians(): Double = this * PI / 180.0

    private fun Double.toDegrees(): Double = this * 180.0 / PI

    private fun wrapLongitude(longitude: Double): Double = when {
        longitude > 180.0 -> longitude - 360.0
        longitude < -180.0 -> longitude + 360.0
        else -> longitude
    }

    private val GeoBounds.isFullLongitude: Boolean
        get() = west == -180.0 && east == 180.0

    @Test
    fun `a point a hair outside any edge of the box is outside the radius`() {
        val seed: Int = GEO_TEST_SEED + 92_100
        val rng = Random(seed)
        var probes = 0
        var nearTangent = 0
        val seams: List<Double> = listOf(180.0, -180.0, 179.9999999, -179.9999999)
        repeat(12_000) { circle: Int ->
            // A third of the circles are centred within a degree of the equator and stop 1e-12 to 1e-8 rad short of
            // both poles: there the tangency is a double root and the relative rounding of the asin argument rules.
            val equatorial: Boolean = circle % 3 == 0
            val latitude: Double = when {
                equatorial -> rng.nextDouble(-1.0, 1.0)
                rng.nextBoolean() -> rng.nextDouble(-90.0, 90.0)
                else -> (if (rng.nextBoolean()) 1.0 else -1.0) * (90.0 - 10.0.pow(rng.nextDouble(-9.0, 1.95)))
            }
            val longitude: Double = if (rng.nextInt(10) < 7) rng.nextDouble(-180.0, 180.0) else seams[rng.nextInt(seams.size)]
            val colatitude: Double = (90.0 - abs(latitude)).toRadians()
            val tangent: Boolean = equatorial || rng.nextInt(10) < 6
            val angularRadius: Double = when {
                equatorial -> colatitude - 10.0.pow(rng.nextDouble(-12.0, -8.0))
                tangent -> colatitude - 10.0.pow(rng.nextDouble(-16.0, -2.0))
                else -> colatitude * rng.nextDouble()
            }
            if (angularRadius <= 0.0) return@repeat
            val radius: Double = angularRadius * EARTH_MEAN_RADIUS_METERS
            val centre = GeoCoordinates(latitude, longitude)
            val box: GeoBounds = centre.boundingBox(radius)
            val note = "seed=$seed circle=$circle centre=$centre radius=$radius box=$box"

            fun probe(point: GeoCoordinates) {
                if (point in box) return
                probes++
                val distance: Double = centre.distanceInMetersTo(point)
                if (distance <= radius) fail("a point outside the box is within the radius: point=$point distance=$distance $note")
            }

            // Latitude edges, on the centre meridian.
            for (outward: Double in listOf(1.0, -1.0)) {
                val edge: Double = if (outward > 0) box.north else box.south
                val latitudeOut: Double = edge + outward * 10.0.pow(rng.nextDouble(-15.0, -9.0))
                if (abs(latitudeOut) <= 90.0) probe(GeoCoordinates(latitudeOut, longitude))
            }
            if (box.isFullLongitude) return@repeat
            // Longitude edges, around the analytic latitude of the tangency: sin(phiT) = sin(phi) / cos(rho).
            val sinTangent: Double = sin(latitude.toRadians()) / cos(angularRadius)
            if (abs(sinTangent) > 1.0) return@repeat
            if (tangent) nearTangent++
            val tangentLatitude: Double = asin(sinTangent).toDegrees()
            repeat(30) {
                val pointLatitude: Double = tangentLatitude + rng.nextDouble(-1.0, 1.0) * 10.0.pow(rng.nextDouble(-14.0, -2.0))
                if (abs(pointLatitude) > 90.0) return@repeat
                probe(GeoCoordinates(pointLatitude, wrapLongitude(box.east + 10.0.pow(rng.nextDouble(-15.0, -9.0)))))
                probe(GeoCoordinates(pointLatitude, wrapLongitude(box.west - 10.0.pow(rng.nextDouble(-15.0, -9.0)))))
            }
        }
        assertTrue(probes > 100_000, "control: enough probes outside the boxes (probes=$probes) [seed=$seed]")
        assertTrue(nearTangent > 500, "control: enough near-tangent circles with a limited box (nearTangent=$nearTangent) [seed=$seed]")
    }

    @Test
    fun `the box of the mirrored centre is the mirrored box`() {
        val seed: Int = GEO_TEST_SEED + 92_101
        val rng = Random(seed)
        repeat(20_000) { index: Int ->
            val latitude: Double = rng.nextDouble(-90.0, 90.0)
            val longitude: Double = rng.nextDouble(-180.0, 180.0)
            val colatitude: Double = (90.0 - abs(latitude)).toRadians()
            val angularRadius: Double = when (index % 3) {
                0 -> colatitude - 10.0.pow(rng.nextDouble(-16.0, -1.0))
                1 -> rng.nextDouble(0.0, PI)
                else -> rng.nextDouble(0.0, 1e-3)
            }
            if (angularRadius < 0.0) return@repeat
            val radius: Double = angularRadius * EARTH_MEAN_RADIUS_METERS
            val north: GeoBounds = GeoCoordinates(latitude, longitude).boundingBox(radius)
            val south: GeoBounds = GeoCoordinates(-latitude, longitude).boundingBox(radius)
            assertEquals(
                GeoBounds(south = -north.north, west = north.west, north = -north.south, east = north.east),
                south,
                "the south pole must be handled like the north pole: centre=($latitude, $longitude) radius=$radius [seed=$seed]",
            )
        }
    }

    @Test
    fun `boxes at the radius boundaries are valid and hold the centre`() {
        val earthHalf: Double = PI * EARTH_MEAN_RADIUS_METERS
        val radii: List<Double> = listOf(
            0.0, -0.0, Double.MIN_VALUE, 1e-300, 1e-9, 1e-3, 1.0, 1_000.0,
            earthHalf / 2.0, earthHalf * (1.0 - 1e-15), earthHalf, earthHalf * 2.0, 1e300, Double.MAX_VALUE,
        )
        val centres: List<GeoCoordinates> = listOf(
            GeoCoordinates(0.0, 0.0), GeoCoordinates(-0.0, -0.0), GeoCoordinates(0.0, 180.0), GeoCoordinates(0.0, -180.0),
            GeoCoordinates(90.0, 0.0), GeoCoordinates(-90.0, 123.0), GeoCoordinates(89.999999999999, 179.99),
            GeoCoordinates(-89.999999999999, -179.99), GeoCoordinates(45.0, -180.0), GeoCoordinates(1e-300, 1e-300),
        )
        for (centre: GeoCoordinates in centres) {
            for (radius: Double in radii) {
                val box: GeoBounds = centre.boundingBox(radius)
                val note = "centre=$centre radius=$radius box=$box"
                assertTrue(centre in box, "the centre is inside its own box: $note")
                if (radius >= earthHalf) {
                    assertEquals(GeoBounds(-90.0, -180.0, 90.0, 180.0), box, "a circle of half the Earth or more covers it: $note")
                }
                if (radius == 0.0 && abs(centre.latitude) < 89.0) {
                    val span: Double = if (box.crossesAntimeridian) box.east + 360.0 - box.west else box.east - box.west
                    assertTrue(span < 1e-8 && box.north - box.south < 1e-8, "a zero radius gives a tiny box: $note")
                }
            }
        }
        for (radius: Double in listOf(-Double.MIN_VALUE, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("radius=$radius") { GeoCoordinates(0.0, 0.0).boundingBox(radius) }
        }
    }

    @Test
    fun `a circle that misses a pole by ten meters or more never gets the full longitude span`() {
        val seed: Int = GEO_TEST_SEED + 92_102
        val rng = Random(seed)
        var limited = 0
        repeat(20_000) { index: Int ->
            // Centres from the equator to 1 km from a pole, in both hemispheres; the circle stops 10 m to 100 km short.
            val latitude: Double = (if (index % 2 == 0) 1.0 else -1.0) * when (index % 4) {
                0, 1 -> rng.nextDouble(0.0, 89.0)
                else -> 90.0 - 10.0.pow(rng.nextDouble(-2.0, 0.0))
            }
            val gapMeters: Double = 10.0.pow(rng.nextDouble(1.0, 5.0))
            val radius: Double = (90.0 - abs(latitude)).toRadians() * EARTH_MEAN_RADIUS_METERS - gapMeters
            if (radius <= 0.0) return@repeat
            val box: GeoBounds = GeoCoordinates(latitude, 10.0).boundingBox(radius)
            assertTrue(
                !box.isFullLongitude,
                "the KDoc limits the full-span fallback to a few meters from a pole: latitude=$latitude gap=$gapMeters m box=$box [seed=$seed]",
            )
            limited++
        }
        assertTrue(limited > 10_000, "control: limited=$limited [seed=$seed]")
    }

    @Test
    fun `a box well away from a pole is no wider than the exact extreme longitude plus two nanodegrees`() {
        val seed: Int = GEO_TEST_SEED + 92_103
        val rng = Random(seed)
        repeat(50_000) {
            val latitude: Double = rng.nextDouble(-89.9, 89.9)
            val colatitude: Double = (90.0 - abs(latitude)).toRadians()
            val angularRadius: Double = rng.nextDouble(0.0, colatitude - 1e-3)
            if (angularRadius <= 0.0) return@repeat
            val box: GeoBounds = GeoCoordinates(latitude, 0.0).boundingBox(angularRadius * EARTH_MEAN_RADIUS_METERS)
            val exact: Double = asin(sin(angularRadius) / cos(latitude.toRadians())).toDegrees()
            assertTrue(
                box.east - exact < 2e-9 && box.east >= exact && -box.west >= exact,
                "tightness: latitude=$latitude rho=$angularRadius exact=$exact box=$box [seed=$seed]",
            )
            val exactNorth: Double = latitude + angularRadius.toDegrees()
            assertTrue(box.north - exactNorth < 2e-9 && box.north >= exactNorth, "latitude edge: $box exact=$exactNorth [seed=$seed]")
        }
    }

    @Test
    fun `destinationPoint takes huge finite distances and bearings together`() {
        val seed: Int = GEO_TEST_SEED + 92_104
        val rng = Random(seed)
        val huge: List<Double> = listOf(Double.MAX_VALUE, -Double.MAX_VALUE, 1e300, -1e300, 5.7e306, 1e15, -1e15)
        val origins: List<GeoCoordinates> = listOf(
            GeoCoordinates(0.0, 180.0), GeoCoordinates(90.0, 0.0), GeoCoordinates(-90.0, -180.0), GeoCoordinates(12.5, -77.25),
        )
        for (origin: GeoCoordinates in origins) {
            for (distance: Double in huge) {
                for (bearing: Double in huge + listOf(0.0, -0.0, 359.99999999999994, rng.nextDouble(-1.0, 1.0) * 1e308)) {
                    val point: GeoCoordinates = origin.destinationPoint(distance, bearing)
                    assertTrue(point.longitude >= -180.0 && point.longitude < 180.0, "longitude range: $origin $distance $bearing -> $point [seed=$seed]")
                    assertEquals(
                        origin.destinationPoint(distance, bearing % 360.0),
                        point,
                        "a bearing is taken modulo a turn: $origin $distance $bearing [seed=$seed]",
                    )
                }
            }
        }
    }
}
