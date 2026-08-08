package io.github.jamal_wia.kmptoolkit.audio.recorder.testing

import io.github.jamal_wia.kmptoolkit.audio.recorder.RecordedFile
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderError
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderOperation
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderResult
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderState
import io.github.jamal_wia.kmptoolkit.audio.recorder.errorOrNull
import io.github.jamal_wia.kmptoolkit.audio.recorder.getOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * The fake is only useful if it refuses what the real recorder refuses — a test that passes against
 * a permissive double proves nothing. These cases are derived from the transition table documented
 * on `AudioRecorder`, the same source the production recorder's own suite works from, so the two
 * cannot quietly drift apart.
 */
class FakeAudioRecorderTest {

    @Test
    fun `a fresh fake is idle`() {
        assertEquals(RecorderState.Idle, FakeAudioRecorder().state.value)
    }

    @Test
    fun `the happy path walks prepare start pause resume and stop`() = runTest {
        val recorder = FakeAudioRecorder()

        val path: String = requireNotNull(recorder.prepare().getOrNull())
        assertEquals(RecorderState.Ready(path), recorder.state.value)

        recorder.start()
        assertEquals(RecorderState.Recording(path), recorder.state.value)

        recorder.advanceElapsed(2.seconds)
        recorder.pause()
        assertEquals(RecorderState.Paused(path, 2.seconds), recorder.state.value)

        recorder.resume()
        recorder.advanceElapsed(3.seconds)
        val recorded: RecordedFile = requireNotNull(recorder.stop().getOrNull())

        assertEquals(RecordedFile(path, 5.seconds), recorded)
        assertEquals(RecorderState.Completed(recorded), recorder.state.value)
        assertContentEquals(listOf(recorded), recorder.completedRecordings)
    }

    @Test
    fun `generated paths are distinct so two recordings never collide`() = runTest {
        val recorder = FakeAudioRecorder()

        val first: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.stop()
        val second: String = requireNotNull(recorder.prepare().getOrNull())

        assertContentEquals(listOf(first, second), recorder.preparedPaths)
        assertEquals(2, recorder.preparedPaths.toSet().size)
    }

    @Test
    fun `an explicit path is honored`() = runTest {
        val recorder = FakeAudioRecorder()

        assertEquals(
            RecorderResult.Success("/tmp/take-1.m4a"),
            recorder.prepare(outputPath = "/tmp/take-1.m4a"),
        )
    }

    // --- the refusals that make the fake worth using ---

    @Test
    fun `starting twice is refused just as the real recorder refuses it`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()

        assertEquals(
            RecorderError.IllegalState(RecorderState.Recording(path), RecorderOperation.START),
            recorder.start().errorOrNull(),
        )
    }

    @Test
    fun `stopping before starting is refused`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())

        assertEquals(
            RecorderError.IllegalState(RecorderState.Ready(path), RecorderOperation.STOP),
            recorder.stop().errorOrNull(),
        )
    }

    @Test
    fun `start is refused while paused because resume is the only way back`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.pause()
        val paused: RecorderState = recorder.state.value

        assertEquals(
            RecorderError.IllegalState(paused, RecorderOperation.START),
            recorder.start().errorOrNull(),
        )
    }

    @Test
    fun `cancel refuses to throw away a completed recording`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.stop()

        assertEquals(
            RecorderError.IllegalState(recorder.state.value, RecorderOperation.CANCEL),
            recorder.cancel().errorOrNull(),
        )
        assertContentEquals(emptyList(), recorder.deletedPaths)
    }

    @Test
    fun `cancel discards the recording and returns to idle`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.advanceElapsed(4.seconds)

        recorder.cancel()

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertContentEquals(listOf(path), recorder.deletedPaths)
        assertEquals(Duration.ZERO, recorder.elapsed.value)
    }

    @Test
    fun `re-preparing over an unused file discards it`() = runTest {
        val recorder = FakeAudioRecorder()
        val first: String = requireNotNull(recorder.prepare().getOrNull())

        recorder.prepare()

        assertContentEquals(listOf(first), recorder.deletedPaths)
    }

    // --- scripted failures ---

    @Test
    fun `a denied permission fails prepare`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.permissionGranted = false

        assertEquals(
            RecorderResult.Failure(RecorderError.PermissionDenied),
            recorder.prepare(),
        )
        assertEquals(RecorderState.Failed(RecorderError.PermissionDenied), recorder.state.value)
    }

    @Test
    fun `a scripted error fails the next operation and only that one`() = runTest {
        val recorder = FakeAudioRecorder()
        val error = RecorderError.InsufficientStorage(
            path = "/fake/recordings",
            requiredBytes = 100,
            availableBytes = 1,
        )
        recorder.failNextOperationWith = error

        assertEquals(RecorderResult.Failure(error), recorder.prepare())
        assertEquals(RecorderState.Failed(error), recorder.state.value)

        assertEquals(
            RecorderResult.Success("/fake/recordings/recording_1.m4a"),
            recorder.prepare(),
        )
    }

    @Test
    fun `an illegal transition does not consume the scripted error`() = runTest {
        val recorder = FakeAudioRecorder()
        val error = RecorderError.EngineFailure(RecorderOperation.START)
        recorder.failNextOperationWith = error

        recorder.start()

        assertEquals(error, recorder.failNextOperationWith)
    }

    // --- release ---

    @Test
    fun `release is idempotent and terminal`() = runTest {
        val recorder = FakeAudioRecorder()

        recorder.release()
        recorder.release()

        assertEquals(1, recorder.releaseCount)
        assertEquals(RecorderState.Released, recorder.state.value)
        assertEquals(
            RecorderError.AlreadyReleased(RecorderOperation.PREPARE),
            recorder.prepare().errorOrNull(),
        )
        assertEquals(
            RecorderError.AlreadyReleased(RecorderOperation.STOP),
            recorder.stop().errorOrNull(),
        )
    }

    // --- elapsed ---

    @Test
    fun `elapsed only moves while recording`() = runTest {
        val recorder = FakeAudioRecorder()

        recorder.advanceElapsed(1.seconds)
        assertEquals(Duration.ZERO, recorder.elapsed.value)

        recorder.prepare()
        recorder.advanceElapsed(1.seconds)
        assertEquals(Duration.ZERO, recorder.elapsed.value)

        recorder.start()
        recorder.advanceElapsed(1.seconds)
        assertEquals(1.seconds, recorder.elapsed.value)

        recorder.pause()
        recorder.advanceElapsed(30.seconds)
        assertEquals(1.seconds, recorder.elapsed.value)
    }

    @Test
    fun `time cannot be rewound`() {
        assertFailsWith<IllegalArgumentException> {
            FakeAudioRecorder().advanceElapsed((-1).seconds)
        }
    }
}
