@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Group loc-heading, round 5: a collector that ends inside an Android heading callback (the audit that answers
 * finding m1 of breaker round 4 for the Android provider: every callback publishes through
 * `HeadingInputs.update`, and a collector that resumes in place, the `Dispatchers.Main.immediate` rule modelled
 * by an unconfined dispatcher, can end the collection, and with it release both listeners, before the callback
 * returns).
 *
 * The audit's result is that no Android callback does anything after its publish, and that a collector cannot end
 * while the registration is still running (it does not collect before `register` returned), so the tests pin
 * that: every site is tried with the three ways a collection ends on a reading (a cancel from the collector,
 * `take`, `first`), and the oracle is exact counters. Both listeners are removed exactly once, nothing is
 * registered again afterwards, nothing is logged, a stale callback of a released listener is inert and reaches
 * nobody, and a new collection registers fresh. The sites: a rotation-vector sample, the two raw samples, an
 * accuracy change, a passive position, and a sample delivered inside the registration itself.
 *
 * Single-threaded and deterministic: synchronous seams, seeded where random, no sleeps, no wall clock.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidRound5Test {

    private val rig = SensorRig(ApplicationProvider.getApplicationContext())
    private val logger = RecordingLogger()

    private val rotationVector: Sensor = rig.add(SensorTypes.ROTATION_VECTOR)
    private val accelerometer: Sensor = rig.add(SensorTypes.ACCELEROMETER)
    private val magnetometer: Sensor = rig.add(SensorTypes.MAGNETIC_FIELD)

    private enum class End { CANCEL, TAKE, FIRST }

    private fun rotationVectorSensors(): ScriptedSensors = ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector))

    private fun rawSensors(): ScriptedSensors =
        ScriptedSensors(mapOf(SensorTypes.ACCELEROMETER to accelerometer, SensorTypes.MAGNETIC_FIELD to magnetometer))

    /** The declination of a fix is its latitude, so every fix with another latitude changes true north. */
    private fun provider(sensors: HeadingSensors, positions: HeadingPositions): HeadingProvider =
        AndroidHeadingProvider(
            sensors,
            positions,
            AndroidHeadingSensor.AUTO,
            logger,
            { FIXED_TIME },
            { coordinates: GeoCoordinates, _: Double, _: Long -> coordinates.latitude },
        )

    private fun yaw(degrees: Double, accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH): SensorEvent =
        rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(degrees).sensorValues(), accuracy)

    private fun accelerationFor(degrees: Double): SensorEvent =
        rig.event(SensorTypes.ACCELEROMETER, rawVectors(Quat.of(degrees)).first)

    private fun magneticFieldFor(degrees: Double): SensorEvent =
        rig.event(SensorTypes.MAGNETIC_FIELD, rawVectors(Quat.of(degrees)).second)

    private fun fix(latitude: Double): Location = Location("gps").apply {
        this.latitude = latitude
        longitude = 2.35
        elapsedRealtimeNanos = 1_000_000_000L
        time = FIXED_TIME
    }

    /** Collects [flow] and ends on the [n]-th heading, the way [how] says. */
    private fun TestScope.collectUntil(flow: Flow<Heading>, n: Int, how: End, received: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            when (how) {
                End.CANCEL -> flow.collect { value: Heading ->
                    received += value
                    if (received.size == n) cancel()
                }
                End.TAKE -> flow.take(n).collect { value: Heading -> received += value }
                End.FIRST -> flow.first { value: Heading ->
                    received += value
                    received.size == n
                }
            }
        }

    /** Both listeners are gone, each was removed exactly once, and nothing was registered a second time. */
    private fun assertReleasedOnce(
        sensors: ScriptedSensors,
        positions: ScriptedPositions,
        sensorsPerCollection: Int,
        context: String,
    ) {
        assertEquals(0, sensors.liveRegistrations, "the sensor listener is removed ($context)")
        assertTrue(positions.liveListeners.isEmpty(), "the position listener is removed ($context)")
        assertEquals(sensorsPerCollection, sensors.registerCalls.load(), "no sensor registration after the release ($context)")
        assertEquals(1, sensors.unregisterCalls.load(), "the sensor listener is removed once ($context)")
        assertEquals(1, positions.registerCalls.load(), "no position registration after the release ($context)")
        assertEquals(1, positions.unregisterCalls.load(), "the position listener is removed once ($context)")
        assertEquals(emptyList(), logger.messages, "nothing was logged ($context)")
    }

    @Test
    fun `a collector that ends on a rotation vector sample releases both listeners once`() = runTest {
        for (how: End in End.entries) {
            val sensors: ScriptedSensors = rotationVectorSensors()
            val positions = ScriptedPositions()
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collectUntil(provider(sensors, positions).observeHeading(minChangeDegrees = 0.0), n = 1, how, received)
            val listener: SensorEventListener = sensors.liveListeners.single()
            assertEquals(1, sensors.liveRegistrations, "control: registered while collecting ($how)")
            assertEquals(1, positions.liveListeners.size, "control: the position listener is registered ($how)")

            listener.onSensorChanged(yaw(10.0))

            assertEquals(1, received.size, "control: the sample reached the collector inside the callback ($how)")
            assertTrue(collection.isCompleted, "control: the collector ended inside the callback ($how)")
            assertReleasedOnce(sensors, positions, sensorsPerCollection = 1, context = "rotation vector, $how")
            listener.onSensorChanged(yaw(20.0))
            assertEquals(1, received.size, "a stale sample reaches nobody ($how)")
            collection.cancelAndJoin()
            assertEquals(1, sensors.unregisterCalls.load(), "nothing is removed twice ($how)")
        }
    }

    @Test
    fun `a collector that ends on the raw sample that completes the azimuth releases both sensor listeners once`() = runTest {
        for (how: End in End.entries) {
            val sensors: ScriptedSensors = rawSensors()
            val positions = ScriptedPositions()
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collectUntil(provider(sensors, positions).observeHeading(minChangeDegrees = 0.0), n = 1, how, received)
            val listener: SensorEventListener = sensors.liveListeners.single()
            assertEquals(2, sensors.liveRegistrations, "control: the accelerometer and the magnetometer are registered ($how)")

            listener.onSensorChanged(accelerationFor(30.0))
            assertTrue(received.isEmpty(), "control: gravity alone is no heading ($how)")
            listener.onSensorChanged(magneticFieldFor(30.0))

            assertEquals(1, received.size, "control: the second raw sample reached the collector inside the callback ($how)")
            assertTrue(collection.isCompleted, "control: the collector ended inside the callback ($how)")
            assertReleasedOnce(sensors, positions, sensorsPerCollection = 2, context = "raw samples, $how")
            listener.onSensorChanged(accelerationFor(40.0))
            listener.onSensorChanged(magneticFieldFor(40.0))
            assertEquals(1, received.size, "stale raw samples reach nobody ($how)")
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `a collector that ends on the heading an accuracy change publishes releases both listeners once`() = runTest {
        for (how: End in End.entries) {
            val sensors: ScriptedSensors = rotationVectorSensors()
            val positions = ScriptedPositions()
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collectUntil(provider(sensors, positions).observeHeading(minChangeDegrees = 90.0), n = 2, how, received)
            val listener: SensorEventListener = sensors.liveListeners.single()
            listener.onSensorChanged(yaw(100.0))
            assertEquals(listOf(false), received.map { it.needsCalibration }, "control: the first heading ($how)")

            listener.onAccuracyChanged(rotationVector, SensorManager.SENSOR_STATUS_ACCURACY_LOW)

            assertEquals(listOf(false, true), received.map { it.needsCalibration }, "control: the calibration change reached the collector inside the callback ($how)")
            assertTrue(collection.isCompleted, "control: the collector ended inside the callback ($how)")
            assertReleasedOnce(sensors, positions, sensorsPerCollection = 1, context = "accuracy change, $how")
            listener.onAccuracyChanged(rotationVector, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
            assertEquals(2, received.size, "a stale accuracy change reaches nobody ($how)")
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `a collector that ends on the heading a passive position publishes releases both listeners once`() = runTest {
        for (how: End in End.entries) {
            val sensors: ScriptedSensors = rotationVectorSensors()
            val positions = ScriptedPositions()
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collectUntil(provider(sensors, positions).observeHeading(minChangeDegrees = 0.0), n = 2, how, received)
            val sensorListener: SensorEventListener = sensors.liveListeners.single()
            val positionListener: android.location.LocationListener = positions.liveListeners.single()
            sensorListener.onSensorChanged(yaw(100.0))
            assertEquals(listOf<Double?>(null), received.map { it.trueDegrees }, "control: no true north without a position ($how)")

            positionListener.onLocationChanged(fix(latitude = 10.0))

            assertEquals(listOf(null, 110), received.map { it.trueDegrees?.roundToInt() }, "control: the position's true north reached the collector inside the callback ($how)")
            assertTrue(collection.isCompleted, "control: the collector ended inside the callback ($how)")
            assertReleasedOnce(sensors, positions, sensorsPerCollection = 1, context = "passive position, $how")
            positionListener.onLocationChanged(fix(latitude = 20.0))
            assertEquals(2, received.size, "a stale position reaches nobody ($how)")
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `a sample delivered inside the registration itself reaches the collector only after the registration and then ends it cleanly`() = runTest {
        for (how: End in End.entries) {
            val sensors: ScriptedSensors = rotationVectorSensors()
            val positions = ScriptedPositions()
            val received: MutableList<Heading> = mutableListOf()
            var receivedInsideRegistration = -1
            sensors.afterRegister = { _: Int ->
                // The platform delivering at once: the listener is registered, the collector is not collecting yet.
                sensors.liveListeners.single().onSensorChanged(yaw(10.0))
                receivedInsideRegistration = received.size
            }

            val collection: Job = collectUntil(provider(sensors, positions).observeHeading(minChangeDegrees = 0.0), n = 1, how, received)

            assertEquals(0, receivedInsideRegistration, "control: nothing reaches a collector that has not started collecting ($how)")
            assertEquals(1, received.size, "control: the sample is served once the registration returned ($how)")
            assertTrue(collection.isCompleted, "control: the collector ended on it ($how)")
            assertReleasedOnce(sensors, positions, sensorsPerCollection = 1, context = "sample inside the registration, $how")
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `a collection started after one that ended inside a callback registers fresh and works`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val heading: HeadingProvider = provider(sensors, positions)
        val first: MutableList<Heading> = mutableListOf()
        collectUntil(heading.observeHeading(minChangeDegrees = 0.0), n = 1, End.CANCEL, first)
        val firstListener: SensorEventListener = sensors.liveListeners.single()
        firstListener.onSensorChanged(yaw(10.0))
        assertEquals(0, sensors.liveRegistrations, "control: the first collection released everything")

        val second: MutableList<Heading> = mutableListOf()
        val collection: Job = collectUntil(heading.observeHeading(minChangeDegrees = 0.0), n = 3, End.CANCEL, second)
        val secondListener: SensorEventListener = sensors.liveListeners.single()
        assertTrue(secondListener !== firstListener, "a fresh listener")
        firstListener.onSensorChanged(yaw(50.0))
        secondListener.onSensorChanged(yaw(60.0))

        assertEquals(listOf(60), second.map { it.magneticDegrees.roundToInt() }, "only the live listener reaches the new collection")
        assertEquals(1, sensors.liveRegistrations)
        collection.cancelAndJoin()
        assertEquals(0, sensors.liveRegistrations)
        assertEquals(2, sensors.unregisterCalls.load(), "one removal per collection")
        assertEquals(2, positions.unregisterCalls.load(), "one removal per collection")
    }

    @Test
    fun `a seeded storm of collectors that end on any reading leaves exactly the live collections registered`() = runTest {
        var endedByCollector = 0
        for (seed: Int in 1..30) {
            val rng = Random(HEADING_TEST_SEED + 56 + seed)
            val raw: Boolean = rng.nextBoolean()
            val sensors: ScriptedSensors = if (raw) rawSensors() else rotationVectorSensors()
            val positions = ScriptedPositions()
            val heading: HeadingProvider = provider(sensors, positions)
            val perCollection: Int = if (raw) 2 else 1
            val jobs: MutableList<Job> = mutableListOf()
            val listeners: MutableList<SensorEventListener> = mutableListOf()
            val locations: MutableList<android.location.LocationListener> = mutableListOf()

            fun startCollection() {
                val before: List<SensorEventListener> = sensors.liveListeners
                val beforePositions: List<android.location.LocationListener> = positions.liveListeners
                jobs += collectUntil(
                    heading.observeHeading(minChangeDegrees = if (rng.nextBoolean()) 0.0 else 3.0),
                    rng.nextInt(1, 7),
                    End.entries[rng.nextInt(3)],
                    mutableListOf(),
                )
                // A collection that ended at once leaves nothing new; otherwise the new listeners are the difference.
                listeners += sensors.liveListeners.filter { it !in before }
                locations += positions.liveListeners.filter { it !in beforePositions }
            }
            repeat(3) { startCollection() }

            repeat(80) { step: Int ->
                val completedBefore: Int = jobs.count { job: Job -> job.isCompleted }
                if (listeners.isNotEmpty()) {
                    val target: SensorEventListener = listeners[rng.nextInt(listeners.size)]
                    when (rng.nextInt(10)) {
                        in 0..4 -> {
                            val degrees: Double = rng.nextInt(0, 360).toDouble()
                            if (raw) {
                                target.onSensorChanged(accelerationFor(degrees))
                                target.onSensorChanged(magneticFieldFor(degrees))
                            } else {
                                target.onSensorChanged(yaw(degrees))
                            }
                        }
                        5, 6 -> target.onAccuracyChanged(
                            if (raw) magnetometer else rotationVector,
                            rng.nextInt(SensorManager.SENSOR_STATUS_UNRELIABLE, SensorManager.SENSOR_STATUS_ACCURACY_HIGH + 1),
                        )
                        7, 8 -> if (locations.isNotEmpty()) locations[rng.nextInt(locations.size)].onLocationChanged(fix(rng.nextInt(-80, 80).toDouble()))
                        else -> if (jobs.size < 6) startCollection()
                    }
                }
                endedByCollector += jobs.count { job: Job -> job.isCompleted } - completedBefore
                val live: Int = jobs.count { job: Job -> job.isActive }
                assertEquals(live * perCollection, sensors.liveRegistrations, "seed=$seed step=$step: sensor listeners of exactly the live collections")
                assertEquals(live, positions.liveListeners.size, "seed=$seed step=$step: position listeners of exactly the live collections")
                assertEquals(jobs.size * perCollection, sensors.registerCalls.load(), "seed=$seed step=$step: one registration per collection, none after a release")
                assertEquals(jobs.size - live, positions.unregisterCalls.load(), "seed=$seed step=$step: every ended collection removed its position listener once")
                assertEquals(emptyList(), logger.messages, "seed=$seed step=$step: nothing logged")
            }

            jobs.forEach { job: Job -> job.cancel() }
            assertEquals(0, sensors.liveRegistrations, "seed=$seed: everything released")
            assertEquals(0, positions.liveListeners.size, "seed=$seed: everything released")
            assertEquals(jobs.size, positions.unregisterCalls.load(), "seed=$seed: one position removal per collection")
        }
        assertTrue(endedByCollector >= 30, "control: many collectors ended on their own reading ($endedByCollector)")
    }

    private companion object {
        const val FIXED_TIME: Long = 1_700_000_000_000L
    }
}
