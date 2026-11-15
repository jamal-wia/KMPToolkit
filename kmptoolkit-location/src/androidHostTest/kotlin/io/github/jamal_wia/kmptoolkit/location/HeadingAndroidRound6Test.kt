@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.location.LocationListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Group loc-heading, round 6, finding m3 of breaker round 5: the removal that undoes a refused sensor or a failed
 * position registration is a release like the handle's, so its failure (an exception or an `Error`) is reported,
 * never dropped: the listener it could not remove may still be registered, and nobody would hear of it.
 *
 * The rules, as for the release handle: the removal never lets its failure escape (the collection stays open and
 * silent after a refused sensor, the compass keeps working after a failed position registration), the failure is
 * handed to the logger at warning level together with the failure it was undoing where there is one, a logger that
 * throws an exception loses its event only, and a logger that throws an `Error` is not absorbed (it surfaces, with
 * the removal's failure reachable from it). Exhaustive over the two paths x the removal outcome x the logger's
 * behaviour. Synchronous seams, no randomness, no sleeps, no wall clock.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidRound6Test {

    private val rig = SensorRig(ApplicationProvider.getApplicationContext())
    private val rotationVector: Sensor = rig.add(SensorTypes.ROTATION_VECTOR)
    private val accelerometer: Sensor = rig.add(SensorTypes.ACCELEROMETER)
    private val magnetometer: Sensor = rig.add(SensorTypes.MAGNETIC_FIELD)

    private enum class Path { REFUSED_SENSOR, FAILED_POSITION }

    private enum class Removal { OK, EXCEPTION, ERROR }

    private enum class LoggerFault { NONE, EXCEPTION, ERROR }

    private class Handed(val level: LogLevel, val throwable: Throwable?, val message: String)

    /** Records every call with its throwable; throws on a warning when told to (the app's logger misbehaving). */
    private class ThrowingLogger(private val fault: LoggerFault) : Logger {
        val handed: MutableList<Handed> = mutableListOf()
        val thrown: MutableList<Throwable> = mutableListOf()

        override val tag: String = "ThrowingLogger"

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            handed += Handed(level, throwable, message())
            if (level != LogLevel.WARN) return
            val failure: Throwable = when (fault) {
                LoggerFault.NONE -> return
                LoggerFault.EXCEPTION -> IllegalStateException("logger exception")
                LoggerFault.ERROR -> LoggerError("logger error")
            }
            thrown += failure
            throw failure
        }
    }

    private class LoggerError(message: String) : Error(message)

    private class RemovalError(message: String) : Error(message)

    private fun removalFailure(removal: Removal): Throwable? = when (removal) {
        Removal.OK -> null
        Removal.EXCEPTION -> IllegalArgumentException("removal exception")
        Removal.ERROR -> RemovalError("removal error")
    }

    /** The graph of causes and suppressed failures reachable from [this], itself included. */
    private fun Throwable.graph(): Set<Throwable> {
        val seen: MutableSet<Throwable> = mutableSetOf()
        fun walk(thrown: Throwable) {
            if (!seen.add(thrown)) return
            thrown.cause?.let { walk(it) }
            thrown.suppressed.forEach { walk(it) }
        }
        walk(this)
        return seen
    }

    /** Everything one scenario leaves behind. */
    private class Outcome(
        val sensors: RigSensors,
        val positions: RigPositions,
        val logger: ThrowingLogger,
        val headings: List<Heading>,
        val surfaced: List<Throwable>,
        val removalFailure: Throwable?,
        val registrationFailure: Throwable?,
    )

    private class RigSensors(
        private val accelerometer: Sensor,
        private val magnetometer: Sensor,
        private val refuseMagnetometer: Boolean,
        private val removalFailure: Throwable?,
    ) : HeadingSensors {
        val live: MutableList<SensorEventListener> = mutableListOf()
        var unregisterCalls = 0

        override fun defaultSensor(type: Int): Sensor? = when (type) {
            SensorTypes.ACCELEROMETER -> accelerometer
            SensorTypes.MAGNETIC_FIELD -> magnetometer
            else -> null
        }

        override fun register(listener: SensorEventListener, sensor: Sensor): Boolean {
            if (refuseMagnetometer && sensor.type == SensorTypes.MAGNETIC_FIELD) return false
            live += listener
            return true
        }

        override fun unregister(listener: SensorEventListener) {
            unregisterCalls++
            // The platform removal is only reported as failing; the fake still forgets the listener afterwards on
            // the paths where the removal "works", and keeps it where the removal throws (it may still be registered).
            if (removalFailure != null) throw removalFailure
            // One listener serves every sensor it was registered for, so one removal forgets them all.
            live.removeAll { registered: SensorEventListener -> registered === listener }
        }
    }

    private class RigPositions(
        private val registrationFailure: Exception?,
        private val removalFailure: Throwable?,
    ) : HeadingPositions {
        val live: MutableList<LocationListener> = mutableListOf()
        var unregisterCalls = 0

        override fun newestPosition(): DeclinationPosition? = null

        override fun register(listener: LocationListener) {
            registrationFailure?.let { failure: Exception -> throw failure }
            live += listener
        }

        override fun unregister(listener: LocationListener) {
            unregisterCalls++
            if (removalFailure != null) throw removalFailure
            live.removeAll { registered: LocationListener -> registered === listener }
        }
    }

    /** Runs one scenario to the end of its registration and cancels the collection; see [Outcome]. */
    private fun TestScope.runScenario(path: Path, removal: Removal, loggerFault: LoggerFault): Outcome {
        val failure: Throwable? = removalFailure(removal)
        val sensorsFailure: Throwable? = if (path == Path.REFUSED_SENSOR) failure else null
        val positionFailure: Throwable? = if (path == Path.FAILED_POSITION) failure else null
        val registration: Exception? =
            if (path == Path.FAILED_POSITION) IllegalStateException("position registration refused") else null
        val sensors = RigSensors(accelerometer, magnetometer, refuseMagnetometer = path == Path.REFUSED_SENSOR, removalFailure = sensorsFailure)
        val positions = RigPositions(registration, positionFailure)
        val logger = ThrowingLogger(loggerFault)
        val provider: HeadingProvider = AndroidHeadingProvider(
            sensors,
            positions,
            AndroidHeadingSensor.ACCELEROMETER_MAGNETOMETER,
            logger,
            { 1_700_000_000_000L },
            { _, _, _ -> 7.0 },
        )
        val headings: MutableList<Heading> = mutableListOf()
        val surfaced: MutableList<Throwable> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                provider.observeHeading(minChangeDegrees = 0.0).collect { heading: Heading -> headings += heading }
            } catch (thrown: Throwable) {
                surfaced += thrown
                if (thrown is CancellationException) throw thrown
            }
        }
        if (path == Path.FAILED_POSITION && !collection.isCompleted) {
            // The compass keeps working: a rotation of the raw pair is served although the position failed.
            val listener: SensorEventListener? = sensors.live.firstOrNull()
            if (listener != null) {
                val raw: Pair<FloatArray, FloatArray> = rawVectors(Quat.of(40.0))
                listener.onSensorChanged(rig.event(SensorTypes.ACCELEROMETER, raw.first))
                listener.onSensorChanged(rig.event(SensorTypes.MAGNETIC_FIELD, raw.second))
            }
        }
        collection.cancel()
        testScheduler.runCurrent()
        return Outcome(sensors, positions, logger, headings, surfaced, failure, registration)
    }

    @Test
    fun `a failing removal while undoing a refused sensor is logged with its failure and the collection stays open and silent`() = runTest {
        for (removal: Removal in listOf(Removal.EXCEPTION, Removal.ERROR)) {
            val outcome: Outcome = runScenario(Path.REFUSED_SENSOR, removal, LoggerFault.NONE)
            val context = "removal=$removal"

            assertTrue(outcome.sensors.unregisterCalls >= 1, "control: the undo ran ($context)")
            val warnings: List<Handed> = outcome.logger.handed.filter { handed: Handed -> handed.level == LogLevel.WARN }
            assertEquals(1, warnings.size, "one warning ($context): ${outcome.logger.handed.map { it.message }}")
            assertTrue(warnings.single().throwable === outcome.removalFailure, "the warning carries the removal's failure ($context)")
            assertTrue("may stay registered" in warnings.single().message, "the warning says a listener may be left ($context): ${warnings.single().message}")
            assertEquals(emptyList(), outcome.headings, "the heading stays silent ($context)")
            assertEquals(1, outcome.surfaced.size, "only the collector's own cancellation surfaces ($context): ${outcome.surfaced}")
            assertTrue(outcome.surfaced.single() is CancellationException, "the failure did not escape into the collector ($context)")
        }
    }

    @Test
    fun `a refused sensor whose undo works is logged as before without a throwable`() = runTest {
        val outcome: Outcome = runScenario(Path.REFUSED_SENSOR, Removal.OK, LoggerFault.NONE)

        val warnings: List<Handed> = outcome.logger.handed.filter { handed: Handed -> handed.level == LogLevel.WARN }
        assertEquals(1, warnings.size, "one warning: ${outcome.logger.handed.map { it.message }}")
        assertNull(warnings.single().throwable, "nothing to attach")
        assertFalse("may stay registered" in warnings.single().message, "no claim of a leftover listener: ${warnings.single().message}")
        assertTrue(outcome.sensors.unregisterCalls >= 1 && outcome.sensors.live.isEmpty(), "control: the sensors were removed")
    }

    @Test
    fun `a failing removal while undoing a failed position registration is logged with its failure and the registration failure rides along`() = runTest {
        for (removal: Removal in listOf(Removal.EXCEPTION, Removal.ERROR)) {
            val outcome: Outcome = runScenario(Path.FAILED_POSITION, removal, LoggerFault.NONE)
            val context = "removal=$removal"

            assertEquals(1, outcome.positions.unregisterCalls, "control: the position undo ran ($context)")
            val warnings: List<Handed> = outcome.logger.handed.filter { handed: Handed -> handed.level == LogLevel.WARN }
            assertEquals(1, warnings.size, "one warning ($context): ${outcome.logger.handed.map { it.message }}")
            val carried: Throwable = warnings.single().throwable ?: error("the warning carries no throwable ($context)")
            assertTrue(carried === outcome.removalFailure, "the warning carries the removal's failure ($context)")
            assertTrue(outcome.registrationFailure in carried.graph(), "the registration's own failure rides along ($context)")
            assertTrue(outcome.headings.isNotEmpty(), "the compass keeps working: a failed position costs only true north ($context)")
            assertTrue(outcome.headings.all { heading: Heading -> heading.trueDegrees == null }, "true north stays null ($context)")
            assertEquals(1, outcome.surfaced.size, "only the collector's own cancellation surfaces ($context): ${outcome.surfaced}")
            assertTrue(outcome.sensors.unregisterCalls >= 1 && outcome.sensors.live.isEmpty(), "control: the sensors were released with the collection ($context)")
        }
    }

    @Test
    fun `a failed position registration whose undo works is logged as before at debug level`() = runTest {
        val outcome: Outcome = runScenario(Path.FAILED_POSITION, Removal.OK, LoggerFault.NONE)

        assertEquals(emptyList(), outcome.logger.handed.filter { handed: Handed -> handed.level == LogLevel.WARN }.map { it.message }, "no warning")
        assertTrue(
            outcome.logger.handed.any { handed: Handed -> handed.level == LogLevel.DEBUG && "No passive position" in handed.message },
            "the debug line of before: ${outcome.logger.handed.map { it.message }}",
        )
        assertTrue(outcome.headings.isNotEmpty(), "control: the compass keeps working")
    }

    @Test
    fun `a logger that throws while the undo failure is reported loses only its event and an Error is not absorbed`() = runTest {
        for (path: Path in Path.entries) {
            for (loggerFault: LoggerFault in listOf(LoggerFault.EXCEPTION, LoggerFault.ERROR)) {
                val outcome: Outcome = runScenario(path, Removal.EXCEPTION, loggerFault)
                val context = "path=$path logger=$loggerFault"

                assertEquals(1, outcome.logger.thrown.size, "control: the logger threw once ($context)")
                val warned: List<Handed> = outcome.logger.handed.filter { handed: Handed -> handed.level == LogLevel.WARN }
                assertTrue(warned.any { handed: Handed -> handed.throwable === outcome.removalFailure }, "the failure reached the logger ($context)")
                if (loggerFault == LoggerFault.EXCEPTION) {
                    assertEquals(1, outcome.surfaced.size, "an exception of the logger is absorbed ($context): ${outcome.surfaced}")
                    assertTrue(outcome.surfaced.single() is CancellationException, "only the cancellation surfaces ($context)")
                } else {
                    val reachable: Set<Throwable> = outcome.surfaced.flatMap { thrown: Throwable -> thrown.graph() }.toSet()
                    assertTrue(outcome.logger.thrown.single() in reachable, "an Error of the logger surfaces ($context): ${outcome.surfaced}")
                    assertTrue(outcome.removalFailure in reachable, "and the removal's failure stays reachable from it ($context)")
                }
                assertTrue(outcome.sensors.unregisterCalls >= 1, "control: the sensors were undone ($context)")
            }
        }
    }

    @Test
    fun `a clean undo leaves nothing registered and logs no warning on either path`() = runTest {
        for (path: Path in Path.entries) {
            val outcome: Outcome = runScenario(path, Removal.OK, LoggerFault.NONE)

            assertEquals(emptyList(), outcome.sensors.live, "no sensor listener stays registered ($path)")
            assertEquals(emptyList(), outcome.positions.live, "no position listener stays registered ($path)")
            assertTrue(outcome.sensors.unregisterCalls >= 1, "control: the sensors were removed ($path)")
        }
    }
}
