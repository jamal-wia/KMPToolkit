package io.github.jamal_wia.kmptoolkit.accelerometer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
 * The Android accelerometer against Robolectric's `SensorManager`: that a collection holds a
 * listener exactly as long as it runs, that samples arrive unscaled, and that the sampling interval
 * reaches the platform in the form it actually reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidAccelerometerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sensorManager: SensorManager
        get() = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val shadowManager: ShadowSensorManager get() = shadowOf(sensorManager)

    private fun addAccelerometer(): Sensor {
        val sensor: Sensor = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        shadowManager.addSensor(sensor)
        return sensor
    }

    private fun event(sensor: Sensor, x: Float, y: Float, z: Float): SensorEvent =
        SensorEventBuilder.newBuilder()
            .setSensor(sensor)
            .setValues(floatArrayOf(x, y, z))
            .setTimestamp(0L)
            .build()

    @Test
    fun `isAvailable is false and nothing is registered on a device without an accelerometer`() = runTest {
        val accelerometer: Accelerometer = createAccelerometer(context)
        val received: MutableList<AccelerometerSample> = mutableListOf()

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { sample: AccelerometerSample -> received += sample }
        }

        assertFalse(accelerometer.isAvailable)
        assertTrue(shadowManager.listeners.isEmpty())
        assertTrue(collection.isActive, "observe() completed instead of staying silent")
        assertTrue(received.isEmpty())
    }

    @Test
    fun `a collection registers a listener and releases it when cancelled`() = runTest {
        addAccelerometer()
        val accelerometer: Accelerometer = createAccelerometer(context)
        assertTrue(shadowManager.listeners.isEmpty(), "registered before anyone collected")

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { }
        }
        assertEquals(1, shadowManager.listeners.size)

        collection.cancel()
        assertTrue(shadowManager.listeners.isEmpty())
    }

    @Test
    fun `samples arrive in m per s squared exactly as the platform reports them`() = runTest {
        val sensor: Sensor = addAccelerometer()
        val accelerometer: Accelerometer = createAccelerometer(context)
        val received: MutableList<AccelerometerSample> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { sample: AccelerometerSample -> received += sample }
        }

        shadowManager.sendSensorEventToListeners(event(sensor, 0.1f, -0.2f, 9.8f))
        shadowManager.sendSensorEventToListeners(event(sensor, 0f, 0f, -9.8f))

        assertEquals(
            listOf(AccelerometerSample(0.1f, -0.2f, 9.8f), AccelerometerSample(0f, 0f, -9.8f)),
            received,
        )
    }

    @Test
    fun `two collections are independent - ending one leaves the other receiving`() = runTest {
        val sensor: Sensor = addAccelerometer()
        val accelerometer: Accelerometer = createAccelerometer(context)
        val first: MutableList<AccelerometerSample> = mutableListOf()
        val second: MutableList<AccelerometerSample> = mutableListOf()
        val firstCollection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { sample: AccelerometerSample -> first += sample }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { sample: AccelerometerSample -> second += sample }
        }

        firstCollection.cancel()
        shadowManager.sendSensorEventToListeners(event(sensor, 1f, 2f, 3f))

        assertTrue(first.isEmpty())
        assertEquals(listOf(AccelerometerSample(1f, 2f, 3f)), second)
        assertEquals(1, shadowManager.listeners.size)
    }

    @Test
    fun `an ordinary interval reaches the platform as microseconds`() {
        assertEquals(200_000, 200.milliseconds.toSamplingPeriodUs())
        assertEquals(4, 4.microseconds.toSamplingPeriodUs())
    }

    @Test
    fun `an interval the platform would misread as a delay constant asks for the fastest rate`() {
        // 1-3 are SENSOR_DELAY_GAME, _UI and _NORMAL: passed through, 3 us would mean 200 ms.
        listOf(3.microseconds, 1.microseconds, Duration.ZERO, (-5).milliseconds).forEach { interval: Duration ->
            assertEquals(SensorManager.SENSOR_DELAY_FASTEST, interval.toSamplingPeriodUs(), "interval=$interval")
        }
    }

    @Test
    fun `an interval too long for the platform's Int is capped rather than overflowing`() {
        assertEquals(Int.MAX_VALUE, 60.minutes.toSamplingPeriodUs())
        assertEquals(Int.MAX_VALUE, Duration.INFINITE.toSamplingPeriodUs())
    }
}
