package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlinx.coroutines.flow.Flow

/**
 * One compass reading for the top edge of the screen as drawn in the requested [ScreenRotation].
 * Angles are in `[0, 360)`.
 *
 * @property magneticDegrees Degrees clockwise from magnetic north; always present.
 * @property trueDegrees Degrees clockwise from true north; `null` when no declination could be
 * applied (iOS without location authorization; Android without `declinationAt` and without a
 * permitted fix).
 * @property accuracyDegrees Error in degrees as reported by the platform (iOS `headingAccuracy`,
 * Android rotation-vector estimate converted from radians); `null` when none, never invented.
 * @property needsCalibration `true` while the platform reports the magnetometer as needing
 * calibration or disturbed.
 */
public data class Heading(
    public val magneticDegrees: Double,
    public val trueDegrees: Double?,
    public val accuracyDegrees: Double?,
    public val needsCalibration: Boolean,
)

/**
 * The device's physical rotation from its natural orientation, counter-clockwise (equivalently the
 * drawn UI's rotation clockwise), exactly Android's `Surface.ROTATION_*` / `Display.getRotation()`.
 * iOS maps it to `headingOrientation` as a `CLDeviceOrientation`.
 */
public enum class ScreenRotation {

    /** Natural orientation (`Surface.ROTATION_0`; `CLDeviceOrientationPortrait`). */
    NATURAL,

    /** `Surface.ROTATION_90`; `CLDeviceOrientationLandscapeLeft` (home button on the right). */
    ROTATION_90,

    /** `Surface.ROTATION_180`; `CLDeviceOrientationPortraitUpsideDown`. */
    ROTATION_180,

    /** `Surface.ROTATION_270`; `CLDeviceOrientationLandscapeRight`. */
    ROTATION_270,
}

/**
 * Device compass heading. Separate from [LocationProvider] so location-only implementations stay
 * untouched.
 */
public interface HeadingProvider {

    /** Whether this device can report a heading at all; when `false`, [observeHeading] never emits. */
    public val isAvailable: Boolean

    /**
     * Cold stream (registers on collection, releases on cancellation; concurrent collectors are
     * independent, each with its own filter and rotation) that emits the first reading at once, and
     * afterwards when the heading has moved at least [minChangeDegrees] from the last emitted
     * heading, measured with [angleDifference]. A change of [Heading.needsCalibration], or of
     * [Heading.trueDegrees] between `null` and non-null, is always emitted.
     *
     * Android computes true north at [declinationAt] (no permission needed); iOS cannot and reports
     * `trueDegrees == null` without authorization. [screenRotation] is fixed per collection, so
     * re-collect on rotation.
     *
     * @param minChangeDegrees throws [IllegalArgumentException] when negative or non-finite.
     */
    public fun observeHeading(
        minChangeDegrees: Double = 1.0,
        declinationAt: GeoCoordinates? = null,
        screenRotation: ScreenRotation = ScreenRotation.NATURAL,
    ): Flow<Heading>
}

/**
 * The shared argument check of [HeadingProvider.observeHeading], for this library's providers and
 * fakes only: throws [IllegalArgumentException] when [minChangeDegrees] is negative or non-finite
 * (`0.0` is accepted). Not part of the public contract; it can change in any release.
 */
@ToolkitInternalApi
public fun checkMinChangeDegrees(minChangeDegrees: Double) {
    require(minChangeDegrees.isFinite() && minChangeDegrees >= 0.0) {
        "minChangeDegrees must be finite and not negative: $minChangeDegrees"
    }
}
