package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows

/**
 * The factory-built recorder end to end over the real Android file system and the Android engine
 * (on the Robolectric shadow of `MediaRecorder`): the pieces that every other test replaces by a
 * fake meet here, cycle after cycle, in a directory that does not exist yet.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [StrictShadowMediaRecorder::class])
class RecorderEndToEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `record cancel and re-record cycles over the real file system end in the documented states`() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val root = File(context.cacheDir, "e2e-${System.nanoTime()}/nested/deeper")
        val recorder: AudioRecorder = createAudioRecorder(
            context,
            AudioRecorderConfig(storage = RecordingStorage(directoryPath = root.path)),
        )
        try {
            runBlocking {
                withTimeout(30_000) {
                    withContext(Dispatchers.Default) {
                        repeat(10) { round: Int ->
                            val prepared = recorder.prepare()
                            val path: String = (prepared as? RecorderResult.Success)?.value
                                ?: error("round $round: prepare gave $prepared, state ${recorder.state.value}")
                            assertTrue(path.startsWith(root.path), "round $round: $path is outside $root")
                            check(recorder.start().isSuccess) { "round $round: start failed, state ${recorder.state.value}" }
                            check(recorder.pause().isSuccess) { "round $round: pause failed" }
                            check(recorder.resume().isSuccess) { "round $round: resume failed" }
                            if (round % 2 == 0) {
                                val stopped = recorder.stop()
                                assertTrue(stopped.isSuccess, "round $round: stop gave $stopped")
                                assertTrue(recorder.state.value is RecorderState.Completed, "state ${recorder.state.value}")
                            } else {
                                assertTrue(recorder.cancel().isSuccess)
                                assertEquals(RecorderState.Idle, recorder.state.value)
                                assertTrue(!File(path).exists(), "round $round: cancelled file is still there")
                            }
                        }
                    }
                }
            }
        } finally {
            recorder.release()
        }
        assertEquals(RecorderState.Released, recorder.state.value)
    }

    @Test
    fun `a recorder pointed at a directory that is really a file reports it instead of crashing`() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val blocker = File(context.cacheDir, "e2e-blocker-${System.nanoTime()}").also { it.writeText("x") }
        val recorder: AudioRecorder = createAudioRecorder(
            context,
            AudioRecorderConfig(storage = RecordingStorage(directoryPath = blocker.path)),
        )
        try {
            val result = runBlocking { withTimeout(10_000) { recorder.prepare() } }
            assertTrue(
                result is RecorderResult.Failure && result.error is RecorderError.DirectoryNotWritable,
                "result was $result",
            )
            val inside = runBlocking { withTimeout(10_000) { recorder.prepare(TestPaths.join(blocker.path, "inside.m4a")) } }
            assertTrue(
                inside is RecorderResult.Failure && inside.error is RecorderError.DirectoryNotWritable,
                "result was $inside",
            )
        } finally {
            recorder.release()
            blocker.delete()
        }
    }

    @Test
    fun `an unsatisfiable free space requirement reports the real numbers`() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val recorder: AudioRecorder = createAudioRecorder(
            context,
            AudioRecorderConfig(minimumFreeSpaceBytes = Long.MAX_VALUE),
        )
        try {
            val result = runBlocking { withTimeout(10_000) { recorder.prepare() } }
            val error = (result as? RecorderResult.Failure)?.error
            assertTrue(error is RecorderError.InsufficientStorage, "result was $result")
            assertTrue(error.availableBytes > 0L && error.requiredBytes == Long.MAX_VALUE, "error was $error")
            assertTrue(recorder.state.value is RecorderState.Failed)
        } finally {
            recorder.release()
        }
    }
}
