package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Rotation (collectors on the level and sample flows that come and go), background cycles (pause and
 * resume), system interruptions, a silenced input, stop and cancel, and re-preparation, mixed in one
 * recorder over many rounds with exact virtual time: the elapsed time of every ending is predictable to
 * the millisecond, and the file system and engine logs must match the endings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderRoundFiveMixTest {

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun `eight mixed rounds on one recorder end exactly as documented and leave nothing behind`() = runRecorderTest { fixture ->
        val recorder: AudioRecorder = fixture.recorder
        val cancelledPaths: MutableList<String> = mutableListOf()
        val explicitPaths: MutableSet<String> = mutableSetOf()
        repeat(8) { round: Int ->
            val explicit: String? = if (round % 2 == 0) "/data/rec/mix-$round.m4a" else null
            val path: String = fixture.recording(explicit)
            if (explicit != null) explicitPaths += explicit
            // Rotation: a new screen collects the meters, the old one goes away.
            repeat(5) {
                val levels = launch { recorder.level.collect { } }
                val samples = launch { recorder.levelSamples.collect { } }
                passTime(fixture, 60.milliseconds)
                levels.cancel()
                samples.cancel()
                runCurrent()
            }
            // Background cycles: the paused stretch must not count.
            repeat(3) {
                assertTrue(recorder.pause().isSuccess)
                passTime(fixture, 1.seconds)
                assertTrue(recorder.resume().isSuccess)
                passTime(fixture, 200.milliseconds)
            }
            val recorded: Duration = 900.milliseconds

            when (round % 4) {
                0 -> {
                    val stopped = recorder.stop()
                    assertEquals(RecordedFile(path, recorded), (stopped as RecorderResult.Success).value, "round $round")
                    assertIs<RecorderState.Completed>(recorder.state.value)
                }
                1 -> {
                    fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
                    runCurrent()
                    assertEquals(
                        RecorderState.Interrupted(RecordedFile(path, recorded), InterruptionReason.StorageLow),
                        recorder.state.value,
                        "round $round",
                    )
                }
                2 -> {
                    assertTrue(recorder.cancel().isSuccess)
                    cancelledPaths += path
                    assertEquals(RecorderState.Idle, recorder.state.value)
                }
                else -> {
                    fixture.engine.emit(EngineEvent.InputSilenced(true))
                    passTime(fixture, 500.milliseconds)
                    assertEquals(
                        RecorderState.Interrupted(RecordedFile(path, recorded), InterruptionReason.MicrophoneSilenced),
                        recorder.state.value,
                        "round $round",
                    )
                }
            }
            assertEquals(0f, recorder.level.value, "round $round: a level survived the ending")
            assertNull(fixture.engine.listener, "round $round: the engine still reports to a finished session")
        }
        assertEquals(cancelledPaths, fixture.fileSystem.deletedPaths, "only cancelled recordings are deleted")
        recorder.release()
        assertEquals(RecorderState.Released, recorder.state.value)
        assertEquals(cancelledPaths, fixture.fileSystem.deletedPaths, "release deleted a finished recording")
    }

    @Test
    fun `a rotation storm of collectors never keeps the meter alive past the ending or ticks after release`() = runRecorderTest { fixture ->
        val recorder: AudioRecorder = fixture.recorder
        fixture.recording()
        repeat(40) {
            val job = launch { recorder.levelSamples.collect { } }
            passTime(fixture, 25.milliseconds)
            job.cancel()
        }
        assertTrue(recorder.stop().isSuccess)
        val peaksAfterStop: Int = fixture.engine.peakCalls
        val levels = launch { recorder.level.collect { } }
        passTime(fixture, 2.seconds)
        levels.cancel()
        assertEquals(peaksAfterStop, fixture.engine.peakCalls, "the microphone was metered after stop")
        recorder.release()
        runCurrent()
        passTime(fixture, 2.seconds)
        assertEquals(peaksAfterStop, fixture.engine.peakCalls, "the microphone was metered after release")
    }

    @Test
    fun `a recorder that was abandoned while recording does not disturb the next one on the same explicit path`() = runRecorderTest { first ->
        val path = "/data/rec/abandoned.m4a"
        first.recording(path)
        passTime(first, 300.milliseconds)
        // The first process died here: nobody releases it. A second recorder starts on the same path.
        val second = RecorderFixture(AudioRecorderConfig(), kotlin.coroutines.EmptyCoroutineContext + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            second.fileSystem.existingPaths += path
            second.recording(path)
            passTime(second, 700.milliseconds)
            val stopped = second.recorder.stop()
            assertEquals(RecordedFile(path, 700.milliseconds), (stopped as RecorderResult.Success).value)
            assertTrue(second.fileSystem.deletedPaths.isEmpty())
        } finally {
            second.recorder.release()
            first.recorder.release()
        }
    }
}
