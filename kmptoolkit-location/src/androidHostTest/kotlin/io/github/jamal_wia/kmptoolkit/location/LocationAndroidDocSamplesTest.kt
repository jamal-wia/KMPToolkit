package io.github.jamal_wia.kmptoolkit.location

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/*
 * The Android samples of docs/kmptoolkit-location (02-getting-started, 03-guide), typed in exactly as the docs
 * print them (each block between "docs-sample:" and "end docs-sample" is the text of one fenced block) and run
 * against the real factories on Robolectric. No location permission is granted, so every call answers the typed
 * PERMISSION_MISSING at once.
 */

// docs-sample: android-wiring
fun androidTracker(context: Context): TrackerSession = TrackerSession(
    provider = createLocationProvider(context),
    platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS, maxUpdateDelay = 1.minutes),
)

fun androidCompass(context: Context): HeadingProvider =
    createHeadingProvider(context, AndroidHeadingOptions(sensor = AndroidHeadingSensor.ROTATION_VECTOR))

fun providerOfFix(fix: LocationFix): String? = fix.androidDetails?.provider
// end docs-sample

// docs-sample: android-one-shot
suspend fun gpsOnlyFix(provider: LocationProvider): LocationReading = provider.getCurrentLocation(
    CurrentLocationOptions(
        accuracy = LocationAccuracy.HIGH_ACCURACY,
        maxAge = Duration.ZERO,
        timeout = 20.seconds,
        platform = AndroidCurrentLocationOptions(source = AndroidLocationSource.GPS),
    ),
)
// end docs-sample

// docs-sample: android-launcher
fun androidLocation(context: Context, activityAccess: ActivityAccess): LocationProvider =
    createLocationProviderWithLauncher(context, SystemScreenLauncher.callerTask(activityAccess))
// end docs-sample

@RunWith(AndroidJUnit4::class)
class LocationAndroidDocSamplesTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the android tracker sample answers PERMISSION_MISSING and completes without a permission`() = runTest(timeout = 60.seconds) {
        val readings: List<LocationReading> = androidTracker(application).readings().toList()

        assertEquals(listOf<LocationReading>(LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)), readings)
    }

    @Test
    fun `the gps only one shot sample answers PERMISSION_MISSING at once`() = runTest(timeout = 60.seconds) {
        val reading: LocationReading = gpsOnlyFix(createLocationProvider(application))

        assertEquals(LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING), reading)
    }

    @Test
    fun `the compass sample builds a provider and a fix without android details reads null`() {
        val compass: HeadingProvider = androidCompass(application)

        assertFalse(compass.isAvailable, "Robolectric has no rotation vector sensor, and an explicit choice is never substituted")
        assertNull(providerOfFix(LocationFix(GeoCoordinates(1.0, 2.0), timestampEpochMillis = 0L)))
    }

    @Test
    fun `the launcher sample builds the same provider with the caller task launcher`() {
        val access: ActivityAccess = createActivityAccess(application) { true }
        try {
            val provider: LocationProvider = androidLocation(application, access)

            provider.openLocationSettings()
        } finally {
            access.release()
        }
    }
}
