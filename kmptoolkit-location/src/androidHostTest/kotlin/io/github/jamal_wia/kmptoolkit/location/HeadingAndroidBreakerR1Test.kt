@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
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

/**
 * Breaker round 1 of group loc-heading (decision D-23): tests that try to break the Android heading provider
 * where the executor's own suite does not look.
 *
 * - The sensor registration must not outlive a failure that happens AFTER it (a non-`Exception` throwable from
 *   the passive registration, a logger that throws on the failure path): plan 6.3 "unregistration", brief
 *   "registration failure leaves no listener", and the `register` contract of `headingStream`.
 * - `needsCalibration` must follow what the platform reports for the sensor (`Heading` KDoc), including the
 *   accuracy a sample carries: the framework (`SystemSensorManager.SensorEventQueue.dispatchSensorEvent`) calls
 *   `onAccuracyChanged` only when the accuracy differs from its cached value, whose initial value is `0`
 *   (`SENSOR_STATUS_UNRELIABLE`), so a sensor that STARTS unreliable never reaches that callback.
 * - An accuracy the platform cannot state (`values[4]` NaN or infinite) is "none" (`null`, never invented), not a
 *   reason to drop the heading itself.
 * - Callbacks never throw into the platform, also when the app's logger throws.
 * - Boundaries: near-gimbal and beyond-vertical poses for every screen rotation, a seeded walk across the
 *   0/360 seam against an independent filter rule, the declination sign, eager argument checks with no
 *   hardware, removal failures on release, and a callback that re-enters from inside the collector.
 *
 * Deterministic: everything runs on the test thread through [ScriptedSensors] / [ScriptedPositions]
 * (synchronous delivery, unconfined collector); the one random walk uses `Random(HEADING_TEST_SEED + 1)` and
 * prints `seed=` in every message. No sleeps, no wall clock.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidBreakerR1Test {

    private val rig = SensorRig(ApplicationProvider.getApplicationContext())
    private val logger = RecordingLogger()

    private val rotationVector: Sensor = rig.add(SensorTypes.ROTATION_VECTOR)
    private val accelerometer: Sensor = rig.add(SensorTypes.ACCELEROMETER)
    private val magnetometer: Sensor = rig.add(SensorTypes.MAGNETIC_FIELD)

    private fun rotationVectorSensors(): ScriptedSensors = ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector))

    private fun rawSensors(): ScriptedSensors =
        ScriptedSensors(mapOf(SensorTypes.ACCELEROMETER to accelerometer, SensorTypes.MAGNETIC_FIELD to magnetometer))

    private fun provider(
        sensors: HeadingSensors?,
        positions: HeadingPositions? = null,
        log: Logger = logger,
        declinationOf: (GeoCoordinates, Double, Long) -> Double = { _, _, _ -> DECLINATION },
    ): HeadingProvider = AndroidHeadingProvider(sensors, positions, AndroidHeadingSensor.AUTO, log, { FIXED_TIME }, declinationOf)

    private fun yaw(degrees: Double, pitch: Double = 0.0, roll: Double = 0.0, accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH): SensorEvent =
        rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(degrees, pitch, roll).sensorValues(), accuracy)

    private fun location(): Location = Location("gps").apply {
        latitude = 48.85
        longitude = 2.35
    }

    private fun TestScope.collect(flow: Flow<Heading>, into: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { heading: Heading -> into += heading } }

    /** Collects and records a terminal failure instead of failing the test scope (cancellation passes through). */
    private fun TestScope.collectRecordingFailure(flow: Flow<Heading>, failure: MutableList<Throwable>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                flow.collect { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                failure += thrown
            }
        }

    private fun assertAngle(expected: Double, actual: Double, tolerance: Double, message: String) {
        assertTrue(abs(angleDifference(expected, actual)) <= tolerance, "$message: expected $expected, got $actual")
    }

    // region B: the sensor registration must not outlive a failure that follows it

    @Test
    fun `a non-Exception throwable from the passive registration leaves no sensor listener registered`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        var sensorLiveAtPositionRegistration = -1
        positions.beforeRegister = { _ ->
            sensorLiveAtPositionRegistration = sensors.liveRegistrations
            // A framework/firmware mismatch surfaces as a LinkageError, not as an Exception.
            throw NoSuchMethodError("simulated framework mismatch in requestLocationUpdates")
        }
        val failure: MutableList<Throwable> = mutableListOf()

        val collection: Job = collectRecordingFailure(provider(sensors, positions).observeHeading(), failure)
        collection.cancelAndJoin()

        assertEquals(1, sensorLiveAtPositionRegistration, "control: the sensor listener was live when the position registration failed")
        assertEquals(1, positions.registerCalls.load(), "control: the position registration was attempted")
        assertEquals(0, sensors.liveRegistrations, "the sensor listener must be released whatever the later failure was (failure=$failure)")
        assertEquals(0, positions.liveListeners.size)
    }

    @Test
    fun `a logger that throws on the passive registration failure path leaves no sensor listener registered`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions().apply { registerFailure = SecurityException("no location permission") }
        val failure: MutableList<Throwable> = mutableListOf()

        val collection: Job = collectRecordingFailure(provider(sensors, positions, log = ThrowingLogger()).observeHeading(), failure)
        val liveWhileCollecting: Int = sensors.liveRegistrations
        collection.cancelAndJoin()

        assertEquals(1, sensors.registerCalls.load(), "control: the sensor was registered")
        assertEquals(1, positions.registerCalls.load(), "control: the position registration was attempted and refused")
        assertTrue(liveWhileCollecting <= 1)
        assertEquals(0, sensors.liveRegistrations, "a throwing logger must not strand the sensor listener (failure=$failure)")
    }

    // endregion

    // region calibration as the platform reports it

    @Test
    fun `a rotation vector that starts unreliable reports needsCalibration without any onAccuracyChanged`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = 0.0), received)

        // The framework never calls onAccuracyChanged for a first accuracy of 0: its cache starts at 0.
        sensors.deliver(yaw(40.0, accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE))

        assertEquals(1, received.size, "control: the sample is served")
        assertTrue(received.single().needsCalibration, "the sample itself says SENSOR_STATUS_UNRELIABLE: needsCalibration must be true")

        // The framework reports the later change to HIGH through onAccuracyChanged; that must clear it.
        sensors.liveListeners.single().onAccuracyChanged(rotationVector, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        sensors.deliver(yaw(40.0, accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
        assertFalse(received.last().needsCalibration, "calibrated again")
        collection.cancelAndJoin()
    }

    @Test
    fun `in the raw pipeline a magnetometer that starts unreliable reports needsCalibration and the accelerometer never does`() = runTest {
        val (gravity: FloatArray, geomagnetic: FloatArray) = rawVectors(Quat.of(80.0))

        // Control first: an unreliable accelerometer says nothing about the compass.
        val calm: ScriptedSensors = rawSensors()
        val calmReceived: MutableList<Heading> = mutableListOf()
        val calmCollection: Job = collect(provider(calm).observeHeading(minChangeDegrees = 0.0), calmReceived)
        calm.deliver(rig.event(SensorTypes.ACCELEROMETER, gravity, SensorManager.SENSOR_STATUS_UNRELIABLE))
        calm.deliver(rig.event(SensorTypes.MAGNETIC_FIELD, geomagnetic, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
        assertFalse(calmReceived.single().needsCalibration, "control: only the magnetometer counts")
        calmCollection.cancelAndJoin()

        val sensors: ScriptedSensors = rawSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = 0.0), received)
        sensors.deliver(rig.event(SensorTypes.ACCELEROMETER, gravity, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
        sensors.deliver(rig.event(SensorTypes.MAGNETIC_FIELD, geomagnetic, SensorManager.SENSOR_STATUS_UNRELIABLE))

        assertEquals(1, received.size, "control: the pair gives a reading")
        assertTrue(received.single().needsCalibration, "the magnetometer sample says SENSOR_STATUS_UNRELIABLE")
        collection.cancelAndJoin()
    }

    // endregion

    // region accuracy the platform cannot state

    @Test
    fun `a rotation vector whose accuracy value is not finite still gives the heading with no accuracy`() = runTest {
        for (badAccuracy: Float in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val sensors: ScriptedSensors = rotationVectorSensors()
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = 0.0), received)

            sensors.deliver(rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(30.0).sensorValues(badAccuracy)))
            sensors.deliver(rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(30.0).sensorValues(0.05f)))

            assertEquals(2, received.size, "values[4]=$badAccuracy is an accuracy the platform cannot state, not an unusable orientation")
            assertAngle(30.0, received[0].magneticDegrees, 0.02, "values[4]=$badAccuracy")
            assertNull(received[0].accuracyDegrees, "never invented")
            assertNotNull(received[1].accuracyDegrees, "control: a finite accuracy is reported")
            collection.cancelAndJoin()
        }
    }

    // endregion

    // region callbacks never throw into the platform

    @Test
    fun `a position callback never throws into the platform even when the logger throws`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val collection: Job = collect(
            provider(sensors, positions, log = ThrowingLogger(), declinationOf = { _, _, _ -> error("model unavailable") }).observeHeading(),
            mutableListOf(),
        )
        assertEquals(1, positions.liveListeners.size, "control: the passive listener is registered")

        val thrown: Throwable? = runCatching { positions.deliver(location()) }.exceptionOrNull()

        assertNull(thrown, "the main looper must never see an exception from the heading's position listener: $thrown")
        collection.cancelAndJoin()
        assertEquals(0, sensors.liveRegistrations)
    }

    // endregion

    // region release when a removal fails

    @Test
    fun `a sensor removal that throws on release still removes the position listener`() = runTest {
        val inner: ScriptedSensors = rotationVectorSensors()
        val sensors = object : HeadingSensors by inner {
            override fun unregister(listener: SensorEventListener) {
                inner.unregister(listener)
                throw IllegalStateException("simulated removal failure")
            }
        }
        val positions = ScriptedPositions()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider(sensors, positions).observeHeading(), received)
        assertEquals(1, inner.liveRegistrations, "control")
        assertEquals(1, positions.liveListeners.size, "control")

        collection.cancelAndJoin()

        assertEquals(0, positions.liveListeners.size, "one failing removal must not strand the other")
        assertTrue(collection.isCancelled)
    }

    @Test
    fun `a position removal that throws on release still removes the sensor listener`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val inner = ScriptedPositions()
        val positions = object : HeadingPositions by inner {
            override fun unregister(listener: LocationListener) {
                inner.unregister(listener)
                throw IllegalStateException("simulated removal failure")
            }
        }
        val collection: Job = collect(provider(sensors, positions).observeHeading(), mutableListOf())
        assertEquals(1, sensors.liveRegistrations, "control")
        assertEquals(1, inner.liveListeners.size, "control")

        collection.cancelAndJoin()

        assertEquals(0, sensors.liveRegistrations, "one failing removal must not strand the other")
        assertEquals(0, inner.liveListeners.size)
    }

    // endregion

    // region re-entrancy

    @Test
    fun `a sample delivered from inside the collector is not lost and nothing deadlocks`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider(sensors).observeHeading(minChangeDegrees = 1.0).collect { heading: Heading ->
                received += heading
                // The platform calls back on the thread the collector is running on, while it is emitting.
                if (received.size == 1) sensors.deliver(yaw(50.0))
            }
        }

        sensors.deliver(yaw(10.0))

        assertEquals(2, received.size, "the re-entrant sample is served after the emission that caused it")
        assertAngle(10.0, received[0].magneticDegrees, 0.02, "first")
        assertAngle(50.0, received[1].magneticDegrees, 0.02, "re-entrant")
        collection.cancelAndJoin()
        assertEquals(0, sensors.liveRegistrations)
    }

    // endregion

    // region boundaries

    @Test
    fun `near-gimbal and beyond-vertical poses read the horizontal bearing of the drawn top edge for every rotation`() = runTest {
        val bearing = 37.0
        // Tilts that keep the drawn top edge (just) off the vertical, including past it (pointing backwards)
        // and face-down devices.
        val tilts: List<Double> = listOf(80.0, 85.0, 89.0, 91.0, 95.0, 120.0, 170.0, 180.0, -85.0, -89.0, -120.0, -180.0)
        for (rotation: ScreenRotation in ScreenRotation.entries) {
            val sensors: ScriptedSensors = rotationVectorSensors()
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = 0.0, screenRotation = rotation), received)
            for (tilt: Double in tilts) {
                // The top edge is device Y for NATURAL/180 (tilt by pitch), device X for 90/270 (tilt by roll).
                val pose: Quat = when (rotation) {
                    ScreenRotation.NATURAL, ScreenRotation.ROTATION_180 -> Quat.of(bearing, pitchDegrees = tilt)
                    ScreenRotation.ROTATION_90, ScreenRotation.ROTATION_270 -> Quat.of(bearing, rollDegrees = tilt)
                }
                val expected: Double = drawnTopEdgeBearing(pose, rotation) ?: continue
                val before: Int = received.size
                sensors.deliver(rig.event(SensorTypes.ROTATION_VECTOR, pose.sensorValues()))
                assertEquals(before + 1, received.size, "rotation=$rotation tilt=$tilt: a defined bearing must be served")
                val actual: Double = received.last().magneticDegrees
                assertTrue(actual >= 0.0 && actual < 360.0, "rotation=$rotation tilt=$tilt: in [0, 360), got $actual")
                assertAngle(expected, actual, 0.1, "rotation=$rotation tilt=$tilt")
            }
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `a seeded walk across the 0 and 360 seam emits exactly when the turn from the last emitted heading reaches the filter`() = runTest {
        val seed: Int = HEADING_TEST_SEED + 1
        val random = Random(seed)
        val filter = 1.0
        val sensors: ScriptedSensors = rotationVectorSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = filter), received)

        var commanded = 359.0
        var lastEmitted: Double? = null
        var crossings = 0
        repeat(3_000) { step: Int ->
            // Stay within 15 degrees of north so the walk keeps crossing the seam.
            val offset: Double = angleDifference(0.0, commanded)
            val drift: Double = if (offset > 15.0) -0.6 else if (offset < -15.0) 0.6 else 0.0
            val next: Double = normalizeDegrees(commanded + drift + random.nextDouble(-1.4, 1.4))
            if ((commanded > 270.0) != (next > 270.0)) crossings++
            commanded = next
            val before: Int = received.size
            sensors.deliver(rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(commanded).sensorValues()))
            val emitted: Boolean = received.size == before + 1
            assertTrue(received.size <= before + 1, "seed=$seed step=$step: at most one reading per sample")

            val reference: Double? = lastEmitted
            val turn: Double = if (reference == null) Double.MAX_VALUE else abs(angleDifference(reference, commanded))
            // Float sensor values: a turn within 0.01 degree of the filter may go either way.
            if (abs(turn - filter) > 0.01) {
                assertEquals(turn >= filter, emitted, "seed=$seed step=$step: turn $turn from last emitted $reference to $commanded")
            }
            if (emitted) {
                val actual: Double = received.last().magneticDegrees
                assertTrue(actual >= 0.0 && actual < 360.0, "seed=$seed step=$step: $actual not in [0, 360)")
                assertAngle(commanded, actual, 0.01, "seed=$seed step=$step")
                lastEmitted = commanded
            }
        }
        assertTrue(crossings >= 20,"control seed=$seed: the walk crossed the seam $crossings times")
        assertTrue(received.size >= 100, "control seed=$seed: ${received.size} readings")
        collection.cancelAndJoin()
    }

    @Test
    fun `the declination is added east-positive and true north wraps across the seam both ways`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val west: MutableList<Heading> = mutableListOf()
        val westCollection: Job = collect(
            provider(sensors, declinationOf = { _, _, _ -> -10.0 }).observeHeading(minChangeDegrees = 0.0, declinationAt = GeoCoordinates(40.0, -74.0)),
            west,
        )
        sensors.deliver(yaw(5.0))
        westCollection.cancelAndJoin()
        assertAngle(355.0, west.single().trueDegrees!!, 0.02, "5 magnetic with 10 W declination")
        assertTrue(west.single().trueDegrees!! < 360.0)

        val east: MutableList<Heading> = mutableListOf()
        val eastCollection: Job = collect(
            provider(sensors, declinationOf = { _, _, _ -> 10.0 }).observeHeading(minChangeDegrees = 0.0, declinationAt = GeoCoordinates(40.0, 74.0)),
            east,
        )
        sensors.deliver(yaw(355.0))
        eastCollection.cancelAndJoin()
        assertAngle(5.0, east.single().trueDegrees!!, 0.02, "355 magnetic with 10 E declination")
        assertTrue(east.single().trueDegrees!! >= 0.0)
    }

    @Test
    fun `the argument check is eager even without hardware and touches nothing`() {
        val positions = ScriptedPositions()
        val noHardware: HeadingProvider = provider(sensors = null, positions = positions)
        assertFalse(noHardware.isAvailable, "control")
        for (bad: Double in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1e-300, -1.0)) {
            assertFailsWith<IllegalArgumentException>("minChangeDegrees=$bad must throw at the call") { noHardware.observeHeading(minChangeDegrees = bad) }
            assertFailsWith<IllegalArgumentException>("minChangeDegrees=$bad must throw at the call") {
                provider(rotationVectorSensors(), positions).observeHeading(minChangeDegrees = bad)
            }
        }
        // -0.0 is not negative: allowed.
        noHardware.observeHeading(minChangeDegrees = -0.0)
        assertEquals(0, positions.newestCalls.load(), "a rejected or uncollected call reads nothing")
        assertEquals(0, positions.registerCalls.load())
    }

    // endregion

    /** The bearing of the drawn top edge from first principles; `null` when that edge is (nearly) vertical. */
    private fun drawnTopEdgeBearing(q: Quat, rotation: ScreenRotation): Double? {
        val r: Array<DoubleArray> = q.matrix()
        val (column: Int, sign: Double) = when (rotation) {
            ScreenRotation.NATURAL -> 1 to 1.0
            ScreenRotation.ROTATION_90 -> 0 to 1.0
            ScreenRotation.ROTATION_180 -> 1 to -1.0
            ScreenRotation.ROTATION_270 -> 0 to -1.0
        }
        val east: Double = sign * r[0][column]
        val north: Double = sign * r[1][column]
        if (hypot(east, north) < 0.01) return null
        return normalizeDegrees(atan2(east, north) * 180.0 / PI)
    }

    /** A logger that throws on every call, as a misbehaving app logger may. */
    private class ThrowingLogger : Logger {
        override val tag: String = "ThrowingLogger"

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            throw IllegalStateException("logger failure at $level")
        }
    }

    private companion object {
        const val FIXED_TIME: Long = 1_700_000_000_000L
        const val DECLINATION: Double = 5.0
    }
}
