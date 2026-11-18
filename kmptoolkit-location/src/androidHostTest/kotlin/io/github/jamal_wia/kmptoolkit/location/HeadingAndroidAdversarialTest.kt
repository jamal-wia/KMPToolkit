@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Attacks on the lifecycle of the Android heading provider (group loc-heading, decision D-23): a cancel that
 * lands while a registration is in flight, a registration that throws, is refused or whose optional half fails,
 * every way a collection can end, re-collection after release, stale callbacks after release, samples the
 * platform should never send, and a seeded storm of collections started, fed and cancelled on real threads.
 *
 * Every registration is counted by a [ScriptedSensors] / [ScriptedPositions] with atomics, and every
 * release proof starts with a control assertion that the thing existed. Latches wait with a hard bound,
 * nothing sleeps, every random draw comes from `Random(HEADING_TEST_SEED)`-derived generators and the seed is
 * in each failure message.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidAdversarialTest {

    private val rig = SensorRig(ApplicationProvider.getApplicationContext())
    private val logger = RecordingLogger()

    private val rotationVector: Sensor = rig.add(SensorTypes.ROTATION_VECTOR)
    private val accelerometer: Sensor = rig.add(SensorTypes.ACCELEROMETER)
    private val magnetometer: Sensor = rig.add(SensorTypes.MAGNETIC_FIELD)

    private fun rotationVectorSensors(): ScriptedSensors = ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector))

    private fun rawSensors(): ScriptedSensors =
        ScriptedSensors(mapOf(SensorTypes.ACCELEROMETER to accelerometer, SensorTypes.MAGNETIC_FIELD to magnetometer))

    private fun provider(
        sensors: ScriptedSensors,
        positions: ScriptedPositions? = null,
        choice: AndroidHeadingSensor = AndroidHeadingSensor.AUTO,
        declinationOf: (GeoCoordinates, Double, Long) -> Double = { _, _, _ -> DECLINATION },
    ): HeadingProvider = AndroidHeadingProvider(sensors, positions, choice, logger, { 0L }, declinationOf)

    private fun yaw(degrees: Double, pitch: Double = 0.0): SensorEvent =
        rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(degrees, pitch).sensorValues())

    private fun location(latitude: Double = 48.85, longitude: Double = 2.35): Location =
        Location("gps").apply {
            this.latitude = latitude
            this.longitude = longitude
        }

    // region cancel racing a registration

    @Test
    fun `a cancel that lands while the sensor registration is in flight leaves nothing registered`() = runBlocking {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val arrived = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        sensors.beforeRegister = { _, _ ->
            arrived.countDown()
            check(proceed.await(20, TimeUnit.SECONDS)) { "the test never released the registration" }
        }
        val collection: Job = launch(Dispatchers.Default) { provider(sensors, positions).observeHeading().collect { } }

        assertTrue(arrived.await(20, TimeUnit.SECONDS), "control: the collection reached the registration")
        collection.cancel()
        proceed.countDown()
        withTimeout(60_000) { collection.join() }

        assertEquals(1, sensors.registerCalls.load(), "control: it registered")
        assertEquals(0, sensors.liveRegistrations, "and the registration was released although the cancel came first")
        assertTrue(sensors.unregisterCalls.load() >= 1)
        assertEquals(positions.registerCalls.load(), positions.unregisterCalls.load(), "no position listener left either")
        assertEquals(0, positions.liveListeners.size)
    }

    @Test
    fun `a cancel that lands while the position registration is in flight releases both listeners`() = runBlocking {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val arrived = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        positions.beforeRegister = { _ ->
            arrived.countDown()
            check(proceed.await(20, TimeUnit.SECONDS)) { "the test never released the registration" }
        }
        val collection: Job = launch(Dispatchers.Default) { provider(sensors, positions).observeHeading().collect { } }

        assertTrue(arrived.await(20, TimeUnit.SECONDS), "control: the collection reached the position registration")
        assertEquals(1, sensors.liveRegistrations, "control: the sensor registration is already live")
        collection.cancel()
        proceed.countDown()
        withTimeout(60_000) { collection.join() }

        assertEquals(0, sensors.liveRegistrations)
        assertEquals(0, positions.liveListeners.size, "the position listener that finished registering after the cancel is released")
        assertEquals(1, positions.registerCalls.load())
    }

    // endregion

    // region registration failures

    @Test
    fun `a second sensor that throws after the first registered fails the collection and leaves nothing`() = runTest {
        val sensors: ScriptedSensors = rawSensors()
        val positions = ScriptedPositions()
        var liveWhenFailing = -1
        sensors.beforeRegister = { call, _ ->
            if (call == 2) {
                liveWhenFailing = sensors.liveRegistrations
                throw SecurityException("registration refused")
            }
        }

        val failure: SecurityException = assertFailsWith { provider(sensors, positions).observeHeading().collect { } }

        assertEquals("registration refused", failure.message)
        assertEquals(1, liveWhenFailing, "control: the accelerometer was registered when the magnetometer threw")
        assertEquals(0, sensors.liveRegistrations, "the accelerometer registration was undone")
        assertTrue(sensors.unregisterCalls.load() >= 1)
        assertEquals(0, positions.registerCalls.load(), "the optional position registration is never reached")
    }

    @Test
    fun `a platform that refuses a sensor leaves the stream open and silent with nothing registered`() = runTest {
        val sensors: ScriptedSensors = rawSensors()
        sensors.refuseType = SensorTypes.MAGNETIC_FIELD
        val positions = ScriptedPositions()
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider(sensors, positions).observeHeading().collect { received += it }
        }

        assertEquals(2, sensors.registerCalls.load(), "control: the accelerometer registered, the magnetometer was refused")
        assertEquals(0, sensors.liveRegistrations, "the accelerometer registration was undone")
        assertTrue(collection.isActive, "like a device without the sensor: open, never completed")
        assertTrue(received.isEmpty())
        assertTrue(logger.messages.any { it.first == LogLevel.WARN }, "the refusal is reported: ${logger.messages}")
        assertEquals(0, positions.registerCalls.load())
        collection.cancelAndJoin()
    }

    @Test
    fun `a failing position registration costs true north only and leaves no position listener`() = runTest {
        for (failure: Exception in listOf(SecurityException("no permission"), IllegalArgumentException("odd oem"))) {
            val sensors: ScriptedSensors = rotationVectorSensors()
            val positions = ScriptedPositions()
            positions.registerFailure = failure
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                provider(sensors, positions).observeHeading(minChangeDegrees = 0.0).collect { received += it }
            }

            sensors.deliver(yaw(30.0))

            assertEquals(1, received.size, "the compass works: $failure")
            assertNull(received.single().trueDegrees)
            assertEquals(1, positions.registerCalls.load(), "control: it did try")
            assertEquals(0, positions.liveListeners.size)
            assertEquals(1, sensors.liveRegistrations, "the sensor registration is unaffected")
            collection.cancelAndJoin()
            assertEquals(0, sensors.liveRegistrations)
        }
    }

    @Test
    fun `declinationAt never touches the position source at all`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider(sensors, positions).observeHeading(declinationAt = GeoCoordinates(0.0, 0.0)).collect { }
        }

        assertEquals(1, sensors.liveRegistrations, "control: the collection is live")
        assertEquals(0, positions.newestCalls.load())
        assertEquals(0, positions.registerCalls.load())
        collection.cancelAndJoin()
    }

    // endregion

    // region every ending releases

    @Test
    fun `cancel, first and a throwing collector each release both registrations`() = runTest {
        suspend fun endingBy(ending: String) {
            val sensors: ScriptedSensors = rotationVectorSensors()
            val positions = ScriptedPositions()
            val stream: Flow<Heading> = provider(sensors, positions).observeHeading(minChangeDegrees = 0.0)
            var thrown: Throwable? = null
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                try {
                    when (ending) {
                        "first" -> stream.first()
                        "throw" -> stream.collect { throw IllegalStateException("collector broke") }
                        else -> stream.collect { }
                    }
                } catch (expected: IllegalStateException) {
                    thrown = expected
                }
            }
            assertEquals(1, sensors.liveRegistrations, "control ($ending): registered")
            assertEquals(1, positions.liveListeners.size, "control ($ending): position listener registered")

            when (ending) {
                "cancel" -> collection.cancelAndJoin()
                else -> {
                    sensors.deliver(yaw(10.0))
                    collection.join()
                }
            }

            assertEquals(0, sensors.liveRegistrations, "$ending: sensor released")
            assertEquals(0, positions.liveListeners.size, "$ending: position listener released")
            assertEquals(1, sensors.unregisterCalls.load(), "$ending: released exactly once")
            if (ending == "throw") assertEquals("collector broke", thrown?.message)
        }
        endingBy("cancel")
        endingBy("first")
        endingBy("throw")
    }

    @Test
    fun `a collection after a released one registers again and starts from scratch`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val stream: Flow<Heading> = provider(sensors, positions).observeHeading(minChangeDegrees = 90.0)

        repeat(3) { round: Int ->
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.collect { received += it } }
            assertEquals(1, sensors.liveRegistrations, "round $round: control, registered")
            sensors.deliver(yaw(10.0))
            sensors.deliver(yaw(11.0))
            assertEquals(1, received.size, "round $round: a fresh filter emits the first reading at once, a stale one would not")
            collection.cancelAndJoin()
            assertEquals(0, sensors.liveRegistrations, "round $round")
        }

        assertEquals(3, sensors.registerCalls.load())
        assertEquals(3, sensors.unregisterCalls.load())
        assertEquals(3, positions.registerCalls.load())
        assertEquals(3, positions.unregisterCalls.load())
    }

    @Test
    fun `two collectors hold two registrations each and cancelling one leaves the other fully registered`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val heading: HeadingProvider = provider(sensors, positions)
        val firstReceived: MutableList<Heading> = mutableListOf()
        val secondReceived: MutableList<Heading> = mutableListOf()
        val first: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            heading.observeHeading(minChangeDegrees = 0.0).collect { firstReceived += it }
        }
        val second: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            heading.observeHeading(minChangeDegrees = 0.0).collect { secondReceived += it }
        }
        assertEquals(2, sensors.liveRegistrations, "control: nothing is shared")
        assertEquals(2, positions.liveListeners.size)

        first.cancelAndJoin()
        assertEquals(1, sensors.liveRegistrations)
        assertEquals(1, positions.liveListeners.size, "the survivor still listens for positions")
        sensors.deliver(yaw(50.0))
        assertEquals(0, firstReceived.size, "a cancelled collector receives nothing")
        assertEquals(1, secondReceived.size, "control: the other one is served")
        positions.deliver(location())
        assertEquals(2, secondReceived.size, "and still learns true north from a passive fix")

        second.cancelAndJoin()
        assertEquals(0, sensors.liveRegistrations)
        assertEquals(0, positions.liveListeners.size)
    }

    @Test
    fun `callbacks that arrive after the release are harmless`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider(sensors, positions).observeHeading(minChangeDegrees = 0.0).collect { received += it }
        }
        sensors.deliver(yaw(20.0))
        val staleSensorListener: SensorEventListener = sensors.liveListeners.single()
        val stalePositionListener: LocationListener = positions.liveListeners.single()
        collection.cancelAndJoin()
        assertEquals(1, received.size, "control: it received before the cancel")

        // The platform may deliver once more to a listener it has just been told to drop.
        staleSensorListener.onSensorChanged(yaw(30.0))
        staleSensorListener.onAccuracyChanged(rotationVector, SensorManager.SENSOR_STATUS_UNRELIABLE)
        stalePositionListener.onLocationChanged(location())
        stalePositionListener.onProviderEnabled("gps")
        stalePositionListener.onProviderDisabled("gps")
        @Suppress("DEPRECATION")
        stalePositionListener.onStatusChanged("gps", 0, Bundle())

        assertEquals(1, received.size)
        assertEquals(0, sensors.liveRegistrations)
        assertEquals(1, sensors.unregisterCalls.load(), "no second release")
    }

    // endregion

    // region samples the platform should not send

    @Test
    fun `unusable sensor samples are dropped without a throw and the next good one is served`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider(sensors).observeHeading(minChangeDegrees = 0.0).collect { received += it }
        }
        val bad: List<FloatArray> = listOf(
            floatArrayOf(0.1f),
            floatArrayOf(0.1f, 0.2f),
            floatArrayOf(Float.NaN, 0f, 0f, 1f),
            floatArrayOf(0f, Float.POSITIVE_INFINITY, 0f, 1f),
            floatArrayOf(0f, 0f, Float.NEGATIVE_INFINITY, 1f, 0f),
        )

        bad.forEach { values: FloatArray -> sensors.deliver(rig.event(SensorTypes.ROTATION_VECTOR, values)) }
        assertTrue(received.isEmpty(), "none of them is a heading: $received")
        sensors.deliver(yaw(75.0))

        assertEquals(1, received.size)
        assertAngleClose(75.0, received.single().magneticDegrees, 0.01, "the good sample")
        collection.cancelAndJoin()
    }

    @Test
    fun `extreme sensor values never produce an unusable heading`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider(sensors).observeHeading(minChangeDegrees = 0.0).collect { received += it }
        }
        val extremes: List<FloatArray> = listOf(
            floatArrayOf(1e30f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 0f),
            floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE),
            floatArrayOf(Float.MIN_VALUE, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 0f, 1f, Float.MAX_VALUE),
        )

        extremes.forEach { values: FloatArray -> sensors.deliver(rig.event(SensorTypes.ROTATION_VECTOR, values)) }

        assertTrue(
            received.all { it.magneticDegrees.isFinite() && it.magneticDegrees >= 0.0 && it.magneticDegrees < 360.0 },
            "whatever was emitted is a valid heading: ${received.map { it.magneticDegrees }}",
        )
        assertTrue(
            received.all { heading: Heading -> heading.accuracyDegrees.let { accuracy: Double? -> accuracy == null || (accuracy.isFinite() && accuracy >= 0.0) } },
            "an accuracy is never invented from garbage",
        )
        collection.cancelAndJoin()
    }

    @Test
    fun `the raw pipeline drops free fall and a vanished field and recovers`() = runTest {
        val sensors: ScriptedSensors = rawSensors()
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider(sensors).observeHeading(minChangeDegrees = 0.0).collect { received += it }
        }

        sensors.deliver(rig.event(SensorTypes.ACCELEROMETER, floatArrayOf(0f, 0f, 0f)))
        sensors.deliver(rig.event(SensorTypes.MAGNETIC_FIELD, floatArrayOf(0f, 0f, 0f)))
        sensors.deliver(rig.event(SensorTypes.MAGNETIC_FIELD, floatArrayOf(Float.NaN, 1f, 1f)))
        sensors.deliver(rig.event(SensorTypes.ACCELEROMETER, floatArrayOf(1f)))
        assertTrue(received.isEmpty(), "free fall and no field give no rotation matrix: $received")

        val (gravity: FloatArray, geomagnetic: FloatArray) = rawVectors(Quat.of(200.0))
        sensors.deliver(rig.event(SensorTypes.ACCELEROMETER, gravity))
        sensors.deliver(rig.event(SensorTypes.MAGNETIC_FIELD, geomagnetic))

        assertEquals(1, received.size)
        assertAngleClose(200.0, received.single().magneticDegrees, 0.02, "recovered")
        collection.cancelAndJoin()
    }

    @Test
    fun `a position callback with a failing model or an invalid fix never throws into the platform`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        var modelCalls = 0
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider(sensors, positions, declinationOf = { _, _, _ ->
                modelCalls++
                if (modelCalls == 1) throw IllegalStateException("model broke") else DECLINATION
            }).observeHeading(minChangeDegrees = 0.0).collect { received += it }
        }
        sensors.deliver(yaw(10.0))

        positions.deliver(location(latitude = 12.0))
        positions.deliver(location(latitude = 91.0))
        positions.deliver(location(latitude = Double.NaN))
        assertEquals(1, received.size, "a failed model and invalid fixes change nothing")
        assertNull(received.single().trueDegrees)
        positions.deliver(location(latitude = 13.0))

        assertEquals(2, received.size, "the next good fix gives true north")
        assertEquals(10.0 + DECLINATION, received.last().trueDegrees!!, 0.01)
        collection.cancelAndJoin()
    }

    @Test
    fun `boundary orientations give a heading in 0 to 360 for every screen rotation`() = runTest {
        val sensors: ScriptedSensors = rotationVectorSensors()
        for (rotation: ScreenRotation in ScreenRotation.entries) {
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                provider(sensors).observeHeading(minChangeDegrees = 0.0, screenRotation = rotation).collect { received += it }
            }
            // The seam, the quarter turns, a device standing upright (its axis has no horizontal direction)
            // and one lying on its face.
            val orientations: List<Quat> = listOf(0.0, 90.0, 180.0, 270.0, 359.9999, -0.0001).map { Quat.of(it) } +
                listOf(Quat.of(40.0, 90.0), Quat.of(40.0, -90.0), Quat.of(40.0, 180.0))
            orientations.forEach { orientation: Quat ->
                sensors.deliver(rig.event(SensorTypes.ROTATION_VECTOR, orientation.sensorValues()))
            }

            assertTrue(
                received.all { it.magneticDegrees.isFinite() && it.magneticDegrees >= 0.0 && it.magneticDegrees < 360.0 && it.magneticDegrees.toRawBits() >= 0 },
                "rotation=$rotation ${received.map { it.magneticDegrees }}",
            )
            assertTrue(received.size >= 6, "control: the ordinary orientations were served, got ${received.size}")
            collection.cancelAndJoin()
        }
    }

    // endregion

    // region storm

    @Test
    fun `a storm of collections started fed and cancelled on real threads releases every registration`() = runBlocking {
        withTimeout(120.seconds) {
            val seed: Int = HEADING_TEST_SEED
            val collectors = 16
            val feeders = 4
            val stepsPerFeeder = 1_500
            val sensors: ScriptedSensors = rotationVectorSensors()
            val positions = ScriptedPositions()
            val heading: HeadingProvider = provider(sensors, positions)

            // Events are built once: building is the slow part and the listeners only read them.
            val rngEvents = Random(seed.toLong())
            val events: List<SensorEvent> = List(64) {
                rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(rngEvents.nextDouble(0.0, 360.0), rngEvents.nextDouble(-60.0, 60.0)).sensorValues())
            }
            val fixes: List<Location> = List(16) { index: Int -> location(latitude = -60.0 + index * 7.0, longitude = index * 20.0) }

            val registered = CountDownLatch(collectors)
            sensors.afterRegister = { _ -> registered.countDown() }
            val problems: AtomicReference<List<String>> = AtomicReference(emptyList())
            fun report(problem: String) {
                while (true) {
                    val before: List<String> = problems.load()
                    if (problems.compareAndSet(before, before + problem)) return
                }
            }
            val emissions: List<AtomicInt> = List(collectors) { AtomicInt(0) }
            val atJoin: List<AtomicInt> = List(collectors) { AtomicInt(-1) }

            val jobs: List<Job> = List(collectors) { index: Int ->
                launch(Dispatchers.Default) {
                    heading.observeHeading(minChangeDegrees = 0.0).collect { reading: Heading ->
                        emissions[index].incrementAndFetch()
                        val ok: Boolean = reading.magneticDegrees.isFinite() && reading.magneticDegrees >= 0.0 && reading.magneticDegrees < 360.0
                        if (!ok) report("seed=$seed collector=$index emitted ${reading.magneticDegrees}")
                        val trueDegrees: Double? = reading.trueDegrees
                        if (trueDegrees != null) {
                            val expected: Double = normalizeDegrees(reading.magneticDegrees + DECLINATION)
                            if (kotlin.math.abs(angleDifference(expected, trueDegrees)) > 1e-9) report("seed=$seed collector=$index true=$trueDegrees magnetic=${reading.magneticDegrees}")
                        }
                    }
                }
            }
            check(registered.await(60, TimeUnit.SECONDS)) { "seed=$seed the collectors did not register" }
            assertEquals(collectors, sensors.liveRegistrations, "seed=$seed control: every collector registered")

            val feederJobs: List<Job> = List(feeders) { worker: Int ->
                launch(Dispatchers.Default) {
                    val rng = Random(seed.toLong() * 1000 + worker)
                    repeat(stepsPerFeeder) { step: Int ->
                        sensors.deliver(events[rng.nextInt(events.size)])
                        if (step % 7 == 0) positions.deliver(fixes[rng.nextInt(fixes.size)])
                        // Cancel a random collector now and then, while events are in flight to it.
                        if (worker == 0 && step % 150 == 149) {
                            val victim: Int = rng.nextInt(collectors)
                            jobs[victim].cancelAndJoin()
                            atJoin[victim].store(emissions[victim].load())
                        }
                    }
                }
            }
            feederJobs.joinAll()
            jobs.forEach { it.cancelAndJoin() }

            assertTrue(problems.load().isEmpty(), "seed=$seed ${problems.load().take(5)}")
            assertTrue(emissions.sumOf { it.load() } > 0, "seed=$seed control: collectors were served")
            atJoin.forEachIndexed { index: Int, count: AtomicInt ->
                if (count.load() >= 0) assertEquals(count.load(), emissions[index].load(), "seed=$seed collector $index emitted after cancelAndJoin returned")
            }
            assertEquals(0, sensors.liveRegistrations, "seed=$seed no sensor registration left")
            assertEquals(sensors.registerCalls.load(), sensors.unregisterCalls.load(), "seed=$seed every collection released its sensors once")
            assertEquals(0, positions.liveListeners.size, "seed=$seed no position listener left")
            assertEquals(positions.registerCalls.load(), positions.unregisterCalls.load(), "seed=$seed every collection released its position listener once")
        }
    }

    // endregion

    private companion object {
        const val DECLINATION: Double = 5.0
    }
}
