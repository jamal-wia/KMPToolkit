package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/*
 * The desktop sample of docs/kmptoolkit-location (03-guide, recipe 9), typed in exactly as the docs print it
 * (the block between "docs-sample:" and "end docs-sample" is the text of one fenced block).
 */

// docs-sample: desktop-wiring
fun desktopTracker(): TrackerSession = TrackerSession(provider = createLocationProvider(), platform = null)

suspend fun desktopFixOrNull(provider: LocationProvider): GeoCoordinates? =
    when (val reading: LocationReading = provider.getCurrentLocation()) {
        is LocationReading.Available -> reading.fix.coordinates
        is LocationReading.Unavailable -> null // UNSUPPORTED on a desktop: show the typed branch, never a spinner
    }
// end docs-sample

class LocationJvmDocSamplesTest {

    @Test
    fun `the desktop tracker answers UNSUPPORTED once and completes`() = runTest(timeout = 60.seconds) {
        val readings: List<LocationReading> = desktopTracker().readings().toList()

        assertEquals(listOf<LocationReading>(LocationReading.Unavailable(LocationUnavailability.UNSUPPORTED)), readings)
    }

    @Test
    fun `the desktop one shot has no coordinates`() = runTest(timeout = 60.seconds) {
        assertEquals(null, desktopFixOrNull(createLocationProvider()))
    }
}
