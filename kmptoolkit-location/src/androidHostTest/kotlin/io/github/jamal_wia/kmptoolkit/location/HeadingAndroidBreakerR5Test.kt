@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Breaker round 5 of group loc-heading (LOC-11, decision D-23): the Android heading provider, attacked with an
 * exhaustive model instead of one site at a time.
 *
 * Every point where a value reaches the collector (a rotation-vector sample, the magnetometer sample that completes
 * the raw pipeline's azimuth, an accuracy change, a passive position, a sample delivered inside the sensor
 * registration, a position delivered inside the position registration) x every collector behaviour on that value
 * (continue, cancel itself, `first`, throw, start a fresh collection and cancel itself) x every platform or app fault
 * (none; a sensor registration refused, throwing an exception or an `Error`; a position registration throwing an
 * exception or an `Error`; a sensor or position removal throwing an exception or an `Error`; the app's logger throwing
 * an exception or an `Error` on every call). The collector runs unconfined, so a value published inside a callback
 * runs it to its end, and its release, before the callback returns.
 *
 * Invariants from the provider and stream KDoc: a per-listener ledger in which every successful registration is
 * removed by the end and nothing is registered for a collection that has ended (so no listener leaks); no platform
 * call is made for a released collection; an exception never escapes into a platform callback; every injected
 * `Error` and the collector's own failure surface; a fresh collection started from inside the callback gets its own
 * listener and is released on its own.
 *
 * Deterministic: synchronous seams, exhaustive loops, no randomness, no sleeps.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidBreakerR5Test {

    private val rig = SensorRig(ApplicationProvider.getApplicationContext())
    private val rotationVector: Sensor = rig.add(SensorTypes.ROTATION_VECTOR)
    private val accelerometer: Sensor = rig.add(SensorTypes.ACCELEROMETER)
    private val magnetometer: Sensor = rig.add(SensorTypes.MAGNETIC_FIELD)

    private enum class Point { ROTATION, RAW, ACCURACY, POSITION, IN_SENSOR_REGISTER, IN_POSITION_REGISTER }

    private enum class Behaviour { CONTINUE, CANCEL, FIRST, THROW, RECOLLECT }

    private enum class Fault {
        NONE, SENSOR_REFUSED, SENSOR_REGISTER_EXCEPTION, SENSOR_REGISTER_ERROR, POSITION_REGISTER_EXCEPTION,
        POSITION_REGISTER_ERROR, SENSOR_REMOVE_EXCEPTION, SENSOR_REMOVE_ERROR, POSITION_REMOVE_EXCEPTION,
        POSITION_REMOVE_ERROR, LOGGER_EXCEPTION, LOGGER_ERROR,
    }

    /** One record per listener instance: how often it was registered and removed, and calls after it ended. */
    private class Ledger {
        val registered: MutableMap<Any, Int> = LinkedHashMap()
        val removed: MutableMap<Any, Int> = LinkedHashMap()
        val live: MutableSet<Any> = LinkedHashSet()
        val problems: MutableList<String> = mutableListOf()
        val faultsFired: MutableList<Throwable> = mutableListOf()

        fun register(listener: Any) {
            if ((removed[listener] ?: 0) > 0) problems += "registered again after its removal: $listener"
            registered[listener] = (registered[listener] ?: 0) + 1
            live += listener
        }

        fun remove(listener: Any) {
            removed[listener] = (removed[listener] ?: 0) + 1
            live -= listener
        }
    }

    private class LedgerSensors(
        private val ledger: Ledger,
        private val sensors: Map<Int, Sensor>,
        private val fault: Fault,
        private val injected: () -> Throwable,
        private val inside: (SensorEventListener, Sensor) -> Unit,
    ) : HeadingSensors {
        private var registerCalls = 0
        private var removeCalls = 0
        val byListener: MutableMap<SensorEventListener, MutableList<Sensor>> = LinkedHashMap()

        override fun defaultSensor(type: Int): Sensor? = sensors[type]

        override fun register(listener: SensorEventListener, sensor: Sensor): Boolean {
            registerCalls++
            if (registerCalls == 1) {
                when (fault) {
                    Fault.SENSOR_REFUSED -> return false
                    Fault.SENSOR_REGISTER_EXCEPTION, Fault.SENSOR_REGISTER_ERROR -> throw injected().also { ledger.faultsFired += it }
                    else -> Unit
                }
            }
            ledger.register(listener)
            byListener.getOrPut(listener) { mutableListOf() } += sensor
            inside(listener, sensor)
            return true
        }

        override fun unregister(listener: SensorEventListener) {
            removeCalls++
            ledger.remove(listener)
            byListener.remove(listener)
            if (removeCalls == 1 && (fault == Fault.SENSOR_REMOVE_EXCEPTION || fault == Fault.SENSOR_REMOVE_ERROR)) {
                throw injected().also { ledger.faultsFired += it }
            }
        }
    }

    private class LedgerPositions(
        private val ledger: Ledger,
        private val fault: Fault,
        private val injected: () -> Throwable,
        private val inside: (LocationListener) -> Unit,
    ) : HeadingPositions {
        private var registerCalls = 0
        private var removeCalls = 0
        val liveListeners: MutableList<LocationListener> = mutableListOf()

        override fun newestPosition(): DeclinationPosition? = null

        override fun register(listener: LocationListener) {
            registerCalls++
            if (registerCalls == 1 && (fault == Fault.POSITION_REGISTER_EXCEPTION || fault == Fault.POSITION_REGISTER_ERROR)) {
                throw injected().also { ledger.faultsFired += it }
            }
            ledger.register(listener)
            liveListeners += listener
            inside(listener)
        }

        override fun unregister(listener: LocationListener) {
            removeCalls++
            ledger.remove(listener)
            liveListeners -= listener
            if (removeCalls == 1 && (fault == Fault.POSITION_REMOVE_EXCEPTION || fault == Fault.POSITION_REMOVE_ERROR)) {
                throw injected().also { ledger.faultsFired += it }
            }
        }
    }

    private class FaultLogger(private val fault: Fault) : Logger {
        val handed: MutableList<Throwable> = mutableListOf()
        val thrown: MutableList<Throwable> = mutableListOf()
        override val tag: String = "FaultLogger"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            message()
            if (throwable != null) handed += throwable
            when (fault) {
                Fault.LOGGER_EXCEPTION -> throw IllegalStateException("logger exception at $level").also { thrown += it }
                Fault.LOGGER_ERROR -> throw AssertionError("logger Error at $level").also { thrown += it }
                else -> Unit
            }
        }
    }

    private fun Throwable.graph(seen: MutableSet<Throwable> = mutableSetOf()): Set<Throwable> {
        if (!seen.add(this)) return seen
        cause?.graph(seen)
        suppressedExceptions.forEach { suppressed: Throwable -> suppressed.graph(seen) }
        return seen
    }

    private fun fix(latitude: Double): Location = Location("gps").apply {
        this.latitude = latitude
        longitude = 37.6
    }

    @Test
    fun `every emission point collector behaviour and fault keeps the listener ledger`() = runTest {
        var endedInsideCallback = 0
        var faultsFired = 0
        var scenarios = 0
        for (point: Point in Point.entries) {
            for (behaviour: Behaviour in Behaviour.entries) {
                for (fault: Fault in Fault.entries) {
                    scenarios++
                    val combination = "point=$point behaviour=$behaviour fault=$fault"
                    val ledger = Ledger()
                    val error: Boolean = fault.name.endsWith("ERROR")
                    var faultCount = 0
                    val injected: () -> Throwable = {
                        faultCount++
                        if (error) AssertionError("injected Error $fault #$faultCount") else IllegalStateException("injected exception $fault #$faultCount")
                    }
                    val raw: Boolean = point == Point.RAW
                    val sensorMap: Map<Int, Sensor> = if (raw) {
                        mapOf(SensorTypes.ACCELEROMETER to accelerometer, SensorTypes.MAGNETIC_FIELD to magnetometer)
                    } else {
                        mapOf(SensorTypes.ROTATION_VECTOR to rotationVector)
                    }
                    var insideCallback = 0
                    var armed = false
                    var acted = false
                    val sensors = LedgerSensors(ledger, sensorMap, fault, injected) { listener: SensorEventListener, sensor: Sensor ->
                        if (point == Point.IN_SENSOR_REGISTER && sensor.type == SensorTypes.ROTATION_VECTOR) {
                            listener.onSensorChanged(rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(222.0).sensorValues(0.05f)))
                        }
                    }
                    val positions = LedgerPositions(ledger, fault, injected) { listener: LocationListener ->
                        if (point == Point.IN_POSITION_REGISTER) listener.onLocationChanged(fix(55.7))
                    }
                    val logger = FaultLogger(fault)
                    val provider: HeadingProvider = AndroidHeadingProvider(
                        sensors,
                        positions,
                        if (raw) AndroidHeadingSensor.ACCELEROMETER_MAGNETOMETER else AndroidHeadingSensor.ROTATION_VECTOR,
                        logger,
                        { 1_700_000_000_000L },
                        { _, _, _ -> 7.0 },
                    )
                    val collectorFailure = IllegalStateException("the collector's own failure")
                    val surfaced: MutableList<Throwable> = mutableListOf()
                    val escaped: MutableList<Throwable> = mutableListOf()
                    val received: MutableList<Heading> = mutableListOf()
                    val extra: MutableList<Job> = mutableListOf()

                    fun callback(block: () -> Unit) {
                        insideCallback++
                        try {
                            block()
                        } catch (thrown: Throwable) {
                            escaped += thrown
                        } finally {
                            insideCallback--
                        }
                    }

                    fun endedHere() {
                        acted = true
                        if (insideCallback > 0) endedInsideCallback++
                    }

                    if (point == Point.IN_SENSOR_REGISTER || point == Point.IN_POSITION_REGISTER) armed = true
                    val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        try {
                            val flow = provider.observeHeading(minChangeDegrees = 0.0)
                            if (behaviour == Behaviour.FIRST) {
                                flow.first { heading: Heading ->
                                    received += heading
                                    val now: Boolean = armed && !acted
                                    if (now) endedHere()
                                    now
                                }
                            } else {
                                flow.collect { heading: Heading ->
                                    received += heading
                                    if (armed && !acted) {
                                        endedHere()
                                        when (behaviour) {
                                            Behaviour.CANCEL -> cancel()
                                            Behaviour.THROW -> throw collectorFailure
                                            Behaviour.RECOLLECT -> {
                                                extra += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                                                    provider.observeHeading(minChangeDegrees = 0.0).collect { }
                                                }
                                                cancel()
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                        } catch (thrown: Throwable) {
                            surfaced += thrown
                            if (thrown is CancellationException) throw thrown
                        }
                    }

                    fun deliverRotation(yaw: Double) {
                        sensors.byListener.entries.toList()
                            .filter { (_, list: MutableList<Sensor>) -> list.any { it.type == SensorTypes.ROTATION_VECTOR } }
                            .forEach { (listener: SensorEventListener, _) ->
                                callback { listener.onSensorChanged(rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(yaw).sensorValues(0.05f))) }
                            }
                    }

                    fun deliverRaw(yaw: Double) {
                        val (gravity: FloatArray, field: FloatArray) = rawVectors(Quat.of(yaw))
                        sensors.byListener.keys.toList().forEach { listener: SensorEventListener ->
                            callback { listener.onSensorChanged(rig.event(SensorTypes.ACCELEROMETER, gravity)) }
                            callback { listener.onSensorChanged(rig.event(SensorTypes.MAGNETIC_FIELD, field)) }
                        }
                    }

                    val firstListeners: List<Any> = ledger.live.toList()
                    if (raw) deliverRaw(10.0) else deliverRotation(10.0)
                    armed = true
                    when (point) {
                        Point.ROTATION -> deliverRotation(50.0)
                        Point.RAW -> deliverRaw(50.0)
                        Point.ACCURACY -> sensors.byListener.keys.toList().forEach { listener: SensorEventListener ->
                            callback { listener.onAccuracyChanged(rotationVector, SensorManager.SENSOR_STATUS_UNRELIABLE) }
                        }
                        Point.POSITION -> positions.liveListeners.toList().forEach { listener: LocationListener ->
                            callback { listener.onLocationChanged(fix(55.7)) }
                        }
                        else -> Unit
                    }
                    // Late callbacks to every listener that ever was registered, removed or not.
                    for (listener: Any in ledger.registered.keys.toList()) {
                        when (listener) {
                            is SensorEventListener -> callback {
                                listener.onSensorChanged(rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(90.0).sensorValues(0.05f)))
                                listener.onAccuracyChanged(rotationVector, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
                            }
                            is LocationListener -> callback { listener.onLocationChanged(fix(40.0)) }
                        }
                    }
                    val liveBeforeEnd: Set<Any> = ledger.live.toSet()
                    collection.cancel()
                    extra.forEach { job: Job -> job.cancel() }
                    testScheduler.runCurrent()
                    if (ledger.faultsFired.isNotEmpty() || logger.thrown.isNotEmpty()) faultsFired++

                    // The ledger: everything registered is removed, nothing registered after its removal.
                    assertEquals(emptyList(), ledger.problems, "$combination")
                    assertEquals(emptySet(), ledger.live, "a listener stays registered; $combination; surfaced=$surfaced escaped=$escaped")
                    for ((listener: Any, count: Int) in ledger.registered) {
                        assertTrue((ledger.removed[listener] ?: 0) >= 1, "registered $count time(s), never removed: $listener; $combination")
                    }
                    if (fault == Fault.NONE) {
                        assertTrue(firstListeners.isNotEmpty() || liveBeforeEnd.isNotEmpty() || acted, "control: something was registered; $combination")
                        assertTrue(acted, "control: the collector got the value of the point; $combination; received=$received")
                        for ((listener: Any, count: Int) in ledger.removed) {
                            assertEquals(1, count, "a listener of a clean collection is removed exactly once: $listener; $combination")
                        }
                    }
                    if (behaviour == Behaviour.RECOLLECT && acted && fault == Fault.NONE) {
                        assertTrue(
                            ledger.registered.keys.count { listener: Any -> listener is SensorEventListener } >= 2,
                            "control: the fresh collection registered its own listener; $combination",
                        )
                    }

                    // Throwables: no exception escapes into a platform callback; every Error and the collector's
                    // own failure surface; a platform exception is logged or reachable.
                    assertEquals(emptyList(), escaped.filter { thrown: Throwable -> thrown !is Error }, "an exception escaped into a callback; $combination")
                    val reachable: Set<Throwable> = (surfaced + escaped).flatMap { thrown: Throwable -> thrown.graph() }.toSet()
                    val removalFault: Boolean = fault in setOf(
                        Fault.SENSOR_REMOVE_EXCEPTION, Fault.SENSOR_REMOVE_ERROR, Fault.POSITION_REMOVE_EXCEPTION, Fault.POSITION_REMOVE_ERROR,
                    )
                    for (thrown: Throwable in ledger.faultsFired + logger.thrown) {
                        if (thrown is Error && removalFault) {
                            // The release handle's contract (pinned by breaker round 4): a removal failure, Error
                            // included, is handed to the logger once both removals ran, or rides along.
                            assertTrue(
                                thrown in reachable || logger.handed.any { handed: Throwable -> handed === thrown },
                                "a removal Error is neither logged nor reachable: $thrown; $combination",
                            )
                        } else if (thrown is Error) {
                            assertTrue(thrown in reachable, "an Error is never absorbed: $thrown; $combination; surfaced=$surfaced escaped=$escaped")
                        } else if (thrown !in logger.thrown) {
                            assertTrue(
                                thrown in reachable || logger.handed.any { handed: Throwable -> handed === thrown } || fault == Fault.POSITION_REGISTER_EXCEPTION,
                                "a platform exception is neither logged nor reachable: $thrown; $combination",
                            )
                        }
                    }
                    if (behaviour == Behaviour.THROW && acted) {
                        assertTrue(collectorFailure in reachable, "the collector's own failure is lost; $combination; surfaced=$surfaced")
                    }
                }
            }
        }
        assertTrue(endedInsideCallback >= 60, "control: collectors ended inside a callback ($endedInsideCallback of $scenarios)")
        assertTrue(faultsFired >= 150, "control: injected faults fired ($faultsFired of $scenarios)")
    }

    /**
     * Two faults on one path: the registration fails and so does the removal that undoes it. The release handle
     * reports every removal failure; the undo of a registration is a release too, and the listener it could not
     * remove may still be registered, so its failure must not vanish.
     */
    @Test
    fun `a removal that fails while undoing a refused sensor or a failed position registration is not lost`() = runTest {
        val lost: MutableList<String> = mutableListOf()
        for (sensorPath: Boolean in listOf(true, false)) {
            for (error: Boolean in listOf(false, true)) {
                val combination = "path=${if (sensorPath) "refused raw sensor" else "failed position registration"} error=$error"
                val removalFailure: Throwable =
                    if (error) AssertionError("undo removal Error") else IllegalArgumentException("undo removal exception")
                val sensors = object : HeadingSensors {
                    var registered = 0
                    override fun defaultSensor(type: Int): Sensor? = when (type) {
                        SensorTypes.ACCELEROMETER -> accelerometer
                        SensorTypes.MAGNETIC_FIELD -> magnetometer
                        else -> null
                    }

                    // The accelerometer is enabled, the magnetometer refused (on the sensor path).
                    override fun register(listener: SensorEventListener, sensor: Sensor): Boolean {
                        if (sensorPath && sensor.type == SensorTypes.MAGNETIC_FIELD) return false
                        registered++
                        return true
                    }

                    override fun unregister(listener: SensorEventListener) {
                        if (sensorPath) throw removalFailure
                    }
                }
                val positions = object : HeadingPositions {
                    override fun newestPosition(): DeclinationPosition? = null
                    override fun register(listener: LocationListener) {
                        throw IllegalStateException("position registration refused")
                    }

                    override fun unregister(listener: LocationListener) {
                        if (!sensorPath) throw removalFailure
                    }
                }
                val logger = FaultLogger(Fault.NONE)
                val provider: HeadingProvider = AndroidHeadingProvider(
                    sensors,
                    positions,
                    AndroidHeadingSensor.ACCELEROMETER_MAGNETOMETER,
                    logger,
                    { 1_700_000_000_000L },
                    { _, _, _ -> 7.0 },
                )
                val surfaced: MutableList<Throwable> = mutableListOf()
                val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    try {
                        provider.observeHeading(minChangeDegrees = 0.0).collect { }
                    } catch (thrown: Throwable) {
                        surfaced += thrown
                        if (thrown is CancellationException) throw thrown
                    }
                }
                assertTrue(sensors.registered >= 1, "control: a sensor was registered before the failure; $combination")
                collection.cancel()
                testScheduler.runCurrent()

                val reachable: Set<Throwable> = surfaced.flatMap { thrown: Throwable -> thrown.graph() }.toSet()
                if (removalFailure !in reachable && logger.handed.none { handed: Throwable -> handed === removalFailure }) {
                    lost += "$combination (surfaced=$surfaced, logged=${logger.handed})"
                }
            }
        }
        assertEquals(emptyList(), lost, "a failed undo removal is neither logged nor reachable (the listener may still be registered)")
    }
}
