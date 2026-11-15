@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.location.Location
import android.location.LocationListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * Breaker round 6 of group loc-heading (LOC-11, decision D-23): the Android heading provider with TWO faults in one
 * run, after the round-5 fix that reports a failing removal while undoing a refused sensor or a failed position
 * registration.
 *
 * - The exhaustive model of round 5 (every point where a value reaches the collector x every collector behaviour)
 *   crossed with every pair of faults from different places (sensor registration refused or throwing, position
 *   registration throwing, sensor removal throwing, position removal throwing, the logger throwing; exception or
 *   `Error` each), with the removal faults either failing once or on every call. Invariants: the per-listener ledger
 *   (nothing stays registered, nothing is registered after its removal), no exception escapes into a platform
 *   callback, every non-removal `Error` surfaces, a removal failure is logged or reachable, the collector's failure
 *   surfaces.
 * - The undo of a failed position registration when the removal throws a `Throwable` with suppression disabled:
 *   the registration's own failure cannot ride along on it, and must not vanish without a trace.
 *
 * Deterministic: synchronous seams, exhaustive loops, no randomness, no sleeps.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidBreakerR6Test {

    private val rig = SensorRig(ApplicationProvider.getApplicationContext())
    private val rotationVector: Sensor = rig.add(SensorTypes.ROTATION_VECTOR)
    private val accelerometer: Sensor = rig.add(SensorTypes.ACCELEROMETER)
    private val magnetometer: Sensor = rig.add(SensorTypes.MAGNETIC_FIELD)

    private enum class Point { ROTATION, RAW, POSITION, IN_SENSOR_REGISTER, IN_POSITION_REGISTER }

    private enum class Behaviour { CONTINUE, CANCEL, FIRST, THROW, RECOLLECT }

    /** Where a fault strikes. */
    private enum class Place { SENSOR_REGISTER, POSITION_REGISTER, SENSOR_REMOVE, POSITION_REMOVE, LOGGER }

    /** One fault: where, which kind, and (for removals and the logger) whether every call fails or only the first. */
    private data class Fault(val place: Place, val error: Boolean, val refused: Boolean = false, val every: Boolean = false) {
        override fun toString(): String =
            "$place:${if (refused) "refused" else if (error) "Error" else "Exception"}${if (every) ":every" else ""}"
    }

    private class Ledger {
        val registered: MutableMap<Any, Int> = LinkedHashMap()
        val removed: MutableMap<Any, Int> = LinkedHashMap()
        val live: MutableSet<Any> = LinkedHashSet()
        val problems: MutableList<String> = mutableListOf()
        val fired: MutableList<Throwable> = mutableListOf()
        val removalFailures: MutableSet<Throwable> = LinkedHashSet()

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

    private fun Fault.throwable(serial: Int): Throwable =
        if (error) AssertionError("injected Error $this #$serial") else IllegalStateException("injected exception $this #$serial")

    private inner class LedgerSensors(
        private val ledger: Ledger,
        private val sensors: Map<Int, Sensor>,
        private val faults: List<Fault>,
        private val inside: (SensorEventListener, Sensor) -> Unit,
    ) : HeadingSensors {
        private var registerCalls = 0
        private var removeCalls = 0
        val byListener: MutableMap<SensorEventListener, MutableList<Sensor>> = LinkedHashMap()

        override fun defaultSensor(type: Int): Sensor? = sensors[type]

        override fun register(listener: SensorEventListener, sensor: Sensor): Boolean {
            registerCalls++
            val fault: Fault? = faults.firstOrNull { it.place == Place.SENSOR_REGISTER }
            // The fault strikes the last sensor of the pipeline, so on the raw pipeline one sensor is registered first.
            if (fault != null && registerCalls == sensors.size) {
                if (fault.refused) return false
                throw fault.throwable(registerCalls).also { ledger.fired += it }
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
            val fault: Fault? = faults.firstOrNull { it.place == Place.SENSOR_REMOVE }
            if (fault != null && (fault.every || removeCalls == 1)) {
                throw fault.throwable(removeCalls).also { ledger.fired += it; ledger.removalFailures += it }
            }
        }
    }

    private inner class LedgerPositions(
        private val ledger: Ledger,
        private val faults: List<Fault>,
        private val inside: (LocationListener) -> Unit,
    ) : HeadingPositions {
        private var registerCalls = 0
        private var removeCalls = 0
        val liveListeners: MutableList<LocationListener> = mutableListOf()

        override fun newestPosition(): DeclinationPosition? = null

        override fun register(listener: LocationListener) {
            registerCalls++
            val fault: Fault? = faults.firstOrNull { it.place == Place.POSITION_REGISTER }
            if (fault != null && registerCalls == 1) throw fault.throwable(registerCalls).also { ledger.fired += it }
            ledger.register(listener)
            liveListeners += listener
            inside(listener)
        }

        override fun unregister(listener: LocationListener) {
            removeCalls++
            ledger.remove(listener)
            liveListeners -= listener
            val fault: Fault? = faults.firstOrNull { it.place == Place.POSITION_REMOVE }
            if (fault != null && (fault.every || removeCalls == 1)) {
                throw fault.throwable(removeCalls).also { ledger.fired += it; ledger.removalFailures += it }
            }
        }
    }

    private class FaultLogger(private val fault: Fault?) : Logger {
        val handed: MutableList<Throwable> = mutableListOf()
        val thrown: MutableList<Throwable> = mutableListOf()
        val lines: MutableList<Pair<LogLevel, String>> = mutableListOf()
        private var calls = 0
        override val tag: String = "FaultLogger"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            calls++
            lines += level to message()
            if (throwable != null) handed += throwable
            if (fault != null && (fault.every || calls == 1)) {
                throw (if (fault.error) AssertionError("logger Error at $level") else IllegalStateException("logger exception at $level")).also { thrown += it }
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

    private fun faultsOf(place: Place): List<Fault> = when (place) {
        Place.SENSOR_REGISTER -> listOf(Fault(place, false, refused = true), Fault(place, false), Fault(place, true))
        Place.POSITION_REGISTER -> listOf(Fault(place, false), Fault(place, true))
        else -> listOf(false, true).flatMap { every: Boolean -> listOf(Fault(place, false, every = every), Fault(place, true, every = every)) }
    }

    @Test
    fun `every emission point collector behaviour and pair of faults keeps the listener ledger`() = runTest {
        val pairs: List<List<Fault>> = buildList {
            for (a: Int in Place.entries.indices) {
                for (b: Int in a + 1 until Place.entries.size) {
                    for (first: Fault in faultsOf(Place.entries[a])) {
                        for (second: Fault in faultsOf(Place.entries[b])) add(listOf(first, second))
                    }
                }
            }
        }
        var endedInsideCallback = 0
        var bothFired = 0
        var scenarios = 0
        for (point: Point in Point.entries) {
            for (behaviour: Behaviour in Behaviour.entries) {
                for (faults: List<Fault> in pairs) {
                    scenarios++
                    val combination = "point=$point behaviour=$behaviour faults=$faults"
                    val ledger = Ledger()
                    val raw: Boolean = point == Point.RAW
                    val sensorMap: Map<Int, Sensor> = if (raw) {
                        mapOf(SensorTypes.ACCELEROMETER to accelerometer, SensorTypes.MAGNETIC_FIELD to magnetometer)
                    } else {
                        mapOf(SensorTypes.ROTATION_VECTOR to rotationVector)
                    }
                    var insideCallback = 0
                    var armed = false
                    var acted = false
                    val sensors = LedgerSensors(ledger, sensorMap, faults) { listener: SensorEventListener, sensor: Sensor ->
                        if (point == Point.IN_SENSOR_REGISTER && sensor.type == SensorTypes.ROTATION_VECTOR) {
                            listener.onSensorChanged(rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(222.0).sensorValues(0.05f)))
                        }
                    }
                    val positions = LedgerPositions(ledger, faults) { listener: LocationListener ->
                        if (point == Point.IN_POSITION_REGISTER) listener.onLocationChanged(fix(55.7))
                    }
                    val logger = FaultLogger(faults.firstOrNull { it.place == Place.LOGGER })
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
                                flow.first { _: Heading ->
                                    val now: Boolean = armed && !acted
                                    if (now) endedHere()
                                    now
                                }
                            } else {
                                flow.collect { _: Heading ->
                                    if (armed && !acted) {
                                        endedHere()
                                        when (behaviour) {
                                            Behaviour.CANCEL -> cancel()
                                            Behaviour.THROW -> throw collectorFailure
                                            Behaviour.RECOLLECT -> {
                                                extra += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                                                    try {
                                                        provider.observeHeading(minChangeDegrees = 0.0).collect { }
                                                    } catch (thrown: Throwable) {
                                                        surfaced += thrown
                                                        if (thrown is CancellationException) throw thrown
                                                    }
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

                    if (raw) deliverRaw(10.0) else deliverRotation(10.0)
                    armed = true
                    when (point) {
                        Point.ROTATION -> deliverRotation(50.0)
                        Point.RAW -> deliverRaw(50.0)
                        Point.POSITION -> positions.liveListeners.toList().forEach { listener: LocationListener ->
                            callback { listener.onLocationChanged(fix(55.7)) }
                        }
                        else -> Unit
                    }
                    for (listener: Any in ledger.registered.keys.toList()) {
                        when (listener) {
                            is SensorEventListener -> callback {
                                listener.onSensorChanged(rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(90.0).sensorValues(0.05f)))
                            }
                            is LocationListener -> callback { listener.onLocationChanged(fix(40.0)) }
                        }
                    }
                    collection.cancel()
                    extra.forEach { job: Job -> job.cancel() }
                    testScheduler.runCurrent()
                    val firedPlaces: Int = (ledger.fired.size + logger.thrown.size + if (faults.any { it.refused } && sensors.byListener.isEmpty()) 1 else 0)
                    if (firedPlaces >= 2) bothFired++

                    assertEquals(emptyList(), ledger.problems, combination)
                    assertEquals(emptySet(), ledger.live, "a listener stays registered; $combination; surfaced=$surfaced escaped=$escaped")
                    for ((listener: Any, count: Int) in ledger.registered) {
                        assertTrue((ledger.removed[listener] ?: 0) >= 1, "registered $count time(s), never removed: $listener; $combination")
                    }
                    assertEquals(emptyList(), escaped.filter { thrown: Throwable -> thrown !is Error }, "an exception escaped into a callback; $combination")
                    val reachable: Set<Throwable> = (surfaced + escaped).flatMap { thrown: Throwable -> thrown.graph() }.toSet()
                    val loggedOrReachable: (Throwable) -> Boolean = { thrown: Throwable ->
                        thrown in reachable || logger.handed.any { handed: Throwable -> handed === thrown || thrown in handed.graph() }
                    }
                    for (thrown: Throwable in ledger.fired + logger.thrown) {
                        when {
                            thrown in ledger.removalFailures ->
                                assertTrue(loggedOrReachable(thrown), "a removal failure is neither logged nor reachable: $thrown; $combination; surfaced=$surfaced")
                            thrown is Error ->
                                assertTrue(thrown in reachable, "an Error is never absorbed: $thrown; $combination; surfaced=$surfaced escaped=$escaped")
                            thrown in logger.thrown -> Unit
                            // A failed position registration is reported at debug level by its class name (documented).
                            faults.any { it.place == Place.POSITION_REGISTER && !it.error } && thrown in ledger.fired &&
                                thrown.message?.contains("POSITION_REGISTER") == true -> Unit
                            else -> assertTrue(loggedOrReachable(thrown), "a platform exception is neither logged nor reachable: $thrown; $combination")
                        }
                    }
                    if (behaviour == Behaviour.THROW && acted) {
                        assertTrue(collectorFailure in reachable, "the collector's own failure is lost; $combination; surfaced=$surfaced")
                    }
                }
            }
        }
        assertTrue(endedInsideCallback >= 200, "control: collectors ended inside a callback ($endedInsideCallback of $scenarios)")
        assertTrue(bothFired >= 400, "control: both faults struck in one run ($bothFired of $scenarios)")
    }

    /** A `Throwable` whose suppressed list is disabled: nothing can ride along on it. */
    private class SealedRemovalFailure : RuntimeException("removal failed, suppression disabled", null, false, true)

    /**
     * The position registration throws and the removal that undoes it throws a `Throwable` with suppression disabled.
     * The listener may stay registered, so the warning must carry the removal's failure; the registration's own failure
     * cannot ride along on it, but must still be traceable (its class named in the warning); the compass keeps working
     * and nothing escapes.
     */
    @Test
    fun `an undo removal failure with suppression disabled still reports the registration failure`() = runTest {
        val removalFailure = SealedRemovalFailure()
        val registrationFailure = SecurityException("position registration refused")
        val sensors = ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector))
        val positions = object : HeadingPositions {
            var undoCalls = 0
            override fun newestPosition(): DeclinationPosition? = null
            override fun register(listener: LocationListener) { throw registrationFailure }
            override fun unregister(listener: LocationListener) {
                undoCalls++
                throw removalFailure
            }
        }
        val logger = FaultLogger(null)
        val provider: HeadingProvider = AndroidHeadingProvider(
            sensors,
            positions,
            AndroidHeadingSensor.ROTATION_VECTOR,
            logger,
            { 1_700_000_000_000L },
            { _, _, _ -> 7.0 },
        )
        val received: MutableList<Heading> = mutableListOf()
        val surfaced: MutableList<Throwable> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                provider.observeHeading(minChangeDegrees = 0.0).collect { heading: Heading -> received += heading }
            } catch (thrown: Throwable) {
                surfaced += thrown
                if (thrown is CancellationException) throw thrown
            }
        }
        assertEquals(1, positions.undoCalls, "control: the undo removal ran")
        val listener: SensorEventListener = assertNotNull(sensors.liveListeners.singleOrNull(), "control: the compass sensor is registered")
        listener.onSensorChanged(rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(30.0).sensorValues(0.05f)))
        assertTrue(received.isNotEmpty(), "the compass still works after the failed position registration")

        val warning: Pair<LogLevel, String>? = logger.lines.singleOrNull { (level: LogLevel, _) -> level == LogLevel.WARN }
        assertNotNull(warning, "one warning: the listener may stay registered; lines=${logger.lines}")
        assertTrue(logger.handed.any { it === removalFailure }, "the warning carries the removal's failure")
        assertTrue(
            registrationFailure in logger.handed.flatMap { it.graph() } || "SecurityException" in warning.second,
            "the registration's own failure is traceable: attached, or named in the warning; warning=$warning",
        )
        collection.cancel()
        testScheduler.runCurrent()
        assertEquals(emptyList(), surfaced.filter { it !is CancellationException }, "nothing but the cancellation surfaces")
        assertEquals(0, sensors.liveListeners.size, "the compass sensor is released with the collection")
    }
}
