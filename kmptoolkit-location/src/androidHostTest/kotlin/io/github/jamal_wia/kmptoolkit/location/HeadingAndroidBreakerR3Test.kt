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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Breaker round 3 of group loc-heading (decision D-23, ticket item LOC-11): attacks on the round-3 release handle of
 * the Android heading provider ("both removals first, then the logging"; `@param logger`: "An `Error` is not absorbed
 * ... but even then every registration is released first").
 *
 * - A removal that itself throws an `Error` (not the logger): the other removal still runs, `close()` keeps the
 *   `headingStream` contract (it does not throw), and the very failure object reaches the logger.
 * - Both removals throwing, in both kinds: both are attempted, both are reported, each with its own cause.
 * - The order "every removal before any log" on every exit path of a collection: cancellation, a collector that
 *   throws, and a terminal operator (`first`).
 * - The original failure of a collection is not lost when the release then meets a logger that throws an `Error`
 *   (the standard `use {}` rule of the Kotlin library, and the rule `registerCollection` already applies to its own
 *   undo: "The undo's own failures ride along as suppressed instead of replacing the original").
 *
 * Deterministic: synchronous delivery through [ScriptedSensors] / [ScriptedPositions], unconfined collector. No
 * sleeps, no wall clock, no randomness.
 */
@RunWith(AndroidJUnit4::class)
class HeadingAndroidBreakerR3Test {

    private val rig = SensorRig(ApplicationProvider.getApplicationContext())
    private val rotationVector: Sensor = rig.add(SensorTypes.ROTATION_VECTOR)

    /** A logger that records every call (with its throwable) into [events], then optionally throws. */
    private class EventLogger(private val events: MutableList<String>, private val throwOnLog: Throwable? = null) : Logger {
        val throwables: MutableList<Throwable?> = mutableListOf()

        override val tag: String = "EventLogger"

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            events += "log:${message()}"
            throwables += throwable
            throwOnLog?.let { failure: Throwable -> throw failure }
        }
    }

    /** Sensors whose removal records into [events], removes, then throws [failure] when given. */
    private class FailingSensors(
        val inner: ScriptedSensors,
        private val events: MutableList<String>,
        private val failure: Throwable?,
    ) : HeadingSensors by inner {
        override fun unregister(listener: SensorEventListener) {
            events += "remove-sensor"
            if (failure != null) throw failure
            inner.unregister(listener)
        }
    }

    /** Positions whose removal records into [events] and throws [failure] (before removing) when given. */
    private class FailingPositions(
        val inner: ScriptedPositions,
        private val events: MutableList<String>,
        private val failure: Throwable?,
    ) : HeadingPositions by inner {
        override fun unregister(listener: LocationListener) {
            events += "remove-position"
            if (failure != null) throw failure
            inner.unregister(listener)
        }
    }

    private fun provider(sensors: HeadingSensors, positions: HeadingPositions, log: Logger): HeadingProvider =
        AndroidHeadingProvider(sensors, positions, AndroidHeadingSensor.AUTO, log, { FIXED_TIME }, { _, _, _ -> DECLINATION })

    private fun sample() = rig.event(SensorTypes.ROTATION_VECTOR, Quat.of(30.0).sensorValues())

    /** Every log event comes after both removal events, and each removal was attempted exactly once. */
    private fun assertRemovalsBeforeLogs(events: List<String>, path: String) {
        val removals: List<Int> = events.indices.filter { index: Int -> events[index].startsWith("remove-") }
        val logs: List<Int> = events.indices.filter { index: Int -> events[index].startsWith("log:") }
        assertEquals(1, events.count { it == "remove-position" }, "$path: the position removal is attempted once; events=$events")
        assertEquals(1, events.count { it == "remove-sensor" }, "$path: the sensor removal is attempted once; events=$events")
        assertTrue(logs.isNotEmpty(), "$path: control, the failed removals are reported; events=$events")
        assertTrue(removals.max() < logs.min(), "$path: every removal comes before any log; events=$events")
    }

    private fun Throwable.chain(): List<Throwable> = listOf(this) + suppressedExceptions.flatMap { suppressed: Throwable -> suppressed.chain() }

    @Test
    fun `a position removal that throws an Error does not stop the sensor removal and the Error reaches the logger unchanged`() = runTest {
        val events: MutableList<String> = mutableListOf()
        val removalError = LinkageError("simulated linkage failure in removeUpdates")
        val sensors = FailingSensors(ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector)), events, null)
        val positions = FailingPositions(ScriptedPositions(), events, removalError)
        val log = EventLogger(events)
        val failures: MutableList<Throwable> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                provider(sensors, positions, log).observeHeading().collect { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                failures += thrown
            }
        }
        assertEquals(1, sensors.inner.liveRegistrations, "control: the sensor listener is registered")
        assertEquals(1, positions.inner.liveListeners.size, "control: the position listener is registered")

        collection.cancelAndJoin()

        assertEquals(0, sensors.inner.liveRegistrations, "the sensor listener is removed although the first removal threw an Error; events=$events")
        assertEquals(emptyList(), failures, "close() keeps the headingStream contract: it does not throw for a failed removal")
        assertEquals(listOf("remove-position", "remove-sensor"), events.filter { it.startsWith("remove-") }, "order of the removals")
        assertSame(removalError, log.throwables.single(), "the failed removal is reported with its own cause; events=$events")
    }

    @Test
    fun `both removals throwing an Error and an exception are both attempted and both reported with their own cause`() = runTest {
        val events: MutableList<String> = mutableListOf()
        val positionError = StackOverflowError("simulated position removal failure")
        val sensorFailure = IllegalStateException("simulated sensor removal failure")
        val sensors = FailingSensors(ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector)), events, sensorFailure)
        val positions = FailingPositions(ScriptedPositions(), events, positionError)
        val log = EventLogger(events)
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider(sensors, positions, log).observeHeading().collect { }
        }
        assertEquals(1, sensors.inner.liveRegistrations, "control: the sensor listener is registered")

        collection.cancelAndJoin()

        assertRemovalsBeforeLogs(events, "cancel")
        assertEquals(listOf<Throwable?>(positionError, sensorFailure), log.throwables, "each failure reported once with its own cause; events=$events")
        assertTrue(positions.inner.liveListeners.size == 1, "control: the failing position removal removed nothing (it threw first)")
    }

    @Test
    fun `every removal comes before any log when the collection ends by cancellation by a failing collector or by first`() = runTest {
        for (path: String in listOf("cancel", "collector-throws", "first")) {
            val events: MutableList<String> = mutableListOf()
            val sensors = FailingSensors(
                ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector)),
                events,
                IllegalStateException("sensor removal failure ($path)"),
            )
            val positions = FailingPositions(ScriptedPositions(), events, IllegalStateException("position removal failure ($path)"))
            val log = EventLogger(events)
            val flow = provider(sensors, positions, log).observeHeading()
            when (path) {
                "cancel" -> {
                    val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { } }
                    sensors.inner.deliver(sample())
                    collection.cancelAndJoin()
                }
                "collector-throws" -> {
                    val boom = IllegalStateException("collector failure")
                    val seen: MutableList<Throwable> = mutableListOf()
                    val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        try {
                            flow.collect { throw boom }
                        } catch (thrown: IllegalStateException) {
                            seen += thrown
                        }
                    }
                    sensors.inner.deliver(sample())
                    collection.join()
                    assertSame(boom, seen.single(), "$path: the collector's own failure surfaces unchanged with a logger that records")
                }
                "first" -> {
                    val received: MutableList<Heading> = mutableListOf()
                    val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { received += flow.first() }
                    sensors.inner.deliver(sample())
                    collection.join()
                    assertEquals(1, received.size, "$path: first() returns its value although both removals failed")
                }
            }
            assertRemovalsBeforeLogs(events, path)
        }
    }

    @Test
    fun `a collector failure is not lost when the release then meets a logger that throws an Error`() = runTest {
        val events: MutableList<String> = mutableListOf()
        val loggerError = AssertionError("test logger refuses a WARN")
        val sensors = FailingSensors(ScriptedSensors(mapOf(SensorTypes.ROTATION_VECTOR to rotationVector)), events, null)
        val positions = FailingPositions(ScriptedPositions(), events, IllegalStateException("simulated position removal failure"))
        val log = EventLogger(events, throwOnLog = loggerError)
        val boom = IllegalStateException("the collector's own failure")
        val seen: MutableList<Throwable> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                provider(sensors, positions, log).observeHeading().collect { throw boom }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                seen += thrown
            }
        }
        assertEquals(1, sensors.inner.liveRegistrations, "control: the sensor listener is registered")

        sensors.inner.deliver(sample())
        collection.join()

        assertEquals(0, sensors.inner.liveRegistrations, "control: released first, as the round-3 KDoc promises; events=$events")
        val chain: List<Throwable> = seen.single().chain()
        assertTrue(
            chain.any { it === boom } && chain.any { it === loggerError },
            "the collection's own failure and the release's Error must both stay reachable (one thrown, the other " +
                "suppressed), as Kotlin's use {} does; the collector saw ${seen.single()} with suppressed " +
                "${seen.single().suppressedExceptions}",
        )
    }

    private companion object {
        const val FIXED_TIME: Long = 1_700_000_000_000L
        const val DECLINATION: Double = 5.0
    }
}
