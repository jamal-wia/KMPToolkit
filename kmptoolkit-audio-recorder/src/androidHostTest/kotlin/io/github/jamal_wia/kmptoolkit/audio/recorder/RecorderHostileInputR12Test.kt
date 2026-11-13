package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Round 12, Q1 (recorder half): hostile arguments at the limits through the real recorder, the real Android file
 * system and the strict `MediaRecorder` stand-in. The contract (04-api-reference, `prepare`): every failure is a
 * value, `state` never stays at `Preparing`, a file that was already at an explicit path is never deleted by a
 * failed or cancelled attempt, and `Completed` promises an existing, closed, regular file. So for each hostile
 * explicit path: the call returns within a bound with a typed answer (never throws, never hangs), the recorder
 * stays usable for the next recording, nothing the test put on disk is deleted, and nothing is left behind that the
 * documentation says is deleted.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [StrictShadowMediaRecorder::class])
class RecorderHostileInputR12Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File

    @Before
    fun setUp() {
        StrictRecorderWorld.reset()
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        root = Files.createTempDirectory(context.cacheDir.toPath(), "r12hostile-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun recorder(directory: File = File(root, "rec")): AudioRecorder = createAudioRecorder(
        context,
        AudioRecorderConfig(storage = RecordingStorage(directoryPath = directory.path), minimumFreeSpaceBytes = 0L),
    )

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    /** The recorder answered typed and is usable: a plain recording afterwards works and is a regular file. */
    private fun assertStillUsable(recorder: AudioRecorder, label: String) {
        bounded {
            val state: RecorderState = recorder.state.value
            assertTrue(state !is RecorderState.Preparing, "$label: left at Preparing")
            val next: String = recorder.prepare().getOrNull() ?: error("$label: the next prepare failed: ${recorder.state.value}")
            assertTrue(recorder.start().isSuccess, label)
            val done: RecordedFile = recorder.stop().getOrNull() ?: error("$label: the next stop failed")
            assertEquals(next, done.path, label)
            assertTrue(File(done.path).isFile, label)
        }
    }

    @Test
    fun `a NUL character or ten thousand characters in the file name give a typed answer and leave nothing behind`() {
        val directory = File(root, "rec")
        val hostile: Map<String, String> = mapOf(
            "NUL in the name" to File(directory, "a\u0000b.m4a").path,
            "NUL as the name" to File(directory, "\u0000").path,
            "name of ten thousand characters" to File(directory, "n".repeat(10_000) + ".m4a").path,
            "name of 255 characters" to File(directory, "m".repeat(251) + ".m4a").path,
            "newline and tab in the name" to File(directory, "a\nb\tc.m4a").path,
            "combining marks in the name" to File(directory, "ẹ́̂.m4a").path,
        )
        val recorder: AudioRecorder = recorder(directory)
        try {
            for ((label: String, path: String) in hostile) {
                val result: RecorderResult<String> = bounded { recorder.prepare(path) }
                if (result.isSuccess) {
                    // Whatever the file system accepted must also finish as the contract says.
                    bounded {
                        assertTrue(recorder.start().isSuccess, label)
                        val finished: RecordedFile = recorder.stop().getOrNull() ?: error("$label: stop failed")
                        assertTrue(File(finished.path).isFile, "$label: Completed names no regular file")
                        assertTrue(File(finished.path).delete(), label)
                    }
                } else {
                    val error: RecorderError = requireNotNull(result.errorOrNull())
                    assertTrue(
                        error is RecorderError.DirectoryNotWritable || error is RecorderError.EngineFailure,
                        "$label: unexpected answer $error",
                    )
                    assertIs<RecorderState.Failed>(recorder.state.value, label)
                }
                assertEquals(emptyList(), directory.listFiles()?.map { it.name } ?: emptyList(), "$label: litter left in the directory")
                assertStillUsable(recorder, label)
                directory.listFiles()?.forEach { it.delete() }
            }
        } finally {
            recorder.release()
        }
    }

    @Test
    fun `a directory of ten thousand characters or a NUL in the configured directory is a typed refusal`() {
        val hostile: Map<String, String> = mapOf(
            "directory of ten thousand characters" to File(root, "d".repeat(10_000)).path,
            "NUL in the directory" to File(root, "x\u0000y").path,
        )
        for ((label: String, directory: String) in hostile) {
            val recorder: AudioRecorder = createAudioRecorder(
                context,
                AudioRecorderConfig(storage = RecordingStorage(directoryPath = directory), minimumFreeSpaceBytes = 0L),
            )
            try {
                val result: RecorderResult<String> = bounded { recorder.prepare() }
                val error: RecorderError? = result.errorOrNull()
                assertTrue(
                    error is RecorderError.DirectoryNotWritable || error is RecorderError.EngineFailure,
                    "$label: answer was $result",
                )
                assertIs<RecorderState.Failed>(recorder.state.value, label)
                assertTrue(bounded { recorder.cancel() }.errorOrNull() is RecorderError.IllegalState, "$label: cancel of a path-less failure")
            } finally {
                recorder.release()
            }
        }
    }

    @Test
    fun `an existing directory as the output path is refused and neither it nor its content is ever deleted`() {
        val victim = File(root, "victim").also { it.mkdirs() }
        val child = File(victim, "keep.txt").also { it.writeText("keep") }
        val recorder: AudioRecorder = recorder()
        try {
            val result: RecorderResult<String> = bounded { recorder.prepare(victim.path) }
            assertFalse(result.isSuccess, "a directory cannot be recorded into: $result")
            assertTrue(victim.isDirectory && child.isFile, "the failed prepare deleted the consumer's directory")
            // Every follow-up that could name the path.
            bounded {
                recorder.cancel()
                recorder.stop()
                recorder.prepare(victim.path)
            }
            recorder.release()
            recorder.release()
            assertTrue(victim.isDirectory && child.isFile, "a later call deleted the consumer's directory or its content")
            assertEquals("keep", child.readText())
        } finally {
            recorder.release()
        }
    }

    @Test
    fun `a link to a device path never completes as a regular file and cancel removes only the link`() {
        val device = File("/dev/null")
        if (!device.exists()) return // no such device here (not a POSIX host): NOT VERIFIED (Windows)
        val directory = File(root, "rec").also { it.mkdirs() }
        val link = File(directory, "device.m4a")
        try {
            Files.createSymbolicLink(link.toPath(), device.toPath())
        } catch (unsupported: UnsupportedOperationException) {
            return // links are not available on this file system: NOT VERIFIED
        } catch (denied: java.io.IOException) {
            return
        }
        val recorder: AudioRecorder = recorder(directory)
        try {
            val prepared: RecorderResult<String> = bounded { recorder.prepare(link.path) }
            if (prepared.isSuccess) {
                bounded {
                    assertTrue(recorder.start().isSuccess)
                    val stopped: RecorderResult<RecordedFile> = recorder.stop()
                    assertFalse(stopped.isSuccess, "a device is not a regular file: Completed would be a false promise")
                    assertIs<RecorderError.EngineFailure>(stopped.errorOrNull())
                    assertTrue(recorder.cancel().isSuccess, "the failed stop keeps the path, so cancel can clear it")
                }
                assertFalse(Files.isSymbolicLink(link.toPath()), "cancel left the link behind")
            } else {
                assertIs<RecorderState.Failed>(recorder.state.value)
            }
            assertTrue(device.exists(), "the device itself must never be touched")
        } finally {
            recorder.release()
        }
    }
}
