package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent

/**
 * Every cell of the transition table documented on [AudioRecorder], driven mechanically: for each
 * of the eight states, each of the six operations is attempted and the *illegal* ones are asserted
 * to be refused with [RecorderError.IllegalState] (or [RecorderError.AlreadyReleased] from
 * `Released`) and to leave the state untouched.
 *
 * [RecorderTransitionTest] covers the legal cells individually, asserting what each one actually
 * does. This class is the complement: it exists so that a cell cannot be quietly opened up without
 * a test noticing, which a hand-written list of "the interesting refusals" cannot guarantee.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderTransitionTableTest {

    /** The operations that are **legal** in each state, straight from the documented table. */
    private val legalOperations: Map<String, Set<RecorderOperation>> = mapOf(
        "Idle" to setOf(RecorderOperation.PREPARE),
        "Preparing" to emptySet(),
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
        "Released" to emptySet(),
    )

    @Test
    fun `illegal operations from idle are refused`() = assertRefusalsFrom("Idle") { }

    @Test
    fun `illegal operations from ready are refused`() = assertRefusalsFrom("Ready") { fixture ->
        fixture.prepared()
    }

    @Test
    fun `illegal operations from recording are refused`() =
        assertRefusalsFrom("Recording") { fixture -> fixture.recording() }

    @Test
    fun `illegal operations from paused are refused`() = assertRefusalsFrom("Paused") { fixture ->
        fixture.recording()
        fixture.recorder.pause()
    }

    @Test
    fun `illegal operations from completed are refused`() =
        assertRefusalsFrom("Completed") { fixture ->
            fixture.recording()
            fixture.recorder.stop()
        }

    @Test
    fun `illegal operations from failed are refused`() = assertRefusalsFrom("Failed") { fixture ->
        fixture.engine.permissionGranted = false
        fixture.recorder.prepare()
        fixture.engine.permissionGranted = true
    }

    @Test
    fun `illegal operations while preparing are refused`() = runRecorderTest { fixture ->
        val gate = CompletableDeferred<Unit>()
        fixture.engine.prepareGate = gate
        val preparing: Job = launch { fixture.recorder.prepare() }
        runCurrent()
        assertEquals(RecorderState.Preparing, fixture.recorder.state.value)

        assertRefusals(fixture, stateName = "Preparing")

        gate.complete(Unit)
        preparing.join()
    }

    @Test
    fun `every operation after release reports that the recorder is gone`() =
        runRecorderTest { fixture ->
            fixture.recorder.release()

            RecorderOperation.entries.forEach { operation ->
                assertEquals(
                    RecorderError.AlreadyReleased(operation),
                    fixture.invoke(operation).errorOrNull(),
                    "$operation from Released",
                )
                assertEquals(RecorderState.Released, fixture.recorder.state.value)
            }
        }

    private fun assertRefusalsFrom(
        stateName: String,
        arrange: suspend (RecorderFixture) -> Unit,
    ) = runRecorderTest { fixture ->
        arrange(fixture)

        assertRefusals(fixture, stateName)
    }

    private suspend fun assertRefusals(fixture: RecorderFixture, stateName: String) {
        val expectedState: RecorderState = fixture.recorder.state.value
        val legal: Set<RecorderOperation> = requireNotNull(legalOperations[stateName])
        val callsBefore: List<String> = fixture.engine.calls.toList()

        RecorderOperation.entries
            .filterNot { it in legal }
            .forEach { operation ->
                assertEquals(
                    RecorderError.IllegalState(expectedState, operation),
                    fixture.invoke(operation).errorOrNull(),
                    "$operation from $stateName",
                )
                assertEquals(
                    expectedState,
                    fixture.recorder.state.value,
                    "a refused $operation must not change the state",
                )
            }

        assertEquals(
            callsBefore,
            fixture.engine.calls,
            "a refused operation must not reach the platform recorder",
        )
    }

    private suspend fun RecorderFixture.invoke(
        operation: RecorderOperation,
    ): RecorderResult<*> = when (operation) {
        RecorderOperation.PREPARE -> recorder.prepare()
        RecorderOperation.START -> recorder.start()
        RecorderOperation.PAUSE -> recorder.pause()
        RecorderOperation.RESUME -> recorder.resume()
        RecorderOperation.STOP -> recorder.stop()
        RecorderOperation.CANCEL -> recorder.cancel()
    }
}
