package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Round 5 (lifecycle chaos + starvation + concurrency storm), core contract: what completion does to
 * the playhead and the length, and what a source that is still in the engine can say to the next
 * prepare.
 */
class VideoPlayerRound5AdversarialTest {

    private val source: VideoSource = VideoSource.Asset("clip.mp4")

    private fun playerTest(
        engine: RecordingVideoPlaybackEngine = RecordingVideoPlaybackEngine(),
        body: suspend TestScope.(RecordingVideoPlaybackEngine, VideoPlayer) -> Unit,
    ): TestResult = runTest {
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            body(engine, player)
        } finally {
            player.release()
        }
    }

    // --- Completion on a source whose length is unknown ---

    @Test
    fun `completion of a source of unknown length does not throw the playhead back to zero`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 0L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.position = 45_000L
        engine.listener!!.onCompleted()
        assertIs<VideoPlayerState.Completed>(player.stateFlow.value)
        assertNotEquals(
            0L,
            player.playbackPositionFlow.value,
            "a live stream that played 45 s completed with the playhead at ${player.playbackPositionFlow.value}",
        )
    }

    @Test
    fun `a length known while playing is not lost when the engine reports zero at the end`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        assertEquals(10_000L, player.stateFlow.value.duration)
        engine.duration = 0L // some platforms drop the length when the item ends
        engine.position = 10_000L
        engine.listener!!.onCompleted()
        assertEquals(
            VideoPlayerState.Completed(10_000L),
            player.stateFlow.value,
            "the known length was replaced by ${player.stateFlow.value}",
        )
        assertEquals(10_000L, player.playbackPositionFlow.value)
    }

    @Test
    fun `control - a length first reported at the end is used by the completion`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 0L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.duration = 8_000L
        engine.position = 8_000L
        engine.listener!!.onCompleted()
        assertEquals(VideoPlayerState.Completed(8_000L), player.stateFlow.value)
        assertEquals(8_000L, player.playbackPositionFlow.value)
    }

    // --- A source the engine still holds, speaking to the next prepare ---

    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue: ArrayDeque<Runnable> = ArrayDeque()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    /** Hops threads inside load like Media3; speaks only while a source is held, like a real engine. */
    private class HoppingEngine(private val hop: CoroutineDispatcher) : VideoPlaybackEngine {
        var attached: VideoPlaybackEngineListener? = null
        var held: Boolean = false
        var releases: Int = 0
        override fun setListener(listener: VideoPlaybackEngineListener?) { attached = listener }
        override suspend fun load(source: VideoSource) {
            withContext(hop) { held = false }
            withContext(hop) { held = true }
        }
        fun fail(cause: Throwable) { if (held) attached?.onFailed(cause) }
        fun stall(value: Boolean) { if (held) attached?.onBufferingChanged(value) }
        override fun start() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setLooping(looping: Boolean) = Unit
        override fun durationMs(): Long = 10_000L
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L
        override fun release() { releases++; held = false }
    }

    private fun hoppingTest(body: suspend TestScope.(HoppingEngine, ManualDispatcher, VideoPlayer) -> Unit): TestResult = runTest {
        val hop = ManualDispatcher()
        val engine = HoppingEngine(hop)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            body(engine, hop, player)
        } finally {
            player.release()
        }
    }

    private suspend fun TestScope.prepareToReady(hop: ManualDispatcher, player: VideoPlayer) {
        val call = launch { player.prepare(VideoSource.Asset("a.mp4")) }
        repeat(4) {
            runCurrent()
            hop.runAll()
        }
        runCurrent()
        call.join()
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
    }

    @Test
    fun `a source that failed in playback is freed before the next prepare so it cannot fail that one too`() =
        hoppingTest { engine, hop, player ->
            prepareToReady(hop, player)
            player.play()
            engine.fail(IllegalStateException("A died"))
            assertIs<VideoPlayerState.Error>(player.stateFlow.value)
            val callB = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                player.prepare(VideoSource.Asset("b.mp4"))
            }
            // The engine still holds A's failed source in the window before B's load resets it.
            engine.fail(IllegalStateException("A died again"))
            repeat(4) {
                runCurrent()
                hop.runAll()
            }
            runCurrent()
            callB.join()
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "B was reported as ${player.stateFlow.value}")
        }

    @Test
    fun `a stall of a source that failed in playback is not shown for the next prepare`() =
        hoppingTest { engine, hop, player ->
            prepareToReady(hop, player)
            player.play()
            engine.fail(IllegalStateException("A died"))
            val callB = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                player.prepare(VideoSource.Asset("b.mp4"))
            }
            engine.stall(true)
            repeat(4) {
                runCurrent()
                hop.runAll()
            }
            runCurrent()
            callB.join()
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
            assertEquals(false, player.isBufferingFlow.value, "B shows A's stall")
        }

    /**
     * Fires [fire] from inside release(): an event the platform queued on another thread before it
     * was told to drop the source, which the player can only apply after the transition that called
     * release() has finished.
     */
    private class ReleaseEchoEngine(private val inner: RecordingVideoPlaybackEngine) : VideoPlaybackEngine by inner {
        var fire: ((VideoPlaybackEngineListener) -> Unit)? = null
        override fun release() {
            // Before the engine is released, so the report is not an event "after release".
            val action = fire
            fire = null
            if (action != null) inner.listener?.let(action)
            inner.release()
        }
    }

    @Test
    fun `a failure of the old source queued while the engine drops it does not fail the next prepare`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = ReleaseEchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(source)
            player.play()
            engine.fire = { it.onFailed(IllegalStateException("A died")) }
            player.prepare(VideoSource.Asset("b.mp4"))
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "B was reported as ${player.stateFlow.value}")
        } finally {
            player.release()
        }
    }

    @Test
    fun `a stall of the old source queued while the engine drops it is not shown for the next prepare`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = ReleaseEchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(source)
            player.play()
            engine.fire = { it.onBufferingChanged(true) }
            player.prepare(VideoSource.Asset("b.mp4"))
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
            assertEquals(false, player.isBufferingFlow.value, "B shows A's stall")
        } finally {
            player.release()
        }
    }

    @Test
    fun `a picture size of the old source queued while the engine drops it is not kept for the next prepare`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = ReleaseEchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(source)
            player.play()
            engine.fire = { it.onVideoSizeChanged(VideoSize(1920, 1080)) }
            player.prepare(VideoSource.Asset("b.mp4"))
            assertEquals(null, player.videoSizeFlow.value, "B shows A's picture size")
        } finally {
            player.release()
        }
    }

    @Test
    fun `control - unload drops the same queued failure`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = ReleaseEchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(source)
            player.play()
            engine.fire = { it.onFailed(IllegalStateException("A died")) }
            player.unload()
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            player.prepare(VideoSource.Asset("b.mp4"))
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
        } finally {
            player.release()
        }
    }

    // --- Engines that misbehave under resource pressure ---

    private class ThrowingReleaseEngine(private val inner: RecordingVideoPlaybackEngine) : VideoPlaybackEngine by inner {
        var throwOnRelease: Boolean = true
        override fun release() {
            inner.release()
            if (throwOnRelease) {
                throwOnRelease = false
                throw IllegalStateException("native handle already gone")
            }
        }
    }

    @Test
    fun `a release that fails inside the engine still disposes it and settles on Idle`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = ThrowingReleaseEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        player.prepare(source)
        player.play()
        runCatching { player.release() }
        assertEquals(1, inner.disposeCount, "release() left the engine undisposed after its release() threw")
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "release() left ${player.stateFlow.value}")
    }

    private class ThrowingSettingEngine(private val inner: RecordingVideoPlaybackEngine) : VideoPlaybackEngine by inner {
        var throwOnVolume: Boolean = true
        override fun setVolume(volume: Float) {
            if (throwOnVolume) {
                throwOnVolume = false
                throw IllegalStateException("audio session gone")
            }
            inner.setVolume(volume)
        }
    }

    @Test
    fun `a setting the engine refuses at the end of prepare does not leave the player Preparing forever`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = ThrowingSettingEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            runCatching { player.prepare(source) }
            assertNotEquals(
                VideoPlayerState.Preparing,
                player.stateFlow.value,
                "prepare ended (threw or not) and the player still says Preparing",
            )
            // And the player stays usable: the next prepare settles.
            player.prepare(VideoSource.Asset("again.mp4"))
            assertTrue(player.stateFlow.value is VideoPlayerState.Ready || player.stateFlow.value is VideoPlayerState.Error)
        } finally {
            runCatching { player.release() }
        }
    }

    @Test
    fun `replay after an unknown-length completion keeps the end as the length until the platform reports one`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 0L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.position = 45_000L
        engine.listener!!.onCompleted()
        assertEquals(VideoPlayerState.Completed(45_000L), player.stateFlow.value)
        assertEquals(45_000L, player.playbackPositionFlow.value)
        player.replay()
        assertEquals(VideoPlayerState.Playing(45_000L, 0L), player.stateFlow.value)
        engine.duration = 60_000L
        engine.position = 1_000L
        player.pause()
        assertEquals(VideoPlayerState.Paused(60_000L, 1_000L), player.stateFlow.value)
    }

    @Test
    fun `an unload whose engine release throws still settles on Idle and the next prepare completes`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = ThrowingReleaseEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(source)
            player.play()
            assertTrue(runCatching { player.unload() }.isFailure, "the engine's exception is rethrown after the cleanup")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            withTimeout(5_000L) { player.prepare(VideoSource.Asset("again.mp4")) }
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        } finally {
            runCatching { player.release() }
        }
    }

    @Test
    fun `a release that fails while the next prepare frees the engine settles that prepare on Error and the one after works`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = ThrowingReleaseEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(source)
            withTimeout(5_000L) { player.prepare(VideoSource.Asset("b.mp4")) }
            assertTrue(player.stateFlow.value is VideoPlayerState.Error, "state was ${player.stateFlow.value}")
            withTimeout(5_000L) { player.prepare(VideoSource.Asset("c.mp4")) }
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        } finally {
            runCatching { player.release() }
        }
    }
}
