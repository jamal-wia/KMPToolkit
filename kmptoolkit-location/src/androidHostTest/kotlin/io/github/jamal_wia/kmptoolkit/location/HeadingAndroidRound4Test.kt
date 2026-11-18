@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.location.LocationListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Group loc-heading, round 4: the cleanup paths of the Android provider when a failure is already in flight
 * (finding m1 of breaker round 3, checked in the provider's own undo and release handle).
 *
 * The rule: a failure in flight is never replaced by a cleanup problem and never silently dropped. The undo of a
 * failed registration attaches its own failures to the original and still runs every undo, also when one of
 * them throws the very same instance (`addSuppressed` refuses self-suppression); the release handle, when the
 * app's logger throws an `Error` at the first log, attaches the failure of the removal it can no longer log.
 *
 * Deterministic: synchronous seams, unconfined collector. No sleeps, no wall clock, no randomness.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidRound4Test {

    private val rig = SensorRig(ApplicationProvider.getApplicationContext())
    private val rotationVector: Sensor = rig.add(SensorTypes.ROTATION_VECTOR)

    private fun rotationVectorSensors(): ScriptedSensors = ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector))

    /** A sensor hub that registers for real and then throws [registerFailure], and whose removal removes and then throws [removeFailure]. */
    private class FaultySensors(
        val inner: ScriptedSensors,
        private val registerFailure: Throwable?,
        private val removeFailure: Throwable?,
    ) : HeadingSensors by inner {
        override fun register(listener: SensorEventListener, sensor: Sensor): Boolean {
            val registered: Boolean = inner.register(listener, sensor)
            registerFailure?.let { failure: Throwable -> throw failure }
            return registered
        }

        override fun unregister(listener: SensorEventListener) {
            inner.unregister(listener)
            removeFailure?.let { failure: Throwable -> throw failure }
        }
    }

    /** Positions that register for real and then throw [registerFailure], and whose removal removes and then throws [removeFailure]. */
    private class FaultyPositions(
        val inner: ScriptedPositions,
        private val registerFailure: Throwable?,
        private val removeFailure: Throwable?,
    ) : HeadingPositions by inner {
        override fun register(listener: LocationListener) {
            inner.register(listener)
            registerFailure?.let { failure: Throwable -> throw failure }
        }

        override fun unregister(listener: LocationListener) {
            inner.unregister(listener)
            removeFailure?.let { failure: Throwable -> throw failure }
        }
    }

    /** A logger of an app that throws [error] at every log call. */
    private class ThrowingLogger(private val error: Throwable) : Logger {
        override val tag: String = "ThrowingLogger"

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String): Unit = throw error
    }

    private fun provider(sensors: HeadingSensors, positions: HeadingPositions, log: Logger): HeadingProvider =
        AndroidHeadingProvider(sensors, positions, AndroidHeadingSensor.AUTO, log, { 1_700_000_000_000L }, { _, _, _ -> 0.0 })

    /** Collects [flow] until it ends; [ended] gets whatever the collecting coroutine caught (cancellation included). */
    private fun TestScope.collectUntilEnd(flow: Flow<Heading>, ended: MutableList<Throwable>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                flow.collect { }
            } catch (thrown: Throwable) {
                ended += thrown
            }
        }

    private fun Throwable.chain(): List<Throwable> = listOf(this) + suppressedExceptions.flatMap { suppressed: Throwable -> suppressed.chain() }

    @Test
    fun `a position registration Error whose undo throws the same instance still removes the sensor listener and surfaces unchanged`() = runTest {
        val shared = LinkageError("one instance, thrown by the registration and again by its undo")
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val ended: MutableList<Throwable> = mutableListOf()

        collectUntilEnd(
            provider(FaultySensors(sensors, null, null), FaultyPositions(positions, registerFailure = shared, removeFailure = shared), RecordingLogger()).observeHeading(),
            ended,
        ).join()

        assertEquals(1, positions.registerCalls.load(), "control: the position registration was attempted")
        assertEquals(1, positions.unregisterCalls.load(), "control: its undo was attempted")
        assertEquals(0, sensors.liveRegistrations, "the sensor listener is removed although the position undo threw the failure's own instance")
        assertSame(shared, ended.single(), "the original failure surfaces, not the IllegalArgumentException of a self-suppression")
        assertTrue(shared.suppressedExceptions.isEmpty(), "nothing attached to itself")
    }

    @Test
    fun `a sensor registration Error whose undo throws the same instance surfaces unchanged`() = runTest {
        val shared = LinkageError("one instance, thrown by the registration and again by its undo")
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val ended: MutableList<Throwable> = mutableListOf()

        collectUntilEnd(
            provider(FaultySensors(sensors, registerFailure = shared, removeFailure = shared), FaultyPositions(positions, null, null), RecordingLogger()).observeHeading(),
            ended,
        ).join()

        assertEquals(1, sensors.registerCalls.load(), "control: the sensor registration was attempted")
        assertEquals(0, sensors.liveRegistrations, "the sensor listener is removed")
        assertSame(shared, ended.single(), "the original failure surfaces unchanged")
        assertTrue(shared.suppressedExceptions.isEmpty(), "nothing attached to itself")
    }

    @Test
    fun `undo failures of different instances ride along as suppressed on the registration failure and every undo still ran`() = runTest {
        val registration = LinkageError("the registration failure")
        val positionUndo = IllegalStateException("the position undo failure")
        val sensorUndo = StackOverflowError("the sensor undo failure")
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val ended: MutableList<Throwable> = mutableListOf()

        collectUntilEnd(
            provider(FaultySensors(sensors, null, sensorUndo), FaultyPositions(positions, registration, positionUndo), RecordingLogger()).observeHeading(),
            ended,
        ).join()

        assertSame(registration, ended.single(), "the registration failure is what the collection ends with")
        assertEquals(setOf<Throwable>(positionUndo, sensorUndo), registration.suppressedExceptions.toSet(), "both undo failures are attached")
        assertEquals(0, sensors.liveRegistrations, "the sensor listener is removed")
        assertEquals(0, positions.liveListeners.size, "the position listener is removed")
    }

    @Test
    fun `a logger Error at the first removal log keeps the second removal's failure reachable and both removals ran`() = runTest {
        val loggerError = AssertionError("the app's logger refuses a WARN")
        val positionRemoval = IllegalStateException("the position removal failure")
        val sensorRemoval = StackOverflowError("the sensor removal failure")
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val ended: MutableList<Throwable> = mutableListOf()
        val collection: Job = collectUntilEnd(
            provider(FaultySensors(sensors, null, sensorRemoval), FaultyPositions(positions, null, positionRemoval), ThrowingLogger(loggerError)).observeHeading(),
            ended,
        )
        assertEquals(1, sensors.liveRegistrations, "control: the sensor listener is registered")
        assertEquals(1, positions.liveListeners.size, "control: the position listener is registered")

        collection.cancelAndJoin()

        assertEquals(0, sensors.liveRegistrations, "the sensor listener is released first")
        assertEquals(0, positions.liveListeners.size, "the position listener is released first")
        assertSame(loggerError, ended.single(), "the Error of the logger is not absorbed")
        val chain: List<Throwable> = loggerError.chain()
        assertTrue(chain.any { it === sensorRemoval }, "the failure of the removal that was never logged is attached, saw $chain")
    }

    @Test
    fun `a logger that throws the second removal's own failure instance does not suppress it into itself`() = runTest {
        val shared = StackOverflowError("thrown by the sensor removal and again by the logger")
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val ended: MutableList<Throwable> = mutableListOf()
        val collection: Job = collectUntilEnd(
            provider(FaultySensors(sensors, null, shared), FaultyPositions(positions, null, IllegalStateException("position removal failure")), ThrowingLogger(shared)).observeHeading(),
            ended,
        )
        assertEquals(1, sensors.liveRegistrations, "control: the sensor listener is registered")

        collection.cancelAndJoin()

        assertEquals(0, sensors.liveRegistrations, "the sensor listener is released")
        assertSame(shared, ended.single(), "the one throwable, unchanged: a self-suppression would throw IllegalArgumentException")
        // The cancellation that ended the collection is attached by the stream; the throwable itself never is.
        assertTrue(shared.suppressedExceptions.none { it === shared }, "nothing attached to itself")
    }
}
