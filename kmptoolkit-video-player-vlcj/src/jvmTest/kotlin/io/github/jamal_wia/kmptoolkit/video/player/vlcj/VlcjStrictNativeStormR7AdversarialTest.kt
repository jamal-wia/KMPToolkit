package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 7 (concurrency, in-process deadlock detection): the engine's every public method called from six threads
 * at once, libvlc events raised on an input thread that holds the input lock for the whole callback, a listener
 * that re-enters the engine and throws, and a libvlc model that enforces the rules the engine documents:
 *
 * - no native call from the event thread (VLCJ's rule);
 * - no two native calls on one player at the same time, and none after its teardown began;
 * - `set_time` waits for a running event callback (libvlc 3), so a seek made while holding a lock a callback
 *   needs is a deadlock, which the model reports after 2 s and which the test also sees through
 *   `ThreadMXBean.findDeadlockedThreads()`, taken by the test itself (an external attach would leave a
 *   `.java_pid` file in the temp directory);
 * - every native player is stopped and released exactly once, in that order, and libvlc is freed once, last.
 *
 * Every wait is bounded; on a hang the failure message carries a dump of every thread's stack.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjStrictNativeStormR7AdversarialTest {

    private val violations: MutableList<String> = CopyOnWriteArrayList()
    private val executors: MutableList<ExecutorService> = CopyOnWriteArrayList()

    @AfterTest
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
    }

    private fun daemonSingle(name: String): ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }.also { executors += it }

    private class Counters {
        val created: AtomicInteger = AtomicInteger()
        val stopped: AtomicInteger = AtomicInteger()
        val released: AtomicInteger = AtomicInteger()
        val runtimeReleased: AtomicInteger = AtomicInteger()
        val exceptionsFromCallbacks: AtomicInteger = AtomicInteger()
    }

    private inner class StrictRuntime(
        val inputThread: ExecutorService,
        val taskThread: ExecutorService,
        val inputLock: ReentrantLock,
        val counters: Counters,
    ) : VlcRuntime {
        val players: MutableList<StrictPlayer> = CopyOnWriteArrayList()

        override fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer {
            counters.created.incrementAndGet()
            return StrictPlayer(this, callbacks).also { players += it }
        }

        override fun release() {
            if (players.any { it.releaseCount.get() != 1 }) violations += "libvlc freed before every native player was released once"
            counters.runtimeReleased.incrementAndGet()
        }
    }

    private inner class StrictPlayer(
        private val runtime: StrictRuntime,
        val callbacks: VlcPlayerCallbacks,
    ) : VlcNativePlayer {
        private val inNative: AtomicInteger = AtomicInteger()
        @Volatile private var tearingDown: Boolean = false
        val stopCount: AtomicInteger = AtomicInteger()
        val releaseCount: AtomicInteger = AtomicInteger()
        @Volatile private var playhead: Long = 0L
        @Volatile private var repeat: Boolean = false

        private inline fun <T> native(name: String, teardown: Boolean = false, block: () -> T): T {
            val caller: String = Thread.currentThread().name
            if (caller == INPUT_THREAD) violations += "$name called on the event thread"
            if (!teardown && tearingDown) violations += "$name made after the teardown of its player began"
            if (inNative.incrementAndGet() > 1) violations += "$name overlapped another native call on the same player"
            try {
                if (Random.nextInt(4) == 0) Thread.yield()
                return block()
            } finally {
                inNative.decrementAndGet()
            }
        }

        override fun prepare(mrl: String, options: List<String>): Boolean = native("prepare") { true }

        override fun parse(): Boolean = native("parse") {
            runtime.inputThread.execute { event { parsed(VlcParseStatus.DONE) } }
            true
        }

        override fun parsedInfo(): VlcParsedInfo = VlcParsedInfo(durationMs = 4_000L, trackCount = 1, videoSize = VideoSize(64, 48))

        override fun play() = native("play") { Unit }

        override fun pause() = native("pause") { Unit }

        override fun setTime(timeMs: Long) = native("setTime") {
            playhead = timeMs
            // libvlc 3: set_time needs the input thread, which may be inside a callback.
            if (!runtime.inputLock.tryLock(2L, TimeUnit.SECONDS)) {
                violations += "setTime waited 2 s for a running event callback: the caller holds a lock the callback needs"
            } else {
                runtime.inputLock.unlock()
            }
        }

        override fun time(): Long = native("time") { playhead }

        override fun setRate(rate: Float) = native("setRate") {
            if (!rate.isFinite() || rate < 1f / 32f || rate > 32f) violations += "setRate($rate) outside what libvlc plays"
        }

        override fun setVolume(percent: Int) = native("setVolume") {
            if (percent !in 0..200) violations += "setVolume($percent) outside libvlc's scale"
        }

        override fun rate(): Float = 1f

        override fun volume(): Int = 100

        override fun setRepeat(repeat: Boolean) {
            this.repeat = repeat
        }

        override fun repeat(): Boolean = repeat

        override fun submit(task: () -> Unit) {
            runtime.taskThread.execute(task)
        }

        override fun stopParsing() = native("stopParsing", teardown = true) { tearingDown = true }

        override fun stop() = native("stop", teardown = true) {
            tearingDown = true
            stopCount.incrementAndGet()
            runtime.counters.stopped.incrementAndGet()
            Unit
        }

        override fun release() = native("release", teardown = true) {
            if (stopCount.get() != 1) violations += "a native player released without exactly one stop before (${stopCount.get()})"
            releaseCount.incrementAndGet()
            runtime.counters.released.incrementAndGet()
            Unit
        }

        /** An event as libvlc raises it: on the input thread, which stays inside the callback until it returns. */
        fun event(block: VlcPlayerCallbacks.() -> Unit) {
            runtime.inputLock.lock()
            try {
                try {
                    callbacks.block()
                } catch (t: Throwable) {
                    // JNA swallows what a callback throws; count it so a test can see the listener's own throws.
                    runtime.counters.exceptionsFromCallbacks.incrementAndGet()
                }
            } finally {
                runtime.inputLock.unlock()
            }
        }
    }

    private fun stacks(): String = buildString {
        for ((t: Thread, frames: Array<StackTraceElement>) in Thread.getAllStackTraces()) {
            append("\"").append(t.name).append("\" ").append(t.state).append('\n')
            frames.take(14).forEach { append("    at ").append(it).append('\n') }
        }
    }

    @Test
    fun `six threads on every method, an input thread inside callbacks and a reentrant throwing listener neither deadlock nor break the native rules`() {
        val counters = Counters()
        val runtime = StrictRuntime(daemonSingle(INPUT_THREAD), daemonSingle("strict-task-thread"), ReentrantLock(), counters)
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        val reentrancyRandom = Random(99)
        val afterFinalRelease = AtomicBoolean(false)
        val listener = object : VideoPlaybackEngineListener {
            private fun react(kind: String) {
                if (afterFinalRelease.get()) violations += "the listener heard $kind after the final release returned"
                // From inside the callback, on the event thread or a caller thread: the engine's own methods.
                when (reentrancyRandom.nextInt(9)) {
                    0 -> engine.seekTo(reentrancyRandom.nextLong(0L, 5_000L))
                    1 -> engine.start()
                    2 -> engine.pause()
                    3 -> engine.positionMs()
                    4 -> engine.setSpeed(1.5f)
                    5 -> engine.setVolume(0.3f)
                    6 -> engine.setLooping(reentrancyRandom.nextBoolean())
                    7 -> engine.release()
                    else -> engine.durationMs()
                }
                if (reentrancyRandom.nextInt(5) == 0) error("the listener threw from $kind")
            }

            override fun onCompleted() = react("completed")
            override fun onFailed(cause: Throwable) = react("failed")
            override fun onBufferingChanged(isBuffering: Boolean) = react("buffering")
            override fun onVideoSizeChanged(size: VideoSize?) = react("size")
        }
        engine.setListener(listener)

        val stop = AtomicBoolean(false)
        val deadlock = AtomicBoolean(false)
        val mx = ManagementFactory.getThreadMXBean()
        val detector = thread(name = "deadlock-detector", isDaemon = true) {
            while (!stop.get()) {
                val ids: LongArray? = mx.findDeadlockedThreads()
                if (ids != null) {
                    deadlock.set(true)
                    violations += "JVM deadlock between: " + mx.getThreadInfo(ids, 12).joinToString { it.toString().take(900) }
                    return@thread
                }
                Thread.sleep(40L)
            }
        }
        val clip: String = TestClip.file.path
        val driverDeadline: Long = System.nanoTime() + 3_000_000_000L
        val drivers = List(6) { n ->
            thread(name = "driver-$n", isDaemon = true) {
                val random = Random(n * 31 + 7)
                while (System.nanoTime() < driverDeadline && !deadlock.get()) {
                    runCatching {
                        when (random.nextInt(16)) {
                            // One loader only: the engine SPI says calls never overlap, and two loads at once leak a session.
                            0, 1 -> if (n == 0) runBlocking { runCatching { withTimeout(random.nextLong(20L, 300L)) { engine.load(VideoSource.File(clip)) } } }
                            2, 3 -> engine.start()
                            4 -> engine.pause()
                            5, 6 -> engine.seekTo(if (random.nextInt(6) == 0) Long.MAX_VALUE else random.nextLong(-50L, 5_000L))
                            7 -> engine.setSpeed(listOf(0.01f, 0.5f, 1f, 2f, 100f, Float.NaN, 0f, -1f).random(random))
                            8 -> engine.setVolume(listOf(0f, 0.4f, 1f, 7f, -3f, Float.NaN).random(random))
                            9 -> engine.setLooping(random.nextBoolean())
                            10 -> { engine.positionMs(); engine.durationMs(); engine.bufferedPositionMs() }
                            11 -> engine.setListener(if (random.nextBoolean()) listener else null)
                            12 -> engine.release()
                            else -> engine.positionMs()
                        }
                    }
                    if (random.nextInt(8) == 0) Thread.yield()
                }
            }
        }
        val eventer = thread(name = "event-driver", isDaemon = true) {
            val random = Random(5)
            while (!stop.get()) {
                val player: StrictPlayer? = runtime.players.lastOrNull()
                if (player == null) {
                    Thread.sleep(1L)
                    continue
                }
                runtime.inputThread.execute {
                    runCatching {
                        player.event {
                            when (random.nextInt(11)) {
                                0 -> playing()
                                1 -> timeChanged(random.nextLong(0L, 4_000L))
                                2 -> finished()
                                3 -> buffering(random.nextInt(101).toFloat())
                                4 -> lengthChanged(4_000L)
                                5 -> durationChanged(4_000L)
                                6 -> paused()
                                7 -> stopped()
                                8 -> bufferFormat(64, 48)
                                9 -> display(ByteBuffer.allocateDirect(64 * 48 * 4).order(ByteOrder.nativeOrder()), 64, 48)
                                else -> if (random.nextInt(6) == 0) error()
                            }
                        }
                    }
                }
                Thread.sleep(0L, 200_000)
            }
        }

        drivers.forEach { it.join(15_000L) }
        val hung: List<String> = drivers.filter { it.isAlive }.map { it.name }
        if (hung.isNotEmpty()) violations += "drivers still running after 15 s: $hung\n${stacks()}"
        stop.set(true)
        eventer.join(5_000L)
        detector.join(2_000L)

        val releaser = thread(name = "final-release", isDaemon = true) {
            engine.setListener(listener)
            engine.release()
            engine.dispose()
        }
        releaser.join(15_000L)
        if (releaser.isAlive) violations += "final release/dispose did not return in 15 s\n${stacks()}"

        afterFinalRelease.set(true)
        // Events and pictures libvlc still delivers after release returned reach nobody.
        runtime.players.lastOrNull()?.let { last: StrictPlayer ->
            runCatching {
                last.event { finished() }
                last.event { buffering(10f) }
                last.event { bufferFormat(16, 16) }
                last.event { display(ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder()), 16, 16) }
            }
        }
        if (engine.frames.value != null) violations += "a picture was published after the final release"
        val until: Long = System.nanoTime() + 10_000_000_000L
        while ((counters.released.get() < counters.created.get() || counters.runtimeReleased.get() < 1) && System.nanoTime() < until) Thread.sleep(20L)

        assertEquals(emptyList(), violations.toList(), "native-rule violations and hangs")
        assertTrue(counters.created.get() >= 3, "the storm created only ${counters.created.get()} native players")
        assertEquals(counters.created.get(), counters.stopped.get(), "stops")
        assertEquals(counters.created.get(), counters.released.get(), "releases")
        assertEquals(1, counters.runtimeReleased.get(), "libvlc frees")
        for (p: StrictPlayer in runtime.players) {
            assertEquals(1, p.stopCount.get(), "a native player was stopped ${p.stopCount.get()} times")
            assertEquals(1, p.releaseCount.get(), "a native player was released ${p.releaseCount.get()} times")
        }
    }

    private companion object {
        const val INPUT_THREAD: String = "strict-input-thread"
    }
}
