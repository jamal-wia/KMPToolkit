package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.lang.ref.WeakReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** An engine with one handle at most, that remembers nothing about older sessions except through weak references. */
private class LeanEngine : RecorderEngine {
    var open: Int = 0
    var doubleOpen: Int = 0
    var currentListener: ((EngineEvent) -> Unit)? = null
    val weakListeners: MutableList<WeakReference<(EngineEvent) -> Unit>> = mutableListOf()
    var started: Boolean = false

    override fun hasRecordAudioPermission(): Boolean = true
    override fun supportsFormat(format: AudioFormat): Boolean = true
    override suspend fun prepare(outputPath: String, config: AudioRecorderConfig, maxFileSizeBytes: Long?) {
        if (open > 0) doubleOpen++
        open = 1
    }
    override fun setEventListener(listener: ((EngineEvent) -> Unit)?) {
        currentListener = listener
        if (listener != null) weakListeners += WeakReference(listener)
    }
    override fun start() { started = true }
    override fun pause() = Unit
    override fun resume() = Unit
    override fun peakDbfs(): Float? = Float.NEGATIVE_INFINITY
    override fun stop() { started = false }
    override fun release() { open = 0; started = false }
}

/**
 * Round 11, Q3: a soak. One long-lived recorder runs 10 000 cycles of record, stop (or cancel, pause and resume, a
 * system end) with stand-ins only. Nothing may grow with the number of cycles: the recorder's own coroutine jobs,
 * the path-ownership registry, the engine listeners it hands out (weak references: all but the live one must be
 * collectable), the platform handles the stand-in counts, and the files in the recording directory.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [StrictShadowMediaRecorder::class])
class RecorderSoakR11Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File

    @Before
    fun setUp() {
        StrictRecorderWorld.reset()
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "r11soak-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun registrySize(): Int {
        val field = OutputPathOwners::class.java.getDeclaredField("slots").also { it.isAccessible = true }
        return ((field.get(OutputPathOwners) as MutableStateFlow<*>).value as Map<*, *>).size
    }

    private fun collectGarbage() {
        repeat(5) {
            System.gc()
            System.runFinalization()
        }
    }

    @Test
    fun `ten thousand cycles with a lean engine retain nothing and leak no handle or job`() = runTest {
        val engine = LeanEngine()
        val fileSystem = FakeRecordingFileSystem()
        val timeSource = kotlin.time.TestTimeSource()
        val recorder = DefaultAudioRecorder(
            engine = engine,
            fileSystem = fileSystem,
            config = AudioRecorderConfig(),
            workerContext = StandardTestDispatcher(testScheduler),
            epochClock = EpochClock { FIXED_EPOCH_MILLIS },
            fileNameToken = FileNameToken { FIXED_FILE_NAME_TOKEN },
            timeSource = timeSource,
        )
        val random = Random(SEED)
        val registryBase: Int = registrySize()
        var deletedExpected = 0
        var kept = 0
        fun pass(d: Duration) {
            timeSource += d
            advanceTimeBy(d)
            runCurrent()
        }
        fun jobs(): Int = recorder.scope.coroutineContext[Job]!!.children.count()
        val jobSamples: MutableList<Int> = mutableListOf()
        val registrySamples: MutableList<Int> = mutableListOf()
        try {
            repeat(CYCLES) { cycle: Int ->
                val path = "/rec/soak-$cycle.m4a"
                val prepared: RecorderResult<String> = runStep(this) { recorder.prepare(path) }
                assertEquals(path, prepared.getOrNull(), "seed $SEED cycle $cycle")
                assertTrue(recorder.start().isSuccess, "cycle $cycle start")
                pass(5.milliseconds)
                when (random.nextInt(8)) {
                    0 -> { // cancel: the partial file goes
                        assertTrue(runStep(this) { recorder.cancel() }.isSuccess)
                        deletedExpected++
                        // 04-api-reference: the claim ends when the file is deleted.
                        assertEquals(registryBase, registrySize(), "seed $SEED cycle $cycle: a cancelled session still owns its path")
                    }
                    1 -> { // pause and resume, then stop
                        assertTrue(recorder.pause().isSuccess)
                        pass(3.milliseconds)
                        assertTrue(recorder.resume().isSuccess)
                        assertTrue(runStep(this) { recorder.stop() }.isSuccess)
                        kept++
                    }
                    2 -> { // the system ends it
                        engine.currentListener!!.invoke(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
                        runCurrent()
                        assertTrue(recorder.state.value is RecorderState.Interrupted, "cycle $cycle ${recorder.state.value}")
                        kept++
                    }
                    else -> {
                        assertTrue(runStep(this) { recorder.stop() }.isSuccess)
                        kept++
                    }
                }
                // Between cycles nothing is open and nothing is running.
                assertEquals(0, engine.open, "seed $SEED cycle $cycle: a platform handle is still open")
                assertEquals(0, engine.doubleOpen, "seed $SEED cycle $cycle: a handle was opened over another")
                if (cycle % 500 == 0) {
                    jobSamples += jobs()
                    registrySamples += registrySize()
                    val frozen: Duration = recorder.elapsed.value
                    pass(10.seconds)
                    assertEquals(frozen, recorder.elapsed.value, "seed $SEED cycle $cycle: elapsed still advances, a ticker leaked")
                }
            }
            assertEquals(deletedExpected, fileSystem.deletedPaths.size, "files deleted: only cancelled sessions")
            assertEquals(deletedExpected, fileSystem.deletedPaths.toSet().size, "no path deleted twice")
            assertTrue(kept + deletedExpected == CYCLES)
            assertTrue(jobSamples.all { it <= 3 }, "recorder jobs sampled every 500 cycles: $jobSamples")
            assertTrue(registrySamples.all { it <= 3 }, "path registry size sampled every 500 cycles: $registrySamples")
            recorder.release()
            runCurrent()
            assertEquals(0, jobs(), "jobs left after release")
            assertTrue(!recorder.scope.coroutineContext[Job]!!.isActive, "release() must cancel the recorder's scope (04-api-reference)")
            assertTrue(registrySize() <= 1, "path registry after release: ${registrySize()}")
            assertNull(engine.currentListener)
        } finally {
            recorder.release()
        }
        collectGarbage()
        val alive: Int = engine.weakListeners.count { it.get() != null }
        assertTrue(alive <= 2, "$alive of ${engine.weakListeners.size} engine listeners are still reachable after the cycles")
    }

    @Test
    fun `ten thousand record and stop cycles over the real files and the strict platform stand-in leak nothing`() {
        val directory = File(root, "soak")
        val recorder: AudioRecorder = createAudioRecorder(
            context,
            AudioRecorderConfig(storage = RecordingStorage(directoryPath = directory.path), minimumFreeSpaceBytes = 0L),
        )
        val random = Random(SEED + 1)
        var maxLive = 0
        runBlocking {
            withTimeout(120_000) {
                repeat(CYCLES) { cycle: Int ->
                    val prepared = recorder.prepare()
                    assertTrue(prepared.isSuccess, "seed ${SEED + 1} cycle $cycle prepare: ${prepared.errorOrNull()}")
                    assertTrue(recorder.start().isSuccess, "cycle $cycle start")
                    when (random.nextInt(6)) {
                        0 -> assertTrue(recorder.cancel().isSuccess, "cycle $cycle cancel")
                        1 -> {
                            // The stand-in delivers the callback synchronously on this thread.
                            StrictRecorderWorld.instances.last().fireError(MediaRecorder.MEDIA_ERROR_SERVER_DIED, 0)
                            val state: RecorderState = recorder.state.value
                            assertTrue(state is RecorderState.Interrupted || state is RecorderState.Failed || state is RecorderState.Recording, "cycle $cycle $state")
                            if (state is RecorderState.Recording) assertTrue(recorder.stop().isSuccess)
                        }
                        else -> assertTrue(recorder.stop().isSuccess, "cycle $cycle stop")
                    }
                    // A finished file is the caller's: this consumer deletes it as soon as it has it.
                    when (val state: RecorderState = recorder.state.value) {
                        is RecorderState.Completed -> assertTrue(File(state.recording.path).delete(), "cycle $cycle: the finished file was not there")
                        is RecorderState.Interrupted -> assertTrue(File(state.recording.path).delete(), "cycle $cycle: the interrupted file was not there")
                        is RecorderState.Failed -> state.outputPath?.let { File(it).delete() }
                        else -> Unit
                    }
                    val live: Int = StrictRecorderWorld.live.get()
                    if (live > maxLive) maxLive = live
                    assertTrue(live <= 1, "seed ${SEED + 1} cycle $cycle: $live platform recorders are alive between cycles")
                    if (cycle % 250 == 249) StrictRecorderWorld.instances.clear() // the stand-in's own bookkeeping, not the product's
                }
            }
        }
        recorder.release()
        assertEquals(0, StrictRecorderWorld.live.get(), "platform recorders left after release")
        assertEquals(emptyList(), directory.listFiles().orEmpty().map { it.name }, "files left in the recording directory")
        assertTrue(StrictRecorderWorld.created.get() >= CYCLES, "the stand-in saw ${StrictRecorderWorld.created.get()} recorders for $CYCLES cycles")
    }

    /** Runs a suspend call of the recorder to completion on the test scheduler. */
    private fun <T> runStep(scope: kotlinx.coroutines.test.TestScope, block: suspend () -> T): T {
        var result: Result<T>? = null
        val job = scope.launch { result = runCatching { block() } }
        scope.runCurrent()
        scope.advanceTimeBy(1.milliseconds)
        scope.runCurrent()
        check(job.isCompleted) { "a recorder call did not finish on the test scheduler" }
        return result!!.getOrThrow()
    }

    private companion object {
        const val CYCLES: Int = 10_000
        const val SEED: Long = 1111L
    }
}

