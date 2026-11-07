package io.github.jamal_wia.kmptoolkit.audio.recorder.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Releasing a recording recorder keeps the file it was writing, in the fake as in the real recorder.
 */
class FakeAudioRecorderContractTest {

    @Test
    fun `releasing a recording fake keeps its file`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()

        recorder.release()

        assertEquals(emptyList(), recorder.deletedPaths)
    }
}
