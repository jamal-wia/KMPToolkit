package io.github.jamal_wia.kmptoolkit.audio.recorder

import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A worker context that refuses work (an executor that was shut down) must surface as a failure
 * value, not as a cancellation thrown into a caller that was never cancelled.
 */
class RecorderOddEnvironmentTest {

    /** Serves the first `serving` dispatches, then refuses like an executor dispatcher does: cancels the job and moves on. */
    private class RefusingDispatcher(private val serving: Int) : CoroutineDispatcher() {
        private var dispatched: Int = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            if (dispatched > serving) {
                context.cancel(CancellationException("The task was rejected"))
            }
            Dispatchers.Default.dispatch(context, block)
        }
    }

    @Test
    fun `prepare on a recorder whose worker dispatcher was shut down reports a failure instead of cancelling its caller`() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val recorder = DefaultAudioRecorder(
            engine = FakeRecorderEngine(),
            fileSystem = FakeRecordingFileSystem(),
            config = AudioRecorderConfig(),
            workerContext = dispatcher,
            epochClock = EpochClock { 1L },
        )
        dispatcher.close()
        try {
            val outcome: Any = runBlocking {
                withTimeout(5_000) {
                    try {
                        recorder.prepare("/closed/dispatcher.m4a")
                    } catch (cancellation: CancellationException) {
                        cancellation
                    }
                }
            }
            assertTrue(
                outcome is RecorderResult.Failure && outcome.error is RecorderError.EngineFailure,
                "the caller was not cancelled, yet prepare gave $outcome; state ${recorder.state.value}",
            )
            assertTrue(recorder.state.value is RecorderState.Failed, "state was ${recorder.state.value}")
        } finally {
            recorder.release()
        }
    }

    @Test
    fun `a worker that refuses the engine hop fails the prepare and leaves nothing open`() {
        val engine = FakeRecorderEngine()
        val recorder = DefaultAudioRecorder(
            engine = engine,
            fileSystem = FakeRecordingFileSystem(),
            config = AudioRecorderConfig(),
            workerContext = RefusingDispatcher(serving = 1),
            epochClock = EpochClock { 1L },
        )
        try {
            val outcome: Any = runBlocking {
                withTimeout(5_000) {
                    try {
                        recorder.prepare("/closed/second-hop.m4a")
                    } catch (cancellation: CancellationException) {
                        cancellation
                    }
                }
            }
            assertTrue(
                outcome is RecorderResult.Failure && outcome.error is RecorderError.EngineFailure,
                "the caller was not cancelled, yet prepare gave $outcome; state ${recorder.state.value}",
            )
            assertTrue(recorder.state.value is RecorderState.Failed, "state was ${recorder.state.value}")
            assertTrue(engine.releaseCount >= 1, "the engine was left open: ${engine.calls}")
        } finally {
            recorder.release()
        }
    }
}
