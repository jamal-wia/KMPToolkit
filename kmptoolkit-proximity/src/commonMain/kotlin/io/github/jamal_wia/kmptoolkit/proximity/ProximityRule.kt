package io.github.jamal_wia.kmptoolkit.proximity

/**
 * Folds a raw distance reading into the boolean the sensor is really answering.
 *
 * Pure and platform-free so the one subtle branch here is testable without hardware.
 */
public object ProximityRule {

    /**
     * How close something has to be to the screen to count as near, in centimetres.
     *
     * Most proximity sensors are binary — they report either zero or their own maximum — so this
     * only has to sit above "touching" and below "arm's length". Five centimetres is the figure
     * platforms themselves use for the call-screen blank.
     */
    public const val NEAR_CM: Float = 5.0f

    /**
     * Whether a reading means "something is against the screen".
     *
     * Compared against the smaller of [NEAR_CM] and the sensor's own maximum: a binary sensor
     * reports exactly its maximum when nothing is near, and on units whose maximum is below five
     * centimetres a flat threshold would read that "far" answer as near.
     *
     * @param distanceCm what the sensor reports.
     * @param maxRangeCm the sensor's own maximum range.
     */
    public fun isNear(distanceCm: Float, maxRangeCm: Float): Boolean =
        distanceCm < minOf(NEAR_CM, maxRangeCm)
}
