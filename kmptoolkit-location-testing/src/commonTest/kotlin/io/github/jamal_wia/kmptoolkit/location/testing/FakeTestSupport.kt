@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.location.Heading
import io.github.jamal_wia.kmptoolkit.location.LocationReading
import io.github.jamal_wia.kmptoolkit.location.LocationUnavailability
import io.github.jamal_wia.kmptoolkit.location.PlatformCurrentLocationOptions
import io.github.jamal_wia.kmptoolkit.location.PlatformFixDetails
import io.github.jamal_wia.kmptoolkit.location.PlatformLocationUpdatesOptions

/** The fixed seed of every storm in this source set; every failure message of a storm starts with `seed=`. */
internal const val FAKE_TEST_SEED: Long = 7_240L

internal val noFix: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX)
internal val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
internal val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)
internal val unsupported: LocationReading = LocationReading.Unavailable(LocationUnavailability.UNSUPPORTED)

/** An available reading at the given point; [timestamp] tells readings at the same point apart. */
internal fun available(latitude: Double, longitude: Double, timestamp: Long = 0L): LocationReading =
    LocationReading.Available(fakeFix(latitude, longitude, timestampEpochMillis = timestamp))

internal fun heading(magnetic: Double, trueNorth: Double? = null, accuracy: Double? = null, needsCalibration: Boolean = false): Heading =
    Heading(magneticDegrees = magnetic, trueDegrees = trueNorth, accuracyDegrees = accuracy, needsCalibration = needsCalibration)

/** A platform options value only the test can tell apart from another; stands in for AndroidCurrentLocationOptions. */
internal class TestCurrentOptions(val tag: String) : PlatformCurrentLocationOptions

/** Stands in for AndroidLocationUpdatesOptions. */
internal class TestUpdatesOptions(val tag: String) : PlatformLocationUpdatesOptions

/** Stands in for AndroidFixDetails. */
internal class TestFixDetails(val tag: String) : PlatformFixDetails
