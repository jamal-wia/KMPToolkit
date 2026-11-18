package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/*
 * The iOS samples of docs/kmptoolkit-location (02-getting-started, 03-guide), typed in exactly as the docs print
 * them (each block between "docs-sample:" and "end docs-sample" is the text of one fenced block). They only build
 * options and providers: no CLLocationManager is created before a call, so nothing here needs authorization.
 */

// docs-sample: ios-wiring
fun iosTracker(): TrackerSession = TrackerSession(
    provider = createLocationProvider().withSystemServicesPrompt(),
    platform = IosLocationUpdatesOptions(
        allowsBackgroundLocationUpdates = true,
        showsBackgroundLocationIndicator = true,
        activityType = IosActivityType.FITNESS,
    ),
)

fun iosCompass(): HeadingProvider = createHeadingProvider(showsSystemCalibration = true)

fun floorOfFix(fix: LocationFix): Int? = fix.iosDetails?.floorLevel
// end docs-sample

// docs-sample: ios-one-shot
fun reducedOneShot(): CurrentLocationOptions = CurrentLocationOptions(
    timeout = 15.seconds,
    platform = IosCurrentLocationOptions(desiredAccuracyOverride = IosDesiredAccuracy.REDUCED),
)
// end docs-sample

class LocationIosDocSamplesTest {

    @Test
    fun `the iOS samples build their providers and options`() {
        iosTracker()
        iosCompass()
        assertNull(floorOfFix(LocationFix(GeoCoordinates(1.0, 2.0), timestampEpochMillis = 0L)))
        assertEquals(
            IosDesiredAccuracy.REDUCED,
            (reducedOneShot().platform as IosCurrentLocationOptions).desiredAccuracyOverride,
        )
    }
}
