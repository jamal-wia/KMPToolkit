package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 7 (concurrency, real JavaFX, in-process deadlock detection): one engine, six threads calling every public
 * method at once (load with a short timeout, start, pause, seek, speed, volume, looping, the three getters, set
 * the listener, release), and a listener that re-enters the engine from the FX thread and throws. The test takes
 * its own thread dump through `ThreadMXBean.findDeadlockedThreads()` and reports the stacks of every thread when
 * a driver does not finish (an external attach would leave a `.java_pid` file in the temp directory). Afterwards
 * the FX thread is still answering and a fresh load plays.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxEngineStormDeadlockR7AdversarialTest {

    private val uncaught: MutableList<String> = CopyOnWriteArrayList()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught += "${t.name}: $e" }
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun stacks(): String = buildString {
        for ((t: Thread, frames: Array<StackTraceElement>) in Thread.getAllStackTraces()) {
            append("\"").append(t.name).append("\" ").append(t.state).append('\n')
            frames.take(14).forEach { append("    at ").append(it).append('\n') }
        }
    }

    @Test
    fun `six threads on every method and a reentrant throwing listener on the FX thread neither deadlock nor leak a stray exception`() {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 4_000L)
        val problems: MutableList<String> = CopyOnWriteArrayList()
        val pick = Random(11)
        val listener = object : VideoPlaybackEngineListener {
            private fun react(kind: String) {
                when (pick.nextInt(8)) {
                    0 -> engine.seekTo(pick.nextLong(0L, 3_000L))
                    1 -> engine.start()
                    2 -> engine.pause()
                    3 -> engine.positionMs()
                    4 -> engine.setSpeed(2f)
                    5 -> engine.setLooping(pick.nextBoolean())
                    6 -> engine.release()
                    else -> engine.durationMs()
                }
                if (pick.nextInt(4) == 0) throw IllegalStateException("deliberate: the listener threw from $kind")
            }

            override fun onCompleted() = react("completed")
            override fun onFailed(cause: Throwable) = react("failed")
            override fun onBufferingChanged(isBuffering: Boolean) = react("buffering")
            override fun onVideoSizeChanged(size: VideoSize?) = react("size")
        }
        engine.setListener(listener)

        val stop = AtomicBoolean(false)
        val deadlocked = AtomicBoolean(false)
        val mx = ManagementFactory.getThreadMXBean()
        val detector = thread(name = "r7-deadlock-detector", isDaemon = true) {
            while (!stop.get()) {
                val ids: LongArray? = mx.findDeadlockedThreads()
                if (ids != null) {
                    deadlocked.set(true)
                    problems += "JVM deadlock: " + mx.getThreadInfo(ids, 12).joinToString { it.toString().take(900) }
                    return@thread
                }
                Thread.sleep(50L)
            }
        }
        val deadline: Long = System.nanoTime() + 6_000_000_000L
        val drivers = List(6) { n ->
            thread(name = "r7-driver-$n", isDaemon = true) {
                val random = Random(n * 17 + 3)
                while (System.nanoTime() < deadline && !deadlocked.get()) {
                    runCatching {
                        when (random.nextInt(14)) {
                            0, 1 -> runBlocking {
                                runCatching {
                                    withTimeout(random.nextLong(30L, 1_500L)) {
                                        engine.load(if (random.nextBoolean()) VideoSource.Asset(TestClip.ASSET) else VideoSource.File(TestClip.copyToTempFile().path))
                                    }
                                }
                            }
                            2, 3 -> engine.start()
                            4 -> engine.pause()
                            5, 6 -> engine.seekTo(random.nextLong(-10L, 3_500L))
                            7 -> engine.setSpeed(listOf(0.5f, 1f, 2f, 4f, Float.NaN, 0f).random(random))
                            8 -> engine.setVolume(listOf(0f, 0.5f, 1f, 9f, Float.NaN).random(random))
                            9 -> engine.setLooping(random.nextBoolean())
                            10 -> { engine.positionMs(); engine.durationMs(); engine.bufferedPositionMs() }
                            11 -> engine.setListener(if (random.nextBoolean()) listener else null)
                            12 -> engine.release()
                            else -> engine.positionMs()
                        }
                    }
                    Thread.sleep(random.nextLong(0L, 6L))
                }
            }
        }
        drivers.forEach { it.join(40_000L) }
        val hung: List<String> = drivers.filter { it.isAlive }.map { it.name }
        if (hung.isNotEmpty()) problems += "drivers still running after 40 s: $hung\n${stacks()}"
        stop.set(true)
        detector.join(2_000L)

        engine.setListener(null)
        val releaser = thread(name = "r7-final-release", isDaemon = true) { engine.release() }
        releaser.join(15_000L)
        if (releaser.isAlive) problems += "the final release did not return in 15 s\n${stacks()}"
        // The FX thread still answers, and nothing the engine posted is left behind.
        runCatching { runBlocking { withTimeout(15_000L) { FxThread.call { 1 } } } }
            .onFailure { problems += "the FX thread did not answer after the storm: $it\n${stacks()}" }

        assertEquals(emptyList(), problems.toList(), "hangs and deadlocks")
        val stray: List<String> = uncaught.filterNot { "deliberate" in it }
        assertEquals(emptyList(), stray, "exceptions that no listener of mine threw")

        // And the engine is still good for a plain load and play.
        val fresh = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        val completed = AtomicBoolean(false)
        fresh.setListener(object : VideoPlaybackEngineListener {
            override fun onCompleted() { completed.set(true) }
            override fun onFailed(cause: Throwable) = Unit
            override fun onBufferingChanged(isBuffering: Boolean) = Unit
            override fun onVideoSizeChanged(size: VideoSize?) = Unit
        })
        try {
            runBlocking { withTimeout(20_000L) { fresh.load(VideoSource.Asset(TestClip.ASSET)) } }
            fresh.setSpeed(4f)
            fresh.start()
            awaitTrue(timeoutMs = 10_000L, message = { "a fresh engine never completed after the storm" }) { completed.get() }
        } finally {
            fresh.release()
        }
        assertTrue(true)
    }
}
