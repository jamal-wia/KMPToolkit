package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Resource release: that [AudioRecorder.release] frees the native handle from every state, that it
 * is idempotent, and that a released recorder refuses every operation instead of touching a handle
 * it no longer owns.
 */
class RecorderReleaseTest {

    @Test
    fun `release from idle moves to the released state`() = runRecorderTest { fixture ->
        fixture.recorder.release()

        assertEquals(RecorderState.Released, fixture.recorder.state.value)
    }

    @Test
    fun `releasing twice is a no-op and does not touch the engine again`() =
        runRecorderTest { fixture ->
            fixture.recording()
            fixture.recorder.release()
            val callsAfterFirst: List<String> = fixture.engine.calls.toList()

            fixture.recorder.release()

            assertContentEquals(callsAfterFirst, fixture.engine.calls)
            assertEquals(RecorderState.Released, fixture.recorder.state.value)
        }

    @Test
    fun `release while recording stops the engine and frees the handle`() =
        runRecorderTest { fixture ->
            fixture.recording()
            val releasesBefore: Int = fixture.engine.releaseCount

            fixture.recorder.release()

            assertTrue("stop" in fixture.engine.calls)
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount)
        }

    @Test
    fun `release keeps the partial file because releasing is a lifecycle event`() =
        runRecorderTest { fixture ->
            fixture.recording()

            fixture.recorder.release()

            assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
        }

    @Test
    fun `release cancels the scope the elapsed ticker runs in`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.timeSource += 1.seconds
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(fixture.scope.isActive)

        fixture.recorder.release()

        assertFalse(
            fixture.scope.isActive,
            "the recorder owns its scope and must not leave the ticker running after release",
        )
        assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)
    }

    @Test
    fun `every operation after release reports that the recorder is gone`() =
        runRecorderTest { fixture ->
            fixture.recorder.release()

            assertEquals(
                RecorderError.AlreadyReleased(RecorderOperation.PREPARE),
                fixture.recorder.prepare().errorOrNull(),
            )
            assertEquals(
                RecorderError.AlreadyReleased(RecorderOperation.START),
                fixture.recorder.start().errorOrNull(),
            )
            assertEquals(
                RecorderError.AlreadyReleased(RecorderOperation.PAUSE),
                fixture.recorder.pause().errorOrNull(),
            )
            assertEquals(
                RecorderError.AlreadyReleased(RecorderOperation.RESUME),
                fixture.recorder.resume().errorOrNull(),
            )
            assertEquals(
                RecorderError.AlreadyReleased(RecorderOperation.STOP),
                fixture.recorder.stop().errorOrNull(),
            )
            assertEquals(
                RecorderError.AlreadyReleased(RecorderOperation.CANCEL),
                fixture.recorder.cancel().errorOrNull(),
            )
        }

    @Test
    fun `a released recorder stays released and never reaches the engine again`() =
        runRecorderTest { fixture ->
            fixture.recorder.release()
            val callsAfterRelease: List<String> = fixture.engine.calls.toList()

            fixture.recorder.prepare()
            fixture.recorder.start()

            assertContentEquals(callsAfterRelease, fixture.engine.calls)
            assertEquals(RecorderState.Released, fixture.recorder.state.value)
        }

    @Test
    fun `release after a completed recording still frees the handle exactly once`() =
        runRecorderTest { fixture ->
            fixture.recording()
            fixture.recorder.stop()
            val releasesAfterStop: Int = fixture.engine.releaseCount

            fixture.recorder.release()

            assertEquals(releasesAfterStop + 1, fixture.engine.releaseCount)
            assertEquals(
                1,
                fixture.engine.calls.count { it == "stop" },
                "a completed recording must not be stopped a second time",
            )
        }
}
