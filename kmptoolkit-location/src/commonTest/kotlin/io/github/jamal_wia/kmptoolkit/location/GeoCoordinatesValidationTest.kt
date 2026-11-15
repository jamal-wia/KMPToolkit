package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract of [GeoCoordinates] validation (PLAN D6): the constructor and `copy` throw for a
 * non-finite or out-of-range value, `orNull` answers `null` for exactly the same inputs, and the
 * inclusive limits (`+-90`, `+-180`) are accepted. Distance between `-180` and `180` is covered in
 * `GeoMathReferenceTest`.
 */
class GeoCoordinatesValidationTest {

    /** Every pair the contract rejects, as (latitude, longitude). */
    private val rejected: List<Pair<Double, Double>> = listOf(
        Double.NaN to 0.0,
        0.0 to Double.NaN,
        Double.NaN to Double.NaN,
        Double.POSITIVE_INFINITY to 0.0,
        Double.NEGATIVE_INFINITY to 0.0,
        0.0 to Double.POSITIVE_INFINITY,
        0.0 to Double.NEGATIVE_INFINITY,
        90.000001 to 0.0,
        -90.000001 to 0.0,
        91.0 to 0.0,
        0.0 to 180.000001,
        0.0 to -180.000001,
        0.0 to 190.0,
        Double.MAX_VALUE to 0.0,
        0.0 to -Double.MAX_VALUE,
    )

    /** Every pair the contract accepts, as (latitude, longitude). */
    private val accepted: List<Pair<Double, Double>> = listOf(
        0.0 to 0.0,
        -0.0 to -0.0,
        90.0 to 0.0,
        -90.0 to 0.0,
        0.0 to 180.0,
        0.0 to -180.0,
        90.0 to 180.0,
        -90.0 to -180.0,
        89.999999 to 179.999999,
        21.422487 to 39.826206,
    )

    @Test
    fun `the constructor rejects every invalid pair`() {
        for ((latitude: Double, longitude: Double) in rejected) {
            assertFailsWith<IllegalArgumentException>("($latitude, $longitude) must throw") {
                GeoCoordinates(latitude, longitude)
            }
        }
    }

    @Test
    fun `orNull returns null for exactly the pairs the constructor rejects`() {
        for ((latitude: Double, longitude: Double) in rejected) {
            assertNull(GeoCoordinates.orNull(latitude, longitude), "($latitude, $longitude) must be null")
        }
    }

    @Test
    fun `the constructor accepts every limit and orNull returns the same value`() {
        for ((latitude: Double, longitude: Double) in accepted) {
            val direct: GeoCoordinates = GeoCoordinates(latitude, longitude)
            val viaOrNull: GeoCoordinates? = GeoCoordinates.orNull(latitude, longitude)
            assertNotNull(viaOrNull, "($latitude, $longitude) must be accepted")
            assertEquals(direct, viaOrNull)
            assertEquals(latitude, direct.latitude)
            assertEquals(longitude, direct.longitude)
        }
    }

    @Test
    fun `copy validates through the constructor`() {
        val valid = GeoCoordinates(10.0, 20.0)

        assertFailsWith<IllegalArgumentException> { valid.copy(latitude = 91.0) }
        assertFailsWith<IllegalArgumentException> { valid.copy(longitude = -180.000001) }
        assertFailsWith<IllegalArgumentException> { valid.copy(latitude = Double.NaN) }
        assertEquals(GeoCoordinates(-90.0, 20.0), valid.copy(latitude = -90.0))
    }

    @Test
    fun `the failure message names the offending values`() {
        val failure: IllegalArgumentException = assertFailsWith<IllegalArgumentException> {
            GeoCoordinates(91.5, 10.0)
        }

        val message: String = failure.message.orEmpty()
        assertTrue("91.5" in message, "message was: $message")
    }

    @Test
    fun `plus and minus 180 are two valid values that are not equal as data`() {
        val east = GeoCoordinates(0.0, 180.0)
        val west = GeoCoordinates(0.0, -180.0)

        assertEquals(180.0, east.longitude)
        assertEquals(-180.0, west.longitude)
        assertNotEquals(east, west, "data equality does not normalise the antimeridian")
    }
}
