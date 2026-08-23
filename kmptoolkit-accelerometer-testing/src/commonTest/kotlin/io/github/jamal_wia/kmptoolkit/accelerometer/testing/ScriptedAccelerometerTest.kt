package io.github.jamal_wia.kmptoolkit.accelerometer.testing

import io.github.jamal_wia.kmptoolkit.accelerometer.AccelerometerSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * The fixture's own contract — what consumers' assertions rest on.
 */
class ScriptedAccelerometerTest {

    private val faceUp = AccelerometerSample(x = 0f, y = 0f, z = 9.8f)
    private val faceDown = AccelerometerSample(x = 0f, y = 0f, z = -9.8f)

    @Test
    fun `an unconfigured fixture is available and replays nothing`() = runTest {
        val accelerometer = ScriptedAccelerometer()

        assertTrue(accelerometer.isAvailable)

        val job = launch { accelerometer.observe().collect { fail("nothing was scripted") } }
        advanceUntilIdle()
        job.cancel()
    }

    @Test
    fun `replays the scripted samples in order`() = runTest {
        val accelerometer = ScriptedAccelerometer(samples = listOf(faceUp, faceDown))

        val collected = accelerometer.observe().take(2).toList()

        assertEquals(listOf(faceUp, faceDown), collected)
    }

    @Test
    fun `each collection replays the script from the start`() = runTest {
        val accelerometer = ScriptedAccelerometer(samples = listOf(faceUp, faceDown))

        val first = accelerometer.observe().take(2).toList()
        val second = accelerometer.observe().take(2).toList()

        assertEquals(first, second)
    }

    @Test
    fun `never emits when isAvailable is false`() = runTest {
        val accelerometer = ScriptedAccelerometer(isAvailable = false, samples = listOf(faceUp))

        val job = launch {
            accelerometer.observe().collect { fail("a device with no accelerometer must stay silent") }
        }
        advanceUntilIdle()
        job.cancel()
    }

    @Test
    fun `every collection counts as its own registration`() = runTest {
        val accelerometer = ScriptedAccelerometer()

        repeat(3) {
            val job = launch { accelerometer.observe().collect {} }
            advanceUntilIdle()
            job.cancel()
            job.join()
        }

        assertEquals(3, accelerometer.registrations)
    }

    @Test
    fun `activeCollectors drops back to zero once the collecting coroutine is cancelled`() = runTest {
        val accelerometer = ScriptedAccelerometer()

        val job = launch { accelerometer.observe().collect {} }
        advanceUntilIdle()
        assertEquals(1, accelerometer.activeCollectors)

        job.cancel()
        job.join()

        assertEquals(0, accelerometer.activeCollectors)
    }

    @Test
    fun `an unavailable accelerometer is registered but never becomes an active collector`() = runTest {
        val accelerometer = ScriptedAccelerometer(isAvailable = false)

        val job = launch { accelerometer.observe().collect {} }
        advanceUntilIdle()

        assertEquals(1, accelerometer.registrations)
        assertEquals(0, accelerometer.activeCollectors)

        job.cancel()
    }
}
