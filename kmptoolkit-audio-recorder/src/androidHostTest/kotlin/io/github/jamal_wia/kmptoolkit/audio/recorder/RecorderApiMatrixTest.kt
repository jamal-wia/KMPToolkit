package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.testsupport.PathSyntax
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The factory-built recorder over the real file system and the Android engine at several API levels,
 * including the oldest the library supports (no silence monitor below 29, a deprecated `MediaRecorder`
 * constructor below 31). Every other host test pins one level.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 28, 29, 31, 34], shadows = [StrictShadowMediaRecorder::class])
class RecorderApiMatrixTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun grant() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun `record pause resume stop and cancel with collectors hold at every api level`() {
        grant()
        val root = File(context.cacheDir, "matrix-${System.nanoTime()}/a/b")
        val recorder: AudioRecorder = createAudioRecorder(
            context,
            AudioRecorderConfig(storage = RecordingStorage(directoryPath = root.path)),
        )
        try {
            runBlocking {
                withTimeout(30_000) {
                    withContext(Dispatchers.Default) {
                        val collectors = listOf(
                            launch { recorder.level.collect { } },
                            launch { recorder.levelSamples.collect { } },
                            launch { recorder.elapsed.collect { } },
                        )
                        repeat(4) { round: Int ->
                            val path: String = (recorder.prepare() as? RecorderResult.Success)?.value
                                ?: error("round $round: prepare failed, state ${recorder.state.value}")
                            check(recorder.start().isSuccess) { "round $round: start" }
                            check(recorder.pause().isSuccess) { "round $round: pause" }
                            check(recorder.resume().isSuccess) { "round $round: resume" }
                            if (round % 2 == 0) {
                                assertTrue(recorder.stop().isSuccess, "round $round: stop")
                                assertTrue(recorder.state.value is RecorderState.Completed)
                            } else {
                                assertTrue(recorder.cancel().isSuccess)
                                assertTrue(!File(path).exists(), "round $round: cancelled file remains")
                            }
                        }
                        collectors.forEach { it.cancel() }
                    }
                }
            }
        } finally {
            recorder.release()
        }
        assertEquals(RecorderState.Released, recorder.state.value)
    }

    @Test
    fun `directory names that only look like escapes stay inside the base directory`() {
        grant()
        val base: String = context.filesDir.canonicalPath
        for (name in listOf("a\\..\\b", "a/%2e%2e", "...", ".hidden", "a b/ c", "a/", "..a", "a..", "~", "a/b/")) {
            // A backslash is a file-name character on a device but a separator on a Windows host, where
            // "a\..\b" is the real path "b": this case only means something on a POSIX file system.
            if (name.contains('\\') && TestHost.pathSyntax == PathSyntax.WINDOWS) continue
            val recorder: AudioRecorder = createAudioRecorder(
                context,
                AudioRecorderConfig(storage = RecordingStorage(directoryName = name)),
            )
            try {
                val result = runBlocking { withTimeout(10_000) { recorder.prepare() } }
                val path: String = (result as? RecorderResult.Success)?.value ?: error("'$name': prepare gave $result")
                assertTrue(TestPaths.isInside(base, File(path).canonicalPath), "'$name' escaped: $path")
                assertTrue(File(path).parentFile!!.isDirectory, "'$name': directory was not created")
                runBlocking { recorder.cancel() }
            } finally {
                recorder.release()
            }
        }
    }

    @Test
    fun `hostile but legal directory names report a result and never throw`() {
        grant()
        val base: String = context.filesDir.canonicalPath
        for (name in listOf("x".repeat(300), "a\nb", "a\u0000b", "a/" + "y".repeat(300), "\u202e..", " ", "a/ /b")) {
            if (name.isBlank()) continue
            val recorder: AudioRecorder = createAudioRecorder(
                context,
                AudioRecorderConfig(storage = RecordingStorage(directoryName = name)),
            )
            try {
                val result = runBlocking { withTimeout(10_000) { recorder.prepare() } }
                when (result) {
                    is RecorderResult.Success ->
                        assertTrue(TestPaths.isInside(base, File(result.value).canonicalPath), "'$name' escaped: ${result.value}")
                    is RecorderResult.Failure ->
                        assertTrue(result.error is RecorderError.DirectoryNotWritable, "'$name' failed with ${result.error}")
                }
                runBlocking { recorder.cancel() }
            } finally {
                recorder.release()
            }
        }
    }
}
