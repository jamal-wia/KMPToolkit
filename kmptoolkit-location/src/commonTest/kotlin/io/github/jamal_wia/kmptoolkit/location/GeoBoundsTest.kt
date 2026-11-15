package io.github.jamal_wia.kmptoolkit.location

import kotlin.math.acos
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [GeoBounds] validation and [boundingBox] (PLAN D4, M3, section 6.1). The expected boxes were
 * computed outside the library in 70-digit decimal arithmetic (`sin(delta-lambda) = sin(rho) /
 * cos(phi)`); the properties use `Random(GEO_TEST_SEED + n)` and print the seed on failure.
 */
class GeoBoundsTest {

    // region validation

    /** Every (south, west, north, east) the contract rejects. */
    private val rejected: List<List<Double>> = listOf(
        listOf(Double.NaN, 0.0, 10.0, 10.0),
        listOf(0.0, Double.NaN, 10.0, 10.0),
        listOf(0.0, 0.0, Double.NaN, 10.0),
        listOf(0.0, 0.0, 10.0, Double.NaN),
        listOf(Double.NEGATIVE_INFINITY, 0.0, 10.0, 10.0),
        listOf(0.0, 0.0, Double.POSITIVE_INFINITY, 10.0),
        listOf(0.0, Double.NEGATIVE_INFINITY, 10.0, 10.0),
        listOf(0.0, 0.0, 10.0, Double.POSITIVE_INFINITY),
        listOf(10.0, 0.0, 0.0, 10.0), // south > north
        listOf(-90.000001, 0.0, 10.0, 10.0),
        listOf(0.0, 0.0, 90.000001, 10.0),
        listOf(91.0, 0.0, 92.0, 10.0),
        listOf(0.0, -180.000001, 10.0, 10.0),
        listOf(0.0, 0.0, 10.0, 180.000001),
        listOf(0.0, 181.0, 10.0, 10.0),
    )

    @Test
    fun `the constructor rejects every invalid box`() {
        for ((south: Double, west: Double, north: Double, east: Double) in rejected) {
            assertFailsWith<IllegalArgumentException>("($south, $west, $north, $east) must throw") {
                GeoBounds(south, west, north, east)
            }
        }
    }

    @Test
    fun `orNull returns null for exactly the boxes the constructor rejects`() {
        for ((south: Double, west: Double, north: Double, east: Double) in rejected) {
            assertNull(GeoBounds.orNull(south, west, north, east), "($south, $west, $north, $east) must be null")
        }
    }

    @Test
    fun `the limits are accepted and orNull returns the same value`() {
        val accepted: List<List<Double>> = listOf(
            listOf(-90.0, -180.0, 90.0, 180.0),
            listOf(0.0, 0.0, 0.0, 0.0), // a point
            listOf(10.0, 170.0, 20.0, -170.0), // crossing
            listOf(-90.0, 0.0, -90.0, 0.0),
            listOf(5.0, 180.0, 6.0, -180.0), // both edges on the antimeridian
        )
        for ((south: Double, west: Double, north: Double, east: Double) in accepted) {
            val direct: GeoBounds = GeoBounds(south, west, north, east)
            assertEquals(direct, GeoBounds.orNull(south, west, north, east))
        }
    }

    @Test
    fun `copy validates through the constructor`() {
        val box = GeoBounds(0.0, 10.0, 10.0, 20.0)

        assertFailsWith<IllegalArgumentException> { box.copy(south = 11.0) }
        assertFailsWith<IllegalArgumentException> { box.copy(east = 181.0) }
        assertEquals(GeoBounds(0.0, -180.0, 10.0, 20.0), box.copy(west = -180.0))
    }

    // endregion

    // region the box value

    @Test
    fun `east exactly at 180 is not a crossing`() {
        val box = GeoBounds(south = 0.0, west = 170.0, north = 10.0, east = 180.0)

        assertFalse(box.crossesAntimeridian)
        assertEquals(listOf(170.0..180.0), box.longitudeRanges)
    }

    @Test
    fun `west greater than east is a crossing and gives two ranges ready for BETWEEN`() {
        val box = GeoBounds(south = 0.0, west = 170.0, north = 10.0, east = -170.0)

        assertTrue(box.crossesAntimeridian)
        assertEquals(listOf(170.0..180.0, -180.0..-170.0), box.longitudeRanges)
    }

    @Test
    fun `a polar cap is one full range`() {
        val box = GeoBounds(south = 80.0, west = -180.0, north = 90.0, east = 180.0)

        assertFalse(box.crossesAntimeridian)
        assertEquals(listOf(-180.0..180.0), box.longitudeRanges)
    }

    @Test
    fun `contains is inclusive on every edge`() {
        val box = GeoBounds(south = 10.0, west = 20.0, north = 30.0, east = 40.0)

        assertTrue(GeoCoordinates(10.0, 20.0) in box)
        assertTrue(GeoCoordinates(30.0, 40.0) in box)
        assertTrue(GeoCoordinates(20.0, 30.0) in box)
        assertFalse(GeoCoordinates(9.999999, 30.0) in box)
        assertFalse(GeoCoordinates(30.000001, 30.0) in box)
        assertFalse(GeoCoordinates(20.0, 19.999999) in box)
        assertFalse(GeoCoordinates(20.0, 40.000001) in box)
    }

    @Test
    fun `contains is antimeridian-aware`() {
        val box = GeoBounds(south = 10.0, west = 170.0, north = 30.0, east = -170.0)

        assertTrue(GeoCoordinates(20.0, 175.0) in box)
        assertTrue(GeoCoordinates(20.0, 180.0) in box)
        assertTrue(GeoCoordinates(20.0, -180.0) in box)
        assertTrue(GeoCoordinates(20.0, -175.0) in box)
        assertTrue(GeoCoordinates(20.0, 170.0) in box)
        assertTrue(GeoCoordinates(20.0, -170.0) in box)
        assertFalse(GeoCoordinates(20.0, 0.0) in box)
        assertFalse(GeoCoordinates(20.0, 169.999999) in box)
        assertFalse(GeoCoordinates(20.0, -169.999999) in box)
        assertFalse(GeoCoordinates(31.0, 175.0) in box)
    }

    @Test
    fun `longitude 180 and minus 180 are the same meridian for a box with an edge on it`() {
        val eastEdge = GeoBounds(south = 0.0, west = 170.0, north = 10.0, east = 180.0)
        val westEdge = GeoBounds(south = 0.0, west = -180.0, north = 10.0, east = -170.0)

        assertTrue(GeoCoordinates(5.0, -180.0) in eastEdge)
        assertTrue(GeoCoordinates(5.0, 180.0) in westEdge)
        assertFalse(GeoCoordinates(5.0, -179.0) in eastEdge)
        assertFalse(GeoCoordinates(5.0, 179.0) in westEdge)
    }

    // endregion

    // region boundingBox against the oracle

    private fun assertBox(
        box: GeoBounds,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        name: String,
    ) {
        assertClose(south, box.south, 1e-9, "$name south")
        assertClose(west, box.west, 1e-9, "$name west")
        assertClose(north, box.north, 1e-9, "$name north")
        assertClose(east, box.east, 1e-9, "$name east")
    }

    @Test
    fun `a city box`() {
        val box: GeoBounds = GeoCoordinates(48.8566, 2.3522).boundingBox(50_000.0)

        assertBox(box, 48.406939818138, 1.668760440741, 49.306260181862, 3.035639559259, "Paris 50 km")
        assertFalse(box.crossesAntimeridian)
        assertEquals(1, box.longitudeRanges.size)
    }

    @Test
    fun `a small box around the Kaaba`() {
        val box: GeoBounds = GeoCoordinates(21.422487, 39.826206).boundingBox(1_000.0)

        assertBox(box, 21.413493796363, 39.816545367819, 21.431480203637, 39.835866632181, "Kaaba 1 km")
    }

    @Test
    fun `a box across the antimeridian wraps and gives two ranges`() {
        val box: GeoBounds = GeoCoordinates(65.0, -179.0).boundingBox(300_000.0)

        assertBox(box, 62.302038908826, 174.605170680078, 67.697961091174, -172.605170680078, "65N 179W 300 km")
        assertTrue(box.crossesAntimeridian)
        assertEquals(2, box.longitudeRanges.size)
        for (range: ClosedFloatingPointRange<Double> in box.longitudeRanges) {
            assertTrue(range.start <= range.endInclusive, "each range ascends: $range")
        }
    }

    @Test
    fun `a circle holding a pole gives the full longitude span`() {
        val box: GeoBounds = GeoCoordinates(85.0, 10.0).boundingBox(800_000.0)

        assertBox(box, 77.805437090204, -180.0, 90.0, 180.0, "85N 800 km")
        assertFalse(box.crossesAntimeridian)
        assertEquals(listOf(-180.0..180.0), box.longitudeRanges)
    }

    @Test
    fun `a circle that reaches the south pole or covers the planet is a full cap`() {
        val south: GeoBounds = GeoCoordinates(-85.0, 10.0).boundingBox(800_000.0)
        assertEquals(-90.0, south.south)
        assertEquals(listOf(-180.0..180.0), south.longitudeRanges)

        val planet: GeoBounds = GeoCoordinates(10.0, 20.0).boundingBox(25_000_000.0)
        assertEquals(GeoBounds(-90.0, -180.0, 90.0, 180.0), planet)

        val atPole: GeoBounds = GeoCoordinates(90.0, 0.0).boundingBox(0.0)
        assertEquals(listOf(-180.0..180.0), atPole.longitudeRanges)
    }

    @Test
    fun `a zero radius is the point itself`() {
        assertBox(GeoCoordinates(0.0, 0.0).boundingBox(0.0), 0.0, 0.0, 0.0, 0.0, "zero radius at the origin")
        assertBox(GeoCoordinates(12.5, -33.25).boundingBox(0.0), 12.5, -33.25, 12.5, -33.25, "zero radius")
    }

    @Test
    fun `a negative or non-finite radius throws`() {
        val here = GeoCoordinates(10.0, 20.0)
        for (bad: Double in listOf(-1.0, -0.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("radius $bad") { here.boundingBox(bad) }
        }
    }

    // endregion

    // region properties

    @Test
    fun `every sampled point within the radius is inside the box`() {
        val seed: Int = GEO_TEST_SEED + 10
        val rng = Random(seed)
        repeat(50_000) { index: Int ->
            val centre: GeoCoordinates = randomCoordinates(rng)
            val fraction: Double = rng.nextDouble(0.0, 1.0)
            // Radii from 1 m to 10 000 km, cubic so small ones are common, and an exact zero now and then.
            val radius: Double = if (fraction < 0.05) 0.0 else 1.0 + 10.0 * 1e6 * fraction * fraction * fraction
            val box: GeoBounds = centre.boundingBox(radius)
            val within: Double = radius * rng.nextDouble(0.0, 0.999999)
            // At distance zero the point is the centre itself: destinationPoint is exact only to rounding, a box edge is not.
            val point: GeoCoordinates = if (radius == 0.0) centre else centre.destinationPoint(within, rng.nextDouble(0.0, 360.0))
            if (point !in box) {
                fail("sample $index: $point ($within m of $centre, radius $radius) is outside $box${seedNote(seed)}")
            }
        }
    }

    @Test
    fun `the box edges are tight`() {
        val seed: Int = GEO_TEST_SEED + 11
        val rng = Random(seed)
        repeat(5_000) { index: Int ->
            // Small enough that no pole is inside: radius up to 3000 km (27 degrees) at latitude up to 60.
            val centre = GeoCoordinates(rng.nextDouble(-60.0, 60.0), rng.nextDouble(-180.0, 180.0))
            val radius: Double = rng.nextDouble(1.0, 3_000_000.0)
            val box: GeoBounds = centre.boundingBox(radius)
            val note = "sample $index: $centre radius $radius box $box${seedNote(seed)}"

            // North and south: straight ahead and straight back.
            assertClose(centre.destinationPoint(radius, 0.0).latitude, box.north, 1e-9, "north edge, $note")
            assertClose(centre.destinationPoint(radius, 180.0).latitude, box.south, 1e-9, "south edge, $note")

            // East and west: the circle is tangent to a meridian where cos(bearing) = tan(rho) * tan(phi) (Napier's rule).
            val rho: Double = radius / SPHERE_RADIUS_METERS
            val tangentBearing: Double = acos(tan(rho) * tan(centre.latitude * kotlin.math.PI / 180.0)) * 180.0 / kotlin.math.PI
            val eastLongitude: Double = centre.destinationPoint(radius, tangentBearing).longitude
            val westLongitude: Double = centre.destinationPoint(radius, 360.0 - tangentBearing).longitude
            assertAngleClose(eastLongitude, box.east, 1e-9, "east edge, $note")
            assertAngleClose(westLongitude, box.west, 1e-9, "west edge, $note")
        }
    }

    @Test
    fun `longitude ranges are one or two ascending ranges and the crossing flag agrees`() {
        val seed: Int = GEO_TEST_SEED + 12
        val rng = Random(seed)
        repeat(50_000) { index: Int ->
            val centre: GeoCoordinates = randomCoordinates(rng)
            val radius: Double = rng.nextDouble(0.0, 3_000_000.0)
            val box: GeoBounds = centre.boundingBox(radius)
            val ranges: List<ClosedFloatingPointRange<Double>> = box.longitudeRanges
            val ok: Boolean = ranges.size in 1..2 &&
                ranges.all { range: ClosedFloatingPointRange<Double> -> range.start <= range.endInclusive } &&
                box.crossesAntimeridian == (ranges.size == 2)
            if (!ok) fail("sample $index: $centre radius $radius gave $box with $ranges${seedNote(seed)}")
        }
    }

    // endregion

    // region SQL prefilter

    /**
     * The clause of docs recipe 3, built the way the recipe builds it:
     * `WHERE lat BETWEEN ? AND ? AND (lng BETWEEN ? AND ? OR lng BETWEEN ? AND ?)`, with the
     * longitude part in parentheses (AND binds tighter than OR). The test builds the same string,
     * then evaluates the predicate in Kotlin, mirroring the clause term by term.
     */
    private fun sqlPrefilter(box: GeoBounds): Pair<String, (GeoCoordinates) -> Boolean> {
        val lng: String = box.longitudeRanges.joinToString(separator = " OR ", prefix = "(", postfix = ")") { "lng BETWEEN ? AND ?" }
        val clause = "WHERE lat BETWEEN ? AND ? AND $lng"
        val parameters: List<Double> = listOf(box.south, box.north) + box.longitudeRanges.flatMap { range: ClosedFloatingPointRange<Double> -> listOf(range.start, range.endInclusive) }
        val predicate: (GeoCoordinates) -> Boolean = { row: GeoCoordinates ->
            val latitudeMatches: Boolean = row.latitude >= parameters[0] && row.latitude <= parameters[1]
            val firstRange: Boolean = row.longitude >= parameters[2] && row.longitude <= parameters[3]
            val secondRange: Boolean = parameters.size == 6 && row.longitude >= parameters[4] && row.longitude <= parameters[5]
            latitudeMatches && (firstRange || secondRange)
        }
        return clause to predicate
    }

    @Test
    fun `the SQL prefilter finds a row just across the antimeridian and respects the latitude band`() {
        val box: GeoBounds = GeoCoordinates(65.0, -179.0).boundingBox(300_000.0)
        val (clause: String, matches: (GeoCoordinates) -> Boolean) = sqlPrefilter(box)

        assertEquals("WHERE lat BETWEEN ? AND ? AND (lng BETWEEN ? AND ? OR lng BETWEEN ? AND ?)", clause)
        // Just across the antimeridian: inside the second longitude range.
        assertTrue(matches(GeoCoordinates(65.0, -179.9)), "a row at 65N 179.9W")
        assertTrue(matches(GeoCoordinates(65.0, 179.9)), "a row at 65N 179.9E")
        // Inside the second longitude range but outside the latitude band: the parentheses matter.
        assertFalse(matches(GeoCoordinates(10.0, -179.9)), "a row at 10N 179.9W")
        assertFalse(matches(GeoCoordinates(10.0, 175.0)), "a row at 10N 175E")
        // Outside both longitude ranges.
        assertFalse(matches(GeoCoordinates(65.0, 0.0)), "a row at 65N 0E")
    }

    @Test
    fun `the plain west to east range would miss the row the two ranges find`() {
        val box: GeoBounds = GeoCoordinates(65.0, -179.0).boundingBox(300_000.0)
        val row = GeoCoordinates(65.0, -179.9)

        assertNotNull(box)
        // The consumer's old box: BETWEEN west AND east, which is empty when west > east.
        assertFalse(row.longitude in box.west..box.east, "the west..east range is empty across the antimeridian")
        assertTrue(row in box)
    }

    @Test
    fun `a box inside one hemisphere builds a single-range clause`() {
        val box: GeoBounds = GeoCoordinates(48.8566, 2.3522).boundingBox(50_000.0)
        val (clause: String, matches: (GeoCoordinates) -> Boolean) = sqlPrefilter(box)

        assertEquals("WHERE lat BETWEEN ? AND ? AND (lng BETWEEN ? AND ?)", clause)
        assertTrue(matches(GeoCoordinates(48.9, 2.4)))
        assertFalse(matches(GeoCoordinates(48.9, 5.0)))
    }

    // endregion
}
