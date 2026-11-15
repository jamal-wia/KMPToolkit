package io.github.jamal_wia.kmptoolkit.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reference values of the geo math (PLAN section 6.1). Every expected number was computed once,
 * outside the library, in 70-digit decimal arithmetic on the exact doubles passed here, with the
 * sphere radius 6 371 008.8 m and the atan2 form; none of them is read back from the implementation.
 *
 * Tolerances (PLAN section 6): distance `max(1e-9 * expected, 1e-6 m)`, bearing 1e-5 degrees, and
 * the destination in 1e-9 degrees unless stated.
 */
class GeoMathReferenceTest {

    private val origin = GeoCoordinates(0.0, 0.0)

    // region sphere anchors

    @Test
    fun `one degree of longitude on the equator`() {
        assertDistanceClose(111_195.080233533, origin.distanceInMetersTo(GeoCoordinates(0.0, 1.0)), "(0,0)-(0,1)")
    }

    @Test
    fun `equator to the pole is a quarter of the circumference`() {
        assertDistanceClose(10_007_557.221017962, origin.distanceInMetersTo(GeoCoordinates(90.0, 0.0)), "(0,0)-(90,0)")
    }

    @Test
    fun `half way round the equator is half the circumference`() {
        val actual: Double = origin.distanceInMetersTo(GeoCoordinates(0.0, 180.0))
        assertDistanceClose(20_015_114.442035924, actual, "(0,0)-(0,180)")
        assertTrue(actual <= HALF_CIRCUMFERENCE_METERS, "never beyond half the circumference: $actual")
    }

    @Test
    fun `meridian arcs of one centimetre one metre and one kilometre equal R times the latitude difference`() {
        for (meters: Double in listOf(0.01, 1.0, 1_000.0)) {
            val latitude1 = 40.0
            val latitude2: Double = latitude1 + meters / SPHERE_RADIUS_METERS * 180.0 / PI
            // The subtraction of two nearby doubles is exact (Sterbenz), so the oracle carries no rounding of its own.
            val expected: Double = SPHERE_RADIUS_METERS * ((latitude2 - latitude1) * PI / 180.0)
            val actual: Double = GeoCoordinates(latitude1, 10.0).distanceInMetersTo(GeoCoordinates(latitude2, 10.0))
            assertDistanceClose(expected, actual, "meridian arc of about $meters m")
            assertDistanceClose(meters, actual, "meridian arc of $meters m against its nominal length")
        }
    }

    @Test
    fun `a degree across the antimeridian is the same distance at bearing east`() {
        val west = GeoCoordinates(0.0, 179.5)
        val east = GeoCoordinates(0.0, -179.5)

        assertDistanceClose(111_195.080233533, west.distanceInMetersTo(east), "(0,179.5)-(0,-179.5)")
        assertAngleClose(90.0, west.initialBearingTo(east) ?: Double.NaN, 1e-5, "bearing across the antimeridian")
        assertAngleClose(270.0, east.initialBearingTo(west) ?: Double.NaN, 1e-5, "bearing back across the antimeridian")
    }

    // endregion

    // region published references

    @Test
    fun `Aviation Formulary LAX to JFK`() {
        val lax = GeoCoordinates(33.95, -118.4) // 33 57' N, 118 24' W
        val jfk = GeoCoordinates(40.63333333333333, -73.78333333333333) // 40 38' N, 73 47' W

        // The formulary prints 0.623585 rad and 65.892 deg: rounded to six and three decimals.
        assertClose(0.623585, lax.distanceInMetersTo(jfk) / SPHERE_RADIUS_METERS, 5e-7, "LAX-JFK central angle")
        assertClose(65.892, lax.initialBearingTo(jfk) ?: Double.NaN, 5e-4, "LAX-JFK initial course")
        // The same pair against the unrounded oracle.
        assertDistanceClose(3_972_863.263795, lax.distanceInMetersTo(jfk), "LAX-JFK distance")
        assertClose(65.892167, lax.initialBearingTo(jfk) ?: Double.NaN, 1e-5, "LAX-JFK oracle bearing")
    }

    @Test
    fun `Geoscience Australia Flinders Peak to Buninyong is within 0 point 6 percent of the WGS-84 geodesic`() {
        val flinders = GeoCoordinates(-37.95103341666667, 144.42486788888888) // 37 57' 03.72030'' S, 144 25' 29.52440'' E
        val buninyong = GeoCoordinates(-37.65282113888889, 143.92649552777777) // 37 39' 10.15610'' S, 143 55' 35.38390'' E
        val geodesicMeters = 54_972.271

        val actual: Double = flinders.distanceInMetersTo(buninyong)

        assertTrue(abs(actual - geodesicMeters) / geodesicMeters <= 0.006, "within 0.6 %: $actual against $geodesicMeters")
        assertDistanceClose(54_925.507770, actual, "Flinders-Buninyong sphere distance (-0.085 %)")
    }

    // endregion

    // region Qibla rows

    private val kaaba = GeoCoordinates(21.422487, 39.826206)

    private fun assertKaabaRow(from: GeoCoordinates, expectedMeters: Double, expectedBearing: Double, name: String) {
        assertDistanceClose(expectedMeters, from.distanceInMetersTo(kaaba), "$name distance to the Kaaba")
        assertDistanceClose(expectedMeters, kaaba.distanceInMetersTo(from), "the Kaaba distance to $name")
        assertAngleClose(expectedBearing, from.initialBearingTo(kaaba) ?: Double.NaN, 1e-5, "$name bearing to the Kaaba")
    }

    @Test
    fun `Cairo to the Kaaba`() = assertKaabaRow(GeoCoordinates(30.0444, 31.2357), 1_287_219.499586, 136.13729708, "Cairo")

    @Test
    fun `Moscow to the Kaaba`() = assertKaabaRow(GeoCoordinates(55.7558, 37.6173), 3_822_090.228513, 176.35624565, "Moscow")

    @Test
    fun `New York to the Kaaba`() = assertKaabaRow(GeoCoordinates(40.7128, -74.0060), 10_306_322.095959, 58.48170605, "New York")

    @Test
    fun `Jakarta to the Kaaba`() = assertKaabaRow(GeoCoordinates(-6.2088, 106.8456), 7_920_136.804065, 295.1517245, "Jakarta")

    // endregion

    // region bearings

    @Test
    fun `quarter turns and the poles`() {
        assertAngleClose(90.0, origin.initialBearingTo(GeoCoordinates(0.0, 90.0)) ?: Double.NaN, 1e-9, "east")
        assertAngleClose(270.0, origin.initialBearingTo(GeoCoordinates(0.0, -90.0)) ?: Double.NaN, 1e-9, "west")
        assertAngleClose(0.0, origin.initialBearingTo(GeoCoordinates(10.0, 0.0)) ?: Double.NaN, 1e-9, "north")
        assertAngleClose(180.0, origin.initialBearingTo(GeoCoordinates(-10.0, 0.0)) ?: Double.NaN, 1e-9, "south")
        val here = GeoCoordinates(10.0, 20.0)
        assertAngleClose(0.0, here.initialBearingTo(GeoCoordinates(90.0, 0.0)) ?: Double.NaN, 1e-9, "to the north pole")
        assertAngleClose(180.0, here.initialBearingTo(GeoCoordinates(-90.0, 0.0)) ?: Double.NaN, 1e-9, "to the south pole")
    }

    @Test
    fun `bearing is null for coincident points including the antimeridian pair`() {
        assertNull(origin.initialBearingTo(origin))
        assertNull(GeoCoordinates(0.0, 180.0).initialBearingTo(GeoCoordinates(0.0, -180.0)))
        assertNull(GeoCoordinates(0.0, -180.0).initialBearingTo(GeoCoordinates(0.0, 180.0)))
        assertNull(GeoCoordinates(21.422487, 39.826206).initialBearingTo(GeoCoordinates(21.422487, 39.826206)))
        assertNull(GeoCoordinates(90.0, 0.0).initialBearingTo(GeoCoordinates(90.0, 120.0)))
    }

    @Test
    fun `bearing is null for exact antipodes`() {
        assertNull(GeoCoordinates(30.0, 10.0).initialBearingTo(GeoCoordinates(-30.0, -170.0)))
        assertNull(GeoCoordinates(-30.0, -170.0).initialBearingTo(GeoCoordinates(30.0, 10.0)))
        assertNull(origin.initialBearingTo(GeoCoordinates(0.0, 180.0)))
        assertNull(origin.initialBearingTo(GeoCoordinates(0.0, -180.0)))
        assertNull(GeoCoordinates(12.3456, 45.6789).initialBearingTo(GeoCoordinates(-12.3456, -134.3211)))
    }

    @Test
    fun `bearing is null for an origin at a pole whatever the target`() {
        assertNull(GeoCoordinates(90.0, 0.0).initialBearingTo(GeoCoordinates(10.0, 10.0)))
        assertNull(GeoCoordinates(90.0, -77.0).initialBearingTo(GeoCoordinates(-10.0, 170.0)))
        assertNull(GeoCoordinates(-90.0, 30.0).initialBearingTo(GeoCoordinates(45.0, 30.0)))
    }

    @Test
    fun `one hundred-billionth of a radian from the antipode stays within a thousandth of a degree of the truth`() {
        // 1e-11 rad = 5.729577951308232e-10 degrees; the oracle used the exact doubles below.
        val from = GeoCoordinates(30.0, 10.0)
        val northOfAntipode = GeoCoordinates(-29.999999999427043, -170.0)
        val eastOfAntipode = GeoCoordinates(-30.0, -169.99999999942705)

        // Heading north over the pole reaches the point that lies just north of the antipode.
        val toNorth: Double? = from.initialBearingTo(northOfAntipode)
        val toEast: Double? = from.initialBearingTo(eastOfAntipode)

        assertNotNull(toNorth, "a point 1e-11 rad from the antipode has a defined direction")
        assertNotNull(toEast, "a point 1e-11 rad from the antipode has a defined direction")
        assertAngleClose(0.0, toNorth, 0.001, "bearing to the point north of the antipode")
        assertAngleClose(270.0, toEast, 0.001, "bearing to the point beside the antipode")
        assertDistanceClose(20_015_114.441972, from.distanceInMetersTo(northOfAntipode), "distance to the point north of the antipode")
    }

    @Test
    fun `bearing is never 360 even when the angle is a tiny negative number`() {
        // sin of a longitude difference of -1e-20 degrees is a tiny negative: atan2 gives -tiny, and 360 + -tiny rounds to 360.
        val bearing: Double? = origin.initialBearingTo(GeoCoordinates(10.0, -1e-20))

        assertNotNull(bearing)
        assertTrue(bearing >= 0.0 && bearing < 360.0, "bearing must be in [0, 360): $bearing")
    }

    // endregion

    // region same ground point and equal values

    @Test
    fun `equal values are exactly zero apart`() {
        for (point: GeoCoordinates in listOf(origin, kaaba, GeoCoordinates(90.0, 0.0), GeoCoordinates(-90.0, 33.0), GeoCoordinates(0.0, 180.0))) {
            assertEquals(0.0, point.distanceInMetersTo(point), "distance of $point to itself")
        }
    }

    @Test
    fun `the same ground point written differently is below one hundred-millionth of a metre apart`() {
        val pairs: List<Pair<GeoCoordinates, GeoCoordinates>> = listOf(
            GeoCoordinates(90.0, 0.0) to GeoCoordinates(90.0, 120.0),
            GeoCoordinates(-90.0, -45.0) to GeoCoordinates(-90.0, 170.0),
            GeoCoordinates(0.0, 180.0) to GeoCoordinates(0.0, -180.0),
            GeoCoordinates(37.5, -180.0) to GeoCoordinates(37.5, 180.0),
        )
        for ((first: GeoCoordinates, second: GeoCoordinates) in pairs) {
            assertTrue(first.distanceInMetersTo(second) < 1e-8, "$first and $second: ${first.distanceInMetersTo(second)} m")
            assertTrue(second.distanceInMetersTo(first) < 1e-8, "$second and $first: ${second.distanceInMetersTo(first)} m")
        }
    }

    // endregion

    // region destinationPoint

    private fun assertDestination(
        from: GeoCoordinates,
        distanceMeters: Double,
        bearingDegrees: Double,
        expectedLatitude: Double,
        expectedLongitude: Double,
    ) {
        val actual: GeoCoordinates = from.destinationPoint(distanceMeters, bearingDegrees)
        val name = "$from + $distanceMeters m at $bearingDegrees deg"
        assertClose(expectedLatitude, actual.latitude, 1e-9, "$name latitude")
        assertClose(expectedLongitude, actual.longitude, 1e-9, "$name longitude")
        assertTrue(actual.longitude >= -180.0 && actual.longitude < 180.0, "$name longitude in [-180, 180): ${actual.longitude}")
    }

    @Test
    fun `destination one degree east on the equator`() =
        assertDestination(origin, 111_195.0802335329, 90.0, 0.0, 1.0)

    @Test
    fun `destination across the antimeridian is normalised into minus 180 to 180`() {
        assertDestination(GeoCoordinates(40.0, 179.9), 50_000.0, 90.0, 39.998519450853, -179.513018805706)
        assertDestination(GeoCoordinates(40.0, -179.9), 50_000.0, 270.0, 39.998519450853, 179.513018805706)
        assertDestination(GeoCoordinates(-80.0, -170.0), 2_500_000.0, 200.0, -76.496450180782, 44.064266104972)
    }

    @Test
    fun `destination over a pole comes out the other side`() =
        assertDestination(GeoCoordinates(80.0, 25.0), 2_000_000.0, 0.0, 82.013592725509, -155.0)

    @Test
    fun `destination ending exactly at a pole is the pole and never throws`() {
        val atPole: GeoCoordinates = GeoCoordinates(80.0, 25.0).destinationPoint(1_111_950.802335329, 0.0)

        assertClose(90.0, atPole.latitude, 1e-9, "latitude of the pole")
        assertTrue(atPole.latitude <= 90.0)
    }

    @Test
    fun `destination of ordinary trips`() {
        assertDestination(GeoCoordinates(10.0, 20.0), 5_000_000.0, 45.0, 37.949911582991, 59.323639360818)
        assertDestination(GeoCoordinates(51.5, -0.12), 1_000_000.0, 120.0, 46.413353245467, 11.203691081694)
    }

    @Test
    fun `a negative distance goes backwards`() {
        val from = GeoCoordinates(10.0, 20.0)

        val back: GeoCoordinates = from.destinationPoint(-5_000_000.0, 45.0)
        val opposite: GeoCoordinates = from.destinationPoint(5_000_000.0, 225.0)

        assertClose(opposite.latitude, back.latitude, 1e-9, "latitude")
        assertClose(opposite.longitude, back.longitude, 1e-9, "longitude")
    }

    @Test
    fun `a zero distance returns the start`() {
        val from = GeoCoordinates(10.0, 20.0)
        val same: GeoCoordinates = from.destinationPoint(0.0, 123.0)

        assertClose(10.0, same.latitude, 1e-12, "latitude")
        assertClose(20.0, same.longitude, 1e-12, "longitude")
    }

    @Test
    fun `starting at a pole never produces NaN`() {
        for (latitude: Double in listOf(90.0, -90.0)) {
            for (bearing: Double in listOf(0.0, 90.0, 180.0, 270.0, 33.0, -45.0)) {
                for (distance: Double in listOf(0.0, 1.0, 1_000_000.0, 10_007_557.221017962, 20_015_114.442035924)) {
                    val result: GeoCoordinates = GeoCoordinates(latitude, 45.0).destinationPoint(distance, bearing)
                    assertTrue(!result.latitude.isNaN() && !result.longitude.isNaN(), "NaN from pole $latitude, $distance m, $bearing deg")
                }
            }
        }
    }

    @Test
    fun `destination rejects a non-finite argument`() {
        for (bad: Double in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("distance $bad") { origin.destinationPoint(bad, 0.0) }
            assertFailsWith<IllegalArgumentException>("bearing $bad") { origin.destinationPoint(1.0, bad) }
        }
    }

    // endregion

    // region coordinates at the limits

    @Test
    fun `plus and minus 180 are both accepted and about zero apart`() {
        val east = GeoCoordinates(25.0, 180.0)
        val west = GeoCoordinates(25.0, -180.0)

        assertTrue(east.distanceInMetersTo(west) < 1e-8, "distance between +180 and -180: ${east.distanceInMetersTo(west)}")
    }

    // endregion
}
