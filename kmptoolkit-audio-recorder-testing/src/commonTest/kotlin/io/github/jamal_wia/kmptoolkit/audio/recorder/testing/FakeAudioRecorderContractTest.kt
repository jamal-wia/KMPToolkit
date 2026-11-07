package io.github.jamal_wia.kmptoolkit.audio.recorder.testing

import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderResult
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
