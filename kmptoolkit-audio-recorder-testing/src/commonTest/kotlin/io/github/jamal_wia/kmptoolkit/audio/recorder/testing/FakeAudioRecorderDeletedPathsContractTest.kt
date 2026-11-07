package io.github.jamal_wia.kmptoolkit.audio.recorder.testing

import io.github.jamal_wia.kmptoolkit.audio.recorder.AudioFormat
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderError
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * `deletedPaths` is documented as every path thrown away in the cases where the real recorder deletes
 * the file it had opened. The real recorder refuses a missing permission, an unsupported format, an
 * unwritable directory and a full disk before it opens or creates anything, so it deletes nothing then.
 */
class FakeAudioRecorderDeletedPathsContractTest {

    @Test
    fun `a failure the real recorder reports before it opens any file deletes no path`() = runTest {
        val errors: List<RecorderError> = listOf(
            RecorderError.PermissionDenied,
            RecorderError.UnsupportedFormat(AudioFormat.WAV),
            RecorderError.DirectoryNotWritable("/notes"),
            RecorderError.InsufficientStorage(path = "/notes", requiredBytes = 8L, availableBytes = 1L),
        )
        for (error in errors) {
            val recorder = FakeAudioRecorder()
            recorder.failNextOperationWith = error

            recorder.prepare()

            assertEquals(emptyList(), recorder.deletedPaths, "$error never opens a file in the real recorder")
        }
    }

    @Test
    fun `an engine failure of prepare still records the file it had opened as deleted`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.failNextOperationWith = RecorderError.EngineFailure(RecorderOperation.PREPARE, IllegalStateException("codec"))

        recorder.prepare("/notes/a.m4a")

        assertEquals(listOf("/notes/a.m4a"), recorder.deletedPaths)
    }
}
