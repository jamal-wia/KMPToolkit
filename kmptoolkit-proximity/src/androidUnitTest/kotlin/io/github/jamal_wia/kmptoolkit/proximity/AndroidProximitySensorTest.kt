package io.github.jamal_wia.kmptoolkit.proximity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor

/**
 * Exercises the one real decision [createProximitySensor] makes on Android: whether
 * [ProximitySensor.isAvailable] is true. The actual `SensorEventListener` wiring is exactly the
 * standard `SensorManager.registerListener` / `unregisterListener` pair with nothing
 * Android-version-dependent about it, so — mirroring how this repository leaves
 * `AndroidConnectivityObserver`'s `NetworkCallback` registration itself untested and instead pins
 * the translation logic (`NetworkStateTracker`) directly — the event-mapping half of this class is
 * covered by [ProximityRuleTest] instead of by simulating a `SensorEvent` here.
 */
@RunWith(AndroidJUnit4::class)
class AndroidProximitySensorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sensorManager(): SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private fun addProximitySensor(maximumRange: Float) {
        val sensor: Sensor = ShadowSensor.newInstance(Sensor.TYPE_PROXIMITY)
        shadowOf(sensor).setMaximumRange(maximumRange)
        shadowOf(sensorManager()).addSensor(sensor)
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
