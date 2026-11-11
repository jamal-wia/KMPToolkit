package io.github.jamal_wia.kmptoolkit.audio.recorder

import io.github.jamal_wia.kmptoolkit.testsupport.PathSyntax
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy

/**
 * Edge inputs for the configuration and the level mapping: an infinite tick interval, floors at
 * the ends of the float range.
 */
class RecorderConfigContractTest {

    @Test
    fun `a relative directory path is rejected where it is written`() {
        assertFailsWith<IllegalArgumentException> { RecordingStorage(directoryPath = "relative/dir") }
    }

    @Test
    fun `an absolute directory path is still accepted`() {
        val directory: String = if (TestHost.pathSyntax == PathSyntax.WINDOWS) "C:\\abs\\dir" else "/abs/dir"

        assertEquals(directory, RecordingStorage(directoryPath = directory).directoryPath)
    }

    @Test
    fun `a slash path is absolute on POSIX and rejected as drive-relative on a Windows JVM`() {
        if (TestHost.pathSyntax == PathSyntax.WINDOWS) {
            assertFailsWith<IllegalArgumentException> { RecordingStorage(directoryPath = "/recordings") }
        } else {
            assertEquals("/recordings", RecordingStorage(directoryPath = "/recordings").directoryPath)
        }
    }

    @Test
    fun `a directory name inside the base is accepted whether nested or with a trailing separator`() {
        for (name in listOf("voice-notes", "audio/notes", "notes/")) {
            assertEquals(name, RecordingStorage(directoryName = name).directoryName)
        }
    }

    @Test
    fun `a directory name that can leave the base or names it is rejected`() {
        for (name in listOf(".", "..", "../x", "a/../../x", "a/./b", "/abs")) {
            assertFailsWith<IllegalArgumentException>("'$name' was accepted") { RecordingStorage(directoryName = name) }
        }
    }

    @Test
    fun `the level mapping stays inside zero to one for every legal floor and any reading`() {
        val random = Random(4)
        val floors: List<Float> = listOf(-Float.MIN_VALUE, -1e-30f, -0.001f, -1f, -50f, -120f, -1e30f, -Float.MAX_VALUE)
        val readings: List<Float> = listOf(
            Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 0f, -0f, 5f, -Float.MIN_VALUE, -Float.MAX_VALUE,
        ) + List(200) { -random.nextFloat() * 200f }
        for (floor in floors) {
            var previous = -1f
            for (reading in readings.filter { !it.isNaN() }.sorted()) {
                val level = normalizedLevel(reading, floor)
                assertTrue(level in 0f..1f, "floor $floor reading $reading gave $level")
                assertTrue(level >= previous, "floor $floor reading $reading gave $level after $previous")
                previous = level
            }
            assertEquals(0f, normalizedLevel(Float.NaN, floor))
        }
    }

    @Test
    fun `an infinite tick interval still records the right duration`() = runRecorderTest(
        AudioRecorderConfig(
            durationUpdateInterval = Duration.INFINITE,
            levelUpdateInterval = Duration.INFINITE,
        ),
    ) { f ->
        f.recording()
        f.timeSource += 3.seconds
        advanceTimeBy(3_000)

        val result = f.recorder.stop()

        assertEquals(3.seconds, (result as RecorderResult.Success).value.duration)
    }
}
