package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The convenience properties on [RecorderState], and the [RecorderResult] accessors. */
class RecorderStateExtensionsTest {

    private val recorded = RecordedFile(path = "/tmp/a.m4a", duration = 5.seconds)

    private val everyState: List<RecorderState> = listOf(
        RecorderState.Idle,
        RecorderState.Preparing,
        RecorderState.Ready("/tmp/a.m4a"),
        RecorderState.Recording("/tmp/a.m4a"),
        RecorderState.Paused("/tmp/a.m4a", 1.seconds),
        RecorderState.Completed(recorded),
        RecorderState.Failed(RecorderError.PermissionDenied),
        RecorderState.Released,
    )

    @Test
    fun `only the recording state reports isRecording`() {
        val recording: List<RecorderState> = everyState.filter { it.isRecording }

        assertEquals(listOf(RecorderState.Recording("/tmp/a.m4a")), recording)
    }

    @Test
    fun `an active recorder is one holding an open file`() {
        val active: List<RecorderState> = everyState.filter { it.isActive }

        assertEquals(
            listOf(
                RecorderState.Recording("/tmp/a.m4a"),
                RecorderState.Paused("/tmp/a.m4a", 1.seconds),
            ),
            active,
        )
    }

    @Test
    fun `outputPath is exposed by every state that has a file`() {
        assertEquals("/tmp/a.m4a", RecorderState.Ready("/tmp/a.m4a").outputPath)
        assertEquals("/tmp/a.m4a", RecorderState.Recording("/tmp/a.m4a").outputPath)
        assertEquals("/tmp/a.m4a", RecorderState.Paused("/tmp/a.m4a", 1.seconds).outputPath)
        assertEquals("/tmp/a.m4a", RecorderState.Completed(recorded).outputPath)
    }

    @Test
    fun `outputPath is null in every state that has no file`() {
        assertNull(RecorderState.Idle.outputPath)
        assertNull(RecorderState.Preparing.outputPath)
        assertNull(RecorderState.Failed(RecorderError.PermissionDenied).outputPath)
        assertNull(RecorderState.Released.outputPath)
    }

    @Test
    fun `a success carries its value and no error`() {
        val result: RecorderResult<String> = RecorderResult.Success("/tmp/a.m4a")

        assertTrue(result.isSuccess)
        assertEquals("/tmp/a.m4a", result.getOrNull())
        assertNull(result.errorOrNull())
    }

    @Test
    fun `a failure carries its error and no value`() {
        val result: RecorderResult<String> = RecorderResult.Failure(RecorderError.PermissionDenied)

        assertFalse(result.isSuccess)
        assertNull(result.getOrNull())
        assertEquals(RecorderError.PermissionDenied, result.errorOrNull())
    }
}
