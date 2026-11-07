package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent

/**
 * Interleavings the threading contract allows, derived from the [AudioRecorder] KDoc: `release()`
 * may land while `prepare()` is suspended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderConcurrencyTest {

    // --- release vs prepare ---

    @Test
    fun `release while the engine is preparing does not drive the engine`() =
        runRecorderTest { fixture ->
            val gate = CompletableDeferred<Unit>()
            fixture.engine.prepareGate = gate
            val preparing: Job = launch { fixture.recorder.prepare() }
            runCurrent()
            assertTrue(fixture.engine.inPrepare, "the engine's prepare must be running")

            fixture.recorder.release()
            gate.complete(Unit)
            preparing.join()

            assertEquals(
                0,
                fixture.engine.releasesDuringPrepare,
                "release() drove the engine while its prepare was still running on the worker",
            )
            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertFalse(fixture.engine.inPrepare)
        }

    @Test
    fun `a release that lands before the worker reaches the engine never opens the microphone`() =
        runRecorderTest { fixture ->
            // Lands on the worker after beginPrepare took Preparing and before engine.prepare ran.
            fixture.fileSystem.onEnsureWritableDirectory = { fixture.recorder.release() }

            val result: RecorderResult<String> = fixture.recorder.prepare()

            assertEquals(
                RecorderError.AlreadyReleased(RecorderOperation.PREPARE),
                result.errorOrNull(),
            )
            assertTrue(
                fixture.engine.calls.none { it.startsWith("prepare(") },
                "engine.prepare ran for a recorder that was already released: ${fixture.engine.calls}",
            )
        }
}
