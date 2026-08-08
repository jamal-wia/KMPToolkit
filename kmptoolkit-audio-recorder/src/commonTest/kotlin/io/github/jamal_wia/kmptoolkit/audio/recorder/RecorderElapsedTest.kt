package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * [AudioRecorder.elapsed]: that it tracks recorded time, freezes across a pause rather than
 * counting it, and resets exactly where the contract says it does.
 *
 * The recorder's own clock is a [kotlin.time.TestTimeSource] driven by hand here, so every expected
 * value below is exact rather than approximate.
 */
class RecorderElapsedTest {

    @Test
    fun `elapsed is zero before anything is recorded`() = runRecorderTest { fixture ->
        assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)

        fixture.prepared()

        assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)
    }

    @Test
    fun `elapsed advances while recording`() = runRecorderTest { fixture ->
        fixture.recording()

        fixture.timeSource += 3.seconds
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(3.seconds, fixture.recorder.elapsed.value)
    }

    @Test
    fun `elapsed only republishes on a tick`() {
        val config = AudioRecorderConfig(durationUpdateInterval = 500.milliseconds)
        runRecorderTest(config) { fixture ->
            fixture.recording()

            fixture.timeSource += 3.seconds
            advanceTimeBy(499.milliseconds)
            runCurrent()

            assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)

            advanceTimeBy(2.milliseconds)
            runCurrent()

            assertEquals(3.seconds, fixture.recorder.elapsed.value)
        }
    }

    @Test
    fun `pause freezes elapsed at the moment of the pause`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.timeSource += 4.seconds

        fixture.recorder.pause()

        assertEquals(4.seconds, fixture.recorder.elapsed.value)

        fixture.timeSource += 10.seconds
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(4.seconds, fixture.recorder.elapsed.value)
    }

    @Test
    fun `paused time is excluded from the recorded duration`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.timeSource += 4.seconds
        fixture.recorder.pause()
        fixture.timeSource += 10.seconds
        fixture.recorder.resume()
        fixture.timeSource += 6.seconds

        val recorded: RecordedFile = requireNotNull(fixture.recorder.stop().getOrNull())

        assertEquals(10.seconds, recorded.duration)
        assertEquals(10.seconds, fixture.recorder.elapsed.value)
    }

    @Test
    fun `the paused state carries the elapsed time it froze at`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        fixture.timeSource += 7.seconds

        fixture.recorder.pause()

        assertEquals(RecorderState.Paused(path, 7.seconds), fixture.recorder.state.value)
    }

    @Test
    fun `stop reports the duration measured at the moment of the stop`() =
        runRecorderTest { fixture ->
            fixture.recording()
            fixture.timeSource += 2500.milliseconds

            val recorded: RecordedFile = requireNotNull(fixture.recorder.stop().getOrNull())

            assertEquals(2500.milliseconds, recorded.duration)
        }

    @Test
    fun `a second recording starts counting from zero`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.timeSource += 9.seconds
        fixture.recorder.stop()

        fixture.recording(outputPath = "/data/app/second.m4a")

        assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)

        fixture.timeSource += 1.seconds
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(1.seconds, fixture.recorder.elapsed.value)
    }

    @Test
    fun `cancel resets elapsed`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.timeSource += 5.seconds
        advanceTimeBy(1.seconds)
        runCurrent()

        fixture.recorder.cancel()

        assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)
    }

    @Test
    fun `preparing a new recording resets elapsed`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.timeSource += 5.seconds
        fixture.recorder.stop()
        assertEquals(5.seconds, fixture.recorder.elapsed.value)

        fixture.prepared(outputPath = "/data/app/second.m4a")

        assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)
    }
}
