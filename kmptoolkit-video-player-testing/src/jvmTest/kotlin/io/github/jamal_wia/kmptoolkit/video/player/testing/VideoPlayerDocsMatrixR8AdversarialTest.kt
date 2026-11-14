package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.DEFAULT_SEEK_AMOUNT_MS
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope

/**
 * D1 round 8 (breaker ht), Q3: the normative sentences of 03-guide.md and 06-testing.md that no test
 * named exactly, each as one short test over the real player and the fake engine. The scheduler is a
 * bare `TestCoroutineScheduler` driven by hand (no `runTest`, so a red assertion cannot turn into a
 * drain that never ends) and every player is released on the exit path of its test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerDocsMatrixR8AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val scheduler = TestCoroutineScheduler()

    private fun withPlayer(
        engine: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(60_000L),
        body: (FakeVideoPlaybackEngine, VideoPlayer) -> Unit,
    ) {
        val player: VideoPlayer = createVideoPlayer(
            engine,
            VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            StandardTestDispatcher(scheduler),
        )
        try {
            body(engine, player)
        } finally {
            player.release()
        }
    }

    private fun VideoPlayer.prepareNow() {
        val player: VideoPlayer = this
        runBlocking { player.prepare(source) }
    }

    // --- 03-guide, "Seeking and scrubbing" -----------------------------------------------------

    @Test
    fun `a negative amount moves seekForward backwards and seekBackward forwards`() {
        withPlayer { engine: FakeVideoPlaybackEngine, player: VideoPlayer ->
            player.prepareNow()
            player.seekTo(10_000L)

            player.seekBackward(-2_000L)
            assertEquals(12_000L, engine.seekTargets.last(), "a negative backward amount goes forward")
            assertEquals(12_000L, player.playbackPositionFlow.value)

            player.seekForward(-3_000L)
            assertEquals(9_000L, engine.seekTargets.last(), "a negative forward amount goes backward")
            assertEquals(9_000L, player.playbackPositionFlow.value)
        }
    }

    @Test
    fun `the default seek amount is ten seconds in both directions`() {
        assertEquals(10_000L, DEFAULT_SEEK_AMOUNT_MS)
        withPlayer { engine: FakeVideoPlaybackEngine, player: VideoPlayer ->
            player.prepareNow()
            player.seekTo(30_000L)

            player.seekForward()
            assertEquals(40_000L, engine.seekTargets.last())
            player.seekBackward()
            assertEquals(30_000L, engine.seekTargets.last())
            player.seekBackward()
            assertEquals(20_000L, engine.seekTargets.last())
        }
    }

    @Test
    fun `on a source of unknown length seekForward skips from the playhead and an absolute seek clamps to zero`() {
        withPlayer(FakeVideoPlaybackEngine(0L)) { engine: FakeVideoPlaybackEngine, player: VideoPlayer ->
            player.prepareNow()
            player.play()
            engine.advancePositionTo(4_000L)
            scheduler.advanceTimeBy(150L)
            scheduler.runCurrent()
            assertEquals(4_000L, player.playbackPositionFlow.value)

            player.seekForward(1_000L)
            assertEquals(5_000L, engine.seekTargets.last(), "a relative skip must not clamp to the unknown length 0")

            player.seekTo(500L)
            assertEquals(0L, engine.seekTargets.last(), "an absolute seek clamps to 0..0 on an unknown length")
        }
    }

    @Test
    fun `seeking a Ready player onto the end leaves it Ready and play starts over from zero`() {
        withPlayer { engine: FakeVideoPlaybackEngine, player: VideoPlayer ->
            player.prepareNow()
            player.seekTo(Long.MAX_VALUE)

            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
            assertEquals(60_000L, player.playbackPositionFlow.value)

            player.play()

            assertEquals(0L, engine.seekTargets.last(), "play() on the end must rewind the platform first")
            assertTrue(engine.isPlaying)
            assertEquals(0L, engine.positionMs)
            assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
        }
    }

    // --- 03-guide, "Every setting outlives the source" and "Buffering" -------------------------

    @Test
    fun `settings changed while Preparing reach the platform the moment the load lands`() {
        val engine = FakeVideoPlaybackEngine(60_000L).apply { suspendLoads = true }
        withPlayer(engine) { _: FakeVideoPlaybackEngine, player: VideoPlayer ->
            val scope = TestScope(StandardTestDispatcher(scheduler))
            val preparing: Job = scope.launch { player.prepare(source) }
            scheduler.runCurrent()
            assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)

            player.setPlaybackSpeed(2f)
            player.setVolume(0.5f)
            player.setMuted(true)
            player.setRepeatMode(RepeatMode.One)
            engine.finishLoad()
            scheduler.runCurrent()
            runBlocking { preparing.join() }

            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
            assertEquals(2f, engine.appliedSpeed)
            assertEquals(0f, engine.appliedVolume, "muted: the platform gets 0f")
            assertTrue(engine.isLooping)
            assertEquals(0.5f, player.volumeFlow.value, "volumeFlow keeps the level the mute replaced")
        }
    }

    @Test
    fun `a stall reported before Ready is published is not shown while Preparing and is kept and shown with Ready`() {
        val engine = FakeVideoPlaybackEngine(60_000L).apply { suspendLoads = true }
        withPlayer(engine) { _: FakeVideoPlaybackEngine, player: VideoPlayer ->
            val scope = TestScope(StandardTestDispatcher(scheduler))
            val preparing: Job = scope.launch { player.prepare(source) }
            scheduler.runCurrent()

            engine.reportBuffering(true)
            scheduler.runCurrent()
            assertFalse(player.isBufferingFlow.value, "waiting for data while loading is what Preparing already says")

            engine.finishLoad()
            scheduler.runCurrent()
            runBlocking { preparing.join() }
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
            assertTrue(player.isBufferingFlow.value, "04-api-reference: the stall is kept and shown with Ready")

            engine.reportBuffering(false)
            scheduler.runCurrent()
            assertFalse(player.isBufferingFlow.value)
        }
    }

    // --- 03-guide, "Handling errors", "Unloading a long-lived player", "Common mistakes" -------

    @Test
    fun `a failure after loading arrives as Error with the very Throwable and stops the poll`() {
        withPlayer { engine: FakeVideoPlaybackEngine, player: VideoPlayer ->
            player.prepareNow()
            player.play()
            val cause = IOException("the stream died")

            engine.failPlayback(cause)
            scheduler.runCurrent()

            val error: VideoPlayerState.Error = assertIs(player.stateFlow.value)
            assertSame(cause, error.cause)
            assertFalse(player.isBufferingFlow.value)
            engine.advancePositionTo(9_000L)
            scheduler.advanceTimeBy(1_000L)
            scheduler.runCurrent()
            assertEquals(0L, player.playbackPositionFlow.value, "polling stops entirely when the state is not Playing")
            player.play()
            assertSame(error, player.stateFlow.value, "play() in Error is silently ignored")
        }
    }

    @Test
    fun `unload drops a completion the platform posted for the old source`() {
        withPlayer { engine: FakeVideoPlaybackEngine, player: VideoPlayer ->
            player.prepareNow()
            player.play()
            player.unload()
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)

            engine.completePlayback()
            engine.reportBuffering(true)
            scheduler.advanceTimeBy(500L)
            scheduler.runCurrent()

            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "a stale completion must not leave Idle")
            assertFalse(player.isBufferingFlow.value)
        }
    }

    @Test
    fun `use closes the player on a normal exit and when the block throws`() {
        val normal = FakeVideoPlaybackEngine(60_000L)
        val player: VideoPlayer = createVideoPlayer(
            normal,
            VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            StandardTestDispatcher(scheduler),
        )
        player.use { it.prepareNow() }
        assertEquals(1, normal.disposeCount)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)

        val thrown = FakeVideoPlaybackEngine(60_000L)
        val second: VideoPlayer = createVideoPlayer(
            thrown,
            VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            StandardTestDispatcher(scheduler),
        )
        assertFailsWith<IllegalStateException> {
            second.use {
                it.prepareNow()
                it.play()
                throw IllegalStateException("the block failed")
            }
        }
        assertEquals(1, thrown.disposeCount, "close() still released the player")
        assertFalse(thrown.isPlaying)
    }

    // --- 06-testing, "Driving the fake": the accounting of releaseCount --------------------------

    @Test
    fun `releaseCount goes up once per replaced loaded failed or Error source an unload and the player's release`() {
        val engine = FakeVideoPlaybackEngine(60_000L)
        val player: VideoPlayer = createVideoPlayer(
            engine,
            VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            StandardTestDispatcher(scheduler),
        )
        try {
            player.prepareNow()
            assertEquals(0, engine.releaseCount, "a first prepare adds none")

            player.prepareNow()
            assertEquals(1, engine.releaseCount, "replacing a loaded source")

            val failure = IllegalStateException("403")
            engine.loadFailure = failure
            player.prepareNow()
            assertEquals(2, engine.releaseCount, "replacing a loaded source with one that then fails")
            assertSame(failure, assertIs<VideoPlayerState.Error>(player.stateFlow.value).cause)
            player.prepareNow()
            assertEquals(3, engine.releaseCount, "replacing a failed or Error source")
            assertSame(failure, assertIs<VideoPlayerState.Error>(player.stateFlow.value).cause, "the same cause every time")

            engine.loadFailure = null
            player.prepareNow()
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
            val beforeUnload: Int = engine.releaseCount
            player.unload()
            assertEquals(beforeUnload + 1, engine.releaseCount, "one per unload")
            assertEquals(0, engine.disposeCount, "an unload never disposes")
            assertTrue(engine.hasListener, "an unload keeps the listener")

            player.release()
            assertEquals(beforeUnload + 2, engine.releaseCount, "and one for the player's own release")
            assertEquals(1, engine.disposeCount)
            assertFalse(engine.hasListener)
        } finally {
            player.release()
        }
    }
}
