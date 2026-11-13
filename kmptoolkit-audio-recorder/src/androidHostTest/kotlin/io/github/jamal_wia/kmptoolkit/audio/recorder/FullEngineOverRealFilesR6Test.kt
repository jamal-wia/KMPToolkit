package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Round 6 of the breaker for unit G, Q1 (recorder): the real Android engine and the real Android file system over the
 * Robolectric app directory (the platform recorder is the module's strict shadow), driven through one happy path, one
 * cancelled path, one disk-full path (mid-recording), and the destination vanishing before `prepare` and during the
 * recording. The seam that does not exist: `createAudioRecorder` has no parameter for a FileAccess, so the disk-full run
 * builds the recorder from the same internal parts the factory uses (`DefaultAudioRecorder`, `MediaRecorderEngine`,
 * `AndroidRecordingFileSystem`) with a FileAccess that only overrides `freeSpaceBytes`; every file call is real.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [StrictShadowMediaRecorder::class])
class FullEngineOverRealFilesR6Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val roots: MutableList<File> = mutableListOf()
    private val sequence: AtomicInteger = AtomicInteger()
    private val clock: AtomicLong = AtomicLong(1_700_000_000_000L)

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun newRoot(): File = File(context.cacheDir, "r6-${sequence.incrementAndGet()}/nested").also { roots += it.parentFile!! }

    private fun grant() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun factoryRecorder(root: File): AudioRecorder {
        grant()
        return createAudioRecorder(context, AudioRecorderConfig(storage = RecordingStorage(directoryPath = root.path)))
    }

    /** The real file layer, except that the free space the watchdog sees can be set. */
    private class SwitchableSpace(private val real: FileAccess) : FileAccess by real {
        val forced: AtomicLong = AtomicLong(-1L)
        override fun freeSpaceBytes(path: String): Long? = forced.get().takeIf { it >= 0 } ?: real.freeSpaceBytes(path)
    }

    private fun internalRecorder(root: File, space: SwitchableSpace): DefaultAudioRecorder {
        grant()
        return DefaultAudioRecorder(
            engine = MediaRecorderEngine(context),
            fileSystem = AndroidRecordingFileSystem(context, space),
            config = AudioRecorderConfig(storage = RecordingStorage(directoryPath = root.path)),
            workerContext = Dispatchers.Default,
            epochClock = EpochClock { clock.incrementAndGet() },
            freeSpacePollInterval = 20.milliseconds,
        )
    }

    private fun names(root: File): List<String> = root.list()?.sorted().orEmpty()

    @Test
    fun `happy path records stops and leaves exactly the recording in the directory`() {
        val root: File = newRoot()
        val recorder: AudioRecorder = factoryRecorder(root)
        try {
            runBlocking {
                withTimeout(30_000) {
                    val path: String = (recorder.prepare() as RecorderResult.Success).value
                    assertTrue(recorder.start().isSuccess)
                    val done: RecorderResult<RecordedFile> = recorder.stop()
                    val file: RecordedFile = (done as RecorderResult.Success).value
                    assertEquals(path, file.path)
                    assertTrue(recorder.state.value is RecorderState.Completed, "${recorder.state.value}")
                    assertTrue(File(path).isFile, "the recording is not a regular file")
                    assertEquals(listOf(File(path).name), names(root), "the directory holds more than the recording")
                }
            }
        } finally {
            recorder.release()
        }
    }

    @Test
    fun `a cancelled recording deletes its file and leaves an empty directory`() {
        val root: File = newRoot()
        val recorder: AudioRecorder = factoryRecorder(root)
        try {
            runBlocking {
                withTimeout(30_000) {
                    val path: String = (recorder.prepare() as RecorderResult.Success).value
                    assertTrue(recorder.start().isSuccess)
                    assertTrue(recorder.cancel().isSuccess)
                    assertEquals(RecorderState.Idle, recorder.state.value)
                    assertFalse(File(path).exists(), "the cancelled file is still there")
                    assertEquals(emptyList(), names(root))
                }
            }
        } finally {
            recorder.release()
        }
    }

    @Test
    fun `a volume that runs out of room mid recording ends in Interrupted StorageLow with the file kept and no litter`() {
        val root: File = newRoot()
        val space = SwitchableSpace(createFileAccess())
        val recorder: DefaultAudioRecorder = internalRecorder(root, space)
        try {
            runBlocking {
                withTimeout(30_000) {
                    val path: String = (recorder.prepare() as RecorderResult.Success).value
                    assertTrue(recorder.start().isSuccess)
                    space.forced.set(1L)
                    val ended: RecorderState = recorder.state.first { it is RecorderState.Interrupted || it is RecorderState.Failed }
                    assertTrue(ended is RecorderState.Interrupted && ended.reason == InterruptionReason.StorageLow, "$ended")
                    assertEquals(path, ended.recording.path)
                    assertTrue(File(path).isFile, "the finalized file is gone")
                    assertEquals(listOf(File(path).name), names(root))
                    // Room is back: the recorder records again from the interrupted state.
                    space.forced.set(-1L)
                    val again: RecorderResult<String> = recorder.prepare()
                    assertTrue(again.isSuccess, "$again")
                    assertTrue(recorder.cancel().isSuccess)
                }
            }
        } finally {
            recorder.release()
        }
    }

    @Test
    fun `a destination directory deleted before prepare is created again`() {
        val root: File = newRoot()
        val recorder: AudioRecorder = factoryRecorder(root)
        try {
            runBlocking {
                withTimeout(30_000) {
                    assertTrue(recorder.prepare().isSuccess)
                    assertTrue(recorder.cancel().isSuccess)
                    roots.forEach { it.deleteRecursively() }
                    assertFalse(root.exists())
                    val path: String = (recorder.prepare() as RecorderResult.Success).value
                    assertTrue(root.isDirectory && path.startsWith(root.path), "$path")
                    assertTrue(recorder.cancel().isSuccess)
                }
            }
        } finally {
            recorder.release()
        }
    }

    @Test
    fun `a destination directory deleted while recording is a typed failure that cancel clears and a new recording works`() {
        val root: File = newRoot()
        val recorder: AudioRecorder = factoryRecorder(root)
        try {
            runBlocking {
                withTimeout(30_000) {
                    val path: String = (recorder.prepare() as RecorderResult.Success).value
                    assertTrue(recorder.start().isSuccess)
                    root.deleteRecursively()
                    val stopped: RecorderResult<RecordedFile> = recorder.stop()
                    val failure: RecorderResult.Failure? = stopped as? RecorderResult.Failure
                    assertTrue(failure != null, "stop of a recording whose directory vanished gave $stopped")
                    assertTrue(failure.error is RecorderError.EngineFailure || failure.error is RecorderError.RecordingLost, "${failure.error}")
                    assertTrue(recorder.state.value is RecorderState.Failed, "${recorder.state.value}")
                    assertTrue(recorder.cancel().isSuccess)
                    assertEquals(RecorderState.Idle, recorder.state.value)
                    assertFalse(File(path).exists())
                    val next: String = (recorder.prepare() as RecorderResult.Success).value
                    assertTrue(recorder.start().isSuccess)
                    assertTrue(recorder.stop().isSuccess)
                    assertTrue(File(next).isFile)
                }
            }
        } finally {
            recorder.release()
        }
    }
}
