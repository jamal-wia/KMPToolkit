package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Round 9, files integration of the recorder: the real `kmptoolkit-files` file system under the real
 * Android engine on the strict platform stand-in. A destination that vanished, was replaced by a
 * directory or a link, a parent that disappeared or became a file, a full disk, and a path handed from
 * one recorder to the next. The outcome is always typed, no handle outlives the recorder, and
 * a half-written result is never reported as a success.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 35], shadows = [StrictShadowMediaRecorder::class])
class RecorderFilesIntegrationR9Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val recorders: MutableList<AudioRecorder> = mutableListOf()
    private lateinit var root: File

    @Before
    fun setUp() {
        StrictRecorderWorld.reset()
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        root = File(context.cacheDir, "r9files-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        recorders.forEach { it.release() }
        root.deleteRecursively()
    }

    private fun newRecorder(directory: File): AudioRecorder = createAudioRecorder(
        context,
        AudioRecorderConfig(storage = RecordingStorage(directoryPath = directory.path)),
    ).also { recorders += it }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

    private fun prepareOk(recorder: AudioRecorder, explicit: String? = null): String =
        (blocking { recorder.prepare(explicit) } as? RecorderResult.Success)?.value
            ?: error("prepare failed, state ${recorder.state.value}")

    /** Runs a link creation; `false` where the host cannot make a symbolic link (Windows without the privilege). */
    private fun tryLink(link: File, target: File): Boolean = try {
        Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    } catch (unsupported: UnsupportedOperationException) {
        false
    } catch (denied: IOException) {
        false
    }

    private fun isLink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    private fun recordUntilStop(directory: File): Pair<AudioRecorder, String> {
        val recorder: AudioRecorder = newRecorder(directory)
        val path: String = prepareOk(recorder)
        assertTrue(recorder.start().isSuccess)
        return recorder to path
    }

    // --- the destination replaced by something else ---

    @Test
    fun `a destination replaced by a dangling link is a typed stop failure and cancel removes only the link`() {
        val (recorder: AudioRecorder, path: String) = recordUntilStop(File(root, "dangling"))
        assertTrue(File(path).delete())
        if (!tryLink(File(path), File(root, "nowhere-r9"))) return

        val failure: RecorderResult.Failure = assertIs<RecorderResult.Failure>(blocking { recorder.stop() })

        assertEquals(RecorderOperation.STOP, assertIs<RecorderError.EngineFailure>(failure.error).operation)
        assertEquals(path, (recorder.state.value as RecorderState.Failed).outputPath)
        assertEquals(0, StrictRecorderWorld.live.get())
        assertTrue(blocking { recorder.cancel() }.isSuccess)
        assertEquals(RecorderState.Idle, recorder.state.value)
        assertFalse(isLink(File(path)), "cancel left the link")
    }

    @Test
    fun `a destination replaced by a link to a file that exists completes and the target is untouched`() {
        val (recorder: AudioRecorder, path: String) = recordUntilStop(File(root, "live-link"))
        val target = File(root, "target-r9.m4a")
        target.writeText("owner data")
        assertTrue(File(path).delete())
        if (!tryLink(File(path), target)) return

        val result: RecorderResult<RecordedFile> = blocking { recorder.stop() }

        assertTrue(result.isSuccess, "a link to an existing file is an existing file: $result")
        assertEquals("owner data", target.readText())
    }

    @Test
    fun `cancel of a prepared recorder whose file became a link removes the link and never the target`() {
        val recorder: AudioRecorder = newRecorder(File(root, "ready-link"))
        val path: String = prepareOk(recorder)
        val target = File(root, "owner-r9.txt")
        target.writeText("owner data")
        assertTrue(File(path).delete())
        if (!tryLink(File(path), target)) return

        assertTrue(blocking { recorder.cancel() }.isSuccess)

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertFalse(isLink(File(path)), "the link survived the cancel")
        assertEquals("owner data", target.readText(), "cancel followed the link and deleted the target")
        assertEquals(0, StrictRecorderWorld.live.get())
    }

    @Test
    fun `cancel after the file became an empty directory removes it and after a non empty directory leaves it alone`() {
        listOf(true, false).forEach { empty: Boolean ->
            val (recorder: AudioRecorder, path: String) = recordUntilStop(File(root, "dir-$empty"))
            assertTrue(File(path).delete())
            assertTrue(File(path).mkdirs())
            if (!empty) File(path, "keep.txt").writeText("keep")

            assertIs<RecorderResult.Failure>(blocking { recorder.stop() })
            val state: RecorderState.Failed = assertIs<RecorderState.Failed>(recorder.state.value)
            assertEquals(path, state.outputPath)
            assertTrue(blocking { recorder.cancel() }.isSuccess, "cancel must not fail, empty=$empty")

            assertEquals(RecorderState.Idle, recorder.state.value)
            if (empty) {
                assertFalse(File(path).exists(), "an empty directory in the way is removed")
            } else {
                assertTrue(File(path, "keep.txt").exists(), "a non-empty directory is never removed recursively")
            }
            assertEquals(0, StrictRecorderWorld.live.get())
        }
    }

    @Test
    fun `release of a prepared recorder whose file became a non empty directory leaves it alone and never throws`() {
        val recorder: AudioRecorder = newRecorder(File(root, "release-dir"))
        val path: String = prepareOk(recorder)
        assertTrue(File(path).delete())
        assertTrue(File(path).mkdirs())
        File(path, "keep.txt").writeText("keep")

        recorder.release()

        assertEquals(RecorderState.Released, recorder.state.value)
        assertTrue(File(path, "keep.txt").exists())
        assertEquals(0, StrictRecorderWorld.live.get())
    }

    // --- the parent directory ---

    @Test
    fun `a parent directory that vanished during the recording is a typed stop failure with no handle left`() {
        val directory = File(root, "vanishing")
        val (recorder: AudioRecorder, path: String) = recordUntilStop(directory)
        assertTrue(directory.deleteRecursively())

        val failure: RecorderResult.Failure = assertIs<RecorderResult.Failure>(blocking { recorder.stop() })

        assertEquals(RecorderOperation.STOP, assertIs<RecorderError.EngineFailure>(failure.error).operation)
        assertEquals(0, StrictRecorderWorld.live.get())
        assertTrue(blocking { recorder.cancel() }.isSuccess, "cancel of a vanished file is not an error")
        assertEquals(RecorderState.Idle, recorder.state.value)
        // The same recorder records again: the directory is created anew.
        val again: String = prepareOk(recorder)
        assertTrue(File(again).isFile, "the platform opened a file in the re-created directory")
        assertEquals(directory.path, File(again).parent)
    }

    @Test
    fun `a parent that is a regular file is refused as not writable before the platform is touched and leaves no litter`() {
        root.mkdirs()
        val blocker = File(root, "blocker-r9")
        blocker.writeText("not a directory")
        val recorder: AudioRecorder = newRecorder(File(blocker, "inside"))

        val failure: RecorderResult.Failure = assertIs<RecorderResult.Failure>(blocking { recorder.prepare() })

        assertIs<RecorderError.DirectoryNotWritable>(failure.error)
        assertEquals(RecorderState.Failed(failure.error, null), recorder.state.value)
        assertEquals(0, StrictRecorderWorld.created.get(), "no platform recorder was built")
        assertEquals("not a directory", blocker.readText())
        assertEquals(listOf("blocker-r9"), root.list()!!.toList())
    }

    // --- a full disk, as the platform reports it ---

    @Test
    fun `a stop that fails with no space left keeps the file and hands the platform's own throwable to the consumer`() {
        val (recorder: AudioRecorder, path: String) = recordUntilStop(File(root, "full-stop"))
        val full = IOException("No space left on device")
        StrictRecorderWorld.failures["stop"] = full

        val failure: RecorderResult.Failure = assertIs<RecorderResult.Failure>(blocking { recorder.stop() })

        val error: RecorderError.EngineFailure = assertIs<RecorderError.EngineFailure>(failure.error)
        assertEquals(RecorderOperation.STOP, error.operation)
        assertSame(full, error.cause, "the cause is the platform's throwable, not a copy")
        assertTrue(File(path).exists(), "whatever was captured stays")
        assertEquals(RecorderState.Failed(error, path), recorder.state.value)
        assertEquals(0, StrictRecorderWorld.live.get())
        StrictRecorderWorld.failures.clear()
        assertTrue(blocking { recorder.cancel() }.isSuccess)
        assertFalse(File(path).exists())
    }

    @Test
    fun `a prepare that fails with no space left leaves nothing of its own and keeps a file that was there`() {
        val directory = File(root, "full-prepare")
        directory.mkdirs()
        val explicit = File(directory, "existing-r9.m4a")
        explicit.writeText("caller data")
        StrictRecorderWorld.failures["prepare"] = IOException("No space left on device")
        val fresh: AudioRecorder = newRecorder(directory)
        val freshFailure: RecorderResult.Failure = assertIs<RecorderResult.Failure>(blocking { fresh.prepare() })
        assertEquals(RecorderOperation.PREPARE, assertIs<RecorderError.EngineFailure>(freshFailure.error).operation)
        assertEquals(listOf("existing-r9.m4a"), directory.list()!!.toList(), "a failed prepare left a file behind")

        val second: AudioRecorder = newRecorder(directory)
        val failure: RecorderResult.Failure = assertIs<RecorderResult.Failure>(blocking { second.prepare(explicit.path) })

        assertIs<RecorderError.EngineFailure>(failure.error)
        assertEquals("caller data", explicit.readText(), "a failed attempt deleted or truncated the caller's file")
        assertEquals(0, StrictRecorderWorld.live.get())
    }

    // --- a path handed from one recorder to the next ---

    @Test
    fun `a recorder that gave its path away neither cancels nor releases the next recorder's file away`() {
        val directory = File(root, "handoff")
        val explicit: String = File(directory, "shared-r9.m4a").path
        val first: AudioRecorder = newRecorder(directory)
        prepareOk(first, explicit)
        val second: AudioRecorder = newRecorder(directory)
        prepareOk(second, explicit)
        assertTrue(second.start().isSuccess)

        assertTrue(blocking { first.cancel() }.isSuccess)
        assertTrue(File(explicit).isFile, "the first recorder's cancel deleted the second recorder's file")
        first.release()
        assertTrue(File(explicit).isFile, "the first recorder's release deleted the second recorder's file")

        val completed: RecordedFile = assertIs<RecorderResult.Success<RecordedFile>>(blocking { second.stop() }).value
        assertEquals(explicit, completed.path)
        assertTrue(File(completed.path).isFile)
        assertEquals(0, StrictRecorderWorld.live.get(), "every platform recorder was released")
    }

    // --- one new directory, many recorders at once ---

    @Test
    fun `eight recorders preparing in one new nested directory at once all succeed and all complete`() {
        val directory = File(File(root, "nested"), "recordings")
        val count = 8
        val recorderList: List<AudioRecorder> = List(count) { newRecorder(directory) }
        val ready = CountDownLatch(count)
        val go = CountDownLatch(1)
        val failures: AtomicReference<List<String>> = AtomicReference(emptyList())
        val paths: Array<String?> = arrayOfNulls(count)
        val threads: List<Thread> = recorderList.mapIndexed { index: Int, recorder: AudioRecorder ->
            Thread {
                ready.countDown()
                go.await(10, TimeUnit.SECONDS)
                runBlocking {
                    withTimeout(30_000) {
                        val prepared: RecorderResult<String> = recorder.prepare()
                        if (prepared is RecorderResult.Success) {
                            paths[index] = prepared.value
                            val started: RecorderResult<Unit> = recorder.start()
                            val stopped: RecorderResult<RecordedFile> = recorder.stop()
                            if (!started.isSuccess || !stopped.isSuccess) {
                                failures.updateAndGet { it + "recorder $index: start $started stop $stopped" }
                            }
                        } else {
                            failures.updateAndGet { it + "recorder $index: prepare $prepared" }
                        }
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        go.countDown()
        threads.forEach { it.join(60_000) }

        assertEquals(emptyList(), failures.get())
        assertEquals(count, paths.filterNotNull().toSet().size, "every recorder got its own file")
        paths.filterNotNull().forEach { path: String -> assertTrue(File(path).isFile, "missing $path") }
        assertEquals(0, StrictRecorderWorld.live.get())
    }
}
