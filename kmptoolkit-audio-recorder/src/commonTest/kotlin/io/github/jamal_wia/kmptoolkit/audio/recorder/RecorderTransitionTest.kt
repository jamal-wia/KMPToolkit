package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The transition table documented on [AudioRecorder], asserted case by case — both that every
 * legal move lands where the table says, and that every illegal one is refused as an inert
 * [RecorderError.IllegalState] that leaves the recorder exactly as it was.
 */
class RecorderTransitionTest {

    @Test
    fun `a fresh recorder is idle`() = runRecorderTest { fixture ->
        assertEquals(RecorderState.Idle, fixture.recorder.state.value)
    }

    // --- the happy path ---

    @Test
    fun `prepare moves idle to ready and returns the resolved path`() = runRecorderTest { fixture ->
        val result: RecorderResult<String> = fixture.recorder.prepare()

        assertEquals(RecorderResult.Success(GENERATED_PATH), result)
        assertEquals(RecorderState.Ready(GENERATED_PATH), fixture.recorder.state.value)
    }

    @Test
    fun `start moves ready to recording`() = runRecorderTest { fixture ->
        val path: String = fixture.prepared()

        assertEquals(RecorderResult.Success(Unit), fixture.recorder.start())
        assertEquals(RecorderState.Recording(path), fixture.recorder.state.value)
    }

    @Test
    fun `pause moves recording to paused`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()

        assertEquals(RecorderResult.Success(Unit), fixture.recorder.pause())
        assertEquals(
            RecorderState.Paused(path, fixture.recorder.elapsed.value),
            fixture.recorder.state.value,
        )
    }

    @Test
    fun `resume moves paused back to recording`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        fixture.recorder.pause()

        assertEquals(RecorderResult.Success(Unit), fixture.recorder.resume())
        assertEquals(RecorderState.Recording(path), fixture.recorder.state.value)
    }

    @Test
    fun `stop from recording completes with the recorded file`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()

        val result: RecorderResult<RecordedFile> = fixture.recorder.stop()

        val recorded: RecordedFile = requireNotNull(result.getOrNull())
        assertEquals(path, recorded.path)
        assertEquals(RecorderState.Completed(recorded), fixture.recorder.state.value)
    }

    @Test
    fun `stop from paused completes with the recorded file`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        fixture.recorder.pause()

        val recorded: RecordedFile = requireNotNull(fixture.recorder.stop().getOrNull())

        assertEquals(path, recorded.path)
        assertEquals(RecorderState.Completed(recorded), fixture.recorder.state.value)
    }

    @Test
    fun `stop releases the native recorder so the microphone is not held`() =
        runRecorderTest { fixture ->
            fixture.recording()
            val releasesBefore: Int = fixture.engine.releaseCount

            fixture.recorder.stop()

            assertEquals(releasesBefore + 1, fixture.engine.releaseCount)
        }

    // --- cancel ---

    @Test
    fun `cancel from recording returns to idle and deletes the partial file`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()

            assertEquals(RecorderResult.Success(Unit), fixture.recorder.cancel())

            assertEquals(RecorderState.Idle, fixture.recorder.state.value)
            assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths)
        }

    @Test
    fun `cancel from ready deletes the file that was opened but never recorded into`() =
        runRecorderTest { fixture ->
            val path: String = fixture.prepared()

            fixture.recorder.cancel()

            assertEquals(RecorderState.Idle, fixture.recorder.state.value)
            assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths)
            assertFalse("stop" in fixture.engine.calls, "nothing was recording, so nothing to stop")
        }

    @Test
    fun `cancel from paused returns to idle and deletes the partial file`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            fixture.recorder.pause()

            fixture.recorder.cancel()

            assertEquals(RecorderState.Idle, fixture.recorder.state.value)
            assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths)
        }

    @Test
    fun `cancel refuses to delete a completed recording`() = runRecorderTest { fixture ->
        fixture.recording()
        val completed: RecordedFile = requireNotNull(fixture.recorder.stop().getOrNull())

        val error: RecorderError? = fixture.recorder.cancel().errorOrNull()

        assertEquals(
            RecorderError.IllegalState(
                RecorderState.Completed(completed),
                RecorderOperation.CANCEL,
            ),
            error,
        )
        assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
    }

    @Test
    fun `cancel from idle is refused`() = runRecorderTest { fixture ->
        assertEquals(
            RecorderError.IllegalState(RecorderState.Idle, RecorderOperation.CANCEL),
            fixture.recorder.cancel().errorOrNull(),
        )
    }

    // --- illegal transitions ---

    @Test
    fun `starting twice is refused and leaves the recording untouched`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            val callsBefore: List<String> = fixture.engine.calls.toList()

            val error: RecorderError? = fixture.recorder.start().errorOrNull()

            assertEquals(
                RecorderError.IllegalState(
                    RecorderState.Recording(path),
                    RecorderOperation.START,
                ),
                error,
            )
            assertEquals(RecorderState.Recording(path), fixture.recorder.state.value)
            assertContentEquals(callsBefore, fixture.engine.calls)
        }

    @Test
    fun `stopping before starting is refused`() = runRecorderTest { fixture ->
        val path: String = fixture.prepared()

        val error: RecorderError? = fixture.recorder.stop().errorOrNull()

        assertEquals(
            RecorderError.IllegalState(RecorderState.Ready(path), RecorderOperation.STOP),
            error,
        )
        assertEquals(RecorderState.Ready(path), fixture.recorder.state.value)
    }

    @Test
    fun `stopping from idle is refused`() = runRecorderTest { fixture ->
        assertEquals(
            RecorderError.IllegalState(RecorderState.Idle, RecorderOperation.STOP),
            fixture.recorder.stop().errorOrNull(),
        )
    }

    @Test
    fun `starting from idle is refused`() = runRecorderTest { fixture ->
        assertEquals(
            RecorderError.IllegalState(RecorderState.Idle, RecorderOperation.START),
            fixture.recorder.start().errorOrNull(),
        )
        assertFalse("start" in fixture.engine.calls)
    }

    @Test
    fun `pausing a recorder that is only ready is refused`() = runRecorderTest { fixture ->
        val path: String = fixture.prepared()

        assertEquals(
            RecorderError.IllegalState(RecorderState.Ready(path), RecorderOperation.PAUSE),
            fixture.recorder.pause().errorOrNull(),
        )
    }

    @Test
    fun `resuming a recorder that is already recording is refused`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()

        assertEquals(
            RecorderError.IllegalState(RecorderState.Recording(path), RecorderOperation.RESUME),
            fixture.recorder.resume().errorOrNull(),
        )
    }

    @Test
    fun `start is refused while paused because resume is the only way back`() =
        runRecorderTest { fixture ->
            fixture.recording()
            fixture.recorder.pause()
            val paused: RecorderState = fixture.recorder.state.value

            assertEquals(
                RecorderError.IllegalState(paused, RecorderOperation.START),
                fixture.recorder.start().errorOrNull(),
            )
            assertEquals(paused, fixture.recorder.state.value)
        }

    @Test
    fun `pausing twice is refused`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.recorder.pause()
        val paused: RecorderState = fixture.recorder.state.value

        assertEquals(
            RecorderError.IllegalState(paused, RecorderOperation.PAUSE),
            fixture.recorder.pause().errorOrNull(),
        )
    }

    @Test
    fun `preparing while recording is refused`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()

        assertEquals(
            RecorderError.IllegalState(RecorderState.Recording(path), RecorderOperation.PREPARE),
            fixture.recorder.prepare().errorOrNull(),
        )
        assertEquals(RecorderState.Recording(path), fixture.recorder.state.value)
    }

    @Test
    fun `preparing while paused is refused`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.recorder.pause()
        val paused: RecorderState = fixture.recorder.state.value

        assertEquals(
            RecorderError.IllegalState(paused, RecorderOperation.PREPARE),
            fixture.recorder.prepare().errorOrNull(),
        )
    }

    // --- re-preparing ---

    @Test
    fun `preparing again after a completed recording starts a new one`() =
        runRecorderTest { fixture ->
            fixture.recording()
            val completed: RecordedFile = requireNotNull(fixture.recorder.stop().getOrNull())

            val second: String = fixture.prepared(outputPath = "/data/app/second.m4a")

            assertEquals(RecorderState.Ready(second), fixture.recorder.state.value)
            assertFalse(
                completed.path in fixture.fileSystem.deletedPaths,
                "a finished recording belongs to the caller and must survive a re-prepare",
            )
        }

    @Test
    fun `preparing again after a failure starts a new recording`() = runRecorderTest { fixture ->
        fixture.engine.permissionGranted = false
        fixture.recorder.prepare()
        assertTrue(fixture.recorder.state.value is RecorderState.Failed)

        fixture.engine.permissionGranted = true
        val result: RecorderResult<String> = fixture.recorder.prepare()

        assertEquals(RecorderResult.Success(GENERATED_PATH), result)
        assertEquals(RecorderState.Ready(GENERATED_PATH), fixture.recorder.state.value)
    }

    @Test
    fun `preparing again from ready discards the file that was never recorded into`() =
        runRecorderTest { fixture ->
            val first: String = fixture.prepared(outputPath = "/data/app/first.m4a")

            fixture.prepared(outputPath = "/data/app/second.m4a")

            assertContentEquals(listOf(first), fixture.fileSystem.deletedPaths)
            assertEquals(
                RecorderState.Ready("/data/app/second.m4a"),
                fixture.recorder.state.value,
            )
        }
}
