package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * D1 round 10 (breaker ij), Q5: sentences of 04-api-reference.md (flows, `VideoPlayerState`) that no earlier round
 * executed, each as one short test over the real player and the fake. A bare scheduler driven by hand; every player
 * is released on the exit path of its test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerDocsMatrixD1R10AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val scheduler = TestCoroutineScheduler()

    private fun withPlayer(engine: FakeVideoPlaybackEngine, body: (FakeVideoPlaybackEngine, VideoPlayer) -> Unit) {
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L), StandardTestDispatcher(scheduler))
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

    private fun tick() {
        scheduler.advanceTimeBy(150L)
        scheduler.runCurrent()
    }

    @Test
    fun `a source of unknown length that plays to its end completes with where it ended and that length counts as known`() {
        withPlayer(FakeVideoPlaybackEngine(0L)) { engine: FakeVideoPlaybackEngine, player: VideoPlayer ->
            player.prepareNow()
            player.play()
            engine.advancePositionTo(4_000L)
            tick()
            engine.completePlayback()
            assertEquals(VideoPlayerState.Completed(4_000L), player.stateFlow.value, "04: Completed(duration = end)")
            assertEquals(4_000L, player.playbackPositionFlow.value, "04: playbackPositionFlow left at the end")

            player.replay()
            assertEquals(VideoPlayerState.Playing(4_000L, 0L), player.stateFlow.value, "04: replay keeps the length that now counts as known")
            player.stop()
            assertEquals(VideoPlayerState.Ready(4_000L), player.stateFlow.value, "04: stop keeps it")
            player.seekTo(9_000L)
            assertEquals(4_000L, engine.seekTargets.last(), "04: an absolute seek clamps to 0..end")
            assertEquals(VideoPlayerState.Ready(4_000L), player.stateFlow.value)
        }
    }

    @Test
    fun `a source of unknown length that ends with the playhead still at zero completes with a length of zero`() {
        withPlayer(FakeVideoPlaybackEngine(0L)) { engine: FakeVideoPlaybackEngine, player: VideoPlayer ->
            player.prepareNow()
            player.play()
            engine.completePlayback()
            assertEquals(VideoPlayerState.Completed(0L), player.stateFlow.value, "04: if the end is still 0 it is Completed(0)")
            assertEquals(0L, player.playbackPositionFlow.value)
        }
    }

    @Test
    fun `a length learned later replaces zero at the next tick pause or seek and no state reverts a known length to zero`() {
        withPlayer(FakeVideoPlaybackEngine(0L)) { engine: FakeVideoPlaybackEngine, player: VideoPlayer ->
            player.prepareNow()
            assertEquals(VideoPlayerState.Ready(0L), player.stateFlow.value)
            engine.durationMs = 9_000L
            player.seekTo(100L)
            assertEquals(VideoPlayerState.Ready(9_000L), player.stateFlow.value, "04: a Ready player picks up a length learned since the load, at a seek")

            player.play()
            engine.durationMs = 0L // a flapping live stream
            tick()
            assertEquals(9_000L, (player.stateFlow.value as VideoPlayerState.Playing).duration, "04: once known, no state reverts it to 0 (tick)")
            player.pause()
            assertEquals(9_000L, (player.stateFlow.value as VideoPlayerState.Paused).duration, "04: ... (pause)")
            player.seekTo(500L)
            assertEquals(9_000L, (player.stateFlow.value as VideoPlayerState.Paused).duration, "04: ... (seek)")
            player.stop()
            assertEquals(VideoPlayerState.Ready(9_000L), player.stateFlow.value, "04: ... (stop)")

            player.prepareNow()
            assertEquals(VideoPlayerState.Ready(0L), player.stateFlow.value, "04: only a new prepare starts from the engine's value again")
        }
    }

    @Test
    fun `the buffered position is snapshotted when the load finishes and on pause and is zero after a failure an unload and a release`() {
        val engine = FakeVideoPlaybackEngine(60_000L).apply { suspendLoads = true }
        withPlayer(engine) { fake: FakeVideoPlaybackEngine, player: VideoPlayer ->
            val loading: Job = CoroutineScope(StandardTestDispatcher(scheduler)).launch { player.prepare(source) }
            scheduler.runCurrent()
            assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
            fake.bufferedPositionMs = 4_000L
            fake.finishLoad()
            scheduler.runCurrent()
            assertTrue(loading.isCompleted, "the load did not finish")
            assertEquals(4_000L, player.bufferedPositionFlow.value, "04: snapshotted when a load finishes")

            player.play()
            fake.bufferedPositionMs = 7_000L
            assertEquals(4_000L, player.bufferedPositionFlow.value, "no poll has run: the flow still shows the snapshot")
            player.pause()
            assertEquals(7_000L, player.bufferedPositionFlow.value, "04: snapshotted on pause()")

            fake.failPlayback(IllegalStateException("died"))
            assertTrue(player.stateFlow.value is VideoPlayerState.Error)
            assertEquals(0L, player.bufferedPositionFlow.value, "04: 0 after a playback failure")
        }
    }

    /** An engine that throws from the calls the contract says only `load` should throw from, when told to. */
    private class ThrowingEngine(val fake: FakeVideoPlaybackEngine) : io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine by fake {
        var throwOnSetVolume: Throwable? = null
        var throwOnRelease: Throwable? = null

        override fun setVolume(volume: Float) {
            throwOnSetVolume?.let { throw it }
            fake.setVolume(volume)
        }

        override fun release() {
            fake.release()
            throwOnRelease?.let { throw it }
        }
    }

    private fun withThrowing(body: (ThrowingEngine, VideoPlayer) -> Unit) {
        val engine = ThrowingEngine(FakeVideoPlaybackEngine(60_000L))
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L), StandardTestDispatcher(scheduler))
        try {
            body(engine, player)
        } finally {
            engine.throwOnRelease = null
            try { player.release() } catch (_: IllegalStateException) { /* the scripted failure of this test */ }
        }
    }

    @Test
    fun `04 if another method throws anyway prepare ends in Error with that Throwable and the next prepare works`() {
        withThrowing { engine: ThrowingEngine, player: VideoPlayer ->
            val boom = IllegalStateException("setVolume refused")
            engine.throwOnSetVolume = boom
            player.prepareNow()
            val state: VideoPlayerState = player.stateFlow.value
            assertTrue(state is VideoPlayerState.Error && state.cause === boom, "04: prepare ends in Error with the engine's own Throwable, was $state")
            assertEquals(1, engine.fake.releaseCount, "the source of the refused setting is freed")

            engine.throwOnSetVolume = null
            player.prepareNow()
            assertEquals(VideoPlayerState.Ready(60_000L), player.stateFlow.value, "the next prepare works")
        }
    }

    @Test
    fun `04 unload resets to Idle when the engine release throws then rethrows and the next prepare works`() {
        withThrowing { engine: ThrowingEngine, player: VideoPlayer ->
            player.prepareNow()
            player.play()
            val boom = IllegalStateException("release refused")
            engine.throwOnRelease = boom
            val thrown: Throwable? = try { player.unload(); null } catch (t: Throwable) { t }
            assertTrue(thrown === boom, "04: unload rethrows the engine's exception, was $thrown")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "04: unload resets to Idle even when the engine throws")
            assertEquals(0L, player.playbackPositionFlow.value)

            engine.throwOnRelease = null
            player.prepareNow()
            assertEquals(VideoPlayerState.Ready(60_000L), player.stateFlow.value, "04: the next prepare never waits on the failed unload")
        }
    }

    @Test
    fun `04 release disposes the engine and resets to Idle when the engine release throws then rethrows once and stays idempotent`() {
        withThrowing { engine: ThrowingEngine, player: VideoPlayer ->
            player.prepareNow()
            val boom = IllegalStateException("release refused")
            engine.throwOnRelease = boom
            val thrown: Throwable? = try { player.release(); null } catch (t: Throwable) { t }
            assertTrue(thrown === boom, "04: release rethrows the engine's exception, was $thrown")
            assertEquals(1, engine.fake.disposeCount, "04: release disposes the engine however its own calls go")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertTrue(!engine.fake.hasListener, "the player detached from the engine")

            player.release() // idempotent: no second throw, no second dispose
            assertEquals(1, engine.fake.disposeCount)
            player.prepareNow()
            assertTrue(player.stateFlow.value is VideoPlayerState.Error, "a released player reports instead of loading")
        }
    }
}
