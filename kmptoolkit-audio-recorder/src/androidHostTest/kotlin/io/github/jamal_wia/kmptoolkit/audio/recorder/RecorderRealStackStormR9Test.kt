package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Round 9, lifecycle chaos on the real stack: several threads drive one recorder with random public
 * calls (including a `release()` in the middle) over the real engine, the real files module and the
 * strict platform stand-in, which throws on any call made in a wrong state. No failure is injected, so
 * an `EngineFailure` in any answer means the recorder drove the platform in an illegal order. Seeded
 * (the seed is in each failure message); every wait is bounded in real time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [StrictShadowMediaRecorder::class])
class RecorderRealStackStormR9Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File

    @Before
    fun setUp() {
        StrictRecorderWorld.reset()
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "r9storm-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun storm(seed: Long, threadCount: Int, steps: Int, releaseAtStep: Int?) {
        val recorder: AudioRecorder = createAudioRecorder(
            context,
            AudioRecorderConfig(storage = RecordingStorage(directoryPath = File(root, "seed-$seed").path)),
        )
        // A recording is guaranteed before the threads start, so the storm always begins inside one.
        assertTrue(runBlocking { withTimeout(20_000) { recorder.prepare() } }.isSuccess, "seed $seed: prologue prepare")
        assertTrue(recorder.start().isSuccess, "seed $seed: prologue start")
        val problems: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        val tally: java.util.concurrent.ConcurrentHashMap<String, Int> = java.util.concurrent.ConcurrentHashMap()
        val ready = CountDownLatch(threadCount)
        val go = CountDownLatch(1)
        val threads: List<Thread> = List(threadCount) { index: Int ->
            Thread {
                val random = Random(seed * 31 + index)
                ready.countDown()
                go.await(10, TimeUnit.SECONDS)
                repeat(steps) { step: Int ->
                    if (index == 0 && step == releaseAtStep) recorder.release()
                    val outcome: Result<RecorderResult<*>> = runCatching {
                        runBlocking {
                            withTimeout(20_000) {
                                // Mostly the call that makes sense in the state seen, sometimes any call at all.
                                val natural: Int? = if (random.nextInt(10) < 7) {
                                    when (val seen: RecorderState = recorder.state.value) {
                                        RecorderState.Idle, is RecorderState.Completed, is RecorderState.Interrupted -> 0
                                        is RecorderState.Ready -> if (random.nextBoolean()) 2 else 6
                                        is RecorderState.Recording -> listOf(3, 5, 5, 6)[random.nextInt(4)]
                                        is RecorderState.Paused -> listOf(4, 4, 5, 6)[random.nextInt(4)]
                                        is RecorderState.Failed -> if (seen.outputPath != null) 6 else 0
                                        else -> null
                                    }
                                } else {
                                    null
                                }
                                when (natural ?: random.nextInt(8)) {
                                    0, 1 -> recorder.prepare()
                                    2 -> recorder.start()
                                    3 -> recorder.pause()
                                    4 -> recorder.resume()
                                    5 -> recorder.stop()
                                    6 -> recorder.cancel()
                                    else -> RecorderResult.Success(recorder.state.value)
                                }
                            }
                        }
                    }
                    val failure: Throwable? = outcome.exceptionOrNull()
                    if (failure != null) {
                        problems += "seed $seed thread $index step $step threw $failure"
                    } else {
                        val result: RecorderResult<*> = outcome.getOrThrow()
                        val error: RecorderError? = (result as? RecorderResult.Failure)?.error
                        tally.merge("${result::class.simpleName}/${error?.let { it::class.simpleName }}", 1, Int::plus)
                        if (error is RecorderError.EngineFailure) {
                            problems += "seed $seed thread $index step $step engine failure ${error.operation}: ${error.cause}"
                        }
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        go.countDown()
        threads.forEach { it.join(120_000) }
        assertTrue(threads.none { it.isAlive }, "seed $seed: a thread is still running (hang)")

        recorder.release()

        assertEquals(emptyList(), problems.toList(), "seed $seed")
        assertEquals(RecorderState.Released, recorder.state.value, "seed $seed")
        assertEquals(0, StrictRecorderWorld.live.get(), "seed $seed: a platform recorder leaked")
        assertTrue(StrictRecorderWorld.calls.any { it.endsWith(":start") }, "seed $seed: the prologue recording never reached the platform")
    }

    @Test
    fun `four threads of random calls never drive the platform in a wrong order and never leak a handle`() {
        listOf(9101L, 9102L, 9103L).forEach { seed: Long -> storm(seed, threadCount = 4, steps = 60, releaseAtStep = null) }
    }

    @Test
    fun `a release landing in the middle of random calls leaves the recorder released and no handle behind`() {
        listOf(9201L, 9202L, 9203L).forEach { seed: Long -> storm(seed, threadCount = 4, steps = 60, releaseAtStep = 25) }
    }
}
