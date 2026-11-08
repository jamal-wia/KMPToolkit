package io.github.jamal_wia.kmptoolkit.audio.recorder

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A worker context that refuses work (an executor closed by a screen that is being torn down) is
 * reported by `prepare` as a failure, and `cancel()` is documented to delete the file and settle on
 * `Idle`. These tests read the other operations against the same environment.
 */
class RecorderClosedWorkerStopTest {

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
    fun `stopping after the worker executor was closed never claims a finished recording and releases the engine`() {
        val executor = Executors.newSingleThreadExecutor()
        val engine = FakeRecorderEngine()
        val recorder = recorderOn(executor, engine, FakeRecordingFileSystem())
        try {
            runBlocking {
                withTimeout(10_000) {
                    recorder.prepare("/data/rec/closed-stop.m4a")
                    recorder.start()
                    executor.shutdown()

                    val stopped = recorder.stop()

                    assertTrue(stopped is RecorderResult.Failure, "stop() claimed success: $stopped")
                    assertTrue(engine.releaseCount >= 1, "the engine was never released")
                    assertEquals(0, engine.calls.count { it == "stop" }, "the encoder cannot have been stopped on a closed executor")
                }
            }
        } finally {
            recorder.release()
            executor.shutdownNow()
        }
    }
}
