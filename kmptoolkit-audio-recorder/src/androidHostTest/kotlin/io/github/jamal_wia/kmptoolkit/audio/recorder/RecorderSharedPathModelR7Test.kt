package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows

/** The Android engine plus the file the platform creates while it prepares, which the shadow does not. */
private class FileMakingEngine(private val delegate: MediaRecorderEngine) : RecorderEngine by delegate {
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
 * Three recorders over the real file system and the Android engine share two explicit output paths,
 * each spelled three ways (`a`, `./a`, `x/../a`), driven one operation at a time by seeded random
 * operations. A reference model tracks who owns each path (the recorder whose `prepare` succeeded
 * last, until it gives the path up by cancelling, by releasing from `Ready` or by re-preparing from
 * `Ready`). The documented guarantee under test: a recorder that owns its path never loses its file to
 * another recorder's cancel, release, failed prepare or re-prepare, whatever the spelling.
 */
@RunWith(AndroidJUnit4::class)
class RecorderSharedPathModelR7Test {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an owner of a shared path never loses its file to another recorder`() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        for (seed in listOf(71, 72, 73, 74)) runSequence(seed)
    }

    private fun runSequence(seed: Int) {
        val random = Random(seed)
        val directory = File(context.cacheDir, "shared-$seed-${System.nanoTime()}")
        File(directory, "x").mkdirs()
        val spellings: List<List<String>> = listOf("a", "b").map { name: String ->
            listOf(
                File(directory, "$name.m4a").path,
                File(File(directory, "."), "$name.m4a").path,
                File(File(directory, "x/.."), "$name.m4a").path,
            )
        }
        val engines: List<FileMakingEngine> = List(3) { FileMakingEngine(MediaRecorderEngine(context)) }
        val recorders: List<AudioRecorder> = engines.map { engine: FileMakingEngine ->
            DefaultAudioRecorder(
                engine = engine,
                fileSystem = AndroidRecordingFileSystem(context),
                config = AudioRecorderConfig(
                    storage = RecordingStorage(directoryPath = directory.path),
                    minimumFreeSpaceBytes = 0L,
                ),
                workerContext = Dispatchers.Default,
                epochClock = EpochClock { System.currentTimeMillis() },
            )
        }
        val owner: MutableMap<Int, Int?> = mutableMapOf()
        val dead: MutableSet<Int> = mutableSetOf()
        val log: MutableList<String> = mutableListOf()

        fun pathIndex(path: String): Int = if (File(path).name.startsWith("a")) 0 else 1

        fun statePath(state: RecorderState): String? = when (state) {
            is RecorderState.Ready -> state.outputPath
            is RecorderState.Recording -> state.outputPath
            is RecorderState.Paused -> state.outputPath
            is RecorderState.Completed -> state.recording.path
            is RecorderState.Interrupted -> state.recording.path
            is RecorderState.Failed -> state.outputPath
            else -> null
        }

        fun giveUp(index: Int, state: RecorderState) {
            val path: String = statePath(state) ?: return
            val key: Int = pathIndex(path)
            if (owner[key] == index) owner[key] = null
        }

        fun check(step: Int) {
            for ((index: Int, recorder: AudioRecorder) in recorders.withIndex()) {
                if (index in dead) continue
                val state: RecorderState = recorder.state.value
                val path: String = statePath(state) ?: continue
                if (owner[pathIndex(path)] == index) {
                    assertTrue(
                        File(path).exists(),
                        "seed $seed step $step: recorder $index owns $path in state $state but the file is gone; last ${log.takeLast(6)}",
                    )
                }
            }
        }

        try {
            runBlocking {
                withTimeout(120_000) {
                    repeat(260) { step: Int ->
                        val index: Int = random.nextInt(3)
                        if (index in dead) return@repeat
                        val recorder: AudioRecorder = recorders[index]
                        when (random.nextInt(11)) {
                            0, 1, 2 -> {
                                val key: Int = random.nextInt(2)
                                val path: String = spellings[key][random.nextInt(3)]
                                val before: RecorderState = recorder.state.value
                                val failing: Boolean = random.nextInt(4) == 0
                                log += "r$index.prepare($path failing=$failing)"
                                engines[index].failAfterCreating = failing
                                val result: RecorderResult<String> = recorder.prepare(path)
                                engines[index].failAfterCreating = false
                                val legal: Boolean = before is RecorderState.Idle || before is RecorderState.Ready ||
                                    before is RecorderState.Completed || before is RecorderState.Interrupted ||
                                    before is RecorderState.Failed
                                if (legal) {
                                    if (before is RecorderState.Ready) giveUp(index, before)
                                    if (result.isSuccess) owner[key] = index
                                }
                            }
                            3 -> { log += "r$index.start"; recorder.start() }
                            4 -> { log += "r$index.pause"; recorder.pause() }
                            5 -> { log += "r$index.resume"; recorder.resume() }
                            6, 7 -> { log += "r$index.stop"; recorder.stop() }
                            8, 9 -> {
                                log += "r$index.cancel"
                                val before: RecorderState = recorder.state.value
                                if (recorder.cancel().isSuccess) giveUp(index, before)
                            }
                            else -> {
                                log += "r$index.release"
                                val before: RecorderState = recorder.state.value
                                recorder.release()
                                dead += index
                                if (before is RecorderState.Ready) giveUp(index, before)
                            }
                        }
                        check(step)
                    }
                }
            }
        } finally {
            recorders.forEach { it.release() }
            directory.deleteRecursively()
        }
    }
}
