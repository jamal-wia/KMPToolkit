package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The value types of the provider contract (PLAN D8, D9) and the interface's default body: a fix
 * keeps every field and the optional ones default to `null`, `fixOrNull` answers per subtype,
 * and `promptToEnableService` keeps its 4.x default.
 */
class LocationTypesTest {

    private val coordinates = GeoCoordinates(21.422487, 39.826206)

    @OptIn(ToolkitInternalApi::class)
    private data class Details(val label: String) : PlatformFixDetails

    @Test
    fun `a fix with only the required fields has every optional field null`() {
        val fix = LocationFix(coordinates, timestampEpochMillis = 1_700_000_000_000L)

        assertEquals(coordinates, fix.coordinates)
        assertEquals(1_700_000_000_000L, fix.timestampEpochMillis)
        assertNull(fix.horizontalAccuracyMeters)
        assertNull(fix.ellipsoidalAltitudeMeters)
        assertNull(fix.mslAltitudeMeters)
        assertNull(fix.verticalAccuracyMeters)
        assertNull(fix.speedMetersPerSecond)
        assertNull(fix.speedAccuracyMetersPerSecond)
        assertNull(fix.courseDegrees)
        assertNull(fix.courseAccuracyDegrees)
        assertNull(fix.isSimulated)
        assertNull(fix.isApproximate)
        assertNull(fix.platformDetails)
    }

    @Test
    fun `every field of a fix round-trips and copy changes only what it names`() {
        val details = Details("platform")
        val fix = LocationFix(
            coordinates = coordinates,
            timestampEpochMillis = 42L,
            horizontalAccuracyMeters = 5.5,
            ellipsoidalAltitudeMeters = 600.25,
            mslAltitudeMeters = 580.5,
            verticalAccuracyMeters = 3.0,
            speedMetersPerSecond = 1.5,
            speedAccuracyMetersPerSecond = 0.25,
            courseDegrees = 271.0,
            courseAccuracyDegrees = 12.0,
            isSimulated = true,
            isApproximate = false,
            platformDetails = details,
        )

        assertEquals(5.5, fix.horizontalAccuracyMeters)
        assertEquals(600.25, fix.ellipsoidalAltitudeMeters)
        assertEquals(580.5, fix.mslAltitudeMeters)
        assertEquals(3.0, fix.verticalAccuracyMeters)
        assertEquals(1.5, fix.speedMetersPerSecond)
        assertEquals(0.25, fix.speedAccuracyMetersPerSecond)
        assertEquals(271.0, fix.courseDegrees)
        assertEquals(12.0, fix.courseAccuracyDegrees)
        assertEquals(true, fix.isSimulated)
        assertEquals(false, fix.isApproximate)
        assertSame(details, fix.platformDetails)

        val moved: LocationFix = fix.copy(timestampEpochMillis = 43L)
        assertEquals(43L, moved.timestampEpochMillis)
        assertEquals(fix.copy(timestampEpochMillis = 43L), moved)
        assertEquals(fix.horizontalAccuracyMeters, moved.horizontalAccuracyMeters)
    }

    @Test
    fun `fixOrNull answers the fix for Available and null for every reason`() {
        val fix = LocationFix(coordinates, 1L)

        assertSame(fix, LocationReading.Available(fix).fixOrNull())
        for (reason: LocationUnavailability in LocationUnavailability.entries) {
            assertNull(LocationReading.Unavailable(reason).fixOrNull(), "reason $reason")
        }
    }

    @Test
    fun `readings compare by value`() {
        val fix = LocationFix(coordinates, 1L)

        assertEquals(LocationReading.Available(fix), LocationReading.Available(fix.copy()))
        assertEquals(
            LocationReading.Unavailable(LocationUnavailability.NO_FIX),
            LocationReading.Unavailable(LocationUnavailability.NO_FIX),
        )
    }

    /** An app-style provider written against the interface only: it implements what the contract requires and nothing else. */
    private class MinimalProvider(private val enabled: Boolean) : LocationProvider {
        override suspend fun getCurrentLocation(options: CurrentLocationOptions): LocationReading =
            LocationReading.Unavailable(LocationUnavailability.NO_FIX)

        override fun observeLocation(options: LocationUpdatesOptions): Flow<LocationReading> = emptyFlow()

        override suspend fun isLocationEnabled(): Boolean = enabled

        override fun observeLocationEnabled(): Flow<Boolean> = flowOf(enabled)

        override fun openLocationSettings() = Unit
    }

    @Test
    fun `the default prompt answers already on when enabled and unsupported otherwise`() = runTest {
        assertEquals(LocationServicePrompt.ALREADY_ON, MinimalProvider(enabled = true).promptToEnableService())
        assertEquals(LocationServicePrompt.UNSUPPORTED, MinimalProvider(enabled = false).promptToEnableService())
    }

    @Test
    fun `the default parameters of the interface calls are the default options`() = runTest {
        val provider: LocationProvider = MinimalProvider(enabled = true)

        assertEquals(LocationReading.Unavailable(LocationUnavailability.NO_FIX), provider.getCurrentLocation())
    }
}
