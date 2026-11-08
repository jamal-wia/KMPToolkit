package io.github.jamal_wia.kmptoolkit.audio.recorder

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A worker context that refuses work (an executor closed by a screen that is being torn down) is
 * reported by `prepare` as a failure, and `cancel()` is documented to delete the file and settle on
 * `Idle`. These tests read the other operations against the same environment.
 */
class RecorderClosedWorkerContractTest {

    private fun recorderOn(
        executor: java.util.concurrent.ExecutorService,
        engine: FakeRecorderEngine,
        fileSystem: FakeRecordingFileSystem,
    ): DefaultAudioRecorder = DefaultAudioRecorder(
        engine = engine,
        fileSystem = fileSystem,
        config = AudioRecorderConfig(),
        workerContext = executor.asCoroutineDispatcher(),
        epochClock = EpochClock { 1L },
    )

    @Test
    fun `cancelling a recording after the worker executor was closed still deletes the file`() {
        val executor = Executors.newSingleThreadExecutor()
        val fileSystem = FakeRecordingFileSystem()
        val recorder = recorderOn(executor, FakeRecorderEngine(), fileSystem)
        try {
            runBlocking {
                withTimeout(10_000) {
                    val path: String = (recorder.prepare("/data/rec/closed.m4a") as RecorderResult.Success).value
                    assertTrue(recorder.start().isSuccess)
                    executor.shutdown()

                    val cancelled = recorder.cancel()

                    // Either the cancel reports that it could not finish, or the file is gone.
                    assertTrue(
                        cancelled !is RecorderResult.Success || fileSystem.deletedPaths == listOf(path),
                        "cancel() returned $cancelled in state ${recorder.state.value} but the file was not deleted: ${fileSystem.deletedPaths}",
                    )
                }
            }
        } finally {
            recorder.release()
            executor.shutdownNow()
        }
    }
}
