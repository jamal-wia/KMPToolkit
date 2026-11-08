package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.Job
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
import kotlin.test.assertTrue

/** Odd numbers and odd moments for the settings and transport calls, checked at the engine seam. */
class VideoPlayerEdgeInputsAdversarialTest {

    private val source: VideoSource = VideoSource.Asset("clip.mp4")

    private fun playerTest(
        engine: RecordingVideoPlaybackEngine = RecordingVideoPlaybackEngine(),
        config: VideoPlayerConfig = VideoPlayerConfig(),
        body: suspend TestScope.(RecordingVideoPlaybackEngine, VideoPlayer) -> Unit,
    ): TestResult = runTest {
        val player: VideoPlayer = createVideoPlayer(engine, config, StandardTestDispatcher(testScheduler))
        try {
            body(engine, player)
        } finally {
            player.release()
        }
    }

    @Test
    fun `infinite and NaN speeds and volumes never reach the engine unclamped`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        for (speed in listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, Float.MAX_VALUE, -Float.MAX_VALUE, 0f, -0f, Float.MIN_VALUE)) {
            player.setPlaybackSpeed(speed)
        }
        for (volume in listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE)) {
            player.setVolume(volume)
        }
        assertTrue(engine.appliedSpeeds.all { it in 0.25f..3.0f }, "speeds ${engine.appliedSpeeds}")
        assertTrue(engine.appliedVolumes.all { it in 0f..1f }, "volumes ${engine.appliedVolumes}")
        assertTrue(player.playbackSpeedFlow.value in 0.25f..3.0f)
        assertTrue(player.volumeFlow.value in 0f..1f)
    }

    @Test
    fun `transport calls before a source is playable are ignored and cause no engine call`() = playerTest { engine, player ->
        player.play(); player.pause(); player.stop(); player.replay()
        player.seekTo(5L); player.seekForward(); player.seekBackward()
        assertEquals(emptyList(), engine.calls)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)

        engine.loadDelayMs = 100L
        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
        val callsDuringLoad: Int = engine.calls.size
        player.play(); player.pause(); player.stop(); player.replay()
        player.seekTo(5L); player.seekForward(); player.seekBackward()
        assertEquals(callsDuringLoad, engine.calls.size, "a transport call reached the engine while preparing")
        advanceTimeBy(200L)
        loading.join()
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        assertEquals(0, engine.started, "a play() made while preparing started the engine later")
    }

    @Test
    fun `a Job in the consumer context is not cancelled by release and use releases once`() = runTest {
        val consumerJob: Job = Job()
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), consumerJob + StandardTestDispatcher(testScheduler))
        player.prepare(source)
        player.play()
        advanceTimeBy(600L)
        player.release()
        assertTrue(consumerJob.isActive, "release() cancelled the consumer's own Job")
        consumerJob.cancel()

        val second = RecordingVideoPlaybackEngine()
        createVideoPlayer(second, VideoPlayerConfig(), StandardTestDispatcher(testScheduler)).use { it.prepare(source) }
        assertEquals(1, second.disposeCount)
        assertEquals(1, engine.disposeCount)
    }

    @Test
    fun `a settings change made while preparing lands on the loaded source and nothing is pushed before`() = playerTest { engine, player ->
        engine.loadDelayMs = 100L
        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        player.setPlaybackSpeed(2f)
        player.setVolume(0.3f)
        player.setMuted(true)
        player.setRepeatMode(RepeatMode.One)
        assertEquals(emptyList(), engine.appliedSpeeds)
        assertEquals(emptyList(), engine.appliedVolumes)
        advanceTimeBy(200L)
        loading.join()
        assertEquals(0f, engine.appliedVolume)
        assertEquals(2f, engine.appliedSpeed)
        assertTrue(engine.looping)
        player.setMuted(false)
        assertEquals(0.3f, engine.appliedVolume)
    }

    @Test
    fun `stop from completed and repeat one set on a completed player behave as documented`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        engine.listener!!.onCompleted()
        assertEquals(VideoPlayerState.Completed(10_000L), player.stateFlow.value)
        player.setRepeatMode(RepeatMode.One)
        assertIs<VideoPlayerState.Completed>(player.stateFlow.value)
        player.stop()
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        assertFalse(player.isBufferingFlow.value)
        player.play()
        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
    }
}
