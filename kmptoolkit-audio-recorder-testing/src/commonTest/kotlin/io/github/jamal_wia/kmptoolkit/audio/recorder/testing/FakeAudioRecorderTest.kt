package io.github.jamal_wia.kmptoolkit.audio.recorder.testing

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
import kotlinx.coroutines.test.runTest

/**
 * The fake is only useful if it refuses what the real recorder refuses — a test that passes against
 * a permissive double proves nothing. These cases are derived from the transition table documented
 * on `AudioRecorder`, the same source the production recorder's own suite works from, so the two
 * cannot quietly drift apart.
 */
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
            "Failed" to setOf(RecorderOperation.PREPARE),
        )
        val arrange: Map<String, suspend (FakeAudioRecorder) -> Unit> = mapOf(
            "Idle" to { },
            "Ready" to { recorder -> recorder.prepare() },
            "Recording" to { recorder -> recorder.prepare(); recorder.start() },
            "Paused" to { recorder -> recorder.prepare(); recorder.start(); recorder.pause() },
            "Completed" to { recorder -> recorder.prepare(); recorder.start(); recorder.stop() },
            "Failed" to { recorder ->
                recorder.permissionGranted = false
                recorder.prepare()
                recorder.permissionGranted = true
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
}
