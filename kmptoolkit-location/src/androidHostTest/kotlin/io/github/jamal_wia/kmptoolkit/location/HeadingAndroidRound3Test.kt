@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.hardware.Sensor
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
 * Group loc-heading, round 3: the fix for breaker round 2 finding M1. When a collection ends, both
 * listener removals run FIRST, each on its own, and only then are their failures logged; so a logger that
 * throws anything, an `Error` included, can never keep one registration from being released.
 *
 * Deterministic: synchronous seams, unconfined collector, one shared event list that records the order of
 * the removals and the log calls, the random run uses `Random(HEADING_TEST_SEED + 3)` and prints `seed=`
 * in its messages. No sleeps.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidRound3Test {

    private val rig = SensorRig(ApplicationProvider.getApplicationContext())
    private val rotationVector: Sensor = rig.add(SensorTypes.ROTATION_VECTOR)

    /** What a logger of the app does with an event. */
    private enum class LoggerMode { RECORD, THROW_EXCEPTION, THROW_ERROR }

    /** Records every log call into [events] as `log`, then behaves as [mode] says. */
    private class OrderLogger(private val events: MutableList<String>, private val mode: LoggerMode) : Logger {
        override val tag: String = "OrderLogger"

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            events += "log"
            when (mode) {
                LoggerMode.RECORD -> Unit
                LoggerMode.THROW_EXCEPTION -> throw IllegalStateException("logger failure")
                LoggerMode.THROW_ERROR -> throw NotImplementedError("logger failure")
            }
        }
    }

    /** Sensors whose removal is recorded as `sensor` and, when [fails], throws before it removes anything. */
    private class RecordingSensors(
        private val inner: ScriptedSensors,
        private val events: MutableList<String>,
        private val fails: Boolean,
    ) : HeadingSensors by inner {
        override fun unregister(listener: android.hardware.SensorEventListener) {
            events += "sensor"
            if (fails) throw IllegalStateException("simulated sensor removal failure")
            inner.unregister(listener)
        }
    }

    /** Positions whose removal is recorded as `position` and, when [fails], throws before it removes anything. */
    private class RecordingPositions(
        private val inner: ScriptedPositions,
        private val events: MutableList<String>,
        private val fails: Boolean,
    ) : HeadingPositions by inner {
        override fun unregister(listener: LocationListener) {
            events += "position"
            if (fails) throw IllegalStateException("simulated position removal failure")
            inner.unregister(listener)
        }
    }

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

    private fun provider(sensors: HeadingSensors, positions: HeadingPositions, log: Logger): HeadingProvider =
        AndroidHeadingProvider(sensors, positions, AndroidHeadingSensor.AUTO, log, { 1_700_000_000_000L }, { _, _, _ -> 0.0 })

    private fun rotationVectorSensors(): ScriptedSensors = ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector))

    @Test
    fun `both failing removals are attempted before the first of them is logged`() = runTest {
        val events: MutableList<String> = mutableListOf()
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val collection: Job = collectRecordingFailure(
            provider(RecordingSensors(sensors, events, fails = true), RecordingPositions(positions, events, fails = true), OrderLogger(events, LoggerMode.RECORD)).observeHeading(),
            mutableListOf(),
        )
        assertEquals(1, sensors.liveRegistrations, "control: the sensor listener is registered")
        assertEquals(1, positions.liveListeners.size, "control: the position listener is registered")
        assertTrue(events.isEmpty(), "control: nothing was removed or logged while collecting, saw $events")

        collection.cancelAndJoin()

        assertEquals(listOf("position", "sensor", "log", "log"), events, "removals first, then one log per failed removal")
    }

    @Test
    fun `a failing sensor removal with a logger that throws an Error still removed the position listener first`() = runTest {
        val events: MutableList<String> = mutableListOf()
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val failure: MutableList<Throwable> = mutableListOf()
        val collection: Job = collectRecordingFailure(
            provider(RecordingSensors(sensors, events, fails = true), RecordingPositions(positions, events, fails = false), OrderLogger(events, LoggerMode.THROW_ERROR)).observeHeading(),
            failure,
        )
        assertEquals(1, positions.liveListeners.size, "control: the position listener is registered")

        collection.cancelAndJoin()

        assertEquals(0, positions.liveListeners.size, "the position listener is released whatever the logger does, collector saw $failure")
        assertEquals(listOf("position", "sensor", "log"), events)
    }

    @Test
    fun `a failing position removal with a logger that throws an Exception releases the sensor listener and does not throw`() = runTest {
        val events: MutableList<String> = mutableListOf()
        val sensors: ScriptedSensors = rotationVectorSensors()
        val positions = ScriptedPositions()
        val failure: MutableList<Throwable> = mutableListOf()
        val collection: Job = collectRecordingFailure(
            provider(RecordingSensors(sensors, events, fails = false), RecordingPositions(positions, events, fails = true), OrderLogger(events, LoggerMode.THROW_EXCEPTION)).observeHeading(),
            failure,
        )
        assertEquals(1, sensors.liveRegistrations, "control: the sensor listener is registered")

        collection.cancelAndJoin()

        assertEquals(0, sensors.liveRegistrations, "the sensor listener is released")
        assertEquals(emptyList(), failure, "an exception from the logger is absorbed, nothing reaches the collector")
        assertEquals(listOf("position", "sensor", "log"), events)
    }

    @Test
    fun `a seeded run of every failure and logger combination attempts both removals before any log and releases what can be released`() = runTest {
        val random = Random(HEADING_TEST_SEED + 3)
        repeat(120) { round: Int ->
            val failSensor: Boolean = random.nextBoolean()
            val failPosition: Boolean = random.nextBoolean()
            val mode: LoggerMode = LoggerMode.entries[random.nextInt(LoggerMode.entries.size)]
            val message = "seed=${HEADING_TEST_SEED + 3} round=$round failSensor=$failSensor failPosition=$failPosition logger=$mode"
            val events: MutableList<String> = mutableListOf()
            val sensors: ScriptedSensors = rotationVectorSensors()
            val positions = ScriptedPositions()
            val collection: Job = collectRecordingFailure(
                provider(RecordingSensors(sensors, events, failSensor), RecordingPositions(positions, events, failPosition), OrderLogger(events, mode)).observeHeading(),
                mutableListOf(),
            )
            assertEquals(1, sensors.liveRegistrations, "control: the sensor listener is registered, $message")
            assertEquals(1, positions.liveListeners.size, "control: the position listener is registered, $message")

            collection.cancelAndJoin()

            val removals: List<String> = events.filter { it != "log" }
            assertEquals(listOf("position", "sensor"), removals, "each removal attempted exactly once, $message")
            val firstLog: Int = events.indexOf("log")
            assertTrue(firstLog == -1 || firstLog >= 2, "no log call precedes the second removal, events=$events, $message")
            if (!failSensor) assertEquals(0, sensors.liveRegistrations, "sensor listener released, $message")
            if (!failPosition) assertEquals(0, positions.liveListeners.size, "position listener released, $message")
            val expectedLogs: Int = (if (failSensor) 1 else 0) + (if (failPosition) 1 else 0)
            val expectedLogsSeen: Int = if (mode == LoggerMode.THROW_ERROR) minOf(expectedLogs, 1) else expectedLogs
            assertEquals(expectedLogsSeen, events.count { it == "log" }, "logs of the failed removals, $message")
        }
    }
}
