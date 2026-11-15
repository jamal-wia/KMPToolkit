package io.github.jamal_wia.kmptoolkit.location

import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * PLAN section 4.1, every Android row, across API 24, 26, 31 and 34: a field is `null` unless the
 * platform says it has a valid value (`has*()` and a finite non-negative accuracy or speed), the
 * level-gated fields (vertical, speed and bearing accuracy from API 26, mock flag API 31, MSL altitude
 * API 34) are absent below their level, altitudes carry their datum in the name, and the Android
 * details carry the provider and the boot-clock stamp. A fix with invalid coordinates maps to `null`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 31, 34])
class FixFieldMappingTest {

    private val level: Int = Build.VERSION.SDK_INT

    private fun bare(provider: String? = LocationManager.GPS_PROVIDER): Location = Location(provider).apply {
        latitude = 41.5
        longitude = -2.25
        time = 1_700_000_123_456L
        elapsedRealtimeNanos = 9_876_543_210L
    }

    @Test
    fun `a location with only coordinates maps every optional field to null`() {
        val fix: LocationFix = assertNotNull(bare().toLocationFix(coarseOnly = false), "API $level")

        assertEquals(GeoCoordinates(41.5, -2.25), fix.coordinates, "API $level")
        assertEquals(1_700_000_123_456L, fix.timestampEpochMillis, "API $level")
        assertNull(fix.horizontalAccuracyMeters, "API $level")
        assertNull(fix.ellipsoidalAltitudeMeters, "API $level")
        assertNull(fix.mslAltitudeMeters, "API $level")
        assertNull(fix.verticalAccuracyMeters, "API $level")
        assertNull(fix.speedMetersPerSecond, "API $level")
        assertNull(fix.speedAccuracyMetersPerSecond, "API $level")
        assertNull(fix.courseDegrees, "API $level")
        assertNull(fix.courseAccuracyDegrees, "API $level")
        assertEquals(false, fix.isSimulated, "isSimulated is never null on Android, API $level")
        assertEquals(false, fix.isApproximate, "API $level")
    }

    @Test
    fun `the fields the platform reports are mapped with their units and names`() {
        val location: Location = bare().apply {
            accuracy = 12.5f
            altitude = 123.0
            speed = 3.5f
            bearing = 270f
        }

        val fix: LocationFix = assertNotNull(location.toLocationFix(coarseOnly = false))

        assertEquals(12.5, fix.horizontalAccuracyMeters, "API $level")
        assertEquals(123.0, fix.ellipsoidalAltitudeMeters, "the platform altitude is the ellipsoidal one, API $level")
        assertEquals(3.5, fix.speedMetersPerSecond, "API $level")
        assertEquals(270.0, fix.courseDegrees, "API $level")
        assertNull(fix.mslAltitudeMeters, "no MSL value was set, API $level")
    }

    @Test
    fun `the level gated accuracies are mapped from API 26 and absent below`() {
        if (level < Build.VERSION_CODES.O) {
            val fix: LocationFix = assertNotNull(bare().toLocationFix(coarseOnly = false))
            assertNull(fix.verticalAccuracyMeters, "API $level has no vertical accuracy")
            assertNull(fix.speedAccuracyMetersPerSecond, "API $level has no speed accuracy")
            assertNull(fix.courseAccuracyDegrees, "API $level has no bearing accuracy")
            return
        }
        val location: Location = bare().apply {
            verticalAccuracyMeters = 4.5f
            speedAccuracyMetersPerSecond = 0.5f
            bearingAccuracyDegrees = 7.5f
        }

        val fix: LocationFix = assertNotNull(location.toLocationFix(coarseOnly = false))

        assertEquals(4.5, fix.verticalAccuracyMeters, "API $level")
        assertEquals(0.5, fix.speedAccuracyMetersPerSecond, "API $level")
        assertEquals(7.5, fix.courseAccuracyDegrees, "API $level")
    }

    @Test
    fun `the MSL altitude is mapped from API 34 and absent below`() {
        if (level < 34) {
            assertNull(assertNotNull(bare().toLocationFix(coarseOnly = false)).mslAltitudeMeters, "API $level")
            return
        }
        val location: Location = bare().apply {
            altitude = 123.0
            mslAltitudeMeters = 88.5
        }

        val fix: LocationFix = assertNotNull(location.toLocationFix(coarseOnly = false))

        assertEquals(88.5, fix.mslAltitudeMeters, "API $level")
        assertEquals(123.0, fix.ellipsoidalAltitudeMeters, "the two datums stay apart, API $level")
    }

    @Test
    fun `the mock flag is read from API 31 and false below`() {
        val location: Location = bare()
        if (level >= Build.VERSION_CODES.S) location.isMock = true

        val fix: LocationFix = assertNotNull(location.toLocationFix(coarseOnly = false))

        assertEquals(level >= Build.VERSION_CODES.S, fix.isSimulated, "API $level")
    }

    @Test
    fun `a negative or non finite accuracy and speed are not measurements`() {
        val location: Location = bare().apply {
            accuracy = -1f
            speed = Float.NaN
        }

        val fix: LocationFix = assertNotNull(location.toLocationFix(coarseOnly = false))

        assertNull(fix.horizontalAccuracyMeters, "API $level")
        assertNull(fix.speedMetersPerSecond, "API $level")
    }

    @Test
    fun `the approximate flag follows the permission at delivery`() {
        assertEquals(true, assertNotNull(bare().toLocationFix(coarseOnly = true)).isApproximate, "API $level")
        assertEquals(false, assertNotNull(bare().toLocationFix(coarseOnly = false)).isApproximate, "API $level")
    }

    @Test
    fun `the Android details carry the provider and the boot clock stamp`() {
        val fix: LocationFix = assertNotNull(bare(LocationManager.NETWORK_PROVIDER).toLocationFix(coarseOnly = false))

        assertEquals(AndroidFixDetails("network", 9_876_543_210L), fix.androidDetails, "API $level")
    }

    @Test
    fun `a location with no provider maps to an empty provider name`() {
        val fix: LocationFix = assertNotNull(bare(provider = null).toLocationFix(coarseOnly = false))

        assertEquals("", fix.androidDetails?.provider, "API $level")
    }

    @Test
    fun `a fix that is not from the Android provider has no Android details`() {
        val common = LocationFix(GeoCoordinates(1.0, 2.0), timestampEpochMillis = 0L)

        assertNull(common.androidDetails)
    }

    @Test
    fun `invalid coordinates map to null`() {
        for ((latitude: Double, longitude: Double) in listOf(Double.NaN to 0.0, 0.0 to Double.NaN, 90.5 to 0.0, 0.0 to 181.0)) {
            val location: Location = bare().apply {
                this.latitude = latitude
                this.longitude = longitude
            }
            assertNull(location.toLocationFix(coarseOnly = false), "$latitude,$longitude at API $level")
        }
    }

    @Test
    fun `the age rules use the boot clock only`() {
        val location: Location = bare().apply { elapsedRealtimeNanos = 5_000_000_000L }

        assertEquals(java.time.Duration.ofSeconds(3).toNanos(), location.cachedAge(8_000_000_000L).inWholeNanoseconds, "API $level")
        assertEquals(kotlin.time.Duration.INFINITE, bare().apply { elapsedRealtimeNanos = 0L }.cachedAge(8_000_000_000L), "a cache with no stamp has an unknown age")
        assertEquals(kotlin.time.Duration.ZERO, bare().apply { elapsedRealtimeNanos = 0L }.deliveredAge(8_000_000_000L), "a delivered fix with no stamp is fresh")
    }
}
