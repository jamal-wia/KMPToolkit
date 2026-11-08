package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The path registry is documented to hand an explicit path from one recorder to the next so that a
 * recorder never deletes a file it no longer owns. Two spellings of one file (a doubled separator is
 * what string concatenation of a directory with a trailing slash produces) are one path to the file
 * system, so the older recorder must still leave the newer one's recording alone.
 */
class RecorderPathAliasContractTest {

    @Test
    fun `an older recorder never deletes a recording the next recorder made on another spelling of the same path`() = runTest {
        val fileSystem = FakeRecordingFileSystem()
        fun newRecorder(): DefaultAudioRecorder = DefaultAudioRecorder(
            engine = FakeRecorderEngine(),
            fileSystem = fileSystem,
            config = AudioRecorderConfig(),
            workerContext = StandardTestDispatcher(testScheduler),
            epochClock = EpochClock { 1L },
        )
        val older = newRecorder()
        val newer = newRecorder()
        try {
            older.prepare("/data/rec/note.m4a")
            newer.prepare("/data/rec//note.m4a")
            newer.start()
            newer.stop()

            older.release()

            assertTrue(
                fileSystem.deletedPaths.none { it == "/data/rec/note.m4a" || it == "/data/rec//note.m4a" },
                "the older recorder deleted ${fileSystem.deletedPaths}",
            )
        } finally {
            older.release()
            newer.release()
        }
        assertEquals(RecorderState.Released, older.state.value)
    }

    @Test
    fun `a dot segment and another letter case are the same path for the fence`() = runTest {
        val fileSystem = FakeRecordingFileSystem()
        fun newRecorder(): DefaultAudioRecorder = DefaultAudioRecorder(
            engine = FakeRecorderEngine(),
            fileSystem = fileSystem,
            config = AudioRecorderConfig(),
            workerContext = StandardTestDispatcher(testScheduler),
            epochClock = EpochClock { 1L },
        )
        val older = newRecorder()
        val newer = newRecorder()
        try {
            older.prepare("/d/x.m4a")
            newer.prepare("/d/./X.m4a")
            newer.start()
            newer.stop()

            older.release()

            assertTrue(fileSystem.deletedPaths.isEmpty(), "the older recorder deleted ${fileSystem.deletedPaths}")
        } finally {
            older.release()
            newer.release()
        }
    }

    @Test
    fun `a recorder alone still deletes its own unused file on release`() = runTest {
        val fileSystem = FakeRecordingFileSystem()
        val recorder = DefaultAudioRecorder(
            engine = FakeRecorderEngine(),
            fileSystem = fileSystem,
            config = AudioRecorderConfig(),
            workerContext = StandardTestDispatcher(testScheduler),
            epochClock = EpochClock { 1L },
        )
        recorder.prepare("/data/rec//own.m4a")

        recorder.release()

        assertEquals(listOf("/data/rec//own.m4a"), fileSystem.deletedPaths)
    }
}
