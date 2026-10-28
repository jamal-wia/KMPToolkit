package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * A generated file name must be unique, not merely "unique unless two things happen in the same
 * millisecond". `RecordingStorage` invites two recorders to share a directory, and both share the
 * default one unless told otherwise; the wall clock can also step backwards (an NTP correction, the
 * user changing the time). A name built from the clock alone then collides, and the second recorder
 * opens — and, when its preparation fails, deletes — the first recorder's file.
 *
 * These recorders use the production name generator, not the fixture's pinned one, and a clock
 * frozen at one instant: the worst case for the clock, so only the rest of the name can keep the
 * paths apart.
 */
class RecorderFileNameTest {

    private fun recorderOn(
        fileSystem: FakeRecordingFileSystem,
        engine: FakeRecorderEngine,
        dispatcher: CoroutineContext,
    ): DefaultAudioRecorder = DefaultAudioRecorder(
        engine = engine,
        fileSystem = fileSystem,
        config = AudioRecorderConfig(),
        workerContext = dispatcher,
        epochClock = EpochClock { FIXED_EPOCH_MILLIS },
    )

    @Test
    fun `two recorders preparing in the same millisecond get different files`() = runTest {
        val dispatcher: CoroutineContext = StandardTestDispatcher(testScheduler)
        val fileSystem = FakeRecordingFileSystem()
        val first: DefaultAudioRecorder = recorderOn(fileSystem, FakeRecorderEngine(), dispatcher)
        val second: DefaultAudioRecorder = recorderOn(fileSystem, FakeRecorderEngine(), dispatcher)
        try {
            val firstPath: String = requireNotNull(first.prepare().getOrNull())
            val secondPath: String = requireNotNull(second.prepare().getOrNull())

            assertNotEquals(firstPath, secondPath, "two live recordings must never share a file")
        } finally {
            first.release()
            second.release()
        }
    }

    @Test
    fun `a clock that returns to an earlier instant does not reuse a finished recording's file`() = runTest {
        val recorder: DefaultAudioRecorder =
            recorderOn(FakeRecordingFileSystem(), FakeRecorderEngine(), StandardTestDispatcher(testScheduler))
        try {
            val firstPath: String = requireNotNull(recorder.prepare().getOrNull())
            assertTrue(recorder.start().isSuccess)
            assertTrue(recorder.stop().isSuccess)

            val secondPath: String = requireNotNull(recorder.prepare().getOrNull())

            assertNotEquals(firstPath, secondPath, "a saved recording must not be overwritten by the next one")
        } finally {
            recorder.release()
        }
    }

    @Test
    fun `a recorder whose prepare fails deletes only its own file and never a concurrent recording's`() = runTest {
        val dispatcher: CoroutineContext = StandardTestDispatcher(testScheduler)
        val fileSystem = FakeRecordingFileSystem()
        val failingEngine = FakeRecorderEngine()
        failingEngine.failures[RecorderOperation.PREPARE] = IllegalStateException("microphone busy")
        val recording: DefaultAudioRecorder = recorderOn(fileSystem, FakeRecorderEngine(), dispatcher)
        val failing: DefaultAudioRecorder = recorderOn(fileSystem, failingEngine, dispatcher)
        try {
            val livePath: String = requireNotNull(recording.prepare().getOrNull())
            assertTrue(recording.start().isSuccess)

            assertTrue(failing.prepare().errorOrNull() is RecorderError.EngineFailure)

            val deleted: List<String> = fileSystem.deletedPaths.toList()
            assertTrue(livePath !in deleted, "the live recording's file was deleted: $deleted")
            assertEquals(1, deleted.size, "the failed preparation deletes exactly its own file: $deleted")
        } finally {
            recording.release()
            failing.release()
        }
    }
}
