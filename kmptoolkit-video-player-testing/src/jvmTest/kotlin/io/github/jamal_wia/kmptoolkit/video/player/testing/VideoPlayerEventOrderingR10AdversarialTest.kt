package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 10 (breaker bt), event ordering and exactly-once delivery as a consumer sees it: collectors registered from
 * inside a callback, collectors that start late or cancel themselves, a collector that releases or replays from
 * inside the Completed update, a stale listener reference kept by an engine thread after the release, and a
 * completion racing the transport calls from another thread (the engine and the state read at rest must agree).
 */
class VideoPlayerEventOrderingR10AdversarialTest {

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

    private class Capture : VideoPlaybackEngineListener {
        override fun onCompleted() = Unit
        override fun onFailed(cause: Throwable) = Unit
        override fun onBufferingChanged(isBuffering: Boolean) = Unit
        override fun onVideoSizeChanged(size: VideoSize?) = Unit
    }

    /** A fake engine that remembers the listener the player gave it, as an engine thread would. */
    private class Spy(val inner: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(durationMs = 4_000L)) :
        io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine by inner {
        @Volatile var kept: VideoPlaybackEngineListener? = null
        @Volatile var onRelease: (() -> Unit)? = null
        override fun release() {
            onRelease?.invoke()
            inner.release()
        }
        override fun setListener(listener: VideoPlaybackEngineListener?) {
            if (listener != null) kept = listener
            inner.setListener(listener)
        }
    }

    private fun newPlayer(engine: io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine): VideoPlayer =
        createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 3_600_000L), Dispatchers.Default)

    private fun VideoPlayer.prepareNow() = runBlocking { withTimeout(5_000L) { prepare(VideoSource.Asset("c.mp4")) } }

    @Test
    fun `a collector started inside a callback sees the current state first and then every later one in order`() {
        val fake = FakeVideoPlaybackEngine(durationMs = 4_000L)
        val player: VideoPlayer = newPlayer(fake)
        val inner: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
        val outer: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
        val started = AtomicInteger()
        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.stateFlow.collect { state: VideoPlayerState ->
                    outer += state
                    if (state is VideoPlayerState.Playing && started.getAndIncrement() == 0) {
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { inner += it } }
                    }
                }
            }
            player.prepareNow()
            player.play()
            fake.completePlayback()
            // Completed arrived after Playing: the late collector saw Playing (its first value) then Completed.
            assertEquals(listOf("Playing", "Completed"), inner.map { it::class.simpleName }, "inner: $inner outer: $outer")
            assertEquals(listOf("Idle", "Preparing", "Ready", "Playing", "Completed"), outer.map { it::class.simpleName }, "outer: $outer")
        } finally {
            scope.coroutineContext[Job]?.cancel()
            player.release()
        }
    }

    @Test
    fun `a collector that cancels itself in the Completed update stops there while another goes on`() {
        val fake = FakeVideoPlaybackEngine(durationMs = 4_000L)
        val player: VideoPlayer = newPlayer(fake)
        val mine: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
        val other: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
        val local = CoroutineScope(Dispatchers.Unconfined)
        try {
            local.launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { other += it } }
            lateinit var self: Job
            self = local.launch(start = CoroutineStart.UNDISPATCHED) {
                player.stateFlow.collect {
                    mine += it
                    if (it is VideoPlayerState.Completed) self.cancel()
                }
            }
            player.prepareNow()
            player.play()
            fake.completePlayback()
            player.replay()
            assertTrue(mine.last() is VideoPlayerState.Completed, "a cancelled collector kept receiving: $mine")
            assertEquals(1, mine.count { it is VideoPlayerState.Completed })
            assertTrue(other.last() is VideoPlayerState.Playing, "the other collector missed the replay: $other")
        } finally {
            local.cancel()
            player.release()
        }
    }

    @Test
    fun `a collector that replays from inside the Completed update gets Playing right after it and no second Completed`() {
        val fake = FakeVideoPlaybackEngine(durationMs = 4_000L)
        val player: VideoPlayer = newPlayer(fake)
        val seen: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
        val local = CoroutineScope(Dispatchers.Unconfined)
        val replays = AtomicInteger()
        try {
            local.launch(start = CoroutineStart.UNDISPATCHED) {
                player.stateFlow.collect {
                    seen += it
                    if (it is VideoPlayerState.Completed && replays.incrementAndGet() <= 3) player.replay()
                }
            }
            player.prepareNow()
            player.play()
            fake.completePlayback()
            assertEquals(listOf("Idle", "Preparing", "Ready", "Playing", "Completed", "Playing"), seen.map { it::class.simpleName }, "seen: $seen")
            assertTrue(fake.isPlaying)
            fake.completePlayback()
            assertEquals(2, replays.get())
            assertEquals(2, seen.count { it is VideoPlayerState.Completed })
        } finally {
            local.cancel()
            player.release()
        }
    }

    @Test
    fun `a collector that releases the player from inside the Completed update leaves one dispose and a final Idle`() {
        val fake = FakeVideoPlaybackEngine(durationMs = 4_000L)
        val player: VideoPlayer = newPlayer(fake)
        val seen: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
        val local = CoroutineScope(Dispatchers.Unconfined)
        try {
            local.launch(start = CoroutineStart.UNDISPATCHED) {
                player.stateFlow.collect {
                    seen += it
                    if (it is VideoPlayerState.Completed) player.release()
                }
            }
            player.prepareNow()
            player.play()
            fake.completePlayback()
            assertEquals(listOf("Idle", "Preparing", "Ready", "Playing", "Completed", "Idle"), seen.map { it::class.simpleName }, "seen: $seen")
            assertEquals(1, fake.disposeCount)
            assertEquals(1, fake.releaseCount)
            assertTrue(!fake.hasListener, "the listener must be detached")
        } finally {
            local.cancel()
            player.release()
        }
    }

    @Test
    fun `a listener reference kept by an engine thread changes nothing after the release`() {
        val spy = Spy()
        val player: VideoPlayer = newPlayer(spy)
        player.prepareNow()
        player.play()
        val listener: VideoPlaybackEngineListener = spy.kept ?: fail("the engine never got a listener")
        player.release()
        val seen: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
        val local = CoroutineScope(Dispatchers.Unconfined)
        local.launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { seen += it } }
        val bufferingBefore: Boolean = player.isBufferingFlow.value
        val sizeBefore: VideoSize? = player.videoSizeFlow.value
        listener.onCompleted()
        listener.onFailed(IllegalStateException("late failure"))
        listener.onBufferingChanged(true)
        listener.onVideoSizeChanged(VideoSize(3, 2))
        Thread.sleep(50L)
        local.cancel()
        assertEquals(listOf("Idle"), seen.map { it::class.simpleName }, "late reports changed the released player: $seen")
        assertEquals(bufferingBefore, player.isBufferingFlow.value)
        assertEquals(sizeBefore, player.videoSizeFlow.value)
        assertEquals(1, spy.inner.disposeCount)
    }

    @Test
    fun `a completion racing seeks from another thread leaves the player and the engine in agreement`() {
        val failures: MutableList<String> = mutableListOf()
        repeat(400) { trial: Int ->
            val fake = FakeVideoPlaybackEngine(durationMs = 4_000L)
            val player: VideoPlayer = newPlayer(fake)
            try {
                player.prepareNow()
                player.play()
                val go = CountDownLatch(1)
                val done = CountDownLatch(2)
                val target: Long = if (trial % 2 == 0) 1_000L else 4_000L
                Thread { go.await(); fake.completePlayback(); done.countDown() }.start()
                Thread { go.await(); player.seekTo(target); done.countDown() }.start()
                go.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS), "threads stuck")
                Thread.sleep(2L)
                val state: VideoPlayerState = player.stateFlow.value
                val engineRuns: Boolean = fake.isPlaying
                val describe: String = "trial $trial target=$target: $state engine playing=$engineRuns pos=${fake.positionMs}"
                when (state) {
                    is VideoPlayerState.Playing -> if (!engineRuns) failures += "Playing but the engine is stopped: $describe"
                    is VideoPlayerState.Completed, is VideoPlayerState.Paused -> if (engineRuns && state is VideoPlayerState.Completed) failures += "Completed but the engine plays: $describe"
                    else -> failures += "unexpected state: $describe"
                }
            } finally {
                player.release()
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `repeat One never emits Completed and a flip to Off emits it once for the next play-through`() {
        val fake = FakeVideoPlaybackEngine(durationMs = 4_000L)
        val player: VideoPlayer = newPlayer(fake)
        val seen: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
        val local = CoroutineScope(Dispatchers.Unconfined)
        try {
            local.launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { seen += it } }
            player.prepareNow()
            player.setRepeatMode(RepeatMode.One)
            player.play()
            repeat(5) { fake.completePlayback() }
            assertEquals(0, seen.count { it is VideoPlayerState.Completed })
            player.setRepeatMode(RepeatMode.Off)
            fake.completePlayback()
            assertEquals(1, seen.count { it is VideoPlayerState.Completed }, "$seen")
            fake.completePlayback()
            assertEquals(1, seen.count { it is VideoPlayerState.Completed }, "a second report of the same end: $seen")
        } finally {
            local.cancel()
            player.release()
        }
    }

    @Test
    fun `reports an engine makes from inside the release that drops a source never become the next prepare's outcome`() {
        for (report: String in listOf("failure", "completion", "buffering")) {
            val spy = Spy()
            val player: VideoPlayer = newPlayer(spy)
            try {
                player.prepareNow()
                player.play()
                val listener: VideoPlaybackEngineListener = spy.kept ?: fail("no listener")
                spy.onRelease = {
                    when (report) {
                        "failure" -> listener.onFailed(IllegalStateException("from the old source"))
                        "completion" -> listener.onCompleted()
                        else -> listener.onBufferingChanged(true)
                    }
                }
                player.prepareNow()
                spy.onRelease = null
                Thread.sleep(30L)
                assertEquals(VideoPlayerState.Ready(4_000L), player.stateFlow.value, "$report of the dropped source decided the next prepare")
                assertTrue(!player.isBufferingFlow.value, "$report: a stall of the dropped source is shown")
                // and the same from inside unload()
                player.play()
                spy.onRelease = {
                    when (report) {
                        "failure" -> listener.onFailed(IllegalStateException("from the old source"))
                        "completion" -> listener.onCompleted()
                        else -> listener.onBufferingChanged(true)
                    }
                }
                player.unload()
                spy.onRelease = null
                Thread.sleep(30L)
                assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "$report during unload")
                assertTrue(!player.isBufferingFlow.value, "$report during unload: buffering")
                player.prepareNow()
                assertEquals(VideoPlayerState.Ready(4_000L), player.stateFlow.value, "$report during unload leaked into the next prepare")
            } finally {
                player.release()
            }
        }
    }
}
