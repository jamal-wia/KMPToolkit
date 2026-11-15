package io.github.jamal_wia.kmptoolkit.location

/**
 * Android-only heading settings, passed to the Android heading factory
 * ([createHeadingProvider]).
 *
 * @property sensor Which sensor pipeline produces the heading. With a sensor other than
 * [AndroidHeadingSensor.AUTO] that is absent on the device, [HeadingProvider.isAvailable] is
 * `false`: the provider never silently substitutes another sensor for an explicit choice.
 */
public data class AndroidHeadingOptions(
    public val sensor: AndroidHeadingSensor = AndroidHeadingSensor.AUTO,
)

/**
 * The Android sensor pipeline behind a heading. An explicitly chosen sensor that is absent on the
 * device makes [HeadingProvider.isAvailable] `false`.
 */
public enum class AndroidHeadingSensor {

    /**
     * Library policy: the rotation vector when the device has one, else the geomagnetic rotation
     * vector, else the accelerometer together with the magnetometer.
     */
    AUTO,

    /** `Sensor.TYPE_ROTATION_VECTOR`: gyroscope-fused, the smoothest, and the most power. */
    ROTATION_VECTOR,

    /** `Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR`: no gyroscope, lower power, noisier. */
    GEOMAGNETIC_ROTATION_VECTOR,

    /**
     * The raw accelerometer and magnetometer through `SensorManager.getRotationMatrix`, without
     * smoothing; needs both sensors.
     */
    ACCELEROMETER_MAGNETOMETER,
}
