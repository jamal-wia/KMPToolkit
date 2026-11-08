package io.github.jamal_wia.kmptoolkit.audio.recorder.testing

import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderError
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderResult
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Releasing a recording recorder keeps the file it was writing, in the fake as in the real recorder.
 */
class FakeAudioRecorderContractTest {

    @Test
    fun `releasing a prepared fake records the unused file as deleted like the real recorder does`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = (recorder.prepare() as RecorderResult.Success).value

        recorder.release()

        assertEquals(listOf(path), recorder.deletedPaths, "the real recorder deletes a Ready file on release")
    }

    @Test
    fun `releasing a recording fake keeps its file`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()

        recorder.release()

        assertEquals(emptyList(), recorder.deletedPaths)
    }

    @Test
    fun `a relative explicit path is refused like the real recorder refuses it`() = runTest {
        val recorder = FakeAudioRecorder()

        val result = recorder.prepare("relative/file.m4a")

        assertTrue(
            result is RecorderResult.Failure && result.error is RecorderError.DirectoryNotWritable,
            "result was $result",
        )
        assertTrue(recorder.state.value is RecorderState.Failed)
        assertEquals(emptyList(), recorder.preparedPaths)
    }

    @Test
    fun `a blank explicit path is refused like the real recorder refuses it`() = runTest {
        val recorder = FakeAudioRecorder()

        val result = recorder.prepare("   ")

        assertTrue(
            result is RecorderResult.Failure && result.error is RecorderError.DirectoryNotWritable,
            "result was $result",
        )
    }

    @Test
    fun `a refused path does not consume a scripted failure`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.failNextOperationWith = RecorderError.PermissionDenied

        recorder.prepare("relative/file.m4a")
        val next = recorder.prepare("/abs/file.m4a")

        assertTrue(
            next is RecorderResult.Failure && next.error is RecorderError.PermissionDenied,
            "result was $next",
        )
    }
}
