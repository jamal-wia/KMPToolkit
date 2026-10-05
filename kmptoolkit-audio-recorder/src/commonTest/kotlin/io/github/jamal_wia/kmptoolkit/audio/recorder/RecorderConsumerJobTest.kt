package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A consumer who passes `viewModelScope.coroutineContext` hands the factory a [Job] of their own.
 * The recorder must run under a job of its own: [AudioRecorder.release] cancelling the consumer's
 * job would tear down their whole scope, and a finalization running under it would be cancelled
 * with it and skip `engine.release()`.
 */
class RecorderConsumerJobTest {

    private fun TestScope.fixtureWith(consumerJob: Job): RecorderFixture =
        RecorderFixture(AudioRecorderConfig(), StandardTestDispatcher(testScheduler) + consumerJob)

    @Test
    fun `release does not cancel a job carried by the coroutine context`() = runTest {
        val consumerJob: Job = Job()
        val fixture: RecorderFixture = fixtureWith(consumerJob)
        try {
            fixture.recording()
            runCurrent()

            fixture.recorder.release()
            runCurrent()

            assertTrue(consumerJob.isActive, "release() cancelled a job that belongs to the caller")
            assertEquals(RecorderState.Released, fixture.recorder.state.value)
        } finally {
            consumerJob.cancel()
        }
    }

    @Test
    fun `a system finalization racing release still releases the engine`() = runTest {
        val consumerJob: Job = Job()
        val fixture: RecorderFixture = fixtureWith(consumerJob)
        try {
            fixture.recording()
            val releasesBefore: Int = fixture.engine.releaseCount
            fixture.engine.onStop = { fixture.recorder.release() }

            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
            runCurrent()

            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount, "the microphone must be freed")
            assertTrue(consumerJob.isActive)
        } finally {
            consumerJob.cancel()
        }
    }

    @Test
    fun `a stop racing release still finishes and frees the recorder for later operations`() = runTest {
        val consumerJob: Job = Job()
        val fixture: RecorderFixture = fixtureWith(consumerJob)
        try {
            fixture.recording()
            val releasesBefore: Int = fixture.engine.releaseCount
            fixture.engine.onStop = { fixture.recorder.release() }

            val result: RecorderResult<RecordedFile> = fixture.recorder.stop()

            assertTrue(result.isSuccess)
            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount)
            assertTrue(consumerJob.isActive)
        } finally {
            consumerJob.cancel()
        }
    }
}
