package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * An input the OS silenced while the recorder kept running: reported only once it has stayed
 * silenced for the debounce, with the elapsed time frozen where the silence began. Time is virtual:
 * the debounce runs on the test scheduler and elapsed on the fixture's time source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderSilenceTest {

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun `a silence that outlasts the debounce ends the recording at the moment it began`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 1.seconds)

            fixture.engine.emit(EngineEvent.InputSilenced(true))
            passTime(fixture, DEFAULT_SILENCE_DEBOUNCE + 100.milliseconds)

            assertEquals(
                RecorderState.Interrupted(
                    RecordedFile(path, 1.seconds),
                    InterruptionReason.MicrophoneSilenced,
                ),
                fixture.recorder.state.value,
            )
            assertEquals(1.seconds, fixture.recorder.elapsed.value, "frozen before the debounce")
            assertEquals(1, fixture.engine.calls.count { it == "stop" })
            assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
        }

    @Test
    fun `a silence that ends within the debounce is ignored`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        passTime(fixture, 1.seconds)

        fixture.engine.emit(EngineEvent.InputSilenced(true))
        passTime(fixture, DEFAULT_SILENCE_DEBOUNCE / 2)
        fixture.engine.emit(EngineEvent.InputSilenced(false))
        passTime(fixture, 5.seconds)

        assertEquals(RecorderState.Recording(path), fixture.recorder.state.value)
        assertEquals(0, fixture.engine.calls.count { it == "stop" })
    }

    @Test
    fun `a second silence after an ignored one is debounced from its own start`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 1.seconds)
            fixture.engine.emit(EngineEvent.InputSilenced(true))
            passTime(fixture, DEFAULT_SILENCE_DEBOUNCE / 2)
            fixture.engine.emit(EngineEvent.InputSilenced(false))
            passTime(fixture, 1.seconds)
            val secondBegan: Duration = fixture.recorder.elapsed.value

            fixture.engine.emit(EngineEvent.InputSilenced(true))
            passTime(fixture, DEFAULT_SILENCE_DEBOUNCE + 50.milliseconds)

            val state: RecorderState = fixture.recorder.state.value
            assertIs<RecorderState.Interrupted>(state)
            assertEquals(path, state.recording.path)
            assertEquals(secondBegan, state.recording.duration)
        }

    @Test
    fun `repeated silenced reports do not restart the debounce`() = runRecorderTest { fixture ->
        fixture.recording()
        passTime(fixture, 1.seconds)

        fixture.engine.emit(EngineEvent.InputSilenced(true))
        passTime(fixture, DEFAULT_SILENCE_DEBOUNCE / 2)
        fixture.engine.emit(EngineEvent.InputSilenced(true))
        passTime(fixture, DEFAULT_SILENCE_DEBOUNCE / 2 + 50.milliseconds)

        assertIs<RecorderState.Interrupted>(fixture.recorder.state.value)
    }

    @Test
    fun `a silence is not raised while paused`() = runRecorderTest { fixture ->
        fixture.recording()
        passTime(fixture, 1.seconds)
        fixture.recorder.pause()
        val paused: RecorderState = fixture.recorder.state.value

        fixture.engine.emit(EngineEvent.InputSilenced(true))
        passTime(fixture, 5.seconds)
        fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.MicrophoneSilenced))
        runCurrent()

        assertEquals(paused, fixture.recorder.state.value)
    }

    @Test
    fun `pausing cancels a silence that was being debounced`() = runRecorderTest { fixture ->
        fixture.recording()
        passTime(fixture, 1.seconds)
        fixture.engine.emit(EngineEvent.InputSilenced(true))

        fixture.recorder.pause()
        passTime(fixture, 5.seconds)

        assertIs<RecorderState.Paused>(fixture.recorder.state.value)
    }

    @Test
    fun `resuming into a silenced input ends the recording with elapsed frozen at the resume`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 2.seconds)
            fixture.recorder.pause()
            passTime(fixture, 10.seconds)
            // The engine looks at the active recording configuration right after resume().
            fixture.engine.onResume = { fixture.engine.emit(EngineEvent.InputSilenced(true)) }

            assertEquals(RecorderResult.Success(Unit), fixture.recorder.resume())
            assertEquals(RecorderState.Recording(path), fixture.recorder.state.value)
            passTime(fixture, DEFAULT_SILENCE_DEBOUNCE + 100.milliseconds)

            assertEquals(
                RecorderState.Interrupted(
                    RecordedFile(path, 2.seconds),
                    InterruptionReason.MicrophoneSilenced,
                ),
                fixture.recorder.state.value,
            )
        }

    @Test
    fun `a recording that starts silenced ends with no audio`() = runRecorderTest { fixture ->
        val path: String = fixture.prepared()
        fixture.engine.onStart = { fixture.engine.emit(EngineEvent.InputSilenced(true)) }

        fixture.recorder.start()
        passTime(fixture, DEFAULT_SILENCE_DEBOUNCE + 100.milliseconds)

        assertEquals(
            RecorderState.Interrupted(
                RecordedFile(path, Duration.ZERO),
                InterruptionReason.MicrophoneSilenced,
            ),
            fixture.recorder.state.value,
        )
    }

    @Test
    fun `a silence is ignored before the recording starts`() = runRecorderTest { fixture ->
        val path: String = fixture.prepared()

        fixture.engine.emit(EngineEvent.InputSilenced(true))
        passTime(fixture, 5.seconds)

        assertEquals(RecorderState.Ready(path), fixture.recorder.state.value)
    }

    @Test
    fun `stopping while a silence is being debounced completes normally`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        passTime(fixture, 1.seconds)
        fixture.engine.emit(EngineEvent.InputSilenced(true))

        fixture.recorder.stop()
        passTime(fixture, 5.seconds)

        assertEquals(
            RecorderState.Completed(RecordedFile(path, 1.seconds)),
            fixture.recorder.state.value,
        )
    }

    @Test
    fun `another event during the debounce wins and the silence is dropped`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        passTime(fixture, 1.seconds)
        fixture.engine.emit(EngineEvent.InputSilenced(true))

        fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
        passTime(fixture, 5.seconds)

        assertEquals(
            RecorderState.Interrupted(
                RecordedFile(path, 1.seconds),
                InterruptionReason.AudioSessionInterrupted,
            ),
            fixture.recorder.state.value,
        )
        assertEquals(1, fixture.engine.calls.count { it == "stop" })
    }

    @Test
    fun `the debounce is a constant of the module and not zero`() {
        assertEquals(400.milliseconds, DEFAULT_SILENCE_DEBOUNCE)
    }
}
