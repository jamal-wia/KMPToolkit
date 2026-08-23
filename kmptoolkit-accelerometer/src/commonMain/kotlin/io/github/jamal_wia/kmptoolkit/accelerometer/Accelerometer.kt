package io.github.jamal_wia.kmptoolkit.accelerometer

import kotlinx.coroutines.flow.Flow

/**
 * One accelerometer reading: acceleration along the device's three axes, in m/s².
 *
 * The axes are fixed to the hardware, not to the interface orientation: X runs right along the
 * short side, Y up along the long side, Z out of the screen. A device lying still reads gravity —
 * about `+9.8` on Z face-up, about `-9.8` face-down.
 */
public data class AccelerometerSample(
    public val x: Float,
    public val y: Float,
    public val z: Float,
)

/**
 * The raw accelerometer, and nothing else — no thresholds, no interpretation. What a reading
 * *means* (face-down, still, put down) belongs to whoever consumes it.
 *
 * Obtain one from the platform factory (`createAccelerometer(context)` on Android,
 * `createAccelerometer()` on iOS) and pass it into shared code as this interface — shared code
 * never names the factory. See `docs/01-architecture.md`.
 */
public interface Accelerometer {

    /**
     * Whether the device has an accelerometer at all.
     *
     * [observe] on a device without one never emits — it stays open, silent, until the collecting
     * coroutine is cancelled. This flag is the thing to check, not the absence of emissions, which
     * looks identical to "nothing has moved yet".
     */
    public val isAvailable: Boolean

    /**
     * Emits every reading the sensor reports.
     *
     * Cold: the sensor is registered when collection starts and released when it ends, so a screen
     * that stops collecting stops costing battery. Never completes on its own — there is no "last"
     * reading, only a collecting coroutine that gets cancelled.
     */
    public fun observe(): Flow<AccelerometerSample>
}
