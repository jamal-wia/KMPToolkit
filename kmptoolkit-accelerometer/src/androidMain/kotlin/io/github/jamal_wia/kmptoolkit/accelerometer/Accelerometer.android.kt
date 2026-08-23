package io.github.jamal_wia.kmptoolkit.accelerometer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Creates the Android [Accelerometer], backed by `TYPE_ACCELEROMETER`.
 *
 * Call this once — in your `Application`, or wherever you assemble dependencies — and pass the
 * resulting [Accelerometer] into shared code. The instance holds only the framework
 * `SensorManager` and `Sensor` obtained from [context]; nothing needs releasing up front, because
 * the platform listener itself is registered per collection of [Accelerometer.observe] and
 * unregistered when that collection ends.
 *
 * No Android permission is required at [samplingInterval]'s default. Asking for a faster interval
 * — below 5 ms, i.e. above 200 Hz — needs `android.permission.HIGH_SAMPLING_RATE_SENSORS` on API
 * 31+, which this library does not declare, on purpose; see
 * `docs/kmptoolkit-accelerometer/05-platform-notes.md`.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param samplingInterval how often the sensor is asked to report, converted to microseconds for
 *   `SensorManager.registerListener`. The default, 200 ms, approximates `SENSOR_DELAY_NORMAL` —
 *   roughly five samples a second, the cheapest rate the platform offers.
 */
public fun createAccelerometer(
    context: Context,
    samplingInterval: Duration = 200.milliseconds,
): Accelerometer = AndroidAccelerometer(context.applicationContext, samplingInterval)

/**
 * Android [Accelerometer] over `TYPE_ACCELEROMETER`. Android already reports in m/s², so samples
 * pass through unscaled.
 */
internal class AndroidAccelerometer(
    context: Context,
    private val samplingInterval: Duration,
) : Accelerometer {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override val isAvailable: Boolean get() = sensor != null

    override fun observe(): Flow<AccelerometerSample> = callbackFlow {
        val accelerometer: Sensor = sensor ?: run {
            // No hardware: stay silent rather than invent readings — isAvailable is the flag to
            // check.
            awaitClose { }
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(
                    AccelerometerSample(
                        x = event.values[X_AXIS],
                        y = event.values[Y_AXIS],
                        z = event.values[Z_AXIS],
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // A sample is a direction and a magnitude, not a calibrated measurement — a drop in
                // accuracy changes neither.
            }
        }

        sensorManager.registerListener(
            listener,
            accelerometer,
            samplingInterval.inWholeMicroseconds.toInt(),
        )
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private companion object {
        // The order is the platform's contract for TYPE_ACCELEROMETER: values[0]=x, values[1]=y,
        // values[2]=z.
        const val X_AXIS = 0
        const val Y_AXIS = 1
        const val Z_AXIS = 2
    }
}
