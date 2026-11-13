package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the recorder tests run on a fake RecorderFileSystem whose paths are logical '/'-separated names

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * A system end of a recording (storage low, an interruption, a silenced input after its debounce) whose
 * file was deleted or replaced meanwhile must not publish Interrupted naming a path that is not an
 * existing regular file: RecordedFile promises "an existing, closed audio file" and Interrupted promises
 * a finalized one, so the documented answer is Failed carrying RecordingLost (as stop() answers a typed
 * EngineFailure for the same situation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderInterruptedVanishedR9Test {

    private val directReasons: List<InterruptionReason> = listOf(
        InterruptionReason.AudioSessionInterrupted,
        InterruptionReason.StorageLow,
        InterruptionReason.EngineDied(),
    )

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    // --- Q4: a system end must not publish a file that is gone ---

    @Test
    fun `a system end of a recording whose file vanished never publishes an interrupted file that does not exist`() {
        directReasons.forEach { reason: InterruptionReason ->
            runRecorderTest { fixture ->
                val path: String = fixture.recording()
                passTime(fixture, 2.seconds)
                fixture.fileSystem.notRegularFiles += path

                fixture.engine.emit(EngineEvent.Interrupted(reason))
                runCurrent()

                val state: RecorderState = fixture.recorder.state.value
                assertFalse(state is RecorderState.Interrupted, "reason $reason: Interrupted names a vanished file: $state")
            }
        }
    }

    @Test
    fun `a system end of a paused recording whose file vanished never publishes an interrupted file that does not exist`() {
        directReasons.forEach { reason: InterruptionReason ->
            runRecorderTest { fixture ->
                val path: String = fixture.recording()
                passTime(fixture, 2.seconds)
                fixture.recorder.pause()
                fixture.fileSystem.notRegularFiles += path

                fixture.engine.emit(EngineEvent.Interrupted(reason))
                runCurrent()

                val state: RecorderState = fixture.recorder.state.value
                assertFalse(state is RecorderState.Interrupted, "reason $reason: Interrupted names a vanished file: $state")
            }
        }
    }

    @Test
    fun `a debounced silence end of a recording whose file vanished never publishes an interrupted file that does not exist`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 1.seconds)
            fixture.fileSystem.notRegularFiles += path

            fixture.engine.emit(EngineEvent.InputSilenced(true))
            passTime(fixture, DEFAULT_SILENCE_DEBOUNCE + 100.milliseconds)

            val state: RecorderState = fixture.recorder.state.value
            assertFalse(state is RecorderState.Interrupted, "Interrupted names a vanished file: $state")
        }

    // --- the documented outcome: a file that is gone is a lost recording, kept by path ---

    @Test
    fun `a system end of a recording whose file vanished fails as a lost recording that keeps the path and frees the engine`() {
        directReasons.forEach { reason: InterruptionReason ->
            runRecorderTest { fixture ->
                val path: String = fixture.recording()
                passTime(fixture, 2.seconds)
                fixture.fileSystem.notRegularFiles += path

                fixture.engine.emit(EngineEvent.Interrupted(reason))
                runCurrent()

                val state: RecorderState = fixture.recorder.state.value
                assertIs<RecorderState.Failed>(state, "reason $reason: $state")
                val lost: RecorderError = state.error
                assertIs<RecorderError.RecordingLost>(lost, "reason $reason: $lost")
                assertEquals(reason, lost.reason)
                assertIs<IllegalStateException>(lost.cause, "reason $reason: the cause says what happened")
                assertEquals(path, state.outputPath, "reason $reason: the path is kept")
                assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths, "reason $reason: nothing is deleted")
                assertTrue(fixture.engine.releaseCount > 0, "reason $reason: the native handle is released")

                fixture.recorder.cancel()
                assertEquals(RecorderState.Idle, fixture.recorder.state.value)
            }
        }
    }
}
