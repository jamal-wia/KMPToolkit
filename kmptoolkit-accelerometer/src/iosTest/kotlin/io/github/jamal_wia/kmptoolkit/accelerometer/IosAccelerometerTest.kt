package io.github.jamal_wia.kmptoolkit.accelerometer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The fan-out that lets several collections share Core Motion's single update handler, driven by a
 * scripted source with synchronous confinement — the simulator has no accelerometer, and Core Motion
 * itself is not what is under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosAccelerometerTest {

    private class ScriptedSource(override val isAvailable: Boolean = true) : MotionSource {
        var starts: Int = 0
        var stops: Int = 0
        var intervalSeconds: Double? = null
        private var handler: ((AccelerometerSample) -> Unit)? = null

        val isRunning: Boolean get() = handler != null

        override fun start(intervalSeconds: Double, onSample: (AccelerometerSample) -> Unit) {
            starts++
            this.intervalSeconds = intervalSeconds
            handler = onSample
        }

        override fun stop() {
            stops++
            handler = null
        }

        fun emit(sample: AccelerometerSample) {
            handler?.invoke(sample)
        }
    }

    private fun accelerometer(source: MotionSource, interval: Duration = 200.milliseconds): Accelerometer =
        IosAccelerometer(interval, source) { block: () -> Unit -> block() }

    @Test
    fun `updates start with the first collection and stop when it ends`() = runTest {
        val source = ScriptedSource()
        val accelerometer: Accelerometer = accelerometer(source)
        assertFalse(source.isRunning)

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { }
        }
        assertEquals(1, source.starts)
        assertEquals(0.2, source.intervalSeconds)

        collection.cancel()
        assertEquals(1, source.stops)
        assertFalse(source.isRunning)
    }

    @Test
    fun `ending one of two collections leaves the other receiving`() = runTest {
        // The defect this shape exists for: with a start and stop per collection, the first one
        // ending called stopAccelerometerUpdates and silenced the second.
        val source = ScriptedSource()
        val accelerometer: Accelerometer = accelerometer(source)
        val second: MutableList<AccelerometerSample> = mutableListOf()
        val firstCollection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { sample: AccelerometerSample -> second += sample }
        }

        firstCollection.cancel()
        source.emit(AccelerometerSample(1f, 2f, 3f))

        assertEquals(listOf(AccelerometerSample(1f, 2f, 3f)), second)
        assertEquals(1, source.starts, "the second collection replaced the first one's handler")
        assertEquals(0, source.stops)
    }

    @Test
    fun `every collection receives every sample`() = runTest {
        val source = ScriptedSource()
        val accelerometer: Accelerometer = accelerometer(source)
        val first: MutableList<AccelerometerSample> = mutableListOf()
        val second: MutableList<AccelerometerSample> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { sample: AccelerometerSample -> first += sample }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { sample: AccelerometerSample -> second += sample }
        }

        source.emit(AccelerometerSample(0f, 0f, 9.81f))

        assertEquals(listOf(AccelerometerSample(0f, 0f, 9.81f)), first)
        assertEquals(first, second)
    }

    @Test
    fun `updates restart for a collection that begins after all others ended`() = runTest {
        val source = ScriptedSource()
        val accelerometer: Accelerometer = accelerometer(source)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { accelerometer.observe().collect { } }
            .cancel()
        val received: MutableList<AccelerometerSample> = mutableListOf()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { sample: AccelerometerSample -> received += sample }
        }
        source.emit(AccelerometerSample(4f, 5f, 6f))

        assertEquals(2, source.starts)
        assertEquals(listOf(AccelerometerSample(4f, 5f, 6f)), received)
    }

    @Test
    fun `without hardware nothing starts and observe stays open and silent`() = runTest {
        val source = ScriptedSource(isAvailable = false)
        val accelerometer: Accelerometer = accelerometer(source)

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            accelerometer.observe().collect { }
        }

        assertFalse(accelerometer.isAvailable)
        assertEquals(0, source.starts)
        assertTrue(collection.isActive)
    }

    @Test
    fun `a negative interval asks Core Motion for its fastest rate`() = runTest {
        val source = ScriptedSource()
        val accelerometer: Accelerometer = accelerometer(source, interval = (-1).milliseconds)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { accelerometer.observe().collect { } }

        assertEquals(0.0, source.intervalSeconds)
    }

    @Test
    fun `the real factory survives the simulator which has no accelerometer`() {
        assertFalse(createAccelerometer().isAvailable)
    }
}
