package io.github.jamal_wia.kmptoolkit.audio.recorder.testing

import io.github.jamal_wia.kmptoolkit.audio.recorder.InterruptionReason
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecordedFile
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderError
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderOperation
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderResult
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderState
import io.github.jamal_wia.kmptoolkit.audio.recorder.errorOrNull
import io.github.jamal_wia.kmptoolkit.audio.recorder.getOrNull
import io.github.jamal_wia.kmptoolkit.audio.recorder.isSuccess
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The fake is only useful if it refuses what the real recorder refuses — a test that passes against
 * a permissive double proves nothing. These cases are derived from the transition table documented
 * on `AudioRecorder`, the same source the production recorder's own suite works from, so the two
 * cannot quietly drift apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeAudioRecorderTest {

    @Test
    fun `a fresh fake is idle`() {
        assertEquals(RecorderState.Idle, FakeAudioRecorder().state.value)
    }

    @Test
    fun `the happy path walks prepare start pause resume and stop`() = runTest {
        val recorder = FakeAudioRecorder()

        val path: String = requireNotNull(recorder.prepare().getOrNull())
        assertEquals(RecorderState.Ready(path), recorder.state.value)

        recorder.start()
        assertEquals(RecorderState.Recording(path), recorder.state.value)

        recorder.advanceElapsed(2.seconds)
        recorder.pause()
        assertEquals(RecorderState.Paused(path, 2.seconds), recorder.state.value)

        recorder.resume()
        recorder.advanceElapsed(3.seconds)
        val recorded: RecordedFile = requireNotNull(recorder.stop().getOrNull())

        assertEquals(RecordedFile(path, 5.seconds), recorded)
        assertEquals(RecorderState.Completed(recorded), recorder.state.value)
        assertContentEquals(listOf(recorded), recorder.completedRecordings)
    }

    @Test
    fun `generated paths are distinct so two recordings never collide`() = runTest {
        val recorder = FakeAudioRecorder()

        val first: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.stop()
        val second: String = requireNotNull(recorder.prepare().getOrNull())

        assertContentEquals(listOf(first, second), recorder.preparedPaths)
        assertEquals(2, recorder.preparedPaths.toSet().size)
    }

    @Test
    fun `an explicit path is honored`() = runTest {
        val recorder = FakeAudioRecorder()

        assertEquals(
            RecorderResult.Success("/tmp/take-1.m4a"),
            recorder.prepare(outputPath = "/tmp/take-1.m4a"),
        )
    }

    // --- the refusals that make the fake worth using ---

    @Test
    fun `starting twice is refused just as the real recorder refuses it`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()

        assertEquals(
            RecorderError.IllegalState(RecorderState.Recording(path), RecorderOperation.START),
            recorder.start().errorOrNull(),
        )
    }

    @Test
    fun `stopping before starting is refused`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())

        assertEquals(
            RecorderError.IllegalState(RecorderState.Ready(path), RecorderOperation.STOP),
            recorder.stop().errorOrNull(),
        )
    }

    @Test
    fun `start is refused while paused because resume is the only way back`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.pause()
        val paused: RecorderState = recorder.state.value

        assertEquals(
            RecorderError.IllegalState(paused, RecorderOperation.START),
            recorder.start().errorOrNull(),
        )
    }

    @Test
    fun `cancel refuses to throw away a completed recording`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.stop()

        assertEquals(
            RecorderError.IllegalState(recorder.state.value, RecorderOperation.CANCEL),
            recorder.cancel().errorOrNull(),
        )
        assertContentEquals(emptyList(), recorder.deletedPaths)
    }

    @Test
    fun `cancel discards the recording and returns to idle`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.advanceElapsed(4.seconds)

        recorder.cancel()

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertContentEquals(listOf(path), recorder.deletedPaths)
        assertEquals(Duration.ZERO, recorder.elapsed.value)
    }

    @Test
    fun `re-preparing over an unused file discards it`() = runTest {
        val recorder = FakeAudioRecorder()
        val first: String = requireNotNull(recorder.prepare().getOrNull())

        recorder.prepare()

        assertContentEquals(listOf(first), recorder.deletedPaths)
    }

    // --- scripted failures ---

    @Test
    fun `a denied permission fails prepare`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.permissionGranted = false

        assertEquals(
            RecorderResult.Failure(RecorderError.PermissionDenied),
            recorder.prepare(),
        )
        assertEquals(RecorderState.Failed(RecorderError.PermissionDenied), recorder.state.value)
    }

    @Test
    fun `a scripted error fails the next operation and only that one`() = runTest {
        val recorder = FakeAudioRecorder()
        val error = RecorderError.InsufficientStorage(
            path = "/fake/recordings",
            requiredBytes = 100,
            availableBytes = 1,
        )
        recorder.failNextOperationWith = error

        assertEquals(RecorderResult.Failure(error), recorder.prepare())
        assertEquals(RecorderState.Failed(error), recorder.state.value)

        // recording_2, not _1: the failed attempt had already generated and discarded a name, the
        // same way the real recorder's failed prepare discards the file it opened.
        assertEquals(
            RecorderResult.Success("/fake/recordings/recording_2.m4a"),
            recorder.prepare(),
        )
    }

    @Test
    fun `an illegal transition does not consume the scripted error`() = runTest {
        val recorder = FakeAudioRecorder()
        val error = RecorderError.EngineFailure(RecorderOperation.START)
        recorder.failNextOperationWith = error

        recorder.start()

        assertEquals(error, recorder.failNextOperationWith)
    }

    // --- release ---

    @Test
    fun `release is idempotent and terminal`() = runTest {
        val recorder = FakeAudioRecorder()

        recorder.release()
        recorder.release()

        assertEquals(1, recorder.releaseCount)
        assertEquals(RecorderState.Released, recorder.state.value)
        assertEquals(
            RecorderError.AlreadyReleased(RecorderOperation.PREPARE),
            recorder.prepare().errorOrNull(),
        )
        assertEquals(
            RecorderError.AlreadyReleased(RecorderOperation.STOP),
            recorder.stop().errorOrNull(),
        )
    }

    // --- elapsed ---

    @Test
    fun `elapsed only moves while recording`() = runTest {
        val recorder = FakeAudioRecorder()

        recorder.advanceElapsed(1.seconds)
        assertEquals(Duration.ZERO, recorder.elapsed.value)

        recorder.prepare()
        recorder.advanceElapsed(1.seconds)
        assertEquals(Duration.ZERO, recorder.elapsed.value)

        recorder.start()
        recorder.advanceElapsed(1.seconds)
        assertEquals(1.seconds, recorder.elapsed.value)

        recorder.pause()
        recorder.advanceElapsed(30.seconds)
        assertEquals(1.seconds, recorder.elapsed.value)
    }

    // --- the fake must refuse and clean up exactly where the real recorder does ---

    @Test
    fun `every illegal cell of the transition table is refused`() = runTest {
        val legal: Map<String, Set<RecorderOperation>> = mapOf(
            "Idle" to setOf(RecorderOperation.PREPARE),
            "Ready" to setOf(
                RecorderOperation.PREPARE,
                RecorderOperation.START,
                RecorderOperation.CANCEL,
            ),
            "Recording" to setOf(
                RecorderOperation.PAUSE,
                RecorderOperation.STOP,
                RecorderOperation.CANCEL,
            ),
            "Paused" to setOf(
                RecorderOperation.RESUME,
                RecorderOperation.STOP,
                RecorderOperation.CANCEL,
            ),
            "Completed" to setOf(RecorderOperation.PREPARE),
            "Interrupted" to setOf(
                RecorderOperation.PREPARE,
                RecorderOperation.STOP,
                RecorderOperation.CANCEL,
            ),
            "Failed" to setOf(RecorderOperation.PREPARE),
            "FailedWithFile" to setOf(RecorderOperation.PREPARE, RecorderOperation.CANCEL),
        )
        val arrange: Map<String, suspend (FakeAudioRecorder) -> Unit> = mapOf(
            "Idle" to { },
            "Ready" to { recorder -> recorder.prepare() },
            "Recording" to { recorder -> recorder.prepare(); recorder.start() },
            "Paused" to { recorder -> recorder.prepare(); recorder.start(); recorder.pause() },
            "Completed" to { recorder -> recorder.prepare(); recorder.start(); recorder.stop() },
            "Interrupted" to { recorder ->
                recorder.prepare()
                recorder.start()
                recorder.simulateInterruption(InterruptionReason.AudioSessionInterrupted)
            },
            "Failed" to { recorder ->
                recorder.permissionGranted = false
                recorder.prepare()
                recorder.permissionGranted = true
            },
            "FailedWithFile" to { recorder ->
                recorder.prepare()
                recorder.start()
                recorder.simulateRecordingLost(InterruptionReason.StorageLow)
            },
        )

        legal.forEach { (stateName, legalOperations) ->
            val recorder = FakeAudioRecorder()
            requireNotNull(arrange[stateName]).invoke(recorder)
            val expectedState: RecorderState = recorder.state.value

            RecorderOperation.entries
                .filterNot { it in legalOperations }
                .forEach { operation ->
                    assertEquals(
                        RecorderError.IllegalState(expectedState, operation),
                        recorder.invoke(operation).errorOrNull(),
                        "$operation from $stateName",
                    )
                    assertEquals(expectedState, recorder.state.value)
                }
        }
    }

    @Test
    fun `every operation after release reports that the recorder is gone`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.release()

        RecorderOperation.entries.forEach { operation ->
            assertEquals(
                RecorderError.AlreadyReleased(operation),
                recorder.invoke(operation).errorOrNull(),
                "$operation from Released",
            )
        }
    }

    @Test
    fun `a scripted prepare failure discards the file it had opened`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.failNextOperationWith = RecorderError.EngineFailure(RecorderOperation.PREPARE)

        recorder.prepare()

        assertContentEquals(
            listOf("/fake/recordings/recording_1.m4a"),
            recorder.deletedPaths,
            "the real recorder deletes the file a failed prepare had opened",
        )
        assertContentEquals(emptyList(), recorder.preparedPaths)
    }

    @Test
    fun `a scripted start failure discards the empty file`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.failNextOperationWith = RecorderError.EngineFailure(RecorderOperation.START)

        recorder.start()

        assertContentEquals(listOf(path), recorder.deletedPaths)
        assertTrue(recorder.state.value is RecorderState.Failed)
    }

    @Test
    fun `a scripted pause failure leaves the recorder recording`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.failNextOperationWith = RecorderError.EngineFailure(RecorderOperation.PAUSE)

        recorder.pause()

        assertEquals(RecorderState.Recording(path), recorder.state.value)
    }

    @Test
    fun `a scripted resume failure leaves the recorder paused`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.pause()
        val paused: RecorderState = recorder.state.value
        recorder.failNextOperationWith = RecorderError.EngineFailure(RecorderOperation.RESUME)

        recorder.resume()

        assertEquals(paused, recorder.state.value)
    }

    @Test
    fun `a scripted stop failure keeps the captured file`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.failNextOperationWith = RecorderError.EngineFailure(RecorderOperation.STOP)

        recorder.stop()

        assertContentEquals(emptyList(), recorder.deletedPaths)
        assertContentEquals(emptyList(), recorder.completedRecordings)
    }

    @Test
    fun `cancel cannot be made to fail because the real cancel never does`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.failNextOperationWith = RecorderError.EngineFailure(RecorderOperation.STOP)

        assertEquals(RecorderResult.Success(Unit), recorder.cancel())

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertContentEquals(listOf(path), recorder.deletedPaths)
    }

    @Test
    fun `preparing again after a failure starts a new recording`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.permissionGranted = false
        recorder.prepare()
        recorder.permissionGranted = true

        assertTrue(recorder.prepare().isSuccess)
    }

    private suspend fun FakeAudioRecorder.invoke(
        operation: RecorderOperation,
    ): RecorderResult<*> = when (operation) {
        RecorderOperation.PREPARE -> prepare()
        RecorderOperation.START -> start()
        RecorderOperation.PAUSE -> pause()
        RecorderOperation.RESUME -> resume()
        RecorderOperation.STOP -> stop()
        RecorderOperation.CANCEL -> cancel()
    }

    @Test
    fun `time cannot be rewound`() {
        assertFailsWith<IllegalArgumentException> {
            FakeAudioRecorder().advanceElapsed((-1).seconds)
        }
    }

    @Test
    fun `level starts at zero and stays there until a level is emitted`() = runTest {
        val recorder = FakeAudioRecorder()
        assertEquals(0f, recorder.level.value)

        recorder.prepare()
        recorder.start()

        assertEquals(0f, recorder.level.value)
    }

    @Test
    fun `an emitted level is published while recording`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()

        recorder.emitLevel(0.75f)
        assertEquals(0.75f, recorder.level.value)

        recorder.emitLevel(0.1f)
        assertEquals(0.1f, recorder.level.value)
    }

    @Test
    fun `the bounds of the level range are accepted`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()

        recorder.emitLevel(1f)
        assertEquals(1f, recorder.level.value)

        recorder.emitLevel(0f)
        assertEquals(0f, recorder.level.value)
    }

    @Test
    fun `a level outside zero to one is rejected`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()

        assertFailsWith<IllegalArgumentException> { recorder.emitLevel(-0.01f) }
        assertFailsWith<IllegalArgumentException> { recorder.emitLevel(1.01f) }
        assertFailsWith<IllegalArgumentException> { recorder.emitLevel(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { recorder.emitLevel(Float.POSITIVE_INFINITY) }
        assertEquals(0f, recorder.level.value)
    }

    @Test
    fun `a level is rejected even when it would have been ignored`() {
        // The range is the caller's mistake in every state, so a test cannot hide it by emitting
        // while the fake happens not to be recording.
        assertFailsWith<IllegalArgumentException> { FakeAudioRecorder().emitLevel(2f) }
    }

    @Test
    fun `a level emitted when not recording is ignored`() = runTest {
        val recorder = FakeAudioRecorder()

        recorder.emitLevel(0.5f)
        assertEquals(0f, recorder.level.value, "idle")

        recorder.prepare()
        recorder.emitLevel(0.5f)
        assertEquals(0f, recorder.level.value, "ready")

        recorder.start()
        recorder.pause()
        recorder.emitLevel(0.5f)
        assertEquals(0f, recorder.level.value, "paused")

        recorder.resume()
        recorder.stop()
        recorder.emitLevel(0.5f)
        assertEquals(0f, recorder.level.value, "completed")

        recorder.release()
        recorder.emitLevel(0.5f)
        assertEquals(0f, recorder.level.value, "released")
    }

    @Test
    fun `pause resets the level and resume leaves it at zero until the next emission`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.emitLevel(0.6f)

        recorder.pause()
        assertEquals(0f, recorder.level.value)

        recorder.resume()
        assertEquals(0f, recorder.level.value)

        recorder.emitLevel(0.3f)
        assertEquals(0.3f, recorder.level.value)
    }

    @Test
    fun `stop resets the level`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.emitLevel(0.6f)

        recorder.stop()

        assertEquals(0f, recorder.level.value)
    }

    @Test
    fun `cancel resets the level`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.emitLevel(0.6f)

        recorder.cancel()

        assertEquals(0f, recorder.level.value)
    }

    @Test
    fun `release resets the level`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.emitLevel(0.6f)

        recorder.release()

        assertEquals(0f, recorder.level.value)
    }

    @Test
    fun `a scripted stop failure resets the level`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.emitLevel(0.6f)
        recorder.failNextOperationWith = RecorderError.EngineFailure(RecorderOperation.STOP)

        recorder.stop()

        assertTrue(recorder.state.value is RecorderState.Failed)
        assertEquals(0f, recorder.level.value)
    }

    @Test
    fun `a scripted pause failure keeps the level because the recording keeps running`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.emitLevel(0.6f)
        recorder.failNextOperationWith = RecorderError.EngineFailure(RecorderOperation.PAUSE)

        recorder.pause()

        assertTrue(recorder.state.value is RecorderState.Recording)
        assertEquals(0.6f, recorder.level.value)
    }

    @Test
    fun `a new recording starts with a zero level`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.emitLevel(0.9f)
        recorder.stop()

        recorder.prepare()
        recorder.start()

        assertEquals(0f, recorder.level.value)
    }

    /** Collects [FakeAudioRecorder.levelSamples] eagerly, so every emission is seen at once. */
    private fun TestScope.collectSamples(recorder: FakeAudioRecorder): List<Float> {
        val seen: MutableList<Float> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            recorder.levelSamples.collect { seen += it }
        }
        return seen
    }

    @Test
    fun `every emitted level arrives as a sample in order`() = runTest {
        val recorder = FakeAudioRecorder()
        val samples: List<Float> = collectSamples(recorder)
        recorder.prepare()
        recorder.start()

        recorder.emitLevel(0.2f)
        recorder.emitLevel(0.9f)
        recorder.emitLevel(0.4f)

        assertEquals(listOf(0.2f, 0.9f, 0.4f), samples)
    }

    @Test
    fun `repeated equal levels each arrive as a sample`() = runTest {
        val recorder = FakeAudioRecorder()
        val samples: List<Float> = collectSamples(recorder)
        recorder.prepare()
        recorder.start()

        recorder.emitLevel(0f)
        recorder.emitLevel(0f)
        recorder.emitLevel(0f)
        recorder.emitLevel(1f)
        recorder.emitLevel(1f)

        assertEquals(listOf(0f, 0f, 0f, 1f, 1f), samples, "a StateFlow would have shown two values")
    }

    @Test
    fun `samples and level carry the same values`() = runTest {
        val recorder = FakeAudioRecorder()
        val samples: List<Float> = collectSamples(recorder)
        recorder.prepare()
        recorder.start()

        recorder.emitLevel(0.7f)

        assertEquals(0.7f, recorder.level.value)
        assertEquals(listOf(0.7f), samples)
    }

    @Test
    fun `a level emitted when not recording produces no sample`() = runTest {
        val recorder = FakeAudioRecorder()
        val samples: List<Float> = collectSamples(recorder)

        recorder.emitLevel(0.5f)
        recorder.prepare()
        recorder.emitLevel(0.5f)
        recorder.start()
        recorder.pause()
        recorder.emitLevel(0.5f)
        recorder.resume()
        recorder.stop()
        recorder.emitLevel(0.5f)
        recorder.release()
        recorder.emitLevel(0.5f)

        assertTrue(samples.isEmpty(), "nothing was emitted while Recording")
    }

    @Test
    fun `an out of range level produces no sample`() = runTest {
        val recorder = FakeAudioRecorder()
        val samples: List<Float> = collectSamples(recorder)
        recorder.prepare()
        recorder.start()

        assertFailsWith<IllegalArgumentException> { recorder.emitLevel(1.5f) }

        assertTrue(samples.isEmpty())
    }

    @Test
    fun `transitions reset level without emitting a sample`() = runTest {
        val recorder = FakeAudioRecorder()
        val samples: List<Float> = collectSamples(recorder)
        recorder.prepare()
        recorder.start()
        recorder.emitLevel(0.6f)

        recorder.pause()

        assertEquals(0f, recorder.level.value)
        assertEquals(listOf(0.6f), samples, "the reset to zero is not a measurement")
    }

    @Test
    fun `the sample stream is hot so a sample with no collector is gone`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.emitLevel(0.8f)

        val samples: List<Float> = collectSamples(recorder)
        recorder.emitLevel(0.3f)

        assertEquals(listOf(0.3f), samples, "no replay of what was emitted before subscribing")
    }

    @Test
    fun `samples keep arriving across a pause and resume`() = runTest {
        val recorder = FakeAudioRecorder()
        val samples: List<Float> = collectSamples(recorder)
        recorder.prepare()
        recorder.start()
        recorder.emitLevel(0.1f)
        recorder.pause()
        recorder.emitLevel(0.9f)
        recorder.resume()

        recorder.emitLevel(0.2f)

        assertEquals(listOf(0.1f, 0.2f), samples)
    }

    // --- the system ends a recording ---

    @Test
    fun `an interruption while recording keeps the file and freezes the elapsed time`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.advanceElapsed(3.seconds)
        recorder.emitLevel(0.5f)

        val applied: Boolean = recorder.simulateInterruption(InterruptionReason.AudioSessionInterrupted)

        assertTrue(applied)
        assertEquals(
            RecorderState.Interrupted(RecordedFile(path, 3.seconds), InterruptionReason.AudioSessionInterrupted),
            recorder.state.value,
        )
        assertEquals(0f, recorder.level.value)
        recorder.advanceElapsed(5.seconds)
        assertEquals(3.seconds, recorder.elapsed.value, "elapsed stays frozen")
        assertContentEquals(emptyList(), recorder.deletedPaths)
        assertContentEquals(emptyList(), recorder.completedRecordings, "an interruption is not a stop")
    }

    @Test
    fun `an interruption while paused keeps the paused duration`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.advanceElapsed(2.seconds)
        recorder.pause()

        assertTrue(recorder.simulateInterruption(InterruptionReason.EngineDied(7)))

        assertEquals(
            RecorderState.Interrupted(RecordedFile(path, 2.seconds), InterruptionReason.EngineDied(7)),
            recorder.state.value,
        )
    }

    @Test
    fun `an interruption of a prepared recorder loses the empty file`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())

        assertTrue(recorder.simulateInterruption(InterruptionReason.StorageLow))

        assertEquals(
            RecorderState.Failed(RecorderError.RecordingLost(InterruptionReason.StorageLow), outputPath = null),
            recorder.state.value,
        )
        assertContentEquals(listOf(path), recorder.deletedPaths)
    }

    @Test
    fun `a silenced microphone is only raised while recording`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        assertEquals(false, recorder.simulateInterruption(InterruptionReason.MicrophoneSilenced))
        assertEquals(false, recorder.simulateRecordingLost(InterruptionReason.MicrophoneSilenced))
        recorder.start()
        recorder.pause()
        assertEquals(false, recorder.simulateInterruption(InterruptionReason.MicrophoneSilenced))
        assertEquals(false, recorder.simulateRecordingLost(InterruptionReason.MicrophoneSilenced))
        assertTrue(recorder.state.value is RecorderState.Paused)
        recorder.resume()

        assertTrue(recorder.simulateInterruption(InterruptionReason.MicrophoneSilenced))
        assertTrue(recorder.state.value is RecorderState.Interrupted)
    }

    @Test
    fun `a lost recording is a failed state that keeps the path`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.advanceElapsed(4.seconds)
        val cause = IllegalStateException("moov")

        assertTrue(recorder.simulateRecordingLost(InterruptionReason.EngineDied(), cause))

        assertEquals(
            RecorderState.Failed(
                RecorderError.RecordingLost(InterruptionReason.EngineDied(), cause),
                outputPath = path,
            ),
            recorder.state.value,
        )
        assertEquals(4.seconds, recorder.elapsed.value)
        assertContentEquals(emptyList(), recorder.deletedPaths)
    }

    @Test
    fun `a lost recording of a prepared recorder has no path`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())

        assertTrue(recorder.simulateRecordingLost(InterruptionReason.AudioSessionInterrupted))

        assertEquals(
            RecorderState.Failed(
                RecorderError.RecordingLost(InterruptionReason.AudioSessionInterrupted),
                outputPath = null,
            ),
            recorder.state.value,
        )
        assertContentEquals(listOf(path), recorder.deletedPaths)
    }

    @Test
    fun `simulating a system end in any other state does nothing and says so`() = runTest {
        val recorder = FakeAudioRecorder()
        assertEquals(false, recorder.simulateInterruption(InterruptionReason.StorageLow), "idle")
        assertEquals(false, recorder.simulateRecordingLost(InterruptionReason.StorageLow), "idle")
        recorder.prepare()
        recorder.start()
        recorder.stop()
        val completed: RecorderState = recorder.state.value
        assertEquals(false, recorder.simulateInterruption(InterruptionReason.StorageLow), "completed")
        assertEquals(false, recorder.simulateRecordingLost(InterruptionReason.StorageLow), "completed")
        assertEquals(completed, recorder.state.value)
        recorder.release()
        assertEquals(false, recorder.simulateInterruption(InterruptionReason.StorageLow), "released")
        assertEquals(false, recorder.simulateRecordingLost(InterruptionReason.StorageLow), "released")
        assertEquals(RecorderState.Released, recorder.state.value)
    }

    @Test
    fun `a second system end is refused because the recording is already over`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        assertTrue(recorder.simulateInterruption(InterruptionReason.StorageLow))
        val interrupted: RecorderState = recorder.state.value

        assertEquals(false, recorder.simulateInterruption(InterruptionReason.EngineDied()))
        assertEquals(false, recorder.simulateRecordingLost(InterruptionReason.EngineDied()))

        assertEquals(interrupted, recorder.state.value)
    }

    @Test
    fun `stop from interrupted returns the recording and leaves the state alone`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.advanceElapsed(6.seconds)
        recorder.simulateInterruption(InterruptionReason.AudioSessionInterrupted)
        val interrupted: RecorderState = recorder.state.value
        val emissions: MutableList<RecorderState> = mutableListOf()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            recorder.state.collect { emissions += it }
        }

        val result: RecorderResult<RecordedFile> = recorder.stop()

        assertEquals(RecorderResult.Success(RecordedFile(path, 6.seconds)), result)
        assertEquals(interrupted, recorder.state.value)
        assertEquals(listOf(interrupted), emissions, "nothing is emitted")
        assertContentEquals(emptyList(), recorder.completedRecordings)
        collector.cancel()
    }

    @Test
    fun `cancel from interrupted deletes the file and returns to idle`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.advanceElapsed(6.seconds)
        recorder.simulateInterruption(InterruptionReason.AudioSessionInterrupted)

        assertEquals(RecorderResult.Success(Unit), recorder.cancel())

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertContentEquals(listOf(path), recorder.deletedPaths)
        assertEquals(Duration.ZERO, recorder.elapsed.value)
    }

    @Test
    fun `cancel from a lost recording deletes the kept file`() = runTest {
        val recorder = FakeAudioRecorder()
        val path: String = requireNotNull(recorder.prepare().getOrNull())
        recorder.start()
        recorder.simulateRecordingLost(InterruptionReason.StorageLow)

        assertEquals(RecorderResult.Success(Unit), recorder.cancel())

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertContentEquals(listOf(path), recorder.deletedPaths)
    }

    @Test
    fun `cancel from a failure that left no file is still refused`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.permissionGranted = false
        recorder.prepare()
        val failed: RecorderState = recorder.state.value

        assertEquals(
            RecorderError.IllegalState(failed, RecorderOperation.CANCEL),
            recorder.cancel().errorOrNull(),
        )
    }

    @Test
    fun `prepare from interrupted starts a fresh recording and keeps the interrupted file`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.advanceElapsed(2.seconds)
        recorder.simulateInterruption(InterruptionReason.AudioSessionInterrupted)

        val next: String = requireNotNull(recorder.prepare().getOrNull())

        assertEquals(RecorderState.Ready(next), recorder.state.value)
        assertContentEquals(emptyList(), recorder.deletedPaths)
        assertEquals(Duration.ZERO, recorder.elapsed.value)
    }

    @Test
    fun `release from interrupted keeps the file`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.simulateInterruption(InterruptionReason.AudioSessionInterrupted)

        recorder.release()

        assertEquals(RecorderState.Released, recorder.state.value)
        assertContentEquals(emptyList(), recorder.deletedPaths)
    }

    @Test
    fun `a system end does not consume a scripted failure`() = runTest {
        val recorder = FakeAudioRecorder()
        recorder.prepare()
        recorder.start()
        recorder.failNextOperationWith = RecorderError.PermissionDenied

        assertTrue(recorder.simulateInterruption(InterruptionReason.StorageLow))

        assertEquals(RecorderError.PermissionDenied, recorder.failNextOperationWith)
    }
}
