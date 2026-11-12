package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 9 (contract lawyer): sentences of `docs/kmptoolkit-video-player/04-api-reference.md` that say "must", "never",
 * "exactly", "at once" or give an order, each turned into a test that fails if the sentence is false. They run over a
 * strict hand-written engine (it records every call with the thread, refuses overlapping calls and calls after `dispose`),
 * with a polling interval of an hour so that no tick interferes.
 */
@OptIn(DelicateCoroutinesApi::class)
class VideoPlayerContractSentencesR9AdversarialTest {

    /** A strict engine: records calls, fails the test on overlap, on a call after dispose, and can fire events or throw on demand. */
    private class StrictEngine(var durationMs: Long = 5_000L) : VideoPlaybackEngine {
        val calls: MutableList<String> = CopyOnWriteArrayList()
        var sink: VideoPlaybackEngineListener? = null
        @Volatile var position: Long = 0L
        @Volatile var buffered: Long = 0L
        val inside: AtomicInteger = AtomicInteger(0)
        val violations: MutableList<String> = CopyOnWriteArrayList()
        @Volatile var disposed: Boolean = false
        val disposeCount: AtomicInteger = AtomicInteger(0)
        val loadCount: AtomicInteger = AtomicInteger(0)
        var onLoad: (StrictEngine) -> Unit = {}
        var loadSuspend: (suspend (Int) -> Unit)? = null
        var failLoadWith: Throwable? = null
        var throwOn: Set<String> = emptySet()
        var inStart: (StrictEngine) -> Unit = {}
        var duringCall: (String) -> Unit = {}

        private fun <T> enter(name: String, block: () -> T): T {
            calls += name
            if (disposed) violations += "$name after dispose"
            if (inside.incrementAndGet() > 1) violations += "$name overlapped another call"
            try {
                duringCall(name)
                if (name in throwOn) throw IllegalStateException("scripted failure of $name")
                return block()
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun setListener(listener: VideoPlaybackEngineListener?) = enter("setListener") { this.sink = listener }
        override suspend fun load(source: VideoSource) {
            val n: Int = loadCount.incrementAndGet()
            calls += "load-start-$n"
            calls += "load"
            if (disposed) violations += "load after dispose"
            try {
                loadSuspend?.invoke(n)
                onLoad(this)
                failLoadWith?.let { throw it }
            } finally {
                calls += "load-end-$n"
            }
        }
        override fun start() = enter("start") { inStart(this) }
        override fun pause() = enter("pause") {}
        override fun seekTo(positionMs: Long) = enter("seekTo($positionMs)") { position = positionMs }
        override fun setSpeed(speed: Float) = enter("setSpeed($speed)") {}
        override fun setVolume(volume: Float) = enter("setVolume($volume)") {}
        override fun setLooping(looping: Boolean) = enter("setLooping($looping)") {}
        override fun durationMs(): Long = durationMs
        override fun positionMs(): Long = position
        override fun bufferedPositionMs(): Long = buffered
        override fun release() = enter("release") {}
        override fun dispose() {
            enter("dispose") { disposeCount.incrementAndGet() }
            disposed = true
        }
    }

    private fun player(engine: StrictEngine, config: VideoPlayerConfig = VideoPlayerConfig(positionUpdateIntervalMs = 3_600_000L)): VideoPlayer =
        createVideoPlayer(engine, config, Dispatchers.Default)

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(30_000L) { block() } }

    private val source: VideoSource = VideoSource.File("clip.mp4")

    @Test
    fun `setVolume while muted reaches the platform as zero and unmuting restores the volume set meanwhile`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.setVolume(0.7f)
        p.prepare(source)
        assertTrue("setVolume(0.7)" in engine.calls, "a volume set before the load is pushed when it succeeds: ${engine.calls}")
        engine.calls.clear()
        p.setMuted(true)
        assertEquals(listOf("setVolume(0.0)"), engine.calls.toList(), "muting pushes zero")
        engine.calls.clear()
        p.setVolume(0.4f)
        assertEquals(listOf("setVolume(0.0)"), engine.calls.toList(), "a volume set while muted reaches the platform as 0f")
        assertEquals(0.4f, p.volumeFlow.value)
        assertTrue(p.isMutedFlow.value)
        engine.calls.clear()
        p.setMuted(false)
        assertEquals(listOf("setVolume(0.4)"), engine.calls.toList(), "unmuting restores the volume set while muted")
        p.release()
        assertEquals(emptyList(), engine.violations)
    }

    @Test
    fun `a load pushes the muted volume zero the repeat mode and the clamped speed`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine, VideoPlayerConfig(positionUpdateIntervalMs = 3_600_000L, minPlaybackSpeed = 2f, maxPlaybackSpeed = 3f))
        assertEquals(2f, p.playbackSpeedFlow.value, "a range that excludes 1.0 starts at its nearest bound")
        assertEquals(2f..3f, p.playbackSpeedRange)
        p.setMuted(true)
        p.setVolume(0.9f)
        p.setRepeatMode(RepeatMode.One)
        p.prepare(source)
        assertTrue("setVolume(0.0)" in engine.calls, "muted at load pushes 0f: ${engine.calls}")
        assertTrue("setLooping(true)" in engine.calls, "repeat One pushed at load: ${engine.calls}")
        assertTrue("setSpeed(2.0)" in engine.calls, "the clamped speed pushed at load: ${engine.calls}")
        assertFalse(engine.calls.any { it == "setVolume(0.9)" }, "the unmuted volume must not reach the platform while muted: ${engine.calls}")
        p.release()
    }

    @Test
    fun `settings after release change the flows and reach no engine call`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        p.release()
        val callsAfterRelease: Int = engine.calls.size
        p.setVolume(0.3f); p.setMuted(true); p.setRepeatMode(RepeatMode.One); p.setPlaybackSpeed(2f)
        p.play(); p.pause(); p.stop(); p.seekTo(10L); p.seekForward(); p.seekBackward(); p.replay(); p.unload(); p.release(); p.close()
        assertEquals(0.3f, p.volumeFlow.value)
        assertTrue(p.isMutedFlow.value)
        assertEquals(RepeatMode.One, p.repeatModeFlow.value)
        assertEquals(2f, p.playbackSpeedFlow.value)
        assertEquals(callsAfterRelease, engine.calls.size, "nothing reaches the engine after the release: ${engine.calls}")
        assertEquals(VideoPlayerState.Idle, p.stateFlow.value)
        assertEquals(emptyList(), engine.violations)
    }

    @Test
    fun `prepare after release sets the released error and loads nothing`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.release()
        p.prepare(source)
        val state: VideoPlayerState = p.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertIs<VideoPlayerReleasedException>(state.cause)
        assertEquals(0, engine.loadCount.get(), "a prepare after release loads nothing")
        assertEquals(1, engine.disposeCount.get())
    }

    @Test
    fun `release with an engine that throws everywhere still disposes once rethrows once and leaves idle`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        p.play()
        engine.throwOn = setOf("setListener", "release", "dispose")
        assertFailsWith<IllegalStateException> { p.release() }
        assertEquals(VideoPlayerState.Idle, p.stateFlow.value)
        assertEquals(0, engine.disposeCount.get() - 0, "dispose threw before counting, but it was called once: ${engine.calls}")
        assertEquals(1, engine.calls.count { it == "dispose" }, "dispose is called exactly once: ${engine.calls}")
        val before: Int = engine.calls.size
        p.release() // idempotent: no second throw, no second call
        p.close()
        assertEquals(before, engine.calls.size, "a second release touches nothing: ${engine.calls.drop(before)}")
        p.setVolume(0.2f)
        assertEquals(0.2f, p.volumeFlow.value)
    }

    @Test
    fun `unload with an engine whose release throws resets to idle rethrows and the next prepare loads`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        p.setVolume(0.5f)
        engine.throwOn = setOf("release")
        assertFailsWith<IllegalStateException> { p.unload() }
        assertEquals(VideoPlayerState.Idle, p.stateFlow.value)
        assertEquals(0L, p.playbackPositionFlow.value)
        assertEquals(0.5f, p.volumeFlow.value, "unload keeps the settings")
        engine.throwOn = emptySet()
        p.prepare(source)
        assertIs<VideoPlayerState.Ready>(p.stateFlow.value)
        assertEquals(2, engine.loadCount.get())
        p.release()
    }

    @Test
    fun `a prepare frees the previous source first even when the player was left in error`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        engine.sink!!.onFailed(RuntimeException("playback failed"))
        // the failure is applied right after the (idle) call that was running; give it a moment, bounded
        val deadline: Long = System.nanoTime() + 5_000_000_000L
        while (p.stateFlow.value !is VideoPlayerState.Error && System.nanoTime() < deadline) Thread.sleep(2L)
        assertIs<VideoPlayerState.Error>(p.stateFlow.value)
        engine.calls.clear()
        p.prepare(source)
        val releaseIndex: Int = engine.calls.indexOf("release")
        val loadIndex: Int = engine.calls.indexOf("load")
        assertTrue(releaseIndex in 0 until loadIndex, "the old source is released before the next load: ${engine.calls}")
        assertIs<VideoPlayerState.Ready>(p.stateFlow.value)
        p.release()
    }

    @Test
    fun `relative seeks on a source of unknown length are floored at zero and capped for the engine`() = bounded {
        val engine = StrictEngine(durationMs = 0L)
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        engine.position = 5_000L
        p.seekForward(Long.MAX_VALUE)
        assertEquals("seekTo(${Long.MAX_VALUE / 1_000_000L})", engine.calls.last { it.startsWith("seekTo") })
        engine.position = 5_000L
        p.seekBackward(Long.MAX_VALUE)
        assertEquals("seekTo(0)", engine.calls.last { it.startsWith("seekTo") })
        engine.position = 5_000L
        p.seekForward(1_000L)
        assertEquals("seekTo(6000)", engine.calls.last { it.startsWith("seekTo") }, "not 0: the target is position + amount")
        p.seekTo(Long.MAX_VALUE)
        assertEquals("seekTo(0)", engine.calls.last { it.startsWith("seekTo") }, "an absolute seek clamps to 0..0 while the length is unknown")
        p.release()
    }

    @Test
    fun `a speed that is infinite is clamped to the bound and NaN is ignored`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine, VideoPlayerConfig(positionUpdateIntervalMs = 3_600_000L, minPlaybackSpeed = 0.5f, maxPlaybackSpeed = 2f))
        p.prepare(source)
        p.play()
        engine.calls.clear()
        p.setPlaybackSpeed(Float.POSITIVE_INFINITY)
        assertEquals(2f, p.playbackSpeedFlow.value)
        p.setPlaybackSpeed(Float.NEGATIVE_INFINITY)
        assertEquals(0.5f, p.playbackSpeedFlow.value)
        p.setPlaybackSpeed(Float.NaN)
        assertEquals(0.5f, p.playbackSpeedFlow.value)
        p.setPlaybackSpeed(-0f)
        assertEquals(0.5f, p.playbackSpeedFlow.value)
        p.setPlaybackSpeed(Float.MIN_VALUE)
        assertEquals(0.5f, p.playbackSpeedFlow.value)
        assertEquals(listOf("setSpeed(2.0)", "setSpeed(0.5)", "setSpeed(0.5)", "setSpeed(0.5)"), engine.calls.toList(), "only finite clamped speeds reach the engine, and a NaN none")
        p.release()
    }

    @Test
    fun `repeat one with a completion reported while paused ready or completed`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        p.setRepeatMode(RepeatMode.One)
        // Ready: ignored.
        engine.sink!!.onCompleted()
        awaitSettled(p) { it is VideoPlayerState.Ready }
        assertIs<VideoPlayerState.Ready>(p.stateFlow.value)
        // Paused: rewound to 0 and stays paused.
        p.play(); engine.position = 2_000L; p.pause()
        assertEquals(VideoPlayerState.Paused(5_000L, 2_000L), p.stateFlow.value)
        engine.calls.clear()
        engine.sink!!.onCompleted()
        awaitSettled(p) { it is VideoPlayerState.Paused && it.currentPosition == 0L }
        assertEquals(VideoPlayerState.Paused(5_000L, 0L), p.stateFlow.value)
        assertEquals(0L, p.playbackPositionFlow.value)
        assertFalse("start" in engine.calls, "a paused source is rewound, never started: ${engine.calls}")
        // Playing: restarts, never Completed.
        p.play()
        engine.sink!!.onCompleted()
        awaitSettled(p) { it is VideoPlayerState.Playing }
        assertIs<VideoPlayerState.Playing>(p.stateFlow.value)
        // Completed: reached with Off, then ignored once the mode is One.
        p.setRepeatMode(RepeatMode.Off)
        engine.sink!!.onCompleted()
        awaitSettled(p) { it is VideoPlayerState.Completed }
        p.setRepeatMode(RepeatMode.One)
        engine.sink!!.onCompleted()
        Thread.sleep(50L)
        assertIs<VideoPlayerState.Completed>(p.stateFlow.value)
        p.release()
    }

    @Test
    fun `a stall reported before ready is shown with ready and a stall after the end is ignored`() = bounded {
        val engine = StrictEngine()
        engine.onLoad = { it.sink!!.onBufferingChanged(true) }
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        assertIs<VideoPlayerState.Ready>(p.stateFlow.value)
        assertTrue(p.isBufferingFlow.value, "a stall of the just-loaded source reported before Ready is shown with Ready")
        engine.sink!!.onBufferingChanged(false)
        awaitSettled(p) { !p.isBufferingFlow.value }
        p.play()
        engine.sink!!.onBufferingChanged(true)
        awaitSettled(p) { p.isBufferingFlow.value }
        engine.sink!!.onCompleted()
        awaitSettled(p) { it is VideoPlayerState.Completed }
        assertFalse(p.isBufferingFlow.value, "false on completion")
        engine.sink!!.onBufferingChanged(true)
        Thread.sleep(50L)
        assertFalse(p.isBufferingFlow.value, "a stall after the end is ignored while Completed")
        p.release()
    }

    @Test
    fun `the picture size is kept on completion and reset by a new prepare a failure unload and release`() = bounded {
        val engine = StrictEngine()
        engine.onLoad = { it.sink!!.onVideoSizeChanged(VideoSize(640, 360)) }
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        assertEquals(VideoSize(640, 360), p.videoSizeFlow.value)
        p.play()
        engine.sink!!.onCompleted()
        awaitSettled(p) { it is VideoPlayerState.Completed }
        assertEquals(VideoSize(640, 360), p.videoSizeFlow.value, "kept on Completed")
        engine.onLoad = {}
        engine.failLoadWith = RuntimeException("boom")
        p.prepare(source)
        assertIs<VideoPlayerState.Error>(p.stateFlow.value)
        assertNull(p.videoSizeFlow.value, "null on a failed prepare")
        engine.failLoadWith = null
        engine.onLoad = { it.sink!!.onVideoSizeChanged(VideoSize(1, 1)) }
        p.prepare(source)
        assertEquals(VideoSize(1, 1), p.videoSizeFlow.value)
        engine.sink!!.onFailed(RuntimeException("mid playback"))
        awaitSettled(p) { it is VideoPlayerState.Error }
        assertNull(p.videoSizeFlow.value, "null on a playback failure")
        p.prepare(source)
        p.unload()
        assertNull(p.videoSizeFlow.value)
        p.prepare(source)
        p.release()
        assertNull(p.videoSizeFlow.value)
    }

    @Test
    fun `the buffered position is snapshotted when a load finishes and on pause and zero after a failure or unload`() = bounded {
        val engine = StrictEngine()
        engine.onLoad = { it.buffered = 700L }
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        assertEquals(700L, p.bufferedPositionFlow.value, "snapshotted when a load finishes")
        p.play()
        engine.buffered = 1_900L
        p.pause()
        assertEquals(1_900L, p.bufferedPositionFlow.value, "snapshotted on pause")
        engine.sink!!.onFailed(RuntimeException("x"))
        awaitSettled(p) { it is VideoPlayerState.Error }
        assertEquals(0L, p.bufferedPositionFlow.value, "zero after a playback failure")
        p.prepare(source)
        assertEquals(700L, p.bufferedPositionFlow.value, "the new load's own snapshot")
        p.unload()
        assertEquals(0L, p.bufferedPositionFlow.value)
        p.release()
    }

    @Test
    fun `play from a paused playhead on the end starts over and a seek from completed becomes paused`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        p.play()
        engine.position = 5_000L
        p.pause()
        assertEquals(VideoPlayerState.Paused(5_000L, 5_000L), p.stateFlow.value)
        engine.calls.clear()
        p.play()
        assertEquals(listOf("seekTo(0)", "start", "setSpeed(1.0)"), engine.calls.toList(), "from the end, rewind then start")
        assertEquals(0L, p.playbackPositionFlow.value)
        engine.sink!!.onCompleted()
        awaitSettled(p) { it is VideoPlayerState.Completed }
        p.seekTo(1_000L)
        assertEquals(VideoPlayerState.Paused(5_000L, 1_000L), p.stateFlow.value, "Completed becomes Paused")
        p.seekTo(Long.MAX_VALUE)
        assertEquals(VideoPlayerState.Paused(5_000L, 5_000L), p.stateFlow.value, "clamped to the duration")
        p.seekTo(-5L)
        assertEquals(VideoPlayerState.Paused(5_000L, 0L), p.stateFlow.value, "clamped to zero")
        p.release()
    }

    @Test
    fun `stop leaves ready at zero with the source still loaded`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        p.play()
        engine.position = 2_000L
        engine.calls.clear()
        p.stop()
        assertEquals(VideoPlayerState.Ready(5_000L), p.stateFlow.value)
        assertEquals(0L, p.playbackPositionFlow.value)
        assertEquals(listOf("pause", "seekTo(0)"), engine.calls.toList())
        assertFalse("release" in engine.calls, "the source stays loaded")
        p.play()
        assertIs<VideoPlayerState.Playing>(p.stateFlow.value)
        p.release()
    }

    @Test
    fun `a completion reported from inside start is applied right after the call and one after a rewind is not stale`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        engine.inStart = { it.sink!!.onCompleted() }
        p.play()
        // "An engine event that arrives while a call is running is applied right after it."
        awaitSettled(p) { it is VideoPlayerState.Completed }
        assertEquals(VideoPlayerState.Completed(5_000L), p.stateFlow.value)
        // From Completed a play rewinds first; a completion the engine reports from inside start() comes after the rewind.
        p.play()
        awaitSettled(p) { it is VideoPlayerState.Completed }
        assertEquals(VideoPlayerState.Completed(5_000L), p.stateFlow.value)
        assertEquals(emptyList(), engine.violations)
        p.release()
    }

    @Test
    fun `an engine that reports from another thread while it blocks inside its own calls never deadlocks the player`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        val lock = Any()
        engine.inStart = { e ->
            synchronized(lock) {
                val helper = Thread { e.sink!!.onBufferingChanged(true); e.sink!!.onVideoSizeChanged(VideoSize(2, 2)) }
                helper.start()
                helper.join(5_000L)
                check(!helper.isAlive) { "the player blocked an engine callback made from another thread" }
            }
        }
        engine.duringCall = { name -> if (name == "pause") { val t = Thread { engine.sink?.onBufferingChanged(false) }; t.start(); t.join(5_000L); check(!t.isAlive) { "blocked in pause" } } }
        repeat(20) { p.play(); p.pause() }
        awaitSettled(p) { it is VideoPlayerState.Paused }
        assertEquals(VideoSize(2, 2), p.videoSizeFlow.value)
        assertEquals(emptyList(), engine.violations)
        p.release()
    }

    @Test
    fun `a calling thread sees its own transition applied while other threads report events`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine)
        p.prepare(source)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val reporters: List<Thread> = (0 until 3).map { i: Int ->
            Thread {
                val rnd = Random(i)
                while (!stop.get()) {
                    engine.sink?.onBufferingChanged(rnd.nextBoolean())
                    engine.sink?.onVideoSizeChanged(if (rnd.nextBoolean()) VideoSize(rnd.nextInt(1, 9), rnd.nextInt(1, 9)) else null)
                    engine.durationMs = if (rnd.nextInt(4) == 0) 0L else 5_000L
                }
            }.apply { isDaemon = true; start() }
        }
        try {
            val rnd = Random(9)
            var kind = "Ready"
            repeat(4_000) { i: Int ->
                when (rnd.nextInt(7)) {
                    0 -> { p.play(); kind = "Playing" }
                    1 -> { p.pause(); if (kind == "Playing") kind = "Paused" }
                    2 -> { p.stop(); kind = "Ready" }
                    3 -> { p.replay(); kind = "Playing" }
                    4 -> p.seekTo(rnd.nextLong(0L, 5_000L))
                    5 -> p.setPlaybackSpeed(rnd.nextInt(1, 4).toFloat())
                    else -> p.setVolume(rnd.nextInt(0, 11) / 10f)
                }
                val state: VideoPlayerState = p.stateFlow.value
                val actual: String = state::class.simpleName!!
                assertEquals(kind, actual, "step $i: after the call returned, the state is the call's own result (got $state)")
            }
        } finally {
            stop.set(true)
            reporters.forEach { it.join(5_000L) }
        }
        assertEquals(emptyList(), engine.violations)
        p.release()
    }

    @Test
    fun `release racing every call from many threads disposes once and touches the engine after dispose never`() = bounded {
        repeat(60) { round: Int ->
            val engine = StrictEngine()
            val p: VideoPlayer = player(engine)
            p.prepare(source)
            val go = CountDownLatch(1)
            val threads: List<Thread> = (0 until 8).map { t: Int ->
                Thread {
                    val rnd = Random(round * 31 + t)
                    go.await()
                    repeat(300) {
                        when (rnd.nextInt(12)) {
                            0 -> p.play(); 1 -> p.pause(); 2 -> p.stop(); 3 -> p.replay()
                            4 -> p.seekTo(rnd.nextLong(0L, 6_000L)); 5 -> p.seekForward(100L); 6 -> p.seekBackward(100L)
                            7 -> p.setPlaybackSpeed(2f); 8 -> p.setVolume(0.5f); 9 -> p.setMuted(rnd.nextBoolean())
                            10 -> p.setRepeatMode(if (rnd.nextBoolean()) RepeatMode.One else RepeatMode.Off)
                            else -> if (t < 3) p.release() else p.unload()
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
            go.countDown()
            Thread.sleep(Random(round).nextLong(0L, 5L))
            p.release()
            val returnedAt: Int = engine.calls.size
            threads.forEach { it.join(20_000L); check(!it.isAlive) { "a caller thread hung (round $round)" } }
            assertEquals(1, engine.disposeCount.get(), "round $round: dispose exactly once")
            assertEquals(emptyList(), engine.violations, "round $round")
            assertTrue(engine.calls.last() == "dispose" || engine.calls.lastIndexOf("dispose") == engine.calls.size - 1, "round $round: nothing after dispose: ${engine.calls.takeLast(5)}")
            assertTrue(returnedAt <= engine.calls.size)
        }
    }

    @Test
    fun `a replaced prepare returns normally without writing state and the newer one wins`() = bounded {
        val engine = StrictEngine()
        val gate = CountDownLatch(1)
        val first = AtomicReference<Throwable?>(null)
        engine.onLoad = { if (engine.loadCount.get() == 1) { gate.await(10, TimeUnit.SECONDS) } }
        val p: VideoPlayer = player(engine)
        val firstPrepare = Thread { try { runBlocking { p.prepare(VideoSource.File("one.mp4")) } } catch (t: Throwable) { first.set(t) } }
        firstPrepare.start()
        val deadline: Long = System.nanoTime() + 5_000_000_000L
        while (engine.loadCount.get() < 1 && System.nanoTime() < deadline) Thread.sleep(1L)
        // onLoad blocks a thread, not a coroutine: the second prepare cancels the first, which can only unwind once released.
        val second = Thread { runBlocking { p.prepare(VideoSource.File("two.mp4")) } }
        second.start()
        Thread.sleep(50L)
        gate.countDown()
        firstPrepare.join(10_000L); second.join(10_000L)
        assertFalse(firstPrepare.isAlive || second.isAlive)
        assertNull(first.get(), "the replaced prepare returns normally")
        assertIs<VideoPlayerState.Ready>(p.stateFlow.value)
        assertNotNull(engine.sink)
        p.release()
    }

    @Test
    fun `a cancelled prepare frees the partly loaded source returns to idle and propagates the cancellation`() = bounded {
        val engine = StrictEngine()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.loadSuspend = { gate.await() }
        val p: VideoPlayer = player(engine)
        val job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) { p.prepare(source) }
        while (engine.loadCount.get() < 1) Thread.sleep(1L)
        assertEquals(VideoPlayerState.Preparing, p.stateFlow.value)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled, "the cancellation propagates to the caller")
        assertEquals(VideoPlayerState.Idle, p.stateFlow.value)
        assertTrue("release" in engine.calls, "the partly loaded source is freed: ${engine.calls}")
        assertEquals(emptyList(), engine.violations)
        p.release()
    }

    @Test
    fun `a newer prepare starts its load only after the older load has fully unwound`() = bounded {
        val engine = StrictEngine()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.loadSuspend = { n: Int -> if (n == 1) { try { gate.await() } finally { Thread.sleep(40L) } } }
        val p: VideoPlayer = player(engine)
        val first = kotlinx.coroutines.GlobalScope.async(Dispatchers.Default) { p.prepare(VideoSource.File("one")) }
        while (engine.loadCount.get() < 1) Thread.sleep(1L)
        val second = kotlinx.coroutines.GlobalScope.async(Dispatchers.Default) { p.prepare(VideoSource.File("two")) }
        first.await()
        second.await()
        val unwound: Int = engine.calls.indexOf("load-end-1")
        val startedSecond: Int = engine.calls.indexOf("load-start-2")
        assertTrue(unwound in 0 until startedSecond, "the second load began before the first had unwound: ${engine.calls}")
        assertIs<VideoPlayerState.Ready>(p.stateFlow.value)
        p.release()
    }

    @Test
    fun `an engine that throws while a load is published leaves an error and a player that can prepare again`() = bounded {
        for (method: String in listOf("setVolume(1.0)", "setSpeed(1.0)", "setLooping(false)")) {
            val engine = StrictEngine()
            val p: VideoPlayer = player(engine)
            engine.throwOn = setOf(method)
            p.prepare(source)
            val state: VideoPlayerState = p.stateFlow.value
            assertIs<VideoPlayerState.Error>(state, "$method threw during the publish of the load: $state")
            engine.throwOn = emptySet()
            p.prepare(source)
            assertIs<VideoPlayerState.Ready>(p.stateFlow.value, "after $method threw once")
            p.release()
            assertEquals(1, engine.disposeCount.get())
        }
    }

    @Test
    fun `the position flow follows a playing engine and holds while paused and after release`() = bounded {
        val engine = StrictEngine()
        val p: VideoPlayer = player(engine, VideoPlayerConfig(positionUpdateIntervalMs = 20L))
        p.prepare(source)
        p.play()
        engine.position = 1_234L
        val until: Long = System.nanoTime() + 3_000_000_000L
        while (p.playbackPositionFlow.value != 1_234L && System.nanoTime() < until) Thread.sleep(5L)
        assertEquals(1_234L, p.playbackPositionFlow.value, "refreshed within a few intervals while Playing")
        assertEquals(VideoPlayerState.Playing(5_000L, 1_234L), p.stateFlow.value)
        p.pause()
        engine.position = 4_000L
        Thread.sleep(200L)
        assertEquals(1_234L, p.playbackPositionFlow.value, "no polling while Paused")
        assertEquals(VideoPlayerState.Paused(5_000L, 1_234L), p.stateFlow.value)
        p.play()
        p.release()
        engine.position = 2_000L
        Thread.sleep(150L)
        assertEquals(0L, p.playbackPositionFlow.value, "zero after release, and no polling")
    }

    private fun awaitSettled(p: VideoPlayer, condition: (VideoPlayerState) -> Boolean) {
        val deadline: Long = System.nanoTime() + 5_000_000_000L
        while (!condition(p.stateFlow.value) && System.nanoTime() < deadline) Thread.sleep(2L)
        assertTrue(condition(p.stateFlow.value), "state never settled: ${p.stateFlow.value}")
    }
}
