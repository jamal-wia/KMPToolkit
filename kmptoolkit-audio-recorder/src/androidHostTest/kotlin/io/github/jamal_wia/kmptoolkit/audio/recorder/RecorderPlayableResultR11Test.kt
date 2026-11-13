package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Round 11, Q4 (recorder half): the file a recorder returns must be a path the player module accepts as
 * `AudioSource.File` (docs of the player: a non-blank path, a file the library never creates or deletes) and
 * must be there: absolute, an existing regular file inside the configured directory with the extension of the
 * configured format, the same string the `Ready` state announced, whichever way the session ended (stop, a system
 * end, a pause in between). The hand-off after the consumer took the file: a finished file is never deleted by the
 * recorder it came from (release, a second release, a new prepare), and a result the consumer deleted is reported
 * as plain absence, never as an exception, by the calls that could still name it. The player half, that a missing
 * file ends in a typed `Error`, is `PlayerRecordedFileHandoffR11Test` in kmptoolkit-audio-player (no module
 * depends on both).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [StrictShadowMediaRecorder::class])
class RecorderPlayableResultR11Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File

    @Before
    fun setUp() {
        StrictRecorderWorld.reset()
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "r11play-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `every finished or interrupted file is an absolute existing regular file the player can be given`() {
        val directory = File(root, "rec")
        val recorder: AudioRecorder = createAudioRecorder(
            context,
            AudioRecorderConfig(storage = RecordingStorage(directoryPath = directory.path), minimumFreeSpaceBytes = 0L),
        )
        val random = Random(SEED)
        val kept: MutableList<File> = mutableListOf()
        runBlocking {
            withTimeout(60_000) {
                repeat(200) { session: Int ->
                    val announced: String = recorder.prepare().getOrNull() ?: error("session $session: prepare failed")
                    assertTrue(recorder.start().isSuccess)
                    if (random.nextBoolean()) {
                        assertTrue(recorder.pause().isSuccess)
                        assertTrue(recorder.resume().isSuccess)
                    }
                    val path: String = if (random.nextInt(4) == 0) {
                        StrictRecorderWorld.instances.last().fireError(MediaRecorder.MEDIA_ERROR_SERVER_DIED, 0)
                        // The finalization runs on the worker: wait for the outcome, bounded in real time.
                        // timing-hygiene-ignore: TH2 bound hang deadline of a wait for a worker thread, never asserted as a time
                        val deadline: kotlin.time.TimeMark = kotlin.time.TimeSource.Monotonic.markNow() + 15.seconds
                        while (recorder.state.value is RecorderState.Recording && !deadline.hasPassedNow()) Thread.yield()
                        val state: RecorderState = recorder.state.value
                        assertIs<RecorderState.Interrupted>(state, "session $session")
                        state.recording.path
                    } else {
                        val file: RecordedFile = recorder.stop().getOrNull() ?: error("session $session: stop failed")
                        file.path
                    }
                    val file = File(path)
                    assertEquals(announced, path, "session $session: the path announced by Ready is not the path of the result")
                    assertTrue(path.isNotBlank(), "session $session: a blank path cannot be an AudioSource.File")
                    assertTrue(file.isAbsolute, "session $session: $path is not absolute")
                    assertTrue(file.isFile, "session $session: $path is not an existing regular file")
                    assertTrue(file.canRead(), "session $session: $path cannot be read")
                    assertEquals(directory.canonicalPath, file.parentFile!!.canonicalPath, "session $session: outside the configured directory")
                    assertTrue(file.name.endsWith(".m4a"), "session $session: ${file.name} lacks the extension of the configured format")
                    kept += file
                }
            }
        }
        // Handing over: the recorder never deletes what it gave away, however it is released.
        recorder.release()
        recorder.release()
        assertTrue(kept.all { it.isFile }, "released recorder deleted a finished file: ${kept.filterNot { it.isFile }}")
        assertEquals(kept.map { it.path }.toSet().size, kept.size, "two sessions got the same path")
    }

    @Test
    fun `a result the consumer deleted is plain absence and every later call stays typed`() {
        val directory = File(root, "gone")
        val recorder: AudioRecorder = createAudioRecorder(
            context,
            AudioRecorderConfig(storage = RecordingStorage(directoryPath = directory.path), minimumFreeSpaceBytes = 0L),
        )
        runBlocking {
            withTimeout(60_000) {
                recorder.prepare()
                recorder.start()
                val finished: RecordedFile = recorder.stop().getOrNull() ?: error("stop failed")
                assertTrue(File(finished.path).delete())
                // Every call that could still name the file answers typed; none throws.
                assertEquals(
                    RecorderError.IllegalState(RecorderState.Completed(finished), RecorderOperation.STOP),
                    recorder.stop().errorOrNull(),
                )
                assertEquals(
                    RecorderError.IllegalState(RecorderState.Completed(finished), RecorderOperation.CANCEL),
                    recorder.cancel().errorOrNull(),
                )
                // The next recording does not care that the old path is gone, and is a different file.
                val next: String = recorder.prepare().getOrNull() ?: error("prepare after the deletion failed")
                assertTrue(next != finished.path)
                assertTrue(recorder.start().isSuccess)
                val second: RecordedFile = recorder.stop().getOrNull() ?: error("second stop failed")
                assertTrue(File(second.path).isFile)
                recorder.release()
                assertTrue(File(second.path).isFile, "release deleted the finished file")
            }
        }
    }

    private companion object {
        const val SEED: Long = 3333L
    }
}
