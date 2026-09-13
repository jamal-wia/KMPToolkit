package io.github.jamal_wia.kmptoolkit.proximity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.hardware.SensorEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

/**
 * The Android proximity sensor against Robolectric's `SensorManager`: whether it counts as
 * available, that a collection holds a listener exactly as long as it runs, and that raw distances
 * reach the collector as near/far — once per change. The threshold itself is pinned separately by
 * [ProximityRuleTest]; here it is only checked that events actually go through it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidProximitySensorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sensorManager(): SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private fun addProximitySensor(maximumRange: Float): Sensor {
        val sensor: Sensor = ShadowSensor.newInstance(Sensor.TYPE_PROXIMITY)
        shadowOf(sensor).setMaximumRange(maximumRange)
        shadowOf(sensorManager()).addSensor(sensor)
        return sensor
    }

    private val shadowManager: ShadowSensorManager get() = shadowOf(sensorManager())

    private fun distance(sensor: Sensor, cm: Float): SensorEvent =
        SensorEventBuilder.newBuilder()
            .setSensor(sensor)
            .setValues(floatArrayOf(cm))
            .setTimestamp(0L)
            .build()

    @Test
    fun `a collection registers a listener and releases it when cancelled`() = runTest {
        addProximitySensor(maximumRange = 5f)
        val sensor: ProximitySensor = createProximitySensor(context)
        assertTrue(shadowManager.listeners.isEmpty(), "registered before anyone collected")

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sensor.observe().collect { }
        }
        assertEquals(1, shadowManager.listeners.size)

        collection.cancel()
        assertTrue(shadowManager.listeners.isEmpty())
    }

    @Test
    fun `distances reach the collector as near or far through the rule`() = runTest {
        val hardware: Sensor = addProximitySensor(maximumRange = 5f)
        val sensor: ProximitySensor = createProximitySensor(context)
        val readings: MutableList<Boolean> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sensor.observe().collect { near: Boolean -> readings += near }
        }

        shadowManager.sendSensorEventToListeners(distance(hardware, cm = 0f))
        shadowManager.sendSensorEventToListeners(distance(hardware, cm = 5f))

        // A binary unit reports its own maximum for "nothing there" — that must read as far.
        assertEquals(listOf(true, false), readings)
    }

    @Test
    fun `a repeated reading is emitted once`() = runTest {
        val hardware: Sensor = addProximitySensor(maximumRange = 5f)
        val sensor: ProximitySensor = createProximitySensor(context)
        val readings: MutableList<Boolean> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sensor.observe().collect { near: Boolean -> readings += near }
        }

        shadowManager.sendSensorEventToListeners(distance(hardware, cm = 0f))
        shadowManager.sendSensorEventToListeners(distance(hardware, cm = 1f))
        shadowManager.sendSensorEventToListeners(distance(hardware, cm = 5f))
        shadowManager.sendSensorEventToListeners(distance(hardware, cm = 5f))

        assertEquals(listOf(true, false), readings)
    }

    @Test
    fun `without usable hardware nothing is registered and observe stays open and silent`() = runTest {
        addProximitySensor(maximumRange = 0f)
        val sensor: ProximitySensor = createProximitySensor(context)
        val readings: MutableList<Boolean> = mutableListOf()

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sensor.observe().collect { near: Boolean -> readings += near }
        }

        assertTrue(shadowManager.listeners.isEmpty())
        assertTrue(collection.isActive, "observe() completed instead of staying silent")
        assertTrue(readings.isEmpty())
    }

    @Test
    fun `isAvailable is false when the device has no proximity sensor`() {
        val sensor: ProximitySensor = createProximitySensor(context)

        assertFalse(sensor.isAvailable)
    }

    @Test
    fun `isAvailable is true for a sensor with a usable range`() {
        addProximitySensor(maximumRange = 5f)

        val sensor: ProximitySensor = createProximitySensor(context)

        assertTrue(sensor.isAvailable)
    }

    @Test
    fun `a sensor reporting a zero maximum range counts as absent`() {
        // A TYPE_PROXIMITY entry with maximumRange == 0 can never answer "near"; taking it at its
        // word would hand consumers a permanent, misleading "far" instead of an honest "absent".
        addProximitySensor(maximumRange = 0f)

        val sensor: ProximitySensor = createProximitySensor(context)

        assertFalse(sensor.isAvailable)
    }

    @Test
    fun `two instances from the same context agree on availability`() {
        // isAvailable is resolved once, at construction, from whatever the platform reports at
        // that moment — a second factory call must see the same hardware fact, not stale state
        // left over from the first.
        addProximitySensor(maximumRange = 5f)

        assertTrue(createProximitySensor(context).isAvailable)
        assertTrue(createProximitySensor(context).isAvailable)
    }
}
