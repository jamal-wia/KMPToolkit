@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The heading provider across API levels (group loc-heading): the real `SensorManager` and
 * `LocationManager` behind the public factory, through the platform's own callback paths. On API 24-29
 * the three legacy `LocationListener` callbacks are abstract and the platform calls them, so a listener
 * that misses one dies with an `AbstractMethodError` on the main thread: the passive listener of the
 * compass must serve a fix, a provider that goes off and comes back, and be released, on every level.
 */
@Suppress("DEPRECATION") // Robolectric shadows: the replacements are not in the pinned Robolectric
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 29, 30, 31, 35])
class HeadingAndroidApiLevelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val rig = SensorRig(application)
    private val locationManager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Test
    fun `the compass reads true north from a passive fix and releases everything on every level`() = runTest {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        rig.add(SensorTypes.ROTATION_VECTOR)
        val shadow = shadowOf(locationManager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            createHeadingProvider(application).observeHeading(minChangeDegrees = 0.0).collect { received += it }
        }
        rig.shadow.sendSensorEventToListeners(rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(120.0).sensorValues()))
        assertEquals(1, received.size)
        assertNull(received.single().trueDegrees, "control: no position yet")
        assertEquals(1, rig.registeredListeners)
        assertEquals(1, shadow.getRequestLocationUpdateListeners().size, "control: the passive listener is registered")

        shadow.simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 21.4
            longitude = 39.8
            elapsedRealtimeNanos = 1_000_000_000L
        })
        shadowOf(Looper.getMainLooper()).idle()
        // The provider going off and on again calls the legacy callbacks of every listener.
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, received.size, "true north appeared from the passive fix")
        assertTrue(received.last().trueDegrees != null)
        collection.cancelAndJoin()
        assertEquals(0, rig.registeredListeners)
        assertTrue(shadow.getRequestLocationUpdateListeners().isEmpty(), "released on this level too")
    }
}
