package io.github.jamal_wia.kmptoolkit.proximity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Creates the Android [ProximitySensor], over `Sensor.TYPE_PROXIMITY`.
 *
 * Create one per process and hold it; each collection of [ProximitySensor.observe] registers and
 * unregisters its own `SensorEventListener`, so there is nothing on this instance itself that needs
 * releasing. Only the application context is retained, so passing an `Activity` here is harmless.
 *
 * No Android permission is required for `TYPE_PROXIMITY` — there is nothing to declare in your
 * manifest for this module. See `docs/kmptoolkit-proximity/05-platform-notes.md`.
 *
 * @param context any `Context`; its application context is what gets retained.
 */
public fun createProximitySensor(context: Context): ProximitySensor =
    AndroidProximitySensor(context.applicationContext)

/**
 * Android [ProximitySensor] over `TYPE_PROXIMITY`.
 *
 * Event-driven: the platform reports only on change, so leaving it registered costs nothing between
 * events.
 */
private class AndroidProximitySensor(context: Context) : ProximitySensor {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /**
     * Only a sensor that reports a usable range counts. One answering `maximumRange == 0` can never
     * read as near, and taking it at its word would hand consumers a permanent, misleading "far".
     */
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        ?.takeIf { proximity: Sensor -> proximity.maximumRange > 0f }

    override val isAvailable: Boolean get() = sensor != null

    override fun observe(): Flow<Boolean> = callbackFlow {
        val proximity: Sensor = sensor ?: run {
            // No usable hardware: stay silent rather than invent readings — isAvailable is the flag.
            awaitClose { }
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(
                    ProximityRule.isNear(
                        distanceCm = event.values[DISTANCE],
                        maxRangeCm = event.sensor.maximumRange,
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Near/far is not a calibrated measurement — a drop in accuracy changes nothing.
            }
        }

        sensorManager.registerListener(listener, proximity, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { sensorManager.unregisterListener(listener) }
    }.distinctUntilChanged()

    private companion object {
        /** Proximity reports a single value: how far away the nearest thing is. */
        const val DISTANCE = 0
    }
}
