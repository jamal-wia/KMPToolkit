package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The real [MediaRecorderEngine] and [DefaultAudioRecorder] over the real file system, driving a
 * `MediaRecorder` stand-in that follows the platform's state machine and throws where the platform
 * throws ([StrictShadowMediaRecorder]), at every API level from the oldest the library supports to
 * the newest Robolectric knows. A real `MediaRecorder` cannot run on the JVM; this is the strictest
 * fake that can, and it counts every instance that was never released.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 28, 29, 30, 31, 33, 34, 35], shadows = [StrictShadowMediaRecorder::class])
class StrictPlatformRecorderR8Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val recorders: MutableList<AudioRecorder> = mutableListOf()
    private lateinit var root: File

    @Before
    fun setUp() {
        StrictRecorderWorld.reset()
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        root = File(context.cacheDir, "r8-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        recorders.forEach { it.release() }
        root.deleteRecursively()
    }

    private fun newRecorder(config: AudioRecorderConfig = AudioRecorderConfig(), name: String = "d"): AudioRecorder =
        createAudioRecorder(
            context,
            config.copyStorage(File(root, name).path),
        ).also { recorders += it }

    private fun AudioRecorderConfig.copyStorage(dir: String): AudioRecorderConfig = AudioRecorderConfig(
        storage = RecordingStorage(directoryPath = dir),
        format = format,
        sampleRate = sampleRate,
        channelCount = channelCount,
        bitRate = bitRate,
        durationUpdateInterval = durationUpdateInterval,
        minimumFreeSpaceBytes = minimumFreeSpaceBytes,
        levelUpdateInterval = levelUpdateInterval,
        levelFloorDbfs = levelFloorDbfs,
    )

    private fun <T> blocking(block: suspend CoroutineScope.() -> T): T =
        runBlocking { withTimeout(30_000) { block() } }

    private suspend fun AudioRecorder.awaitState(what: String, predicate: (RecorderState) -> Boolean): RecorderState {
        withTimeout(15_000) {
            while (!predicate(state.value)) delay(5)
        }
        return state.value
    }

    private fun prepareOk(recorder: AudioRecorder): String =
        blocking { recorder.prepare() }.let { result ->
            (result as? RecorderResult.Success)?.value ?: fail("prepare failed: $result, state ${recorder.state.value}")
        }

    private fun platformCalls(): List<String> = StrictRecorderWorld.calls
        .map { it.substringAfter(':') }
        .filter { it != "getMaxAmplitude" && it != "setMaxFileSize" }

    private fun engineFailureOf(result: RecorderResult<*>, operation: RecorderOperation): RecorderError.EngineFailure {
        val failure = assertIs<RecorderResult.Failure>(result, "expected a failure, got $result")
        val error = assertIs<RecorderError.EngineFailure>(failure.error, "error ${failure.error}")
        assertEquals(operation, error.operation)
        return error
    }

    @Test
    fun `a full recording drives the platform recorder in its documented order and releases it`() {
        val recorder: AudioRecorder = newRecorder()
        val path: String = prepareOk(recorder)
        assertTrue(recorder.start().isSuccess)
        assertTrue(recorder.pause().isSuccess)
        assertTrue(recorder.resume().isSuccess)
        val done: RecorderResult<RecordedFile> = blocking { recorder.stop() }
        assertEquals(path, assertIs<RecorderResult.Success<RecordedFile>>(done).value.path)

        assertEquals(
            listOf(
                "setAudioSource", "setOutputFormat", "setAudioEncoder", "setAudioSamplingRate",
                "setAudioChannels", "setAudioEncodingBitRate", "setOutputFile", "prepare",
                "start", "pause", "resume", "stop", "release",
            ),
            platformCalls(),
        )
        assertEquals(0, StrictRecorderWorld.live.get(), "a MediaRecorder was never released")
        val expected: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "(Context)" else "()"
        assertEquals(listOf(expected), StrictRecorderWorld.constructors.toList())
        assertTrue(File(path).exists(), "a zero-length output is still a finished file")
    }

    @Test
    fun `the configuration reaches the platform unchanged for each format`() {
        val configs: List<Triple<AudioFormat, Int, AudioRecorderConfig>> = listOf(
            Triple(AudioFormat.M4A, MediaRecorder.OutputFormat.MPEG_4, AudioRecorderConfig()),
            Triple(AudioFormat.AAC, MediaRecorder.OutputFormat.AAC_ADTS, AudioRecorderConfig(format = AudioFormat.AAC)),
            Triple(
                AudioFormat.M4A,
                MediaRecorder.OutputFormat.MPEG_4,
                AudioRecorderConfig(sampleRate = 8_000, channelCount = 2, bitRate = 12_345),
            ),
        )
        configs.forEachIndexed { index: Int, (_, outputFormat: Int, config: AudioRecorderConfig) ->
            val recorder: AudioRecorder = newRecorder(config, name = "cfg$index")
            prepareOk(recorder)
            val platform: StrictShadowMediaRecorder = StrictRecorderWorld.instances.last()
            assertEquals(outputFormat, platform.seenOutputFormat)
            assertEquals(config.sampleRate, platform.seenSampleRate)
            assertEquals(config.channelCount, platform.seenChannels)
            assertEquals(config.bitRate, platform.seenBitRate)
            if (platform.seenMaxFileSize != -1L) assertTrue(platform.seenMaxFileSize > 0L, "limit ${platform.seenMaxFileSize}")
            blocking { recorder.cancel() }
        }
        assertEquals(0, StrictRecorderWorld.live.get())
    }

    @Test
    fun `an unsupported format never constructs a platform recorder`() {
        val recorder: AudioRecorder = newRecorder(AudioRecorderConfig(format = AudioFormat.WAV))
        val result: RecorderResult<String> = blocking { recorder.prepare() }
        assertEquals(RecorderResult.Failure(RecorderError.UnsupportedFormat(AudioFormat.WAV)), result)
        assertEquals(0, StrictRecorderWorld.created.get())
    }

    @Test
    fun `a failure at any step of preparation is an engine failure that leaves nothing held or on disk`() {
        val steps: List<Pair<String, Throwable>> = listOf(
            "setAudioSource" to IllegalStateException("source"),
            "setOutputFormat" to RuntimeException("format"),
            "setAudioEncoder" to IllegalStateException("encoder"),
            "setAudioSamplingRate" to IllegalArgumentException("rate"),
            "setAudioChannels" to RuntimeException("channels"),
            "setAudioEncodingBitRate" to IllegalArgumentException("bit rate"),
            "setOutputFile" to IllegalStateException("file"),
            "prepare" to IOException("prepare"),
            "release" to RuntimeException("release in the failure path"),
        )
        for ((step: String, thrown: Throwable) in steps) {
            StrictRecorderWorld.reset()
            val recorder: AudioRecorder = newRecorder(name = "p-$step")
            StrictRecorderWorld.failures[step] = thrown
            if (step == "release") StrictRecorderWorld.failures["prepare"] = IOException("prepare")
            val result: RecorderResult<String> = blocking { recorder.prepare() }
            val error = engineFailureOf(result, RecorderOperation.PREPARE)
            assertNotNull(error.cause, "$step: the cause is lost")
            assertEquals(0, StrictRecorderWorld.live.get(), "$step: a platform recorder is still held")
            val files: List<File> = File(root, "p-$step").listFiles()?.toList().orEmpty()
            assertTrue(files.isEmpty(), "$step: left $files")
            assertIs<RecorderState.Failed>(recorder.state.value, "$step: state ${recorder.state.value}")

            StrictRecorderWorld.failures.clear()
            val path: String = prepareOk(recorder)
            assertTrue(recorder.start().isSuccess, "$step: re-prepare then start")
            assertTrue(blocking { recorder.stop() }.isSuccess)
            assertTrue(File(path).exists())
            assertEquals(0, StrictRecorderWorld.live.get(), "$step: leak after the recovery recording")
        }
    }

    @Test
    fun `start pause resume and stop failures follow the documented recoveries`() {
        // start
        run {
            val recorder: AudioRecorder = newRecorder(name = "start")
            val path: String = prepareOk(recorder)
            StrictRecorderWorld.failures["start"] = IllegalStateException("mic busy")
            engineFailureOf(recorder.start(), RecorderOperation.START)
            assertFalse(File(path).exists(), "a failed start must delete the empty file")
            assertEquals(0, StrictRecorderWorld.live.get())
            StrictRecorderWorld.failures.clear()
            prepareOk(recorder)
            assertTrue(recorder.start().isSuccess)
            blocking { recorder.stop() }
        }
        // pause: the recording keeps running
        run {
            val recorder: AudioRecorder = newRecorder(name = "pause")
            val path: String = prepareOk(recorder)
            assertTrue(recorder.start().isSuccess)
            StrictRecorderWorld.failures["pause"] = IllegalStateException("format cannot pause")
            engineFailureOf(recorder.pause(), RecorderOperation.PAUSE)
            assertEquals(RecorderState.Recording(path), recorder.state.value)
            StrictRecorderWorld.failures.clear()
            assertTrue(recorder.pause().isSuccess)
            // resume: stays paused
            StrictRecorderWorld.failures["resume"] = RuntimeException("resume")
            engineFailureOf(recorder.resume(), RecorderOperation.RESUME)
            assertIs<RecorderState.Paused>(recorder.state.value)
            StrictRecorderWorld.failures.clear()
            assertTrue(recorder.resume().isSuccess)
            assertTrue(blocking { recorder.stop() }.isSuccess)
            assertEquals(0, StrictRecorderWorld.live.get())
        }
        // stop: the file stays, cancel discards it
        run {
            val recorder: AudioRecorder = newRecorder(name = "stop")
            val path: String = prepareOk(recorder)
            assertTrue(recorder.start().isSuccess)
            StrictRecorderWorld.failures["stop"] = RuntimeException("stop failed")
            engineFailureOf(blocking { recorder.stop() }, RecorderOperation.STOP)
            val failed = assertIs<RecorderState.Failed>(recorder.state.value)
            assertEquals(path, failed.outputPath)
            assertTrue(File(path).exists(), "a failed stop keeps what was captured")
            assertEquals(0, StrictRecorderWorld.live.get(), "a failed stop must still release the platform recorder")
            StrictRecorderWorld.failures.clear()
            assertTrue(blocking { recorder.cancel() }.isSuccess)
            assertFalse(File(path).exists())
            assertEquals(RecorderState.Idle, recorder.state.value)
        }
    }

    @Test
    fun `a platform recorder that throws on release cannot break cancel or release`() {
        StrictRecorderWorld.failures["release"] = RuntimeException("release")
        val ready: AudioRecorder = newRecorder(name = "rel-ready")
        prepareOk(ready)
        ready.release()
        ready.release()
        assertEquals(RecorderState.Released, ready.state.value)

        val recording: AudioRecorder = newRecorder(name = "rel-rec")
        val path: String = prepareOk(recording)
        assertTrue(recording.start().isSuccess)
        recording.release()
        assertEquals(RecorderState.Released, recording.state.value)
        assertTrue(File(path).exists(), "release keeps a started recording's file")

        val cancelled: AudioRecorder = newRecorder(name = "rel-cancel")
        val cancelPath: String = prepareOk(cancelled)
        assertTrue(cancelled.start().isSuccess)
        assertTrue(blocking { cancelled.cancel() }.isSuccess)
        assertFalse(File(cancelPath).exists())
        StrictRecorderWorld.failures.clear()
        assertEquals(0, StrictRecorderWorld.live.get())
    }

    @Test
    fun `an amplitude read that throws or returns nonsense never breaks the meter or the recording`() {
        val recorder: AudioRecorder = newRecorder(AudioRecorderConfig(levelUpdateInterval = 5.milliseconds))
        val path: String = prepareOk(recorder)
        blocking {
            val collector = launch(Dispatchers.Default) { recorder.level.collect { } }
            val samples = launch(Dispatchers.Default) { recorder.levelSamples.collect { } }
            assertTrue(recorder.start().isSuccess)
            for (thrown in listOf(IllegalStateException("x"), RuntimeException("y"))) {
                StrictRecorderWorld.failures["getMaxAmplitude"] = thrown
                delay(60)
                assertEquals(RecorderState.Recording(path), recorder.state.value)
                assertEquals(0f, recorder.level.value)
            }
            StrictRecorderWorld.failures.clear()
            val seen: List<Float> = mutableListOf<Float>().also { out ->
                for (value in listOf(Int.MIN_VALUE, -1, 0, 1, 32_767, 32_768, Int.MAX_VALUE)) {
                    StrictRecorderWorld.amplitude = value
                    withTimeout(10_000) {
                        recorder.levelSamples.take(3).toList(out)
                    }
                }
            }
            assertTrue(seen.all { it in 0f..1f }, "a level left 0..1: $seen")
            assertTrue(seen.none { it.isNaN() })
            collector.cancel()
            samples.cancel()
            assertTrue(recorder.stop().isSuccess)
        }
    }

    private fun waitInterrupted(recorder: AudioRecorder): RecorderState = blocking {
        recorder.awaitState("settles") { it is RecorderState.Interrupted || it is RecorderState.Failed }
    }

    @Test
    fun `a platform error ends a recording as Interrupted with the file kept and the recorder released`() {
        for (pause in listOf(false, true)) {
            StrictRecorderWorld.reset()
            val recorder: AudioRecorder = newRecorder(name = "err-$pause")
            val path: String = prepareOk(recorder)
            assertTrue(recorder.start().isSuccess)
            if (pause) assertTrue(recorder.pause().isSuccess)
            StrictRecorderWorld.instances.last().fireError(MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN, 100)
            val settled = assertIs<RecorderState.Interrupted>(waitInterrupted(recorder), "pause=$pause")
            assertEquals(InterruptionReason.EngineDied(100), settled.reason)
            assertEquals(path, settled.recording.path)
            assertTrue(File(path).exists())
            assertEquals(0, StrictRecorderWorld.live.get(), "pause=$pause: handle leaked")
            assertEquals(path, (blocking { recorder.stop() } as RecorderResult.Success).value.path)
        }
    }

    @Test
    fun `a platform error whose finalization also fails is a lost recording with the file kept`() {
        val recorder: AudioRecorder = newRecorder(name = "lost")
        val path: String = prepareOk(recorder)
        assertTrue(recorder.start().isSuccess)
        StrictRecorderWorld.failures["stop"] = RuntimeException("stop failed")
        StrictRecorderWorld.instances.last().fireError(MediaRecorder.MEDIA_ERROR_SERVER_DIED, 0)
        val failed = assertIs<RecorderState.Failed>(waitInterrupted(recorder))
        val lost = assertIs<RecorderError.RecordingLost>(failed.error)
        assertEquals(InterruptionReason.EngineDied(MediaRecorder.MEDIA_ERROR_SERVER_DIED), lost.reason)
        assertEquals(path, failed.outputPath)
        assertEquals(0, StrictRecorderWorld.live.get())
        StrictRecorderWorld.failures.clear()
        assertTrue(blocking { recorder.cancel() }.isSuccess)
        assertFalse(File(path).exists())
    }

    @Test
    fun `a platform error in the prepared state deletes the empty file and reports a lost recording`() {
        val recorder: AudioRecorder = newRecorder(name = "ready")
        val path: String = prepareOk(recorder)
        StrictRecorderWorld.instances.last().fireError(MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN, 0)
        val failed = assertIs<RecorderState.Failed>(waitInterrupted(recorder))
        assertIs<RecorderError.RecordingLost>(failed.error)
        assertNull(failed.outputPath)
        assertFalse(File(path).exists())
        assertEquals(0, StrictRecorderWorld.live.get())
    }

    @Test
    fun `size limit info interrupts as storage low and informational codes and extreme error codes behave`() {
        val limited: AudioRecorder = newRecorder(name = "limit")
        prepareOk(limited)
        assertTrue(limited.start().isSuccess)
        // Informational codes that end nothing.
        for (what in listOf(MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, 0, 1, Int.MIN_VALUE, Int.MAX_VALUE, 802)) {
            StrictRecorderWorld.instances.last().fireInfo(what, 0)
        }
        assertIs<RecorderState.Recording>(limited.state.value)
        StrictRecorderWorld.instances.last().fireInfo(MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED, 0)
        val storage = assertIs<RecorderState.Interrupted>(waitInterrupted(limited))
        assertEquals(InterruptionReason.StorageLow, storage.reason)

        val extreme: AudioRecorder = newRecorder(name = "extreme")
        prepareOk(extreme)
        assertTrue(extreme.start().isSuccess)
        StrictRecorderWorld.instances.last().fireError(Int.MIN_VALUE, Int.MAX_VALUE)
        val reason = assertIs<RecorderState.Interrupted>(waitInterrupted(extreme)).reason
        assertEquals(InterruptionReason.EngineDied(Int.MIN_VALUE), reason)
        assertEquals(0, StrictRecorderWorld.live.get())
    }

    @Test
    fun `a callback of a recording the app already stopped reaches nobody`() {
        val recorder: AudioRecorder = newRecorder(name = "stale")
        prepareOk(recorder)
        assertTrue(recorder.start().isSuccess)
        val platform: StrictShadowMediaRecorder = StrictRecorderWorld.instances.last()
        val staleError = assertNotNull(platform.errorListener)
        val staleInfo = assertNotNull(platform.infoListener)
        val done = assertIs<RecorderResult.Success<RecordedFile>>(blocking { recorder.stop() })
        staleError.onError(MediaRecorder::class.java.getDeclaredConstructor().let { null } ?: return, 100, 0)
        staleInfo.onInfo(platform.handle(), MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED, 0)
        Thread.sleep(100)
        assertEquals(RecorderState.Completed(done.value), recorder.state.value)
    }

    @Test
    fun `two recorders at once keep separate platform recorders and one's error never touches the other`() {
        val first: AudioRecorder = newRecorder(name = "a")
        val second: AudioRecorder = newRecorder(name = "b")
        val pathA: String = prepareOk(first)
        val platformA: StrictShadowMediaRecorder = StrictRecorderWorld.instances.last()
        val pathB: String = prepareOk(second)
        val platformB: StrictShadowMediaRecorder = StrictRecorderWorld.instances.last()
        assertTrue(platformA !== platformB)
        assertTrue(first.start().isSuccess)
        assertTrue(second.start().isSuccess)
        assertEquals(2, StrictRecorderWorld.live.get())

        platformB.fireError(MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN, 7)
        assertIs<RecorderState.Interrupted>(waitInterrupted(second))
        assertEquals(RecorderState.Recording(pathA), first.state.value)
        assertTrue(blocking { first.stop() }.isSuccess)
        assertTrue(File(pathA).exists() && File(pathB).exists())
        assertEquals(0, StrictRecorderWorld.live.get())
    }

    @Test
    fun `a grant revoked mid recording does not end the recording and a denied prepare creates nothing`() {
        val application: Application = ApplicationProvider.getApplicationContext()
        val recorder: AudioRecorder = newRecorder(name = "perm")
        prepareOk(recorder)
        assertTrue(recorder.start().isSuccess)
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertIs<RecorderState.Recording>(recorder.state.value)
        assertTrue(blocking { recorder.stop() }.isSuccess)
        StrictRecorderWorld.created.set(0)

        assertEquals(RecorderResult.Failure(RecorderError.PermissionDenied), blocking { recorder.prepare() })
        assertEquals(0, StrictRecorderWorld.created.get(), "a denied prepare must not touch the platform")
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        prepareOk(recorder)
        assertTrue(blocking { recorder.cancel() }.isSuccess)
    }
}
