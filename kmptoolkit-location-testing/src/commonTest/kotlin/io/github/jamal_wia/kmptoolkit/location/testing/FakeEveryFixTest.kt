package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.GeoCoordinates
import io.github.jamal_wia.kmptoolkit.location.LocationFix
import io.github.jamal_wia.kmptoolkit.location.LocationReading
import io.github.jamal_wia.kmptoolkit.location.fixOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * `fakeFix` is documented as a [LocationFix] with test defaults: every parameter lands in the field of
 * the same name (round trip), the defaults are the documented ones, and a fix survives the fake's
 * stream and one-shot answer untouched (nothing is rounded, defaulted or dropped on the way).
 * Group loc-jvm-fakes.
 */
class FakeEveryFixTest {

    @Test
    fun `every field round-trips to the field of the same name`() {
        val details = TestFixDetails("details")

        val fix: LocationFix = fakeFix(
            latitude = 21.4225,
            longitude = 39.8262,
            timestampEpochMillis = 1_700_000_000_123L,
            horizontalAccuracyMeters = 3.5,
            ellipsoidalAltitudeMeters = 301.25,
            mslAltitudeMeters = 270.5,
            verticalAccuracyMeters = 7.75,
            speedMetersPerSecond = 1.5,
            speedAccuracyMetersPerSecond = 0.25,
            courseDegrees = 359.5,
            courseAccuracyDegrees = 4.5,
            isSimulated = true,
            isApproximate = true,
            platformDetails = details,
        )

        assertEquals(GeoCoordinates(21.4225, 39.8262), fix.coordinates)
        assertEquals(1_700_000_000_123L, fix.timestampEpochMillis)
        assertEquals(3.5, fix.horizontalAccuracyMeters)
        assertEquals(301.25, fix.ellipsoidalAltitudeMeters)
        assertEquals(270.5, fix.mslAltitudeMeters)
        assertEquals(7.75, fix.verticalAccuracyMeters)
        assertEquals(1.5, fix.speedMetersPerSecond)
        assertEquals(0.25, fix.speedAccuracyMetersPerSecond)
        assertEquals(359.5, fix.courseDegrees)
        assertEquals(4.5, fix.courseAccuracyDegrees)
        assertEquals(true, fix.isSimulated)
        assertEquals(true, fix.isApproximate)
        assertSame(details, fix.platformDetails, "the details object itself, not a copy")
    }

    @Test
    fun `the defaults are the documented test defaults`() {
        val fix: LocationFix = fakeFix(latitude = 1.0, longitude = 2.0)

        assertEquals(LocationFix(coordinates = GeoCoordinates(1.0, 2.0), timestampEpochMillis = 0L, horizontalAccuracyMeters = 5.0, isSimulated = false, isApproximate = false), fix)
        assertNull(fix.ellipsoidalAltitudeMeters)
        assertNull(fix.mslAltitudeMeters)
        assertNull(fix.verticalAccuracyMeters)
        assertNull(fix.speedMetersPerSecond)
        assertNull(fix.speedAccuracyMetersPerSecond)
        assertNull(fix.courseDegrees)
        assertNull(fix.courseAccuracyDegrees)
        assertNull(fix.platformDetails)
    }

    @Test
    fun `an explicit null replaces a non-null default`() {
        val fix: LocationFix = fakeFix(1.0, 2.0, horizontalAccuracyMeters = null, isSimulated = null, isApproximate = null)

        assertNull(fix.horizontalAccuracyMeters)
        assertNull(fix.isSimulated, "null means the platform cannot tell, and the fake can say so")
        assertNull(fix.isApproximate)
    }

    @Test
    fun `the extreme valid positions are accepted exactly`() {
        for ((latitude, longitude) in listOf(90.0 to 180.0, -90.0 to -180.0, 90.0 to -180.0, 0.0 to 0.0, -0.0 to -0.0)) {
            val fix: LocationFix = fakeFix(latitude, longitude)
            assertEquals(latitude, fix.coordinates.latitude)
            assertEquals(longitude, fix.coordinates.longitude)
        }
    }

    @Test
    fun `an invalid position throws like GeoCoordinates does`() {
        for ((latitude, longitude) in listOf(
            90.0001 to 0.0,
            -90.0001 to 0.0,
            0.0 to 180.0001,
            0.0 to -180.0001,
            Double.NaN to 0.0,
            0.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 0.0,
            0.0 to Double.NEGATIVE_INFINITY,
        )) {
            assertFailsWith<IllegalArgumentException>("latitude=$latitude longitude=$longitude") { fakeFix(latitude, longitude) }
        }
    }

    @Test
    fun `a fix reaches a stream collector and a one-shot caller with every field intact`() = runTest {
        val fix: LocationFix = fakeFix(
            latitude = -33.8688,
            longitude = 151.2093,
            timestampEpochMillis = 42L,
            horizontalAccuracyMeters = 12.0,
            ellipsoidalAltitudeMeters = 58.0,
            mslAltitudeMeters = 40.0,
            verticalAccuracyMeters = 9.0,
            speedMetersPerSecond = 11.0,
            speedAccuracyMetersPerSecond = 1.0,
            courseDegrees = 90.0,
            courseAccuracyDegrees = 2.0,
            isSimulated = null,
            isApproximate = true,
            platformDetails = TestFixDetails("x"),
        )
        val reading: LocationReading = LocationReading.Available(fix)
        val provider = FakeLocationProvider()

        provider.emit(reading)
        provider.currentLocationAnswer = reading

        val streamed: LocationFix? = provider.observeLocation().first().fixOrNull()
        val answered: LocationFix? = provider.getCurrentLocation().fixOrNull()
        assertSame(fix, streamed, "the stream hands out the very fix it was given")
        assertSame(fix, answered, "so does the one-shot answer")
    }
}
