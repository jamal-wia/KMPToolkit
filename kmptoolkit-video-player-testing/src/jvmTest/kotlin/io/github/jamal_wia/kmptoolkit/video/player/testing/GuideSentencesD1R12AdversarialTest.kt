package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.duration
import io.github.jamal_wia.kmptoolkit.video.player.playbackPosition
import io.github.jamal_wia.kmptoolkit.video.player.progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * D1 round 12, Q3: sentences of `03-guide.md` and `06-testing.md` that no earlier test pinned, each written from the
 * sentence and not from the code, through the public player and the shipped fake only (virtual time, no sleep).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideSentencesD1R12AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")

    private fun playerTest(
        engine: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(durationMs = 30_000L),
        config: VideoPlayerConfig = VideoPlayerConfig(),
        body: suspend TestScope.(VideoPlayer, FakeVideoPlaybackEngine) -> Unit,
    ) = runTest {
        val player: VideoPlayer = createVideoPlayer(engine, config, StandardTestDispatcher(testScheduler))
        try {
            body(player, engine)
        } finally {
            player.release()
        }
    }

    private fun TestScope.tick() {
        advanceTimeBy(251L)
        runCurrent()
    }

    // --- 03 "Looping" ---------------------------------------------------------------------------------------------

    @Test
    fun `setting One on a Completed player leaves it Completed until the next play`() = playerTest { player, engine ->
        player.prepare(source)
        player.play()
        engine.completePlayback()
        assertEquals(VideoPlayerState.Completed(30_000L), player.stateFlow.value)
        player.setRepeatMode(RepeatMode.One)
        assertEquals(VideoPlayerState.Completed(30_000L), player.stateFlow.value)
        assertTrue(engine.isLooping, "the mode reaches the loaded source")
        player.play()
        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value, "play from Completed starts from 0")
    }

    @Test
    fun `with One the end of the source leaves the player Playing and the playhead back at zero`() = playerTest { player, engine ->
        player.setRepeatMode(RepeatMode.One)
        player.prepare(source)
        player.play()
        engine.advancePositionTo(29_000L)
        engine.completePlayback()
        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
        assertEquals(0L, engine.positionMs)
        tick()
        assertEquals(0L, player.playbackPositionFlow.value)
    }

    // --- 03 "Volume and mute", "Playback speed" ------------------------------------------------------------------------

    @Test
    fun `a NaN volume and a NaN speed are ignored and a mute keeps the volume flow`() = playerTest { player, engine ->
        player.setVolume(0.5f)
        player.setVolume(Float.NaN)
        assertEquals(0.5f, player.volumeFlow.value)
        player.setPlaybackSpeed(1.5f)
        player.setPlaybackSpeed(Float.NaN)
        assertEquals(1.5f, player.playbackSpeedFlow.value)
        player.prepare(source)
        player.setMuted(true)
        assertEquals(0.5f, player.volumeFlow.value, "volumeFlow still reads 0.5 while muted")
        assertEquals(0f, engine.appliedVolume)
        player.setMuted(false)
        assertEquals(0.5f, engine.appliedVolume)
    }

    @Test
    fun `a speed range that excludes one starts at its nearest bound and clamps every later value`() = playerTest(
        config = VideoPlayerConfig(minPlaybackSpeed = 2f, maxPlaybackSpeed = 3f),
    ) { player, _ ->
        assertEquals(2f, player.playbackSpeedFlow.value)
        assertEquals(2f..3f, player.playbackSpeedRange)
        player.setPlaybackSpeed(10f)
        assertEquals(3f, player.playbackSpeedFlow.value)
        player.setPlaybackSpeed(0.1f)
        assertEquals(2f, player.playbackSpeedFlow.value)
    }

    @Test
    fun `the rate reaches the platform only while playing on every play and when a source loads`() = playerTest { player, engine ->
        player.setPlaybackSpeed(2f)
        player.prepare(source)
        assertEquals(2f, engine.appliedSpeed, "pushed when the source loads")
        player.setPlaybackSpeed(1.5f)
        assertEquals(2f, engine.appliedSpeed, "not while Ready")
        player.play()
        assertEquals(1.5f, engine.appliedSpeed, "on play")
        player.pause()
        player.setPlaybackSpeed(0.5f)
        assertEquals(1.5f, engine.appliedSpeed, "not while Paused")
        player.play()
        assertEquals(0.5f, engine.appliedSpeed)
        player.setPlaybackSpeed(1.25f)
        assertEquals(1.25f, engine.appliedSpeed, "at once while Playing")
        player.unload()
        player.prepare(source)
        assertEquals(1.25f, engine.appliedSpeed, "when the next source loads")
    }

    // --- 03 "Every setting outlives the source", "The release contract" --------------------------------------------

    @Test
    fun `settings changed while Preparing are applied the moment the load lands`() = playerTest { player, engine ->
        engine.suspendLoads = true
        val loading = launch { player.prepare(source) }
        runCurrent()
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
        player.setVolume(0.3f)
        player.setMuted(true)
        player.setPlaybackSpeed(2f)
        player.setRepeatMode(RepeatMode.One)
        engine.finishLoad()
        loading.join()
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
        assertEquals(0f, engine.appliedVolume)
        assertEquals(2f, engine.appliedSpeed)
        assertTrue(engine.isLooping)
        player.setMuted(false)
        assertEquals(0.3f, engine.appliedVolume)
    }

    @Test
    fun `unload and a failed load keep every setting and the next load receives them`() = playerTest { player, engine ->
        player.setVolume(0.4f)
        player.setMuted(true)
        player.setPlaybackSpeed(2f)
        player.setRepeatMode(RepeatMode.One)
        player.prepare(source)
        player.unload()
        engine.loadFailure = IllegalStateException("offline")
        player.prepare(source)
        assertIs<VideoPlayerState.Error>(player.stateFlow.value)
        engine.loadFailure = null
        player.prepare(source)
        assertEquals(0f, engine.appliedVolume)
        assertEquals(2f, engine.appliedSpeed)
        assertTrue(engine.isLooping)
        assertEquals(0.4f, player.volumeFlow.value)
        assertTrue(player.isMutedFlow.value)
        assertEquals(RepeatMode.One, player.repeatModeFlow.value)
    }

    @Test
    fun `settings still record their value after release but nothing reaches the platform`() = playerTest { player, engine ->
        player.prepare(source)
        player.release()
        val volume: Float = engine.appliedVolume
        val speed: Float = engine.appliedSpeed
        val looping: Boolean = engine.isLooping
        player.setVolume(0.2f)
        player.setMuted(true)
        player.setPlaybackSpeed(2f)
        player.setRepeatMode(RepeatMode.One)
        assertEquals(0.2f, player.volumeFlow.value)
        assertTrue(player.isMutedFlow.value)
        assertEquals(2f, player.playbackSpeedFlow.value)
        assertEquals(RepeatMode.One, player.repeatModeFlow.value)
        assertEquals(volume, engine.appliedVolume)
        assertEquals(speed, engine.appliedSpeed)
        assertEquals(looping, engine.isLooping)
    }

    @Test
    fun `prepare after release reports the refusal and close is release so use releases`() = runTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler))
        player.use { it.setVolume(0.5f) }
        assertEquals(1, engine.disposeCount, "use { } released the player")
        player.prepare(source)
        val state: VideoPlayerState = player.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertIs<VideoPlayerReleasedException>(state.cause)
        assertTrue(engine.loadedSources.isEmpty(), "nothing was loaded")
        player.play()
        player.seekTo(500L)
        assertTrue(engine.seekTargets.isEmpty() && !engine.isPlaying)
    }

    // --- 03 "State machine" --------------------------------------------------------------------------------------------

    @Test
    fun `a transport call in Idle Preparing or Error is silently ignored`() = playerTest { player, engine ->
        fun pokeAll() {
            player.play(); player.pause(); player.stop(); player.seekTo(5L); player.seekForward(5L); player.seekBackward(5L); player.replay()
        }
        pokeAll()
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        engine.suspendLoads = true
        val loading = launch { player.prepare(source) }
        runCurrent()
        pokeAll()
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
        engine.failLoad(IllegalStateException("x"))
        loading.join()
        val error: VideoPlayerState = player.stateFlow.value
        assertIs<VideoPlayerState.Error>(error)
        pokeAll()
        assertEquals(error, player.stateFlow.value)
        assertTrue(engine.seekTargets.isEmpty() && !engine.isPlaying, "nothing reached the engine: ${engine.seekTargets}")
    }

    @Test
    fun `stop from Playing Paused and Completed gives Ready at zero with the source still loaded`() = playerTest { player, engine ->
        player.prepare(source)
        player.play()
        engine.advancePositionTo(12_000L)
        tick()
        player.stop()
        assertEquals(VideoPlayerState.Ready(30_000L), player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        player.play()
        engine.advancePositionTo(5_000L)
        player.pause()
        player.stop()
        assertEquals(VideoPlayerState.Ready(30_000L), player.stateFlow.value)
        player.play()
        engine.completePlayback()
        player.stop()
        assertEquals(VideoPlayerState.Ready(30_000L), player.stateFlow.value)
        assertEquals(0L, engine.positionMs)
        assertEquals(1, engine.loadedSources.size, "never reloaded")
        assertEquals(0, engine.releaseCount, "the source is still loaded")
    }

    @Test
    fun `replay from every playable state plays from zero`() = playerTest { player, engine ->
        player.prepare(source)
        player.replay()
        assertEquals(VideoPlayerState.Playing(30_000L, 0L), player.stateFlow.value)
        engine.advancePositionTo(9_000L)
        tick()
        player.replay()
        assertEquals(VideoPlayerState.Playing(30_000L, 0L), player.stateFlow.value)
        player.pause()
        engine.advancePositionTo(4_000L)
        player.replay()
        assertEquals(VideoPlayerState.Playing(30_000L, 0L), player.stateFlow.value)
        engine.completePlayback()
        player.replay()
        assertEquals(VideoPlayerState.Playing(30_000L, 0L), player.stateFlow.value)
        assertEquals(0L, engine.positionMs)
    }

    @Test
    fun `seeking out of Completed lands in Paused and play from there resumes at the target`() = playerTest { player, engine ->
        player.prepare(source)
        player.play()
        engine.completePlayback()
        player.seekTo(7_000L)
        assertEquals(VideoPlayerState.Paused(30_000L, 7_000L), player.stateFlow.value)
        player.play()
        assertEquals(7_000L, engine.positionMs, "play resumes from the target, not from 0")
        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
    }

    @Test
    fun `seeking a Ready player moves the playhead and leaves it Ready and play starts from there`() = playerTest { player, engine ->
        player.prepare(source)
        player.seekTo(8_000L)
        assertEquals(VideoPlayerState.Ready(30_000L), player.stateFlow.value)
        assertEquals(8_000L, player.playbackPositionFlow.value)
        player.play()
        assertEquals(8_000L, engine.positionMs)
        assertEquals(VideoPlayerState.Playing(30_000L, 8_000L), player.stateFlow.value)
    }

    @Test
    fun `play with the playhead sitting on the end after a seek onto it starts over from zero`() = playerTest { player, engine ->
        player.prepare(source)
        player.play()
        player.pause()
        player.seekTo(Long.MAX_VALUE)
        assertEquals(30_000L, player.playbackPositionFlow.value)
        player.play()
        assertEquals(0L, engine.positionMs)
        assertEquals(VideoPlayerState.Playing(30_000L, 0L), player.stateFlow.value)
    }

    // --- 03 "Seeking and scrubbing" ----------------------------------------------------------------------------------

    @Test
    fun `seek targets are clamped and a negative relative amount moves the other way without overflow`() = playerTest { player, engine ->
        player.prepare(source)
        player.seekTo(-1L)
        assertEquals(0L, player.playbackPositionFlow.value)
        player.seekTo(Long.MAX_VALUE)
        assertEquals(30_000L, player.playbackPositionFlow.value)
        player.seekTo(10_000L)
        player.seekForward(-4_000L)
        assertEquals(6_000L, player.playbackPositionFlow.value, "a negative forward amount moves backward")
        player.seekBackward(-4_000L)
        assertEquals(10_000L, player.playbackPositionFlow.value, "a negative backward amount moves forward")
        player.seekBackward(Long.MAX_VALUE)
        assertEquals(0L, player.playbackPositionFlow.value)
        player.seekForward(Long.MAX_VALUE)
        assertEquals(30_000L, player.playbackPositionFlow.value)
        player.seekBackward(Long.MIN_VALUE)
        assertEquals(30_000L, player.playbackPositionFlow.value, "the most negative amount is a forward skip, not a wrapped one")
        player.seekForward(Long.MIN_VALUE)
        assertEquals(0L, player.playbackPositionFlow.value, "and a backward skip the other way")
        assertTrue(engine.seekTargets.all { it in 0L..30_000L }, "every target the engine saw is in range: ${engine.seekTargets}")
    }

    @Test
    fun `the default relative seek is ten seconds and a source of unknown length skips relative to the playhead`() = playerTest(
        engine = FakeVideoPlaybackEngine(durationMs = 0L),
    ) { player, engine ->
        player.prepare(source)
        player.seekTo(50_000L)
        assertEquals(0L, player.playbackPositionFlow.value, "an absolute seek on an unknown length still clamps to 0..0")
        engine.advancePositionTo(30_000L)
        player.seekForward(5_000L)
        assertEquals(35_000L, engine.seekTargets.last(), "relative to the playhead, not clamped to 0")
        player.seekBackward(5_000L)
        assertEquals(30_000L, engine.seekTargets.last())
    }

    // --- 03 "Buffering", "Picture size", "Handling errors" -------------------------------------------------------------

    @Test
    fun `a source of unknown length that plays to its end completes with the position where it ended`() = playerTest(
        engine = FakeVideoPlaybackEngine(durationMs = 0L),
    ) { player, engine ->
        player.prepare(source)
        player.play()
        engine.advancePositionTo(7_000L)
        engine.completePlayback()
        assertEquals(VideoPlayerState.Completed(7_000L), player.stateFlow.value)
    }

    @Test
    fun `buffering is false when idle completed or failed and a stall after the end is ignored`() = playerTest { player, engine ->
        player.prepare(source)
        player.play()
        engine.reportBuffering(true)
        assertTrue(player.isBufferingFlow.value, "a stall while Playing is shown, the state is still Playing")
        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
        engine.completePlayback()
        assertFalse(player.isBufferingFlow.value, "completed")
        engine.reportBuffering(true)
        assertFalse(player.isBufferingFlow.value, "a stall reported after the end is ignored")
        player.replay()
        engine.reportBuffering(true)
        engine.failPlayback(IllegalStateException("dies"))
        assertFalse(player.isBufferingFlow.value, "failed")
        player.prepare(source)
        player.play()
        engine.reportBuffering(true)
        player.unload()
        assertFalse(player.isBufferingFlow.value, "idle")
        engine.reportBuffering(true)
        assertFalse(player.isBufferingFlow.value, "a stall reported while idle is ignored")
    }

    @Test
    fun `a stall the engine reports before Ready is published is shown with Ready`() = playerTest { player, engine ->
        engine.suspendLoads = true
        val loading = launch { player.prepare(source) }
        runCurrent()
        engine.reportBuffering(true)
        assertFalse(player.isBufferingFlow.value, "not shown while Preparing")
        engine.finishLoad()
        loading.join()
        assertTrue(player.isBufferingFlow.value, "kept and shown with Ready")
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
    }

    @Test
    fun `the buffered position is polled while Playing only snapshotted on load and pause and zero when nothing is loaded`() = playerTest { player, engine ->
        player.prepare(source)
        assertEquals(0L, player.bufferedPositionFlow.value, "the fake resets its buffered position with a load")
        engine.bufferedPositionMs = 6_000L
        tick()
        assertEquals(0L, player.bufferedPositionFlow.value, "not polled while Ready")
        player.play()
        tick()
        assertEquals(6_000L, player.bufferedPositionFlow.value, "polled while Playing")
        engine.bufferedPositionMs = 7_000L
        player.pause()
        assertEquals(7_000L, player.bufferedPositionFlow.value, "snapshot on pause")
        engine.bufferedPositionMs = 8_000L
        tick()
        tick()
        assertEquals(7_000L, player.bufferedPositionFlow.value, "not polled while Paused")
        player.unload()
        assertEquals(0L, player.bufferedPositionFlow.value, "zero when nothing is loaded")
    }

    @Test
    fun `polling stops entirely whenever the state is not Playing`() = playerTest { player, engine ->
        player.prepare(source)
        player.play()
        engine.advancePositionTo(1_000L)
        tick()
        assertEquals(1_000L, player.playbackPositionFlow.value)
        player.pause()
        engine.advancePositionTo(9_000L)
        tick()
        assertEquals(1_000L, player.playbackPositionFlow.value, "Paused")
        player.play()
        engine.completePlayback()
        val ended: Long = player.playbackPositionFlow.value
        engine.advancePositionTo(2_000L)
        tick()
        assertEquals(ended, player.playbackPositionFlow.value, "Completed")
        player.stop()
        engine.advancePositionTo(4_000L)
        tick()
        assertEquals(0L, player.playbackPositionFlow.value, "Ready")
    }

    @Test
    fun `the picture size is null after unload a failure and a new source and survives Completed`() = playerTest { player, engine ->
        engine.videoSizeOnLoad = VideoSize(1920, 1080)
        player.prepare(source)
        assertEquals(VideoSize(1920, 1080), player.videoSizeFlow.value, "usually learned while preparing")
        player.play()
        engine.completePlayback()
        assertEquals(VideoSize(1920, 1080), player.videoSizeFlow.value, "survives Completed")
        player.unload()
        assertNull(player.videoSizeFlow.value, "unload")
        player.prepare(source)
        player.play()
        engine.failPlayback(IllegalStateException("dies"))
        assertNull(player.videoSizeFlow.value, "a failure")
        engine.suspendLoads = true
        engine.videoSizeOnLoad = null
        val loading = launch { player.prepare(source) }
        runCurrent()
        assertNull(player.videoSizeFlow.value, "a new source that starts preparing")
        engine.finishLoad()
        loading.join()
    }

    @Test
    fun `an Error cause can be a CancellationException the engine threw on its own and prepare returns normally`() = playerTest { player, engine ->
        val cause = CancellationException("engine timeout")
        engine.loadFailure = cause
        player.prepare(source)
        val state: VideoPlayerState = player.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertSame(cause, state.cause)
    }

    @Test
    fun `a failure reported while Preparing wins over Ready and the first of two is kept`() = playerTest { player, engine ->
        engine.suspendLoads = true
        val loading = launch { player.prepare(source) }
        runCurrent()
        val first = IllegalStateException("first")
        engine.finishLoad()
        engine.failPlayback(first)
        engine.failPlayback(IllegalStateException("second"))
        loading.join()
        val state: VideoPlayerState = player.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertSame(first, state.cause)
    }

    // --- 03 "Replacing a load in flight", "Cancellation", "Unloading" -----------------------------------------------

    @Test
    fun `a replaced prepare returns normally without writing state and the newer settles for its own source`() = playerTest { player, engine ->
        engine.suspendLoads = true
        val clip1 = VideoSource.Remote("https://example.test/1.mp4")
        val clip2 = VideoSource.Remote("https://example.test/2.mp4")
        var firstReturned = false
        val first = launch { player.prepare(clip1); firstReturned = true }
        runCurrent()
        val second = launch { player.prepare(clip2) }
        runCurrent()
        assertTrue(firstReturned, "the replaced call returns quietly")
        assertTrue(first.isCompleted && !first.isCancelled, "it returns normally, not cancelled")
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value, "and wrote no state of its own")
        engine.finishLoad()
        second.join()
        assertEquals(VideoPlayerState.Ready(30_000L), player.stateFlow.value)
        assertEquals(listOf(clip1, clip2), engine.loadedSources.toList())
    }

    @Test
    fun `a cancelled prepare frees the half loaded source returns to Idle not Error and propagates the cancellation`() = playerTest { player, engine ->
        engine.suspendLoads = true
        val loading = launch { player.prepare(source) }
        runCurrent()
        loading.cancel()
        loading.join()
        assertTrue(loading.isCancelled)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(1, engine.releaseCount, "one releaseCount, as 06 says")
        assertFalse(engine.isLoadPending)
        engine.suspendLoads = false
        player.prepare(source)
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
    }

    @Test
    fun `unload abandons a prepare in flight and that call returns without touching the state`() = playerTest { player, engine ->
        engine.suspendLoads = true
        var returned = false
        val loading = launch { player.prepare(source); returned = true }
        runCurrent()
        player.unload()
        runCurrent()
        assertTrue(returned && !loading.isCancelled, "prepare returned normally")
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0, engine.disposeCount, "unload never disposes")
        assertTrue(engine.hasListener, "unload keeps the listener")
        engine.suspendLoads = false
        player.prepare(source)
        assertEquals(VideoPlayerState.Ready(30_000L), player.stateFlow.value)
    }

    @Test
    fun `releaseCount goes up once per unload and once per replaced loaded source and a first prepare adds none`() = playerTest { player, engine ->
        player.prepare(source)
        assertEquals(0, engine.releaseCount, "a first prepare adds none")
        player.prepare(source)
        assertEquals(1, engine.releaseCount, "a prepare that replaces a loaded source")
        player.unload()
        assertEquals(2, engine.releaseCount, "an unload")
        assertEquals(0, engine.disposeCount, "never raised by an unload")
        player.release()
        assertEquals(3, engine.releaseCount, "the player's own release")
        assertEquals(1, engine.disposeCount)
        assertFalse(engine.hasListener)
        player.release()
        assertEquals(3, engine.releaseCount, "idempotent")
        assertEquals(1, engine.disposeCount)
    }

    @Test
    fun `the player releases the engine and then disposes it`() {
        val engine = ScriptedEngineD1R11()
        val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = StandardTestDispatcher())
        kotlinx.coroutines.runBlocking { player.prepare(source) }
        player.release()
        val tail: List<String> = engine.log.takeLast(3)
        assertEquals(listOf("setListener", "release", "dispose"), tail, "setListener(null), release, dispose, in that order")
        assertTrue(engine.callsAfterDispose().isEmpty())
    }

    @Test
    fun `after release no transport call throws and unload and the settings are no-ops`() = playerTest { player, _ ->
        player.release()
        player.play(); player.pause(); player.stop(); player.seekTo(1L); player.seekForward(1L); player.seekBackward(1L); player.replay()
        player.unload()
        player.setVolume(0.1f)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
    }

    // --- 04 "VideoPlayerState" ------------------------------------------------------------------------------------------

    @Test
    fun `the state extensions answer as the table says and progress is never NaN`() {
        val ready = VideoPlayerState.Ready(10_000L)
        assertEquals(null, ready.playbackPosition, "Ready has no position")
        assertEquals(10_000L, ready.duration)
        assertEquals(1_000L, VideoPlayerState.Playing(10_000L, 1_000L).playbackPosition)
        assertEquals(2_000L, VideoPlayerState.Paused(10_000L, 2_000L).playbackPosition)
        assertEquals(10_000L, VideoPlayerState.Completed(10_000L).playbackPosition, "Completed reports the duration")
        assertNull(VideoPlayerState.Idle.duration)
        assertNull(VideoPlayerState.Preparing.duration)
        assertNull(VideoPlayerState.Error(IllegalStateException()).duration)
        assertEquals(0f, VideoPlayerState.Playing(0L, 500L).progress, "a duration of 0 is 0f")
        assertEquals(0f, VideoPlayerState.Ready(10_000L).progress, "a missing position is 0f")
        assertEquals(0f, VideoPlayerState.Playing(-5L, 500L).progress, "a negative duration is 0f")
        assertEquals(1f, VideoPlayerState.Playing(1_000L, 5_000L).progress, "clamped to 1f")
        assertEquals(0f, VideoPlayerState.Paused(1_000L, -50L).progress, "clamped to 0f")
        assertEquals(1f, VideoPlayerState.Completed(7_000L).progress)
        assertFalse(VideoPlayerState.Playing(Long.MAX_VALUE, Long.MAX_VALUE).progress.isNaN())
        assertEquals(0.5f, VideoPlayerState.Paused(10_000L, 5_000L).progress)
    }

    @Test
    fun `a length learned later replaces zero at the next update and no state reverts a known length to zero`() = playerTest(
        engine = FakeVideoPlaybackEngine(durationMs = 0L),
    ) { player, engine ->
        player.prepare(source)
        assertEquals(VideoPlayerState.Ready(0L), player.stateFlow.value)
        player.play()
        engine.durationMs = 20_000L
        tick()
        assertEquals(VideoPlayerState.Playing(20_000L, 0L), player.stateFlow.value, "the next position update")
        engine.durationMs = 0L // a live stream flapping back to unknown
        engine.advancePositionTo(3_000L)
        tick()
        assertEquals(VideoPlayerState.Playing(20_000L, 3_000L), player.stateFlow.value, "the poll keeps the known length")
        player.pause()
        assertEquals(VideoPlayerState.Paused(20_000L, 3_000L), player.stateFlow.value, "pause keeps it")
        player.seekTo(4_000L)
        assertEquals(VideoPlayerState.Paused(20_000L, 4_000L), player.stateFlow.value, "a seek keeps it")
        player.stop()
        assertEquals(VideoPlayerState.Ready(20_000L), player.stateFlow.value, "stop keeps it")
        player.replay()
        assertEquals(VideoPlayerState.Playing(20_000L, 0L), player.stateFlow.value, "replay keeps it")
        assertEquals(0, engine.seekTargets.count { it > 20_000L })
    }

    @Test
    fun `a length found at the end counts as known for replay stop and an absolute seek`() = playerTest(
        engine = FakeVideoPlaybackEngine(durationMs = 0L),
    ) { player, engine ->
        player.prepare(source)
        player.play()
        engine.advancePositionTo(7_000L)
        engine.completePlayback()
        assertEquals(VideoPlayerState.Completed(7_000L), player.stateFlow.value)
        assertEquals(7_000L, player.playbackPositionFlow.value, "the playhead is left at the end")
        player.seekTo(50_000L)
        assertEquals(7_000L, player.playbackPositionFlow.value, "an absolute seek clamps to 0..end")
        assertEquals(VideoPlayerState.Paused(7_000L, 7_000L), player.stateFlow.value)
        player.stop()
        assertEquals(VideoPlayerState.Ready(7_000L), player.stateFlow.value, "stop keeps the length")
        player.replay()
        assertEquals(VideoPlayerState.Playing(7_000L, 0L), player.stateFlow.value, "replay keeps the length")
    }

    @Test
    fun `an unknown length with the playhead still at zero completes as Completed zero`() = playerTest(
        engine = FakeVideoPlaybackEngine(durationMs = 0L),
    ) { player, engine ->
        player.prepare(source)
        player.play()
        engine.completePlayback()
        assertEquals(VideoPlayerState.Completed(0L), player.stateFlow.value)
    }

    @Test
    fun `a completion reported while paused rewinds in One and stays paused and while Ready or Completed is ignored`() {
        val engine = ScriptedEngineD1R11()
        val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = StandardTestDispatcher())
        try {
            kotlinx.coroutines.runBlocking { player.prepare(source) }
            player.setRepeatMode(RepeatMode.One)
            val sink = checkNotNull(engine.sink)
            player.play()
            player.pause()
            player.seekTo(6_000L)
            sink.onCompleted()
            assertEquals(VideoPlayerState.Paused(10_000L, 0L), player.stateFlow.value, "rewound to 0 and stays paused")
            assertEquals(0L, engine.position)
            assertFalse(engine.playing)
            player.stop()
            sink.onCompleted()
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "ignored while Ready")
            // Completed is reachable only with Off; set One on it afterwards.
            player.setRepeatMode(RepeatMode.Off)
            player.play()
            engine.position = 10_000L
            engine.playing = false
            sink.onCompleted()
            assertEquals(VideoPlayerState.Completed(10_000L), player.stateFlow.value)
            player.setRepeatMode(RepeatMode.One)
            val before: Int = engine.log.size
            sink.onCompleted()
            assertEquals(VideoPlayerState.Completed(10_000L), player.stateFlow.value, "ignored while Completed")
            assertEquals(before, engine.log.size, "and nothing reached the engine")
        } finally {
            player.release()
        }
    }

    // --- 04 "VideoSource", "VideoSize", "VideoPlayerConfig", "VideoPlayerReleasedException" ----------------------------

    @Test
    fun `a remote source copies its headers and prints header names with the values redacted and the url as given`() {
        val headers: MutableMap<String, String> = mutableMapOf("Authorization" to "Bearer s3cret-token", "X-Trace" to "abc123")
        val remote = VideoSource.Remote("https://example.test/a.m3u8?sig=1", headers)
        headers["Authorization"] = "changed"
        headers["Extra"] = "added"
        assertEquals(mapOf("Authorization" to "Bearer s3cret-token", "X-Trace" to "abc123"), remote.headers, "copied, a later change has no effect")
        val text: String = remote.toString()
        assertTrue("Authorization" in text && "X-Trace" in text, "names are printed: $text")
        assertFalse("s3cret-token" in text || "abc123" in text || "changed" in text, "values are not: $text")
        assertTrue("<redacted>" in text, text)
        assertTrue("https://example.test/a.m3u8?sig=1" in text, "the url is printed as given: $text")
        assertEquals(VideoSource.Remote("https://example.test/x"), VideoSource.Remote("https://example.test/x"))
        assertEquals(VideoSource.Remote("https://example.test/x").hashCode(), VideoSource.Remote("https://example.test/x").hashCode())
        assertTrue(VideoSource.Remote("https://example.test/x") != VideoSource.Remote("https://example.test/x", mapOf("a" to "b")))
        assertTrue(VideoSource.Remote("https://example.test/x") != VideoSource.Remote("https://example.test/x", format = io.github.jamal_wia.kmptoolkit.video.player.RemoteFormat.Hls))
        assertTrue(VideoSource.File("/a.mp4") == VideoSource.File("/a.mp4") && VideoSource.Asset("a.mp4") == VideoSource.Asset("a.mp4"))
    }

    @Test
    fun `a picture size needs positive dimensions and the aspect ratio is width over height`() {
        assertFailsWith<IllegalArgumentException> { VideoSize(0, 10) }
        assertFailsWith<IllegalArgumentException> { VideoSize(10, 0) }
        assertFailsWith<IllegalArgumentException> { VideoSize(-1, 10) }
        assertFailsWith<IllegalArgumentException> { VideoSize(10, -1) }
        assertEquals(16f / 9f, VideoSize(1920, 1080).aspectRatio)
        assertEquals(9f / 16f, VideoSize(1080, 1920).aspectRatio)
    }

    @Test
    fun `the config refuses what its rules say and exposes its defaults`() {
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(positionUpdateIntervalMs = 0L) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(positionUpdateIntervalMs = -1L) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(minPlaybackSpeed = 0f) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(minPlaybackSpeed = -1f) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(minPlaybackSpeed = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(minPlaybackSpeed = Float.POSITIVE_INFINITY, maxPlaybackSpeed = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(maxPlaybackSpeed = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(maxPlaybackSpeed = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(minPlaybackSpeed = 2f, maxPlaybackSpeed = 1f) }
        VideoPlayerConfig(minPlaybackSpeed = 1f, maxPlaybackSpeed = 1f)
        val defaults = VideoPlayerConfig()
        assertEquals(VideoPlayerConfig.DEFAULT_POSITION_UPDATE_INTERVAL_MS, defaults.positionUpdateIntervalMs)
        assertEquals(VideoPlayerConfig.DEFAULT_MIN_PLAYBACK_SPEED, defaults.minPlaybackSpeed)
        assertEquals(VideoPlayerConfig.DEFAULT_MAX_PLAYBACK_SPEED, defaults.maxPlaybackSpeed)
        assertEquals(250L, defaults.positionUpdateIntervalMs)
        assertEquals(0.25f, defaults.minPlaybackSpeed)
        assertEquals(3.0f, defaults.maxPlaybackSpeed)
    }

    @Test
    fun `the released exception is an IllegalStateException and prepare after release never throws it`() = runTest {
        val player: VideoPlayer = createVideoPlayer(FakeVideoPlaybackEngine(), coroutineContext = StandardTestDispatcher(testScheduler))
        player.release()
        player.prepare(source)
        val state: VideoPlayerState = player.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertTrue(state.cause is IllegalStateException && state.cause is VideoPlayerReleasedException)
        player.prepare(source)
        assertEquals(state::class, player.stateFlow.value::class)
    }
}
