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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
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
 * Breaker round 2 of group loc-heading (decision D-23, ticket item LOC-11): attacks on the round-2 fixes of the
 * Android heading provider.
 *
 * - The `logger` contract of `createHeadingProvider`: "A logger that throws loses its own event and nothing else:
 *   it never reaches the platform's thread and never stops a registration from being released." A logger can throw
 *   an `Error` as well as an `Exception` (a stub written with `TODO()` throws `NotImplementedError`, a test logger
 *   throws `AssertionError`); `guardLogger` deliberately lets an `Error` through, so every release must be ordered so
 *   that no log call stands between two removals.
 * - Every late-failure path of the registration (passive registration throwing `SecurityException` or an `Error`,
 *   with a logger that throws an `Error`, a collector cancelled while the registration runs) ends with nothing
 *   registered, and the provider works again afterwards.
 * - The calibration state against the real `SensorEvent.accuracy` values (`SENSOR_STATUS_NO_CONTACT` -1,
 *   `UNRELIABLE` 0, `LOW` 1, `MEDIUM` 2, `HIGH` 3, any other negative value): both sources (`onAccuracyChanged` and
 *   the sample) on both pipelines, the newest non-negative word of the relevant sensor wins, accuracy changes
 *   without value changes are emitted, the accelerometer never counts. Checked by a seeded storm against an
 *   independent oracle (`seed=` in every message).
 *
 * Deterministic: synchronous delivery through [ScriptedSensors] / [ScriptedPositions], unconfined collector, one
 * seeded sequence from `Random(HEADING_TEST_SEED + 22)`. No sleeps, no wall clock.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidBreakerR2Test {

    private val rig = SensorRig(ApplicationProvider.getApplicationContext())

    private val rotationVector: Sensor = rig.add(SensorTypes.ROTATION_VECTOR)
    private val accelerometer: Sensor = rig.add(SensorTypes.ACCELEROMETER)
    private val magnetometer: Sensor = rig.add(SensorTypes.MAGNETIC_FIELD)

    private fun rotationVectorSensors(): ScriptedSensors = ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector))

    private fun rawSensors(): ScriptedSensors =
        ScriptedSensors(mapOf(SensorTypes.ACCELEROMETER to accelerometer, SensorTypes.MAGNETIC_FIELD to magnetometer))

    private fun provider(
        sensors: HeadingSensors?,
        positions: HeadingPositions? = null,
        log: Logger = RecordingLogger(),
    ): HeadingProvider = AndroidHeadingProvider(sensors, positions, AndroidHeadingSensor.AUTO, log, { FIXED_TIME }, { _, _, _ -> DECLINATION })

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

    /** An app logger stubbed with `TODO()`: every call throws `NotImplementedError`, an `Error`. */
    private class TodoLogger : Logger {
        override val tag: String = "TodoLogger"

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            throw NotImplementedError("An operation is not implemented: logger at $level")
        }
    }

    // region release: no log call may stand between two removals

    @Test
    fun `a logger that throws an Error while a position removal fails still releases the sensor listener`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val inner = ScriptedPositions()
        val positions = object : HeadingPositions by inner {
            override fun unregister(listener: LocationListener) {
                // The framework call fails before it removed anything (a dead binder, for example).
                throw IllegalStateException("simulated position removal failure")
            }
        }
        val failure: MutableList<Throwable> = mutableListOf()
        val collection: Job = collectRecordingFailure(provider(sensors, positions, log = TodoLogger()).observeHeading(), failure)
        assertEquals(1, sensors.liveRegistrations, "control: the sensor listener is registered")
        assertEquals(1, inner.liveListeners.size, "control: the position listener is registered")

        collection.cancelAndJoin()

        assertEquals(
            0,
            sensors.liveRegistrations,
            "a throwing logger must never stop a registration from being released (createHeadingProvider @param logger); " +
                "collector saw $failure",
        )
    }

    @Test
    fun `a SecurityException from the passive registration with a logger that throws an Error leaves nothing registered and the provider works again`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions().apply { registerFailure = SecurityException("no location permission") }
        val failure: MutableList<Throwable> = mutableListOf()

        collectRecordingFailure(provider(sensors, positions, log = TodoLogger()).observeHeading(), failure).cancelAndJoin()

        assertEquals(1, sensors.registerCalls.load(), "control: the sensor was registered")
        assertEquals(1, positions.registerCalls.load(), "control: the passive registration was attempted")
        assertEquals(0, sensors.liveRegistrations, "nothing stays registered whatever the logger throws (collector saw $failure)")
        assertEquals(0, positions.liveListeners.size)

        // Re-init after the failed collection: a fresh collection with a working position source and logger.
        positions.registerFailure = null
        val received: MutableList<Heading> = mutableListOf()
        val again: Job = collect(provider(sensors, positions).observeHeading(minChangeDegrees = 0.0), received)
        assertEquals(1, sensors.liveRegistrations, "re-init registers again")
        assertEquals(1, positions.liveListeners.size)
        sensors.deliver(yaw(40.0, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
        assertEquals(1, received.size, "the re-initialised collection works")
        again.cancelAndJoin()
        assertEquals(0, sensors.liveRegistrations)
        assertEquals(0, positions.liveListeners.size)
    }

    @Test
    fun `a collector cancelled while its registration runs releases everything once the registration returns`() = runTest {
        for (cancelAt: String in listOf("sensor", "position")) {
            val sensors: ScriptedSensors = rotationVectorSensors()
            val positions = ScriptedPositions()
            var collection: Job? = null
            when (cancelAt) {
                "sensor" -> sensors.afterRegister = { _ -> collection?.cancel() }
                else -> positions.beforeRegister = { _ -> collection?.cancel() }
            }
            val failure: MutableList<Throwable> = mutableListOf()
            collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.LAZY) {
                try {
                    provider(sensors, positions).observeHeading().collect { }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (thrown: Throwable) {
                    failure += thrown
                }
            }
            collection.start()
            collection.join()

            assertEquals(1, sensors.registerCalls.load(), "cancelAt=$cancelAt: control: the sensor registration ran")
            assertEquals(1, positions.registerCalls.load(), "cancelAt=$cancelAt: control: the position registration ran")
            assertTrue(collection.isCancelled, "cancelAt=$cancelAt: the collection ends cancelled")
            assertTrue(failure.isEmpty(), "cancelAt=$cancelAt: a cancel is not a failure: $failure")
            assertEquals(0, sensors.liveRegistrations, "cancelAt=$cancelAt: the sensor listener is released")
            assertEquals(0, positions.liveListeners.size, "cancelAt=$cancelAt: the position listener is released")
        }
    }

    // endregion

    // region calibration against the real SensorEvent.accuracy values

    @Test
    fun `accuracy changes on unchanged samples are emitted at every flip and negative values say nothing`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = 179.0), received)

        val script: List<Int> = listOf(
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH, // first reading: calibrated
            SensorManager.SENSOR_STATUS_UNRELIABLE, // flip to true
            SensorManager.SENSOR_STATUS_ACCURACY_LOW, // still true
            SensorManager.SENSOR_STATUS_NO_CONTACT, // says nothing
            Int.MIN_VALUE, // says nothing
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM, // flip to false
            -2, // says nothing
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH, // still false
            SensorManager.SENSOR_STATUS_ACCURACY_LOW, // flip to true
        )
        for (accuracy: Int in script) sensors.deliver(yaw(200.0, accuracy))

        assertEquals(
            listOf(false, true, false, true),
            received.map { heading: Heading -> heading.needsCalibration },
            "the same orientation every time: only the accuracy flips, each flip once",
        )
        collection.cancelAndJoin()
    }

    @Test
    fun `a seeded storm of accuracies from both sources follows the newest word of the heading sensor`() = runTest {
        val seed: Int = HEADING_TEST_SEED + 22
        val random = Random(seed)
        val accuracies: IntArray = intArrayOf(
            SensorManager.SENSOR_STATUS_NO_CONTACT,
            SensorManager.SENSOR_STATUS_UNRELIABLE,
            SensorManager.SENSOR_STATUS_ACCURACY_LOW,
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            -7,
        )
        val (gravity: FloatArray, geomagnetic: FloatArray) = rawVectors(Quat.of(120.0))

        for (raw: Boolean in listOf(false, true)) {
            val sensors: ScriptedSensors = if (raw) rawSensors() else rotationVectorSensors()
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collect(provider(sensors).observeHeading(minChangeDegrees = 179.0), received)
            // Prime: both raw vectors (or one rotation vector sample) with a reliable accuracy.
            if (raw) {
                sensors.deliver(rig.event(SensorTypes.ACCELEROMETER, gravity, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
                sensors.deliver(rig.event(SensorTypes.MAGNETIC_FIELD, geomagnetic, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
            } else {
                sensors.deliver(yaw(300.0, SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
            }
            var expected = false
            val expectedFlags: MutableList<Boolean> = mutableListOf(false)
            assertEquals(expectedFlags, received.map { heading: Heading -> heading.needsCalibration }, "seed=$seed raw=$raw: control: primed")

            val listener: SensorEventListener = sensors.liveListeners.single()
            for (step: Int in 0 until 2_000) {
                val accuracy: Int = accuracies[random.nextInt(accuracies.size)]
                val throughCallback: Boolean = random.nextBoolean()
                val fromAccelerometer: Boolean = raw && random.nextInt(3) == 0
                if (throughCallback) {
                    val sensor: Sensor = when {
                        !raw -> rotationVector
                        fromAccelerometer -> accelerometer
                        else -> magnetometer
                    }
                    listener.onAccuracyChanged(sensor, accuracy)
                } else {
                    val event: SensorEvent = when {
                        !raw -> yaw(300.0, accuracy)
                        fromAccelerometer -> rig.event(SensorTypes.ACCELEROMETER, gravity, accuracy)
                        else -> rig.event(SensorTypes.MAGNETIC_FIELD, geomagnetic, accuracy)
                    }
                    sensors.deliver(event)
                }
                // The oracle: only the heading sensor counts, a negative value says nothing, 0 and 1 need calibration.
                if (!fromAccelerometer && accuracy >= 0) {
                    val next: Boolean = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
                    if (next != expected) {
                        expected = next
                        expectedFlags += next
                    }
                }
                assertEquals(
                    expectedFlags,
                    received.map { heading: Heading -> heading.needsCalibration },
                    "seed=$seed raw=$raw step=$step accuracy=$accuracy callback=$throughCallback accelerometer=$fromAccelerometer",
                )
            }
            assertTrue(expectedFlags.size > 50, "seed=$seed raw=$raw: control: the storm flipped often (${expectedFlags.size})")
            collection.cancelAndJoin()
            assertEquals(0, sensors.liveRegistrations, "seed=$seed raw=$raw: released")
        }
    }

    // endregion

    private companion object {
        const val FIXED_TIME: Long = 1_700_000_000_000L
        const val DECLINATION: Double = 5.0
    }
}
