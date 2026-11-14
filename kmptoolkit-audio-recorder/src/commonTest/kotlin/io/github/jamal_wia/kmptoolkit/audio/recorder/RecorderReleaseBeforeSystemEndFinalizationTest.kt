package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the recorder tests run on a fake RecorderFileSystem whose paths are logical '/'-separated names

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent

/**
 * The system ends a session and `release()` lands before the finalization the system end launched
 * on the recorder's own scope has started to run. `release()` cancels that scope, and it leaves
 * the engine to the finalizer ([DefaultAudioRecorder.release] touches it only when nothing is
 * finalizing), so the finalization has to run to the end anyway: otherwise the engine is never
 * released and the microphone stays held. This is the cancellation-mid-operation edge of the
 * non-cancellable finalization, pinned independently of how the launch is spelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderReleaseBeforeSystemEndFinalizationTest {

    @Test
    fun `a release before the finalization of an interrupted recording starts still finalizes and releases the engine`() =
        runRecorderTest { fixture ->
            fixture.recording()
            val releasesBefore: Int = fixture.engine.releaseCount

            // emit() hands the finalization to the recorder's scope; the test dispatcher has not run it yet.
            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
            fixture.recorder.release()
            runCurrent()

            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(1, fixture.engine.calls.count { it == "stop" }, "the encoder was stopped")
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount, "the engine was released exactly once")
            assertEquals(emptyList(), fixture.fileSystem.deletedPaths, "the recording is kept")
            assertFalse((fixture.recorder as DefaultAudioRecorder).isFinalizing, "the finalization ended")
        }

    @Test
    fun `a release before the finalization of an interrupted prepared recording starts still releases the engine and drops the file`() =
        runRecorderTest { fixture ->
            val path: String = fixture.prepared()
            val releasesBefore: Int = fixture.engine.releaseCount

            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            fixture.recorder.release()
            runCurrent()

            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount, "the engine was released exactly once")
            assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths, "the empty file goes")
            assertFalse((fixture.recorder as DefaultAudioRecorder).isFinalizing, "the finalization ended")
        }
}
