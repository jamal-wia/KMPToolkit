package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * What happens when the platform recorder itself misbehaves, and what happens when the caller's
 * coroutine is cancelled while a recording is being prepared. Neither may escape as a platform
 * exception, and neither may leave a native handle or a stray file behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderFailureTest {

    // --- engine failures ---

    @Test
    fun `a failing prepare is reported as an engine failure carrying the cause`() =
        runRecorderTest { fixture ->
            val cause = IllegalStateException("codec unavailable")
            fixture.engine.failures[RecorderOperation.PREPARE] = cause

            val result: RecorderResult<String> = fixture.recorder.prepare()

            val error: RecorderError? = result.errorOrNull()
            assertTrue(error is RecorderError.EngineFailure)
            assertEquals(RecorderOperation.PREPARE, error.operation)
            assertSame(cause, error.cause)
            assertEquals(RecorderState.Failed(error), fixture.recorder.state.value)
        }

    @Test
    fun `a failing prepare deletes the file it opened and frees the handle`() =
        runRecorderTest { fixture ->
            fixture.engine.failures[RecorderOperation.PREPARE] = IllegalStateException("boom")

            fixture.recorder.prepare()

            assertContentEquals(listOf(GENERATED_PATH), fixture.fileSystem.deletedPaths)
            assertEquals(
                "release",
                fixture.engine.calls.last(),
                "a half-open native recorder must not outlive a failed prepare",
            )
        }

    @Test
    fun `a failing start deletes the empty file and reports an engine failure`() =
        runRecorderTest { fixture ->
            val path: String = fixture.prepared()
            fixture.engine.failures[RecorderOperation.START] = IllegalStateException("busy")

            val error: RecorderError? = fixture.recorder.start().errorOrNull()

            assertEquals(
                RecorderOperation.START,
                (error as RecorderError.EngineFailure).operation,
            )
            assertEquals(RecorderState.Failed(error), fixture.recorder.state.value)
            assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths)
        }

    @Test
    fun `a failing pause leaves the recorder recording`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        fixture.engine.failures[RecorderOperation.PAUSE] = IllegalStateException("no pause here")

        val error: RecorderError? = fixture.recorder.pause().errorOrNull()

        assertEquals(RecorderOperation.PAUSE, (error as RecorderError.EngineFailure).operation)
        assertEquals(
            RecorderState.Recording(path),
            fixture.recorder.state.value,
            "an engine that refused to pause is still capturing",
        )

        fixture.timeSource += 3.seconds
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(
            3.seconds,
            fixture.recorder.elapsed.value,
            "the timer must keep running too — a frozen timer over a running recording is a lie",
        )
    }

    @Test
    fun `a failing resume leaves the recorder paused`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.recorder.pause()
        val paused: RecorderState = fixture.recorder.state.value
        fixture.engine.failures[RecorderOperation.RESUME] = IllegalStateException("nope")

        val error: RecorderError? = fixture.recorder.resume().errorOrNull()

        assertEquals(RecorderOperation.RESUME, (error as RecorderError.EngineFailure).operation)
        assertEquals(paused, fixture.recorder.state.value)

        fixture.timeSource += 3.seconds
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(
            Duration.ZERO,
            fixture.recorder.elapsed.value,
            "nothing resumed, so nothing may accumulate",
        )
    }

    @Test
    fun `a failing stop keeps whatever was captured on disk`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        fixture.engine.failures[RecorderOperation.STOP] = IllegalStateException("encoder died")

        val error: RecorderError? = fixture.recorder.stop().errorOrNull()

        assertEquals(RecorderOperation.STOP, (error as RecorderError.EngineFailure).operation)
        assertEquals(RecorderState.Failed(error, path), fixture.recorder.state.value)
        assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
    }

    @Test
    fun `an engine that throws while cancelling does not stop the file being deleted`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            fixture.engine.failures[RecorderOperation.STOP] = IllegalStateException("encoder died")

            assertEquals(RecorderResult.Success(Unit), fixture.recorder.cancel())

            assertEquals(RecorderState.Idle, fixture.recorder.state.value)
            assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths)
        }

    @Test
    fun `an engine that throws while releasing cannot break release`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.engine.failures[RecorderOperation.STOP] = IllegalStateException("encoder died")

        fixture.recorder.release()

        assertEquals(RecorderState.Released, fixture.recorder.state.value)
    }

    // --- cancellation mid-preparation ---

    @Test
    fun `no operation is legal while a preparation is in flight`() = runRecorderTest { fixture ->
        val gate = CompletableDeferred<Unit>()
        fixture.engine.prepareGate = gate
        val preparing: Job = launch { fixture.recorder.prepare() }
        runCurrent()

        assertEquals(RecorderState.Preparing, fixture.recorder.state.value)
        assertEquals(
            RecorderError.IllegalState(RecorderState.Preparing, RecorderOperation.START),
            fixture.recorder.start().errorOrNull(),
        )
        assertEquals(
            RecorderError.IllegalState(RecorderState.Preparing, RecorderOperation.STOP),
            fixture.recorder.stop().errorOrNull(),
        )
        assertEquals(
            RecorderError.IllegalState(RecorderState.Preparing, RecorderOperation.CANCEL),
            fixture.recorder.cancel().errorOrNull(),
        )
        assertEquals(
            RecorderError.IllegalState(RecorderState.Preparing, RecorderOperation.PREPARE),
            fixture.recorder.prepare().errorOrNull(),
        )

        gate.complete(Unit)
        preparing.join()
        assertEquals(RecorderState.Ready(GENERATED_PATH), fixture.recorder.state.value)
    }

    @Test
    fun `cancelling mid-preparation frees the handle and deletes the half-open file`() =
        runRecorderTest { fixture ->
            fixture.engine.prepareGate = CompletableDeferred()
            val preparing: Job = launch { fixture.recorder.prepare() }
            runCurrent()
            val releasesBefore: Int = fixture.engine.releaseCount

            preparing.cancel()
            preparing.join()

            assertEquals(RecorderState.Idle, fixture.recorder.state.value)
            assertContentEquals(listOf(GENERATED_PATH), fixture.fileSystem.deletedPaths)
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount)
        }

    @Test
    fun `cancellation propagates to the caller rather than being reported as a failure`() =
        runRecorderTest { fixture ->
            fixture.engine.prepareGate = CompletableDeferred()

            val preparing = launch {
                assertFailsWith<CancellationException> { fixture.recorder.prepare() }
            }
            runCurrent()
            preparing.cancel()
            preparing.join()
        }

    @Test
    fun `a recorder cancelled mid-preparation can prepare again`() = runRecorderTest { fixture ->
        fixture.engine.prepareGate = CompletableDeferred()
        val preparing: Job = launch { fixture.recorder.prepare() }
        runCurrent()
        preparing.cancel()
        preparing.join()

        fixture.engine.prepareGate = null

        assertEquals(RecorderResult.Success(GENERATED_PATH), fixture.recorder.prepare())
    }
}
