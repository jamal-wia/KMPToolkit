@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor

/**
 * The Android heading provider against Robolectric's `SensorManager` and `LocationManager`
 * (PLAN section 6.3 `HeadingAndroidTest`, group loc-heading): sensor choice and absence; the azimuth of the
 * screen's top edge for every [ScreenRotation] on a flat and on a tilted device, checked against an oracle
 * built from first principles; true north from `declinationAt` without any permission, from the newest
 * permitted fix, and `null` with neither; accuracy; calibration; the minimum-change filter through real sensor
 * events; two concurrent collectors; and the release of every listener.
 *
 * The lifecycle attacks (cancel racing registration, a failing registration, a storm) are in
 * [HeadingAndroidAdversarialTest]. Time is injected, every random draw comes from `Random(HEADING_TEST_SEED)`.
 */
@Suppress("DEPRECATION") // Robolectric shadows: the replacements are not in the pinned Robolectric
@RunWith(AndroidJUnit4::class)
class HeadingAndroidTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val rig = SensorRig(application)
    private val locationManager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val logger = RecordingLogger()

    /** Every declination the provider asked for: position, altitude, time. */
    private val declinationCalls: MutableList<Triple<GeoCoordinates, Double, Long>> = mutableListOf()

    private fun providerOverManagers(
        choice: AndroidHeadingSensor = AndroidHeadingSensor.AUTO,
        declination: Double = DECLINATION,
    ): HeadingProvider = AndroidHeadingProvider(
        sensors = ManagerHeadingSensors(rig.manager),
        positions = ManagerHeadingPositions(locationManager),
        choice = choice,
        logger = logger,
        currentTimeMillis = { FIXED_TIME },
        declinationOf = { coordinates: GeoCoordinates, altitude: Double, time: Long ->
            declinationCalls += Triple(coordinates, altitude, time)
            declination
        },
    )

    private fun TestScope.collect(flow: Flow<Heading>, into: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { heading: Heading -> into += heading } }

    private fun grantLocation() {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun fix(
        provider: String,
        latitude: Double,
        longitude: Double,
        altitude: Double? = null,
        elapsedNanos: Long = 1_000_000_000L,
    ): Location = Location(provider).apply {
        this.latitude = latitude
        this.longitude = longitude
        if (altitude != null) this.altitude = altitude
        elapsedRealtimeNanos = elapsedNanos
        time = FIXED_TIME
    }

    private fun addRotationVector(): Sensor = rig.add(SensorTypes.ROTATION_VECTOR)

    private fun sendYaw(yaw: Double, pitch: Double = 0.0, roll: Double = 0.0, accuracyRadians: Float? = null) {
        rig.shadow.sendSensorEventToListeners(
            rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(yaw, pitch, roll).sensorValues(accuracyRadians)),
        )
    }

    // region availability and sensor choice

    @Test
    fun `a device without a compass sensor is unavailable and observeHeading stays open and silent`() = runTest {
        val provider: HeadingProvider = createHeadingProvider(application)
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = collect(provider.observeHeading(), received)

        assertFalse(provider.isAvailable)
        assertTrue(collection.isActive, "no sensor: the stream stays open, it never completes")
        assertTrue(received.isEmpty())
        assertEquals(0, rig.registeredListeners, "no sensor, so nothing is registered")
        collection.cancelAndJoin()
    }

    @Test
    fun `AUTO takes the rotation vector, else the geomagnetic one, else the accelerometer and magnetometer`() = runTest {
        val types: List<Int> = listOf(
            SensorTypes.ROTATION_VECTOR,
            SensorTypes.GEOMAGNETIC_ROTATION_VECTOR,
            SensorTypes.ACCELEROMETER,
            SensorTypes.MAGNETIC_FIELD,
        )

        /** The sensor types one collection registers on a device that has exactly [present], or null when unavailable. */
        suspend fun registeredTypes(present: List<Int>): List<Int>? {
            val sensors: Map<Int, Sensor> = present.associateWith { type: Int -> ShadowSensor.newInstance(type) }
            val scripted = ScriptedSensors(sensors)
            val provider: HeadingProvider =
                AndroidHeadingProvider(scripted, null, AndroidHeadingSensor.AUTO, logger, { FIXED_TIME }, { _, _, _ -> 0.0 })
            if (!provider.isAvailable) return null
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { provider.observeHeading().collect { } }
            val registered: List<Int> = scripted.registeredSensorTypes
            collection.cancelAndJoin()
            assertEquals(0, scripted.liveRegistrations, "released again")
            return registered
        }

        assertEquals(listOf(SensorTypes.ROTATION_VECTOR), registeredTypes(types))
        assertEquals(listOf(SensorTypes.GEOMAGNETIC_ROTATION_VECTOR), registeredTypes(types.drop(1)))
        assertEquals(listOf(SensorTypes.ACCELEROMETER, SensorTypes.MAGNETIC_FIELD), registeredTypes(types.drop(2)))
        assertNull(registeredTypes(emptyList()), "no candidate at all: unavailable")
        assertNull(registeredTypes(listOf(SensorTypes.ACCELEROMETER)), "the raw pair needs the magnetometer too")
    }

    @Test
    fun `an explicitly chosen sensor that is absent makes the provider unavailable and nothing is substituted`() {
        rig.add(SensorTypes.GEOMAGNETIC_ROTATION_VECTOR)
        rig.add(SensorTypes.ACCELEROMETER)

        assertFalse(providerOverManagers(AndroidHeadingSensor.ROTATION_VECTOR).isAvailable, "only the geomagnetic one exists")
        assertTrue(providerOverManagers(AndroidHeadingSensor.GEOMAGNETIC_ROTATION_VECTOR).isAvailable, "control: the chosen one exists")
        assertFalse(providerOverManagers(AndroidHeadingSensor.ACCELEROMETER_MAGNETOMETER).isAvailable, "no magnetometer")
        assertTrue(providerOverManagers(AndroidHeadingSensor.AUTO).isAvailable, "AUTO may fall back")
    }

    @Test
    fun `the public factory wires the real managers and honours the sensor option`() = runTest {
        addRotationVector()
        val received: MutableList<Heading> = mutableListOf()

        val provider: HeadingProvider = createHeadingProvider(application, AndroidHeadingOptions(AndroidHeadingSensor.ROTATION_VECTOR))
        val collection: Job = collect(provider.observeHeading(minChangeDegrees = 0.0), received)
        sendYaw(123.0)

        assertTrue(provider.isAvailable)
        assertEquals(1, received.size)
        assertAngleClose(123.0, received.single().magneticDegrees, 0.01, "yaw 123")
        assertEquals(AndroidHeadingSensor.AUTO, AndroidHeadingOptions().sensor, "the default option is AUTO")
        collection.cancelAndJoin()
    }

    // endregion

    // region azimuth and screen rotation

    @Test
    fun `a flat device reads the bearing of the screen top edge as drawn for every screen rotation`() = runTest {
        addRotationVector()
        val provider: HeadingProvider = providerOverManagers()
        for (rotation: ScreenRotation in ScreenRotation.entries) {
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collect(provider.observeHeading(minChangeDegrees = 0.0, screenRotation = rotation), received)
            for (yaw: Double in listOf(0.0, 10.0, 90.0, 135.0, 200.0, 270.0, 359.0)) {
                sendYaw(yaw)
                val expected: Double = assertNotNull(expectedAzimuth(Quat.of(yaw), rotation))
                assertAngleClose(expected, received.last().magneticDegrees, 0.01, "rotation=$rotation yaw=$yaw")
            }
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `the rotation turns the reading by a quarter turn per step on a flat device`() = runTest {
        // Contract (D23): ROTATION_90 means the device was turned counter-clockwise by 90 degrees, the drawn
        // top edge is then the device's right edge, 90 degrees clockwise from where the top edge points.
        addRotationVector()
        val provider: HeadingProvider = providerOverManagers()
        val byRotation: MutableMap<ScreenRotation, Double> = mutableMapOf()
        for (rotation: ScreenRotation in ScreenRotation.entries) {
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collect(provider.observeHeading(minChangeDegrees = 0.0, screenRotation = rotation), received)
            sendYaw(40.0)
            byRotation[rotation] = received.last().magneticDegrees
            collection.cancelAndJoin()
        }

        assertAngleClose(40.0, byRotation.getValue(ScreenRotation.NATURAL), 0.01, "natural")
        assertAngleClose(130.0, byRotation.getValue(ScreenRotation.ROTATION_90), 0.01, "90")
        assertAngleClose(220.0, byRotation.getValue(ScreenRotation.ROTATION_180), 0.01, "180")
        assertAngleClose(310.0, byRotation.getValue(ScreenRotation.ROTATION_270), 0.01, "270")
    }

    @Test
    fun `a device tilted 60 degrees still reads the horizontal bearing of the drawn top edge`() = runTest {
        addRotationVector()
        val provider: HeadingProvider = providerOverManagers()
        for (rotation: ScreenRotation in ScreenRotation.entries) {
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collect(provider.observeHeading(minChangeDegrees = 0.0, screenRotation = rotation), received)
            for (yaw: Double in listOf(15.0, 100.0, 250.0)) {
                for (roll: Double in listOf(0.0, 25.0, -25.0)) {
                    val orientation: Quat = Quat.of(yaw, pitchDegrees = 60.0, rollDegrees = roll)
                    val expected: Double = expectedAzimuth(orientation, rotation) ?: continue
                    sendYaw(yaw, pitch = 60.0, roll = roll)
                    assertAngleClose(expected, received.last().magneticDegrees, 0.01, "rotation=$rotation yaw=$yaw tilt=60 roll=$roll")
                }
            }
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `random orientations match the oracle for the rotation vector and for the raw pair`() = runTest {
        // Both pipelines, every rotation, 150 seeded orientations each; the raw pair is fed the gravity and
        // geomagnetic vectors the same orientation would produce.
        val rv: Sensor = addRotationVector()
        rig.add(SensorTypes.ACCELEROMETER)
        rig.add(SensorTypes.MAGNETIC_FIELD)
        check(rv.type == SensorTypes.ROTATION_VECTOR)
        val rotationVectorProvider: HeadingProvider = providerOverManagers(AndroidHeadingSensor.ROTATION_VECTOR)
        val rawProvider: HeadingProvider = providerOverManagers(AndroidHeadingSensor.ACCELEROMETER_MAGNETOMETER)
        for (rotation: ScreenRotation in ScreenRotation.entries) {
            val viaVector: MutableList<Heading> = mutableListOf()
            val viaRaw: MutableList<Heading> = mutableListOf()
            val first: Job = collect(rotationVectorProvider.observeHeading(0.0, screenRotation = rotation), viaVector)
            val second: Job = collect(rawProvider.observeHeading(0.0, screenRotation = rotation), viaRaw)
            val rng = Random(HEADING_TEST_SEED + rotation.ordinal)
            var checked = 0
            repeat(150) { index: Int ->
                val orientation: Quat = Quat.of(rng.nextDouble(0.0, 360.0), rng.nextDouble(-70.0, 70.0), rng.nextDouble(-70.0, 70.0))
                val expected: Double = expectedAzimuth(orientation, rotation) ?: return@repeat
                val note = "rotation=$rotation index=$index" + seedNote(HEADING_TEST_SEED + rotation.ordinal)
                val before: Int = viaVector.size
                rig.shadow.sendSensorEventToListeners(rig.event(SensorTypes.ROTATION_VECTOR, orientation.sensorValues()))
                assertEquals(before + 1, viaVector.size, "no reading for $note")
                assertAngleClose(expected, viaVector.last().magneticDegrees, 0.02, "vector $note")

                val (gravity: FloatArray, geomagnetic: FloatArray) = rawVectors(orientation)
                rig.shadow.sendSensorEventToListeners(rig.event(SensorTypes.ACCELEROMETER, gravity))
                rig.shadow.sendSensorEventToListeners(rig.event(SensorTypes.MAGNETIC_FIELD, geomagnetic))
                assertTrue(viaRaw.isNotEmpty(), "no raw reading for $note")
                assertAngleClose(expected, viaRaw.last().magneticDegrees, 0.02, "raw $note")
                checked++
            }
            assertTrue(checked > 100, "control: most orientations are usable, got $checked" + seedNote())
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test
    fun `every reading is in 0 to 360 and finite including the seam`() = runTest {
        addRotationVector()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 0.0), received)

        for (yaw: Double in listOf(0.0, 360.0, -0.00001, 359.99999, 180.0, -180.0, 1e-9, -1e-9)) sendYaw(yaw)

        assertTrue(received.size == 8)
        assertTrue(received.all { it.magneticDegrees.isFinite() && it.magneticDegrees >= 0.0 && it.magneticDegrees < 360.0 }, received.map { it.magneticDegrees }.toString())
        collection.cancelAndJoin()
    }

    // endregion

    // region true north

    @Test
    fun `declinationAt gives true north without any location permission and registers no position listener`() = runTest {
        addRotationVector()
        val here = GeoCoordinates(21.4225, 39.8262)
        val received: MutableList<Heading> = mutableListOf()

        // No permission was granted: a read of any provider would throw SecurityException.
        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 0.0, declinationAt = here), received)
        sendYaw(100.0)

        assertEquals(100.0 + DECLINATION, received.single().trueDegrees!!, 0.01)
        assertAngleClose(100.0, received.single().magneticDegrees, 0.01, "magnetic is untouched")
        assertEquals(listOf(Triple(here, 0.0, FIXED_TIME)), declinationCalls, "computed once at the requested position, at the injected time")
        assertTrue(shadowOf(locationManager).getRequestLocationUpdateListeners().isEmpty(), "declinationAt needs no position at all")
        collection.cancelAndJoin()
    }

    @Test
    fun `declinationAt wins over a permitted fix`() = runTest {
        grantLocation()
        addRotationVector()
        shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(locationManager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(LocationManager.GPS_PROVIDER, -33.0, 151.0))
        val named = GeoCoordinates(10.0, 20.0)
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = collect(providerOverManagers().observeHeading(0.0, declinationAt = named), received)
        sendYaw(5.0)

        assertEquals(listOf(named), declinationCalls.map { it.first }, "only the requested position is used")
        assertTrue(shadowOf(locationManager).getRequestLocationUpdateListeners().isEmpty(), "and no passive listener either")
        collection.cancelAndJoin()
    }

    @Test
    fun `without declinationAt the newest cached permitted fix of any provider is used`() = runTest {
        grantLocation()
        addRotationVector()
        val shadow = shadowOf(locationManager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(LocationManager.GPS_PROVIDER, 10.0, 20.0, altitude = 100.0, elapsedNanos = 5_000_000_000L))
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(LocationManager.NETWORK_PROVIDER, 30.0, 40.0, altitude = 250.0, elapsedNanos = 9_000_000_000L))
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 0.0), received)
        sendYaw(70.0)

        assertEquals(70.0 + DECLINATION, received.single().trueDegrees!!, 0.01)
        assertEquals(listOf(Triple(GeoCoordinates(30.0, 40.0), 250.0, FIXED_TIME)), declinationCalls, "the newer fix by the monotonic stamp, with its altitude")
        assertEquals(1, shadow.getRequestLocationUpdateListeners().size, "control: the passive listener is registered for later fixes")
        collection.cancelAndJoin()
        assertTrue(shadow.getRequestLocationUpdateListeners().isEmpty(), "and released on cancel")
    }

    @Test
    fun `with neither a position nor a permission true north is null and the compass still works`() = runTest {
        addRotationVector()
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 0.0), received)
        sendYaw(250.0)

        assertNull(received.single().trueDegrees)
        assertAngleClose(250.0, received.single().magneticDegrees, 0.01, "magnetic is always present")
        assertTrue(declinationCalls.isEmpty())
        assertEquals(1, rig.registeredListeners, "control: the sensor listener is registered")
        collection.cancelAndJoin()
    }

    @Test
    fun `the first permitted fix makes true north appear without any movement`() = runTest {
        grantLocation()
        addRotationVector()
        val shadow = shadowOf(locationManager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 45.0), received)
        sendYaw(10.0)
        assertNull(received.single().trueDegrees, "control: no position yet")

        shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER, 48.85, 2.35))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, received.size, "true north appeared although the device did not move")
        assertEquals(10.0 + DECLINATION, received.last().trueDegrees!!, 0.01)
        assertAngleClose(10.0, received.last().magneticDegrees, 0.01, "same heading")
        collection.cancelAndJoin()
    }

    @Test
    fun `a fix with invalid coordinates never reaches the declination`() = runTest {
        grantLocation()
        addRotationVector()
        val shadow = shadowOf(locationManager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 0.0), received)
        sendYaw(10.0)

        shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER, 91.0, 2.0))
        shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER, Double.NaN, 2.0))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, received.size)
        assertTrue(declinationCalls.isEmpty(), "dropped, never thrown and never used")
        assertTrue(collection.isActive)

        // Control: the passive path is alive, a valid fix right behind the invalid ones is used.
        // (The passive registration asks for fixes at least a minute apart, so the control fix is stamped later.)
        shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER, 48.85, 2.35, elapsedNanos = 200_000_000_000L))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(GeoCoordinates(48.85, 2.35)), declinationCalls.map { it.first })
        assertEquals(2, received.size, "true north appeared from the valid fix")
        collection.cancelAndJoin()
    }

    @Test
    fun `a declination model that fails costs true north, not the compass`() = runTest {
        addRotationVector()
        val provider: HeadingProvider = AndroidHeadingProvider(
            ManagerHeadingSensors(rig.manager),
            null,
            AndroidHeadingSensor.AUTO,
            logger,
            { FIXED_TIME },
            { _, _, _ -> throw IllegalStateException("no model") },
        )
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = collect(provider.observeHeading(minChangeDegrees = 0.0, declinationAt = GeoCoordinates(1.0, 2.0)), received)
        sendYaw(33.0)

        assertNull(received.single().trueDegrees)
        assertTrue(logger.messages.any { it.first == LogLevel.WARN }, "the failure is reported: ${logger.messages}")
        collection.cancelAndJoin()
    }

    @Test
    fun `the default declination is the framework geomagnetic field`() {
        val here = GeoCoordinates(21.4225, 39.8262)

        val actual: Double = geomagneticDeclination(here, 300.0, FIXED_TIME)

        val expected: Float = GeomagneticField(21.4225f, 39.8262f, 300f, FIXED_TIME).declination
        assertEquals(expected.toDouble(), actual, 1e-6)
        // At the poles and on the antimeridian the model must still answer a finite number.
        assertTrue(geomagneticDeclination(GeoCoordinates(90.0, 0.0), 0.0, FIXED_TIME).isFinite())
        assertTrue(geomagneticDeclination(GeoCoordinates(-90.0, 180.0), 0.0, FIXED_TIME).isFinite())
    }

    // endregion

    // region accuracy and calibration

    @Test
    fun `accuracy is the rotation vector estimate in degrees and null when absent`() = runTest {
        addRotationVector()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 0.0), received)

        sendYaw(10.0, accuracyRadians = 0.1f)
        sendYaw(20.0, accuracyRadians = -1f)
        sendYaw(30.0)

        assertEquals(0.1f.toDouble() * 180.0 / kotlin.math.PI, received[0].accuracyDegrees!!, 1e-4)
        assertNull(received[1].accuracyDegrees, "-1 means unavailable: never invented")
        assertNull(received[2].accuracyDegrees, "a four value vector has none")
        collection.cancelAndJoin()
    }

    @Test
    fun `the raw accelerometer and magnetometer pipeline reports no accuracy`() = runTest {
        rig.add(SensorTypes.ACCELEROMETER)
        rig.add(SensorTypes.MAGNETIC_FIELD)
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 0.0), received)
        val (gravity: FloatArray, geomagnetic: FloatArray) = rawVectors(Quat.of(80.0))

        rig.shadow.sendSensorEventToListeners(rig.event(SensorTypes.ACCELEROMETER, gravity))
        assertTrue(received.isEmpty(), "needs both vectors before the first reading")
        rig.shadow.sendSensorEventToListeners(rig.event(SensorTypes.MAGNETIC_FIELD, geomagnetic))

        assertEquals(1, received.size)
        assertNull(received.single().accuracyDegrees)
        assertAngleClose(80.0, received.single().magneticDegrees, 0.02, "raw yaw 80")
        collection.cancelAndJoin()
    }

    @Test
    fun `calibration follows the sensor accuracy and a flip is emitted without movement`() = runTest {
        addRotationVector()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 45.0), received)
        sendYaw(100.0)
        val listener: SensorEventListener = rig.shadow.listeners.first()
        val sensor: Sensor = rig.sensor(SensorTypes.ROTATION_VECTOR)

        listener.onAccuracyChanged(sensor, SensorManager.SENSOR_STATUS_UNRELIABLE)
        assertEquals(listOf(false, true), received.map { it.needsCalibration }, "unreliable: needs calibration, no movement needed")
        listener.onAccuracyChanged(sensor, SensorManager.SENSOR_STATUS_ACCURACY_LOW)
        assertEquals(2, received.size, "control: still needs calibration, nothing new")
        listener.onAccuracyChanged(sensor, SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
        assertEquals(listOf(false, true, false), received.map { it.needsCalibration }, "medium accuracy: calibrated again")
        listener.onAccuracyChanged(sensor, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        assertEquals(3, received.size)
        collection.cancelAndJoin()
    }

    @Test
    fun `in the raw pipeline only the magnetometer accuracy counts`() = runTest {
        rig.add(SensorTypes.ACCELEROMETER)
        rig.add(SensorTypes.MAGNETIC_FIELD)
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 45.0), received)
        val (gravity: FloatArray, geomagnetic: FloatArray) = rawVectors(Quat.of(80.0))
        rig.shadow.sendSensorEventToListeners(rig.event(SensorTypes.ACCELEROMETER, gravity))
        rig.shadow.sendSensorEventToListeners(rig.event(SensorTypes.MAGNETIC_FIELD, geomagnetic))
        val listener: SensorEventListener = rig.shadow.listeners.first()

        listener.onAccuracyChanged(rig.sensor(SensorTypes.ACCELEROMETER), SensorManager.SENSOR_STATUS_UNRELIABLE)
        assertEquals(1, received.size, "an unreliable accelerometer says nothing about the compass")
        listener.onAccuracyChanged(null, SensorManager.SENSOR_STATUS_UNRELIABLE)
        assertEquals(1, received.size, "an accuracy report without a sensor cannot be attributed")
        listener.onAccuracyChanged(rig.sensor(SensorTypes.MAGNETIC_FIELD), SensorManager.SENSOR_STATUS_UNRELIABLE)

        assertEquals(listOf(false, true), received.map { it.needsCalibration })
        collection.cancelAndJoin()
    }

    // endregion

    // region minimum change

    @Test
    fun `the first reading comes at once and a turn of 359_5 to 0_5 passes a one degree filter`() = runTest {
        addRotationVector()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 0.9), received)

        sendYaw(359.5)
        assertEquals(1, received.size, "the first reading is emitted at once")
        sendYaw(0.5)
        assertEquals(2, received.size, "359.5 to 0.5 is a one degree turn across north, above the 0.9 filter")
        sendYaw(0.2)
        assertEquals(2, received.size, "0.3 degrees from the last emitted is below it")
        collection.cancelAndJoin()
    }

    @Test
    fun `steps of 0_4 degrees emit once the turn from the last emitted heading reaches the filter`() = runTest {
        addRotationVector()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(providerOverManagers().observeHeading(minChangeDegrees = 1.0), received)

        // 0.4 degree steps with a margin: 10.0, 10.4, 10.8, 11.2 (1.2 from 10.0), 11.6, 12.0, 12.4 (1.2 from 11.2).
        for (index: Int in 0..6) sendYaw(10.0 + index * 0.4)

        assertEquals(3, received.size, "10.0, 11.2 and 12.4 only: ${received.map { it.magneticDegrees }}")
        assertAngleClose(11.2, received[1].magneticDegrees, 0.01, "second")
        assertAngleClose(12.4, received[2].magneticDegrees, 0.01, "third")
        collection.cancelAndJoin()
    }

    // endregion

    // region concurrent collectors and release

    @Test
    fun `two concurrent collectors each get their own remap and their own filter`() = runTest {
        addRotationVector()
        val provider: HeadingProvider = providerOverManagers()
        val natural: MutableList<Heading> = mutableListOf()
        val landscape: MutableList<Heading> = mutableListOf()
        val firstJob: Job = collect(provider.observeHeading(minChangeDegrees = 1.0, screenRotation = ScreenRotation.NATURAL), natural)
        val secondJob: Job = collect(provider.observeHeading(minChangeDegrees = 20.0, screenRotation = ScreenRotation.ROTATION_90), landscape)
        assertEquals(2, rig.registeredListeners, "control: each collection registered its own sensor listener")

        sendYaw(10.0)
        sendYaw(15.0)
        sendYaw(40.0)

        assertEquals(listOf(10.0, 15.0, 40.0), natural.map { Math.round(it.magneticDegrees).toDouble() })
        assertEquals(listOf(100.0, 130.0), landscape.map { Math.round(it.magneticDegrees).toDouble() }, "remapped by 90 degrees; 10 -> 100, 15 is below the 20 degree filter, 40 -> 130")

        firstJob.cancelAndJoin()
        assertEquals(1, rig.registeredListeners, "cancelling one leaves the other registered")
        sendYaw(200.0)
        assertEquals(3, natural.size, "a cancelled collector receives nothing more")
        assertEquals(3, landscape.size, "control: the other collector is still served")
        secondJob.cancelAndJoin()
        assertEquals(0, rig.registeredListeners)
    }

    @Test
    fun `a collection registers a listener and releases it when cancelled`() = runTest {
        addRotationVector()
        val provider: HeadingProvider = providerOverManagers()
        val stream: Flow<Heading> = provider.observeHeading()
        assertEquals(0, rig.registeredListeners, "registered before anyone collected")

        val collection: Job = collect(stream, mutableListOf())
        assertEquals(1, rig.registeredListeners, "control: one listener while collecting")

        collection.cancelAndJoin()
        assertEquals(0, rig.registeredListeners)
        assertTrue(shadowOf(locationManager).getRequestLocationUpdateListeners().isEmpty())
    }

    @Test
    fun `the argument check happens at the call and a zero filter is allowed`() {
        addRotationVector()
        val provider: HeadingProvider = providerOverManagers()

        assertFailsWith<IllegalArgumentException> { provider.observeHeading(minChangeDegrees = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { provider.observeHeading(minChangeDegrees = -1.0) }
        assertFailsWith<IllegalArgumentException> { provider.observeHeading(minChangeDegrees = Double.POSITIVE_INFINITY) }
        provider.observeHeading(minChangeDegrees = 0.0)
        assertEquals(0, rig.registeredListeners, "refusing, or merely building the flow, registers nothing")
    }

    // endregion

    private companion object {
        const val FIXED_TIME: Long = 1_700_000_000_000L
        const val DECLINATION: Double = 5.0
    }
}
