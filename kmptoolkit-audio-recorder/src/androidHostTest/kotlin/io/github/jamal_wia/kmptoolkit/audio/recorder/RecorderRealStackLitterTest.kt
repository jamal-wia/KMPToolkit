package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import org.robolectric.Shadows

/** The Android engine, plus the file the platform creates (or truncates) while it prepares, which the shadow does not. */
private class FileCreatingEngine(private val delegate: MediaRecorderEngine) : RecorderEngine by delegate {
    @Volatile
    var failAfterCreating: Boolean = false

    override suspend fun prepare(outputPath: String, config: AudioRecorderConfig, maxFileSizeBytes: Long?) {
        delegate.prepare(outputPath, config, maxFileSizeBytes)
        File(outputPath).writeText("audio")
        if (failAfterCreating) {
            failAfterCreating = false
            delegate.release()
            throw IllegalStateException("the platform failed after creating the file")
        }
    }
}

/**
 * The recorder over the real file system and the Android engine, driven by seeded random
 * operations (prepare with a generated or a fresh explicit path, prepare cancelled at a random instant,
 * start, pause, resume, stop, cancel) from one thread, as documented. After every operation the files
 * in the recording directory must be exactly the ones the documentation says exist: the session's own
 * file while it is prepared or recording, and the caller's finished, interrupted or kept files. Anything
 * else is litter a cancelled or replaced session left behind.
 */
@RunWith(AndroidJUnit4::class)
class RecorderRealStackLitterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `random operation sequences over the real file system never leave litter`() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        for (seed in listOf(31, 32, 33)) runSequence(seed)
    }

    private fun runSequence(seed: Int) {
        val random = Random(seed)
        val directory = File(context.cacheDir, "litter-$seed-${System.nanoTime()}")
        val engine = FileCreatingEngine(MediaRecorderEngine(context))
        val recorder: AudioRecorder = DefaultAudioRecorder(
            engine = engine,
            fileSystem = AndroidRecordingFileSystem(context),
            config = AudioRecorderConfig(storage = RecordingStorage(directoryPath = directory.path), minimumFreeSpaceBytes = 0L),
            workerContext = Dispatchers.Default,
            epochClock = EpochClock { System.currentTimeMillis() },
        )
        val callers: MutableSet<String> = mutableSetOf()
        val log: MutableList<String> = mutableListOf()
        var explicitCounter = 0
        var sawFile = false

        fun sessionFile(state: RecorderState): String? = when (state) {
            is RecorderState.Ready -> state.outputPath
            is RecorderState.Recording -> state.outputPath
            is RecorderState.Paused -> state.outputPath
            else -> null
        }

        fun check(step: Int) {
            val state: RecorderState = recorder.state.value
            when (state) {
                is RecorderState.Completed -> callers += state.recording.path
                is RecorderState.Interrupted -> callers += state.recording.path
                is RecorderState.Failed -> state.outputPath?.let { callers += it }
                else -> Unit
            }
            val expected: Set<String> = callers.toSet() + listOfNotNull(sessionFile(state))
            val actual: Set<String> = directory.listFiles().orEmpty().map { it.path }.toSet()
            if (actual.isNotEmpty()) sawFile = true
            assertEquals(expected, actual, "seed $seed step $step state $state last ${log.takeLast(5)}")
        }

        try {
            runBlocking {
                withTimeout(120_000) {
                    repeat(300) { step: Int ->
                        when (random.nextInt(10)) {
                            0, 1 -> {
                                val explicit: String? = if (random.nextBoolean()) File(directory, "e${explicitCounter++}.m4a").path else null
                                log += "prepare($explicit)"
                                recorder.prepare(explicit)
                            }
                            2 -> {
                                val explicit = File(directory, "c${explicitCounter++}.m4a").path
                                log += "prepare-cancelled($explicit)"
                                val job = launch(Dispatchers.Default) { recorder.prepare(explicit) }
                                repeat(random.nextInt(0, 6)) { yield() }
                                job.cancel()
                                job.join()
                            }
                            9 -> {
                                val explicit = File(directory, "f${explicitCounter++}.m4a").path
                                log += "prepare-engine-fails($explicit)"
                                engine.failAfterCreating = true
                                recorder.prepare(explicit)
                                engine.failAfterCreating = false
                            }
                            3 -> { log += "start"; recorder.start() }
                            4 -> { log += "pause"; recorder.pause() }
                            5 -> { log += "resume"; recorder.resume() }
                            6 -> { log += "stop"; recorder.stop() }
                            else -> {
                                log += "cancel"
                                val before: RecorderState = recorder.state.value
                                val result = recorder.cancel()
                                if (result.isSuccess) {
                                    when (before) {
                                        is RecorderState.Interrupted -> callers -= before.recording.path
                                        is RecorderState.Failed -> before.outputPath?.let { callers -= it }
                                        else -> Unit
                                    }
                                }
                            }
                        }
                        check(step)
                    }
                }
            }
            assertTrue(sawFile, "seed $seed: the shadow never created a file, so the check proved nothing")
        } finally {
            recorder.release()
            directory.deleteRecursively()
        }
    }
}
