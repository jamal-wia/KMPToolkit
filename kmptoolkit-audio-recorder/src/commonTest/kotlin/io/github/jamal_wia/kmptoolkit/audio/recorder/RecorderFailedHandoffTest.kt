package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A later `prepare()` on the path an earlier, still prepared recorder holds takes the path over in the
 * registry. When that later attempt ends without a recording (it failed, or its caller went away), it
 * drops its claim and the path is nobody's: the earlier recorder's never-used file is then litter that
 * neither recorder will ever delete, although the documentation promises that `release()` and
 * `cancel()` of a prepared recorder delete that file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderFailedHandoffTest {

    private val path = "/data/rec/handoff.m4a"

    private fun TestScope.recorder(engine: FakeRecorderEngine, fileSystem: FakeRecordingFileSystem) =
        DefaultAudioRecorder(
            engine = engine,
            fileSystem = fileSystem,
            config = AudioRecorderConfig(),
            workerContext = StandardTestDispatcher(testScheduler),
            epochClock = EpochClock { 1L },
        )

    @Test
    fun `releasing a prepared recorder still deletes its unused file when a later recorder only touched the path`() = runTest {
        val fileSystem = FakeRecordingFileSystem()
        val older = recorder(FakeRecorderEngine(), fileSystem)
        val newerEngine = FakeRecorderEngine().also { it.permissionGranted = true }
        val newer = recorder(newerEngine, fileSystem)
        try {
            older.prepare(path)
            fileSystem.existingPaths += path // the older engine created the (empty) file
            newerEngine.failures[RecorderOperation.PREPARE] = IllegalStateException("microphone busy")
            val failed = newer.prepare(path)
            assertTrue(failed is RecorderResult.Failure, "the later prepare was meant to fail, was $failed")

            older.release()

            assertEquals(listOf(path), fileSystem.deletedPaths, "the unused file of the older recorder was left behind")
        } finally {
            older.release()
            newer.release()
        }
    }

    @Test
    fun `cancelling a prepared recorder still deletes its unused file when a later prepare failed on the directory`() = runTest {
        val fileSystem = FakeRecordingFileSystem()
        val older = recorder(FakeRecorderEngine(), fileSystem)
        val newer = recorder(FakeRecorderEngine(), fileSystem)
        try {
            older.prepare(path)
            fileSystem.existingPaths += path
            fileSystem.unwritableDirectories += "/data/rec"
            val failed = newer.prepare(path)
            assertTrue(failed is RecorderResult.Failure, "the later prepare was meant to fail, was $failed")

            val cancelled = older.cancel()

            assertTrue(cancelled.isSuccess, "cancel gave $cancelled")
            assertEquals(listOf(path), fileSystem.deletedPaths, "the unused file of the older recorder was left behind")
        } finally {
            older.release()
            newer.release()
        }
    }

    @Test
    fun `a later prepare whose caller was cancelled does not strip the older recorder of its file`() = runTest {
        val fileSystem = FakeRecordingFileSystem()
        val older = recorder(FakeRecorderEngine(), fileSystem)
        val newerEngine = FakeRecorderEngine().also { it.prepareGate = CompletableDeferred() }
        val newer = recorder(newerEngine, fileSystem)
        try {
            older.prepare(path)
            fileSystem.existingPaths += path
            val job = launch { newer.prepare(path) }
            runCurrent()
            job.cancel()
            runCurrent()
            assertEquals(RecorderState.Idle, newer.state.value)

            older.release()

            assertEquals(listOf(path), fileSystem.deletedPaths, "the unused file of the older recorder was left behind")
        } finally {
            newerEngine.prepareGate?.complete(Unit)
            older.release()
            newer.release()
        }
    }

    @Test
    fun `control - without a later recorder the prepared recorder deletes its own unused file`() = runTest {
        val fileSystem = FakeRecordingFileSystem()
        val older = recorder(FakeRecorderEngine(), fileSystem)
        older.prepare(path)
        fileSystem.existingPaths += path

        older.release()

        assertEquals(listOf(path), fileSystem.deletedPaths)
    }

    @Test
    fun `cancelling an interrupted recording still deletes its file when a later prepare on the path failed`() = runTest {
        val fileSystem = FakeRecordingFileSystem()
        val olderEngine = FakeRecorderEngine()
        val older = recorder(olderEngine, fileSystem)
        val newerEngine = FakeRecorderEngine()
        val newer = recorder(newerEngine, fileSystem)
        try {
            older.prepare(path)
            fileSystem.existingPaths += path
            older.start()
            olderEngine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            runCurrent()
            repeat(10) { runCurrent() }
            assertTrue(older.state.value is RecorderState.Interrupted, "state was ${older.state.value}")
            newerEngine.failures[RecorderOperation.PREPARE] = IllegalStateException("microphone busy")
            assertTrue(newer.prepare(path) is RecorderResult.Failure)

            val cancelled = older.cancel()

            assertTrue(cancelled.isSuccess, "cancel gave $cancelled")
            assertEquals(listOf(path), fileSystem.deletedPaths, "cancel() left the interrupted recording on disk")
        } finally {
            older.release()
            newer.release()
        }
    }

    @Test
    fun `cancelling an interrupted recording never deletes the recording a later recorder made on another spelling`() = runTest {
        val fileSystem = FakeRecordingFileSystem()
        val olderEngine = FakeRecorderEngine()
        val older = recorder(olderEngine, fileSystem)
        val newer = recorder(FakeRecorderEngine(), fileSystem)
        try {
            older.prepare(path)
            older.start()
            olderEngine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            runCurrent()
            repeat(10) { runCurrent() }
            newer.prepare("/data/rec//handoff.m4a")
            newer.start()
            newer.stop()

            older.cancel()

            assertTrue(fileSystem.deletedPaths.isEmpty(), "the older recorder deleted ${fileSystem.deletedPaths}")
        } finally {
            older.release()
            newer.release()
        }
    }

    @Test
    fun `a recorder that succeeded and was released never hands the path back to the older prepared one`() = runTest {
        val fileSystem = FakeRecordingFileSystem()
        val older = recorder(FakeRecorderEngine(), fileSystem)
        val newer = recorder(FakeRecorderEngine(), fileSystem)
        try {
            older.prepare(path)
            assertTrue(newer.prepare(path).isSuccess)
            assertTrue(newer.start().isSuccess)
            assertTrue(newer.stop().isSuccess)
            newer.release()

            older.release()

            assertTrue(fileSystem.deletedPaths.isEmpty(), "the older recorder deleted ${fileSystem.deletedPaths}")
        } finally {
            older.release()
            newer.release()
        }
    }
}
