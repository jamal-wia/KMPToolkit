package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * D1 boss round 1, attack B3 (an unrun item of r10-r14): an engine whose `load` ignores cancellation and blocks its
 * thread until the test lets it go, on real threads. `04-api-reference.md` and the class KDoc: one load at a time on the
 * engine, a newer prepare waits for an older one to unwind, `release` is final and Idle at once, the engine is released
 * and disposed once, never during a running load, and nothing is called after `dispose`. Every wait is a latch with a
 * bound; no sleep.
 */
class NonCooperativeLoadRealThreadsD1Boss1Test {

    private class StubbornEngine : VideoPlaybackEngine {
        @Volatile var sink: VideoPlaybackEngineListener? = null
        val loading: AtomicInteger = AtomicInteger(0)
        val maxLoading: AtomicInteger = AtomicInteger(0)
        val violations: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        val log: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        val entered: ConcurrentLinkedQueue<CountDownLatch> = ConcurrentLinkedQueue()
        val gates: ConcurrentLinkedQueue<CountDownLatch> = ConcurrentLinkedQueue()
        @Volatile var disposed: Boolean = false

        private fun note(call: String) {
            if (disposed) violations += "$call after dispose"
            if (loading.get() > 0 && (call == "release" || call == "dispose")) violations += "$call during a running load"
            log += call
        }

        override fun setListener(listener: VideoPlaybackEngineListener?) { note("setListener"); sink = listener }

        override suspend fun load(source: VideoSource) {
            note("load")
            val now: Int = loading.incrementAndGet()
            maxLoading.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                entered.poll()?.countDown()
                // Blocks the thread and ignores cancellation, as a platform call that cannot be interrupted would.
                val gate: CountDownLatch = checkNotNull(gates.poll()) { "no gate scripted" }
                check(gate.await(10, TimeUnit.SECONDS)) { "the test never let the load go" }
            } finally {
                loading.decrementAndGet()
            }
        }

        override fun start() = note("start")
        override fun pause() = note("pause")
        override fun seekTo(positionMs: Long) = note("seek")
        override fun setSpeed(speed: Float) = note("speed")
        override fun setVolume(volume: Float) = note("volume")
        override fun setLooping(looping: Boolean) = note("looping")
        override fun durationMs(): Long = 10_000L
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L
        override fun release() = note("release")
        override fun dispose() { note("dispose"); disposed = true }
    }

    private val a: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val b: VideoSource = VideoSource.Remote("https://example.test/b.mp4")

    private fun player(engine: StubbornEngine): VideoPlayer =
        createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 50L), Dispatchers.Default)

    private fun prepareOn(player: VideoPlayer, source: VideoSource, failure: AtomicReference<Throwable?>): Thread =
        Thread {
            try {
                runBlocking { player.prepare(source) }
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }.also { it.start() }

    private fun joinOrFail(thread: Thread) {
        thread.join(30_000L)
        assertTrue(!thread.isAlive, "a prepare did not finish within 30 s")
    }

    @Test
    fun `a release during a load that ignores cancellation is Idle at once and disposes once after the load returns`() {
        val engine = StubbornEngine()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        engine.entered += entered
        engine.gates += gate
        val player: VideoPlayer = player(engine)
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        val first: Thread = prepareOn(player, a, failure)
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the load never started")

        player.release()
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        player.play()
        player.seekTo(500L)
        gate.countDown()
        joinOrFail(first)

        assertNull(failure.get(), "the prepare of a caller that was not cancelled threw")
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(1, engine.log.count { it == "dispose" }, "dispose exactly once: ${engine.log}")
        assertTrue(engine.violations.isEmpty(), "violations ${engine.violations}; log ${engine.log}")
        assertTrue(engine.log.none { it == "start" || it == "seek" }, "a transport call reached the engine: ${engine.log}")
    }

    @Test
    fun `an unload during a load that ignores cancellation makes the next prepare wait for it and loads one at a time`() {
        val engine = StubbornEngine()
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstGate = CountDownLatch(1)
        val secondGate = CountDownLatch(1)
        engine.entered += firstEntered
        engine.entered += secondEntered
        engine.gates += firstGate
        engine.gates += secondGate
        val player: VideoPlayer = player(engine)
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        val first: Thread = prepareOn(player, a, failure)
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS), "the first load never started")

        player.unload()
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        val second: Thread = prepareOn(player, b, failure)
        // The second load must not start while the first still runs: let the first go only now.
        assertTrue(!secondEntered.await(0, TimeUnit.MILLISECONDS), "the second load started while the first ran")
        firstGate.countDown()
        assertTrue(secondEntered.await(10, TimeUnit.SECONDS), "the second load never started")
        secondGate.countDown()
        joinOrFail(first)
        joinOrFail(second)

        assertNull(failure.get(), "a prepare threw")
        assertEquals(1, engine.maxLoading.get(), "two loads ran at once: ${engine.log}")
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "log ${engine.log}")
        assertTrue(engine.violations.isEmpty(), "violations ${engine.violations}; log ${engine.log}")
        player.release()
        assertEquals(1, engine.log.count { it == "dispose" })
    }

    @Test
    fun `a newer prepare from another thread over a load that ignores cancellation waits and then wins`() {
        val engine = StubbornEngine()
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstGate = CountDownLatch(1)
        val secondGate = CountDownLatch(1)
        engine.entered += firstEntered
        engine.entered += secondEntered
        engine.gates += firstGate
        engine.gates += secondGate
        val player: VideoPlayer = player(engine)
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        val first: Thread = prepareOn(player, a, failure)
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS), "the first load never started")
        val second: Thread = prepareOn(player, b, failure)
        assertTrue(!secondEntered.await(0, TimeUnit.MILLISECONDS), "the second load started while the first ran")
        firstGate.countDown()
        assertTrue(secondEntered.await(10, TimeUnit.SECONDS), "the second load never started")
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
        secondGate.countDown()
        joinOrFail(first)
        joinOrFail(second)

        assertNull(failure.get(), "a prepare threw")
        assertEquals(1, engine.maxLoading.get(), "two loads ran at once: ${engine.log}")
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "log ${engine.log}")
        assertTrue(engine.violations.isEmpty(), "violations ${engine.violations}; log ${engine.log}")
        player.release()
        assertEquals(1, engine.log.count { it == "dispose" })
    }
}
