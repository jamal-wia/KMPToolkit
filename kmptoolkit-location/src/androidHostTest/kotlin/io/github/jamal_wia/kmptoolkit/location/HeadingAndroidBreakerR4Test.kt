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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Breaker round 4 of group loc-heading (LOC-11, decision D-23): the round-4 cleanup code of the Android provider
 * and of the common stream, attacked with the whole Throwable matrix instead of single cases.
 *
 * - The release handle, exhaustively: position removal (fine, exception, `Error`) x sensor removal (fine,
 *   exception, `Error`, the position removal's own instance) x the app's logger (fine, throws a fresh `Error`, throws
 *   back the very throwable it is handed) x how the collection ends (cancellation, failing collector, `first()`).
 *   The oracle comes from the provider KDoc ("even then every registration is released first", "an Error is not
 *   absorbed") and the stream KDoc ("the earlier throwable is attached to it as suppressed, so neither is lost"):
 *   both listeners removed and each removal attempted exactly once; every logger `Error` and the collector's own
 *   failure reachable from what the collection ended with; every removal failure handed to the logger or reachable.
 * - The stream with a release `Error` that refuses suppression (`Throwable(enableSuppression = false)`, which the
 *   JVM uses for its preallocated errors): the release still runs once, the `Error` still surfaces unchanged, and
 *   the refused `addSuppressed` does not replace it with anything else.
 *
 * Deterministic: synchronous seams, unconfined collector, exhaustive loops, no randomness, no sleeps.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidBreakerR4Test {

    private val rig = SensorRig(ApplicationProvider.getApplicationContext())
    private val rotationVector: Sensor = rig.add(SensorTypes.ROTATION_VECTOR)

    private enum class Removal { FINE, EXCEPTION, ERROR, SAME_AS_POSITION }

    private enum class Log { FINE, THROWS_ERROR, RETHROWS_GIVEN }

    private enum class End { CANCEL, COLLECTOR_FAILS, FIRST }

    private class RemovingSensors(val inner: ScriptedSensors, private val removeFailure: () -> Throwable?) : HeadingSensors by inner {
        override fun unregister(listener: SensorEventListener) {
            inner.unregister(listener)
            removeFailure()?.let { failure: Throwable -> throw failure }
        }
    }

    private class RemovingPositions(val inner: ScriptedPositions, private val removeFailure: Throwable?) : HeadingPositions by inner {
        override fun unregister(listener: LocationListener) {
            inner.unregister(listener)
            removeFailure?.let { failure: Throwable -> throw failure }
        }
    }

    /** Records what it is handed; throws a fresh `Error` per call, or the very throwable it was handed, when told to. */
    private class MatrixLogger(private val mode: Log) : Logger {
        val handed: MutableList<Throwable> = mutableListOf()
        val thrownErrors: MutableList<Throwable> = mutableListOf()

        override val tag: String = "MatrixLogger"

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            message()
            if (throwable != null) handed += throwable
            when (mode) {
                Log.FINE -> Unit
                Log.THROWS_ERROR -> throw AssertionError("matrix logger Error at $level").also { error: AssertionError -> thrownErrors += error }
                Log.RETHROWS_GIVEN -> if (throwable != null) throw throwable
            }
        }
    }

    private fun Throwable.graph(seen: MutableSet<Throwable> = mutableSetOf()): Set<Throwable> {
        if (!seen.add(this)) return seen
        cause?.graph(seen)
        suppressedExceptions.forEach { suppressed: Throwable -> suppressed.graph(seen) }
        return seen
    }

    @Test
    fun `every removal failure logger behaviour and collection end releases both listeners once and loses nothing`() = runTest {
        for (position: Removal in listOf(Removal.FINE, Removal.EXCEPTION, Removal.ERROR)) {
            for (sensor: Removal in Removal.entries) {
                if (sensor == Removal.SAME_AS_POSITION && position == Removal.FINE) continue
                for (log: Log in Log.entries) {
                    for (end: End in End.entries) {
                        val combination = "position=$position sensor=$sensor log=$log end=$end"
                        val positionFailure: Throwable? = when (position) {
                            Removal.FINE -> null
                            Removal.EXCEPTION -> IllegalStateException("position removal exception")
                            else -> StackOverflowError("position removal Error")
                        }
                        val sensorFailure: Throwable? = when (sensor) {
                            Removal.FINE -> null
                            Removal.EXCEPTION -> IllegalArgumentException("sensor removal exception")
                            Removal.ERROR -> LinkageError("sensor removal Error")
                            Removal.SAME_AS_POSITION -> positionFailure
                        }
                        val collectorFailure = IllegalStateException("the collector's own failure")
                        val scripted = ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector))
                        val positions = ScriptedPositions()
                        // The removal only fails once the collection is ending (the undo of a registration is not under test).
                        var ending = false
                        val logger = MatrixLogger(log)
                        val provider: HeadingProvider = AndroidHeadingProvider(
                            RemovingSensors(scripted) { if (ending) sensorFailure else null },
                            RemovingPositions(positions, positionFailure),
                            AndroidHeadingSensor.AUTO,
                            logger,
                            { 1_700_000_000_000L },
                            { _, _, _ -> 0.0 },
                        )
                        val surfaced: MutableList<Throwable> = mutableListOf()
                        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                            try {
                                when (end) {
                                    End.CANCEL -> provider.observeHeading().collect { }
                                    End.COLLECTOR_FAILS -> provider.observeHeading().collect {
                                        ending = true
                                        throw collectorFailure
                                    }
                                    End.FIRST -> {
                                        provider.observeHeading().first()
                                        surfaced += IllegalStateException("marker: first() returned")
                                    }
                                }
                            } catch (thrown: Throwable) {
                                surfaced += thrown
                            }
                        }
                        assertEquals(1, scripted.liveRegistrations, "control: the sensor listener is registered; $combination")
                        assertEquals(1, positions.liveListeners.size, "control: the position listener is registered; $combination")
                        if (end == End.CANCEL) {
                            ending = true
                            collection.cancel()
                        } else {
                            if (end == End.FIRST) ending = true
                            scripted.deliver(rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(30.0).sensorValues(0.05f)))
                        }
                        testScheduler.runCurrent()
                        collection.join()

                        assertEquals(0, scripted.liveRegistrations, "the sensor listener is removed; $combination; surfaced=$surfaced")
                        assertEquals(0, positions.liveListeners.size, "the position listener is removed; $combination")
                        assertEquals(1, scripted.unregisterCalls.load(), "the sensor removal is attempted exactly once; $combination")
                        assertEquals(1, positions.unregisterCalls.load(), "the position removal is attempted exactly once; $combination")
                        assertEquals(1, surfaced.size, "the collection ends exactly once; $combination; surfaced=$surfaced")

                        val reachable: Set<Throwable> = surfaced.single().graph()
                        for (error: Throwable in logger.thrownErrors) {
                            assertTrue(error in reachable, "the logger's Error is not absorbed: $error; $combination; surfaced=$surfaced")
                        }
                        if (end == End.COLLECTOR_FAILS) {
                            assertTrue(collectorFailure in reachable, "the collector's own failure is never lost; $combination; surfaced=$surfaced")
                        }
                        for (failure: Throwable in listOfNotNull(positionFailure, sensorFailure)) {
                            assertTrue(
                                failure in reachable || logger.handed.any { handed: Throwable -> handed === failure },
                                "$failure is neither logged nor reachable; $combination; surfaced=$surfaced",
                            )
                        }
                        if (log == Log.FINE && end == End.COLLECTOR_FAILS) {
                            assertSame(collectorFailure, surfaced.single(), "a release that does not throw changes nothing; $combination")
                        }
                    }
                }
            }
        }
    }

    /** An `Error` that refuses suppression, as the JVM's preallocated errors do. */
    private class SuppressionRefusingError(message: String) : Error(message, null, false, false)

    @Test
    fun `a release Error that refuses suppression still surfaces unchanged after the release ran once`() = runTest {
        val refusing = SuppressionRefusingError("the release's Error, suppression disabled")
        val collectorFailure = IllegalStateException("the collector's own failure")
        var inputs: HeadingInputs<Double>? = null
        var releases = 0
        val stream = headingStream(
            minChangeDegrees = 0.0,
            initial = { Double.NaN },
            compose = { magnetic: Double -> if (magnetic.isNaN()) null else Heading(magnetic, null, null, false) },
            register = { registered: HeadingInputs<Double> ->
                inputs = registered
                AutoCloseable {
                    releases++
                    throw refusing
                }
            },
        )
        val surfaced: MutableList<Throwable> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                stream.collect { throw collectorFailure }
            } catch (thrown: Throwable) {
                surfaced += thrown
            }
        }
        assertEquals(0, releases, "control: nothing released while open")
        inputs!!.update { 42.0 }
        collection.join()

        assertEquals(1, releases, "released exactly once")
        assertSame(refusing, surfaced.single(), "the release's Error surfaces unchanged, not an exception of the refused addSuppressed")
        assertTrue(refusing.suppressedExceptions.isEmpty(), "control: this Error really refuses suppression")
    }
}
