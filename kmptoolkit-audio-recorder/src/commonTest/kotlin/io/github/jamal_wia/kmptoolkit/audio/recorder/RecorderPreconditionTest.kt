package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Everything [AudioRecorder.prepare] refuses before it opens the microphone — a missing permission,
 * a format the platform cannot encode, a directory it cannot write, a volume with no room — plus
 * how an output path is resolved when the caller does not supply one.
 */
class RecorderPreconditionTest {

    // --- permission ---

    @Test
    fun `prepare without the record permission fails instead of crashing`() =
        runRecorderTest { fixture ->
            fixture.engine.permissionGranted = false

            val result: RecorderResult<String> = fixture.recorder.prepare()

            assertEquals(RecorderResult.Failure(RecorderError.PermissionDenied), result)
            assertEquals(
                RecorderState.Failed(RecorderError.PermissionDenied),
                fixture.recorder.state.value,
            )
        }

    @Test
    fun `a denied permission stops prepare before the platform recorder is touched`() =
        runRecorderTest { fixture ->
            fixture.engine.permissionGranted = false

            fixture.recorder.prepare()

            assertFalse(
                fixture.engine.calls.any { it.startsWith("prepare(") },
                "the engine must not be asked to open the microphone without permission",
            )
            assertEquals(emptySet(), fixture.fileSystem.createdDirectories)
        }

    @Test
    fun `prepare succeeds once the permission is granted`() = runRecorderTest { fixture ->
        fixture.engine.permissionGranted = false
        fixture.recorder.prepare()

        fixture.engine.permissionGranted = true

        assertEquals(RecorderResult.Success(GENERATED_PATH), fixture.recorder.prepare())
    }

    // --- format support ---

    @Test
    fun `prepare fails for a format the platform cannot encode`() {
        val config = AudioRecorderConfig(format = AudioFormat.WAV)
        runRecorderTest(config) { fixture ->
            fixture.engine.unsupportedFormats = setOf(AudioFormat.WAV)

            val result: RecorderResult<String> = fixture.recorder.prepare()

            assertEquals(
                RecorderResult.Failure(RecorderError.UnsupportedFormat(AudioFormat.WAV)),
                result,
            )
        }
    }

    // --- storage ---

    @Test
    fun `prepare fails when the output directory cannot be written`() =
        runRecorderTest { fixture ->
            fixture.fileSystem.unwritableDirectories += DEFAULT_DIRECTORY

            val result: RecorderResult<String> = fixture.recorder.prepare()

            assertEquals(
                RecorderResult.Failure(RecorderError.DirectoryNotWritable(DEFAULT_DIRECTORY)),
                result,
            )
            assertFalse(fixture.engine.calls.any { it.startsWith("prepare(") })
        }

    @Test
    fun `prepare fails when a path has no parent directory at all`() = runRecorderTest { fixture ->
        val result: RecorderResult<String> = fixture.recorder.prepare(outputPath = "orphan.m4a")

        assertEquals(
            RecorderResult.Failure(RecorderError.DirectoryNotWritable("orphan.m4a")),
            result,
        )
    }

    @Test
    fun `prepare fails when free space is below the configured minimum`() {
        val config = AudioRecorderConfig(minimumFreeSpaceBytes = 1_000L)
        runRecorderTest(config) { fixture ->
            fixture.fileSystem.freeSpace = 999L

            val result: RecorderResult<String> = fixture.recorder.prepare()

            assertEquals(
                RecorderResult.Failure(
                    RecorderError.InsufficientStorage(
                        path = DEFAULT_DIRECTORY,
                        requiredBytes = 1_000L,
                        availableBytes = 999L,
                    )
                ),
                result,
            )
            assertFalse(
                fixture.engine.calls.any { it.startsWith("prepare(") },
                "a doomed recording must not reach the microphone",
            )
        }
    }

    @Test
    fun `exactly the minimum free space is enough`() {
        val config = AudioRecorderConfig(minimumFreeSpaceBytes = 1_000L)
        runRecorderTest(config) { fixture ->
            fixture.fileSystem.freeSpace = 1_000L

            assertTrue(fixture.recorder.prepare().isSuccess)
        }
    }

    @Test
    fun `an unknown free space is treated as enough`() {
        val config = AudioRecorderConfig(minimumFreeSpaceBytes = 1_000L)
        runRecorderTest(config) { fixture ->
            fixture.fileSystem.freeSpace = -1L

            assertTrue(fixture.recorder.prepare().isSuccess)
        }
    }

    @Test
    fun `a zero minimum disables the free space check`() {
        val config = AudioRecorderConfig(minimumFreeSpaceBytes = 0L)
        runRecorderTest(config) { fixture ->
            fixture.fileSystem.freeSpace = 0L

            assertTrue(fixture.recorder.prepare().isSuccess)
        }
    }

    // --- path resolution ---

    @Test
    fun `a generated path defaults to the app id under the app private directory`() =
        runRecorderTest { fixture ->
            assertEquals(RecorderResult.Success(GENERATED_PATH), fixture.recorder.prepare())
            assertTrue(DEFAULT_DIRECTORY in fixture.fileSystem.createdDirectories)
        }

    @Test
    fun `a configured directory name replaces the app id`() {
        val config = AudioRecorderConfig(storage = RecordingStorage(directoryName = "voice-notes"))
        runRecorderTest(config) { fixture ->
            assertEquals(
                RecorderResult.Success("/data/app/voice-notes/$GENERATED_FILE_NAME"),
                fixture.recorder.prepare(),
            )
        }
    }

    @Test
    fun `a configured absolute directory replaces the app private base entirely`() {
        val config = AudioRecorderConfig(
            storage = RecordingStorage(directoryPath = "/sdcard/notes", directoryName = "ignored"),
        )
        runRecorderTest(config) { fixture ->
            assertEquals(
                RecorderResult.Success("/sdcard/notes/$GENERATED_FILE_NAME"),
                fixture.recorder.prepare(),
            )
        }
    }

    @Test
    fun `a generated file name uses the prefix timestamp and format extension`() {
        val config = AudioRecorderConfig(
            storage = RecordingStorage(fileNamePrefix = "note"),
            format = AudioFormat.AAC,
        )
        runRecorderTest(config) { fixture ->
            assertEquals(
                RecorderResult.Success("$DEFAULT_DIRECTORY/note_$FIXED_EPOCH_MILLIS.aac"),
                fixture.recorder.prepare(),
            )
        }
    }

    @Test
    fun `an explicit output path is used verbatim and its parent is created`() =
        runRecorderTest { fixture ->
            val result: RecorderResult<String> =
                fixture.recorder.prepare(outputPath = "/tmp/custom/take-1.m4a")

            assertEquals(RecorderResult.Success("/tmp/custom/take-1.m4a"), result)
            assertTrue("/tmp/custom" in fixture.fileSystem.createdDirectories)
        }
}
