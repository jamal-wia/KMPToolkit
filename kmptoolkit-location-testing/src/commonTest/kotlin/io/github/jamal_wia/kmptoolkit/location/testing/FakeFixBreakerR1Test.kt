package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.LocationFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Breaker round 1 of group loc-jvm-fakes (BREAKER-REVIEW.md, D-23): `fakeFix` with exotic values
 * (PLAN 3.5: every parameter maps one to one onto the field of the same name; coordinates are
 * validated by `GeoCoordinates`, nothing else is). Targets: a value silently normalised (the sign of
 * -0.0, NaN, infinities, subnormals, the Long extremes), a parameter wired to the wrong field, and an
 * invalid coordinate accepted.
 */
class FakeFixBreakerR1Test {

    @Test
    fun `exotic optional values pass through unchanged and each lands in its own field`() {
        val details = TestFixDetails("exotic")
        val fix: LocationFix = fakeFix(
            latitude = -0.0,
            longitude = -180.0,
            timestampEpochMillis = Long.MIN_VALUE,
            horizontalAccuracyMeters = Double.NaN,
            ellipsoidalAltitudeMeters = Double.NEGATIVE_INFINITY,
            mslAltitudeMeters = Double.POSITIVE_INFINITY,
            verticalAccuracyMeters = -Double.MIN_VALUE,
            speedMetersPerSecond = -0.0,
            speedAccuracyMetersPerSecond = Double.MAX_VALUE,
            courseDegrees = 720.0,
            courseAccuracyDegrees = -1.0,
            isSimulated = null,
            isApproximate = true,
            platformDetails = details,
        )
        assertEquals((-0.0).toRawBits(), fix.coordinates.latitude.toRawBits(), "latitude -0.0 keeps its sign")
        assertEquals(-180.0, fix.coordinates.longitude)
        assertEquals(Long.MIN_VALUE, fix.timestampEpochMillis)
        assertTrue(fix.horizontalAccuracyMeters!!.isNaN())
        assertEquals(Double.NEGATIVE_INFINITY, fix.ellipsoidalAltitudeMeters)
        assertEquals(Double.POSITIVE_INFINITY, fix.mslAltitudeMeters)
        assertEquals((-Double.MIN_VALUE).toRawBits(), fix.verticalAccuracyMeters!!.toRawBits())
        assertEquals((-0.0).toRawBits(), fix.speedMetersPerSecond!!.toRawBits())
        assertEquals(Double.MAX_VALUE, fix.speedAccuracyMetersPerSecond)
        assertEquals(720.0, fix.courseDegrees)
        assertEquals(-1.0, fix.courseAccuracyDegrees)
        assertEquals(null, fix.isSimulated)
        assertEquals(true, fix.isApproximate)
        assertSame(details, fix.platformDetails)

        val max: LocationFix = fakeFix(90.0, 180.0, timestampEpochMillis = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, max.timestampEpochMillis)
        assertEquals(fakeFix(90.0, 180.0, timestampEpochMillis = Long.MAX_VALUE), max, "equal inputs give equal fixes")
    }

    @Test
    fun `every invalid coordinate throws`() {
        val invalid: List<Pair<Double, Double>> = listOf(
            Double.NaN to 0.0, 0.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 0.0, 0.0 to Double.NEGATIVE_INFINITY,
            90.0.nextUpCompat() to 0.0, (-90.0).nextDownCompat() to 0.0,
            0.0 to 180.0.nextUpCompat(), 0.0 to (-180.0).nextDownCompat(),
            Double.MAX_VALUE to 0.0,
        )
        for ((latitude, longitude) in invalid) {
            assertFailsWith<IllegalArgumentException>("lat=$latitude lon=$longitude") { fakeFix(latitude, longitude) }
        }
        // The boundaries themselves and subnormals are valid.
        for ((latitude, longitude) in listOf(90.0 to 180.0, -90.0 to -180.0, Double.MIN_VALUE to -Double.MIN_VALUE)) {
            assertEquals(latitude, fakeFix(latitude, longitude).coordinates.latitude)
        }
    }

    private fun Double.nextUpCompat(): Double = Double.fromBits(toRawBits() + if (this >= 0.0) 1 else -1)

    private fun Double.nextDownCompat(): Double = Double.fromBits(toRawBits() + if (this > 0.0) -1 else 1)
}
