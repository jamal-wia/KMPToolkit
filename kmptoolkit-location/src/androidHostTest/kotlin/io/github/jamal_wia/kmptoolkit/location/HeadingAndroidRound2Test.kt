@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
 * Group loc-heading, round 2: the fixes for breaker round 1 (findings M1, M2, m1, m3), each from the
 * contract and each with a control that proves the thing it relies on existed.
 *
 * - M1 / m3: whatever fails after the sensor listener was registered, with any throwable and with an app
 *   logger that throws, the sensor listener and the position listener are released, and a throwable the
 *   library does not absorb reaches the collector as itself (never replaced by a logger's failure).
 * - M2: `Heading.needsCalibration` follows the platform's word for the heading sensor wherever it says it:
 *   in `onAccuracyChanged` AND in the `accuracy` of each sample; a negative accuracy says nothing.
 * - m1: only the orientation values decide whether a rotation-vector sample is usable; extra values (the
 *   accuracy estimate and anything after it) never cost the heading.
 *
 * Deterministic: synchronous delivery through [ScriptedSensors] / [ScriptedPositions], unconfined collector,
 * the one random sequence uses `Random(HEADING_TEST_SEED + 2)` and prints `seed=` in its messages. No sleeps.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidRound2Test {

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

    private fun yaw(degrees: Double, accuracy: Int): SensorEvent = rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(degrees).sensorValues(), accuracy)

    private fun TestScope.collect(flow: Flow<Heading>, into: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { heading: Heading -> into += heading } }

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

    // region M2: calibration as the platform reports it, per sample

    @Test
    fun `a sample that says reliable clears a calibration need without any onAccuracyChanged`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = 45.0), received)

        sensors.deliver(yaw(100.0, SensorManager.SENSOR_STATUS_UNRELIABLE))
        sensors.deliver(yaw(100.0, SensorManager.SENSOR_STATUS_ACCURACY_LOW))
        assertEquals(listOf(true), received.map { heading: Heading -> heading.needsCalibration }, "control: LOW still needs calibration, nothing new")
        sensors.deliver(yaw(100.0, SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM))

        assertEquals(listOf(true, false), received.map { heading: Heading -> heading.needsCalibration }, "MEDIUM in the sample alone is calibrated again, emitted without movement")
        collection.cancelAndJoin()
    }

    @Test
    fun `a sample without contact says nothing and keeps the calibration state`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = 45.0), received)

        sensors.deliver(yaw(100.0, SensorManager.SENSOR_STATUS_UNRELIABLE))
        assertTrue(received.single().needsCalibration, "control: the need is registered")
        sensors.deliver(yaw(100.0, SensorManager.SENSOR_STATUS_NO_CONTACT))
        assertEquals(1, received.size, "NO_CONTACT is not a statement about calibration: nothing changes")

        // And the other way round: a calibrated sensor stays calibrated.
        sensors.liveListeners.single().onAccuracyChanged(rotationVector, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        assertFalse(received.last().needsCalibration, "control: calibrated through the callback")
        sensors.deliver(yaw(100.0, SensorManager.SENSOR_STATUS_NO_CONTACT))
        assertEquals(2, received.size)
        assertFalse(received.last().needsCalibration)
        collection.cancelAndJoin()
    }

    @Test
    fun `onAccuracyChanged keeps working beside the sample accuracy`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = 45.0), received)

        sensors.deliver(yaw(100.0, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
        sensors.liveListeners.single().onAccuracyChanged(rotationVector, SensorManager.SENSOR_STATUS_UNRELIABLE)
        assertEquals(listOf(false, true), received.map { heading: Heading -> heading.needsCalibration })
        sensors.liveListeners.single().onAccuracyChanged(rotationVector, SensorManager.SENSOR_STATUS_NO_CONTACT)
        assertEquals(2, received.size, "a negative value through the callback says nothing either")
        collection.cancelAndJoin()
    }

    @Test
    fun `in the raw pipeline the magnetometer sample accuracy counts and the accelerometer sample accuracy does not, in both orders`() = runTest {
        val (gravity: FloatArray, geomagnetic: FloatArray) = rawVectors(Quat.of(80.0))
        for (magnetometerFirst: Boolean in listOf(false, true)) {
            val sensors: ScriptedSensors = rawSensors()
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = 45.0), received)
            val acceleration: SensorEvent = rig.event(SensorTypes.ACCELEROMETER, gravity, SensorManager.SENSOR_STATUS_UNRELIABLE)
            val field: SensorEvent = rig.event(SensorTypes.MAGNETIC_FIELD, geomagnetic, SensorManager.SENSOR_STATUS_UNRELIABLE)
            if (magnetometerFirst) {
                sensors.deliver(field)
                sensors.deliver(acceleration)
            } else {
                sensors.deliver(acceleration)
                sensors.deliver(field)
            }

            assertEquals(1, received.size, "magnetometerFirst=$magnetometerFirst: control: the pair gives a reading")
            assertTrue(received.single().needsCalibration, "magnetometerFirst=$magnetometerFirst: the magnetometer sample says UNRELIABLE")
            sensors.deliver(rig.event(SensorTypes.ACCELEROMETER, gravity, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
            assertEquals(1, received.size, "magnetometerFirst=$magnetometerFirst: an accelerometer sample must not clear it")
            sensors.deliver(rig.event(SensorTypes.MAGNETIC_FIELD, geomagnetic, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
            assertEquals(listOf(true, false), received.map { heading: Heading -> heading.needsCalibration }, "magnetometerFirst=$magnetometerFirst")
            collection.cancelAndJoin()
        }
    }

    // endregion

    // region m1: which values decide whether a sample is usable

    @Test
    fun `a non-finite orientation value still drops the sample whatever the accuracy says and extra values never do`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = 0.0), received)
        val good: FloatArray = Quat.of(30.0).sensorValues(0.05f)

        for (index: Int in 0..3) {
            val broken: FloatArray = good.copyOf().also { values: FloatArray -> values[index] = Float.NaN }
            sensors.deliver(rig.event(SensorTypes.ROTATION_VECTOR, broken))
        }
        assertTrue(received.isEmpty(), "a NaN in any of the four orientation values makes the sample unusable")

        val sixValues: FloatArray = good.copyOf(6).also { values: FloatArray -> values[5] = Float.POSITIVE_INFINITY }
        sensors.deliver(rig.event(SensorTypes.ROTATION_VECTOR, sixValues))
        assertEquals(1, received.size, "a non-finite value beyond the orientation and the accuracy is ignored")
        assertEquals(0.05 * 180.0 / PI, received.single().accuracyDegrees!!, 1e-4, "the accuracy before it is still read")
        collection.cancelAndJoin()
    }

    // endregion

    // region M1 / m3: nothing stays registered, whatever fails after the sensor registered

    @Test
    fun `an Error from the passive registration reaches the collector as itself and leaves nothing registered`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val error = NoSuchMethodError("simulated framework mismatch")
        positions.beforeRegister = { _ -> throw error }
        val failure: MutableList<Throwable> = mutableListOf()

        val collection: Job = collectRecordingFailure(provider(sensors, positions, log = ThrowingLogger()).observeHeading(), failure)
        collection.cancelAndJoin()

        assertEquals(1, sensors.registerCalls.load(), "control: the sensor was registered")
        assertEquals(1, positions.registerCalls.load(), "control: the position registration was attempted")
        assertEquals(1, failure.size, "the collector sees the failure")
        assertSame(error, failure.single(), "the original throwable, not a logger's")
        assertEquals(0, sensors.liveRegistrations)
        assertEquals(0, positions.liveListeners.size)
        assertTrue(positions.unregisterCalls.load() >= 1, "the half-registered position listener was undone")
    }

    @Test
    fun `a failing undo rides along as suppressed and still releases the other registration`() = runTest {
        val inner: ScriptedSensors = rotationVectorSensors()
        val sensors = object : HeadingSensors by inner {
            override fun unregister(listener: SensorEventListener) {
                inner.unregister(listener)
                throw IllegalStateException("simulated removal failure")
            }
        }
        val positions = ScriptedPositions()
        val error = NoSuchMethodError("simulated framework mismatch")
        positions.beforeRegister = { _ -> throw error }
        val failure: MutableList<Throwable> = mutableListOf()

        collectRecordingFailure(provider(sensors, positions).observeHeading(), failure).cancelAndJoin()

        assertSame(error, failure.single())
        assertEquals(listOf("simulated removal failure"), error.suppressed.map { suppressed: Throwable -> suppressed.message })
        assertEquals(0, inner.liveRegistrations)
    }

    @Test
    fun `a sensor the platform refuses with a logger that throws leaves nothing registered and the stream silent`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors().apply { refuseType = SensorTypes.ROTATION_VECTOR }
        val positions = ScriptedPositions()
        val failure: MutableList<Throwable> = mutableListOf()

        val collection: Job = collectRecordingFailure(provider(sensors, positions, log = ThrowingLogger()).observeHeading(), failure)

        assertEquals(1, sensors.registerCalls.load(), "control: the registration was attempted")
        assertTrue(collection.isActive, "open and silent, like a device without the sensor")
        assertTrue(failure.isEmpty(), "a throwing logger is not the collector's problem: $failure")
        assertEquals(0, sensors.liveRegistrations)
        assertEquals(0, positions.registerCalls.load(), "no passive listener without a sensor")
        collection.cancelAndJoin()
    }

    @Test
    fun `a throwing logger never stops release when both removals fail`() = runTest {
        val innerSensors: ScriptedSensors = rotationVectorSensors()
        val sensors = object : HeadingSensors by innerSensors {
            override fun unregister(listener: SensorEventListener) {
                innerSensors.unregister(listener)
                throw IllegalStateException("simulated sensor removal failure")
            }
        }
        val innerPositions = ScriptedPositions()
        val positions = object : HeadingPositions by innerPositions {
            override fun unregister(listener: LocationListener) {
                innerPositions.unregister(listener)
                throw IllegalStateException("simulated position removal failure")
            }
        }
        val failure: MutableList<Throwable> = mutableListOf()
        val collection: Job = collectRecordingFailure(provider(sensors, positions, log = ThrowingLogger()).observeHeading(), failure)
        assertEquals(1, innerSensors.liveRegistrations, "control")
        assertEquals(1, innerPositions.liveListeners.size, "control")

        collection.cancelAndJoin()

        assertEquals(0, innerSensors.liveRegistrations)
        assertEquals(0, innerPositions.liveListeners.size)
        assertTrue(failure.isEmpty(), "release never turns into a failure of the collector: $failure")
    }

    @Test
    fun `a declination model that fails with a throwing logger neither fails the start nor the registrations`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val received: MutableList<Heading> = mutableListOf()
        val failure: MutableList<Throwable> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                provider(sensors, log = ThrowingLogger(), declinationOf = { _, _, _ -> error("model unavailable") })
                    .observeHeading(minChangeDegrees = 0.0, declinationAt = GeoCoordinates(21.4, 39.8))
                    .collect { heading: Heading -> received += heading }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                failure += thrown
            }
        }

        assertEquals(1, sensors.liveRegistrations, "control: the collection started (failure=$failure)")
        sensors.deliver(yaw(70.0, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
        assertEquals(1, received.size)
        assertNull(received.single().trueDegrees, "the model failed: true north costs, the compass does not")
        collection.cancelAndJoin()
        assertEquals(0, sensors.liveRegistrations)
        assertTrue(failure.isEmpty())
    }

    @Test
    fun `a seeded run of failures after the sensor registered always ends with nothing registered`() = runTest {
        val seed: Int = HEADING_TEST_SEED + 2
        val random = Random(seed)
        repeat(200) { round: Int ->
            val kind: Int = random.nextInt(5)
            val loggerThrows: Boolean = random.nextBoolean()
            val cancelBeforeUse: Boolean = random.nextBoolean()
            val note = "round=$round kind=$kind loggerThrows=$loggerThrows cancelBeforeUse=$cancelBeforeUse seed=$seed"
            val sensors: ScriptedSensors = rotationVectorSensors()
            val positions = ScriptedPositions()
            when (kind) {
                0 -> Unit
                1 -> positions.registerFailure = SecurityException("no permission")
                2 -> positions.beforeRegister = { _ -> throw NoSuchMethodError("mismatch") }
                3 -> positions.beforeRegister = { _ -> throw IllegalArgumentException("bad") }
                else -> sensors.refuseType = SensorTypes.ROTATION_VECTOR
            }
            val failure: MutableList<Throwable> = mutableListOf()
            val log: Logger = if (loggerThrows) ThrowingLogger() else logger
            val collection: Job = collectRecordingFailure(provider(sensors, positions, log = log).observeHeading(), failure)
            assertEquals(1, sensors.registerCalls.load(), "control: the sensor registration was attempted: $note")
            if (!cancelBeforeUse) sensors.deliver(yaw(10.0, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
            collection.cancelAndJoin()

            assertEquals(0, sensors.liveRegistrations, "sensor listener released: $note")
            assertEquals(0, positions.liveListeners.size, "position listener released: $note")
            // Only an Error is the collector's business; everything else is absorbed.
            assertEquals(if (kind == 2) 1 else 0, failure.size, "failure=$failure: $note")
        }
    }

    // endregion

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
