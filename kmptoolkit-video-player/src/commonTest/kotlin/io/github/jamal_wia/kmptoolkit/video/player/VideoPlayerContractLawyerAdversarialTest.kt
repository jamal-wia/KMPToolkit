package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every documented guarantee of the headless player, tested literally with edge inputs. Virtual time,
 * explicit advances only.
 */
class VideoPlayerContractLawyerAdversarialTest {

    private val source: VideoSource = VideoSource.Asset("clip.mp4")

    private fun playerTest(
        engine: RecordingVideoPlaybackEngine = RecordingVideoPlaybackEngine(),
        config: VideoPlayerConfig = VideoPlayerConfig(),
        body: suspend TestScope.(RecordingVideoPlaybackEngine, VideoPlayer) -> Unit,
    ): TestResult = runTest {
        val player: VideoPlayer = createVideoPlayer(
            engine = engine,
            config = config,
            coroutineContext = StandardTestDispatcher(testScheduler),
        )
        try {
            body(engine, player)
        } finally {
            player.release()
        }
    }

    @Test
    fun `buffering is false after a failure even when the engine reports a stall afterwards`() = playerTest { _, player ->
        player.prepare(source)
        player.play()
        player.onEngine { onFailed(IllegalStateException("boom")) }
        assertIs<VideoPlayerState.Error>(player.stateFlow.value)
        player.onEngine { onBufferingChanged(true) }
        assertFalse(player.isBufferingFlow.value)
        assertNull(player.videoSizeFlow.value)
    }

    @Test
    fun `extreme seek amounts always land inside zero to duration`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 10_000L),
    ) { engine, player ->
        player.prepare(source)
        val amounts: List<Long> = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        for (start in listOf(0L, 5_000L, 10_000L)) {
            for (amount in amounts) {
                engine.position = start
                player.seekForward(amount)
                player.seekBackward(amount)
            }
        }
        for (target in amounts) player.seekTo(target)
        assertTrue(engine.seekTargets.isNotEmpty())
        assertTrue(engine.seekTargets.all { it in 0L..10_000L }, "out of range: ${engine.seekTargets.filter { it !in 0L..10_000L }}")
        // seekTo(position - amount) with a huge negative amount is a huge forward step: the end.
        engine.position = 5_000L
        player.seekBackward(Long.MIN_VALUE)
        assertEquals(10_000L, engine.seekTargets.last())
        engine.position = 5_000L
        player.seekForward(Long.MIN_VALUE)
        assertEquals(0L, engine.seekTargets.last())
    }

    @Test
    fun `a released player ignores every call and never touches the engine again`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        player.release()
        val callsAtRelease: List<String> = engine.calls.toList()
        assertEquals(1, engine.disposeCount)

        player.play(); player.pause(); player.stop(); player.replay()
        player.seekTo(5L); player.seekForward(5L); player.seekBackward(5L)
        player.setPlaybackSpeed(2f); player.setVolume(0.2f); player.setMuted(true)
        player.setRepeatMode(RepeatMode.One)
        player.unload(); player.release(); player.close()
        player.prepare(source)
        player.prepare(VideoSource.File("/x"))
        engine.listener?.let { listener ->
            listener.onCompleted(); listener.onFailed(IllegalStateException())
            listener.onBufferingChanged(true); listener.onVideoSizeChanged(VideoSize(3, 4))
        }
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(callsAtRelease, engine.calls.toList(), "the engine was called after release")
        assertEquals(1, engine.disposeCount)
        assertIs<VideoPlayerState.Error>(player.stateFlow.value)
        assertIs<VideoPlayerReleasedException>((player.stateFlow.value as VideoPlayerState.Error).cause)
        assertEquals(2f, player.playbackSpeedFlow.value)
        assertEquals(0.2f, player.volumeFlow.value)
        assertTrue(player.isMutedFlow.value)
        assertEquals(RepeatMode.One, player.repeatModeFlow.value)
        assertFalse(player.isBufferingFlow.value)
        assertNull(player.videoSizeFlow.value)
    }

    @Test
    fun `a released player returns every flow to idle values`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        engine.listener?.onVideoSizeChanged(VideoSize(10, 10))
        engine.listener?.onBufferingChanged(true)
        engine.buffered = 4_000L
        engine.position = 3_000L
        advanceTimeBy(500L)
        runCurrent()
        player.release()
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        assertEquals(0L, player.bufferedPositionFlow.value)
        assertFalse(player.isBufferingFlow.value)
        assertNull(player.videoSizeFlow.value)
    }

    @Test
    fun `re-prepare while playing frees the old source resets every source flow and keeps the settings`() = playerTest { engine, player ->
        player.setVolume(0.4f)
        player.setMuted(true)
        player.setRepeatMode(RepeatMode.One)
        player.setPlaybackSpeed(1.5f)
        player.prepare(source)
        player.play()
        engine.listener?.onVideoSizeChanged(VideoSize(10, 10))
        engine.listener?.onBufferingChanged(true)
        engine.position = 4_000L
        engine.buffered = 6_000L
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(4_000L, player.playbackPositionFlow.value)

        engine.loadDelayMs = 100L
        val before: Int = engine.releaseCount
        val job = CoroutineScope(StandardTestDispatcher(testScheduler)).launch {
            player.prepare(VideoSource.Asset("next.mp4"))
        }
        runCurrent()
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
        assertEquals(before + 1, engine.releaseCount, "the old source must be freed before the new load")
        assertEquals(0L, player.playbackPositionFlow.value)
        assertEquals(0L, player.bufferedPositionFlow.value)
        assertFalse(player.isBufferingFlow.value)
        assertNull(player.videoSizeFlow.value)
        advanceTimeBy(50L)
        // polling must be off while preparing
        val callsMid: Int = engine.calls.size
        advanceTimeBy(40L)
        assertEquals(callsMid, engine.calls.size, "no polling while preparing")
        advanceTimeBy(200L)
        runCurrent()
        job.join()
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        assertEquals(0f, engine.appliedVolume)
        assertEquals(true, engine.looping)
        assertEquals(1.5f, engine.appliedSpeed)
        assertEquals(0.4f, player.volumeFlow.value)
    }

    private fun VideoPlayer.onEngine(event: VideoPlaybackEngineListener.() -> Unit) {
        val engine: VideoPlaybackEngine = (this as EngineVideoPlayer).engine
        (engine as RecordingVideoPlaybackEngine).listener!!.event()
    }
}
