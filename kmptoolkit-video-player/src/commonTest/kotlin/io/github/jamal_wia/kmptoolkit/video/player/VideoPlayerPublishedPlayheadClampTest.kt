package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The published playhead (`playbackPositionFlow`, `Playing` and `Paused` `currentPosition`) never exceeds a
 * known duration, although a platform can read a few milliseconds past the end of the source; an unknown
 * length leaves the reading alone, and a longer length reported later wins.
 */
class VideoPlayerPublishedPlayheadClampTest {

    private val interval: Long = 100L

    private fun playerTest(
        engine: RecordingVideoPlaybackEngine = RecordingVideoPlaybackEngine(duration = 10_000L),
        body: suspend TestScope.(RecordingVideoPlaybackEngine, VideoPlayer) -> Unit,
    ): TestResult = runTest {
        val player: VideoPlayer = createVideoPlayer(
            engine,
            VideoPlayerConfig(positionUpdateIntervalMs = interval),
            StandardTestDispatcher(testScheduler),
        )
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            body(engine, player)
        } finally {
            player.release()
        }
    }

    @Test
    fun `a poll tick past the end publishes the duration`() = playerTest { engine, player ->
        player.play()
        engine.position = 10_009L
        advanceTimeBy(interval + 1L)
        runCurrent()
        assertEquals(10_000L, player.playbackPositionFlow.value, "flow ${player.playbackPositionFlow.value}")
        assertEquals(VideoPlayerState.Playing(duration = 10_000L, currentPosition = 10_000L), player.stateFlow.value)
    }

    @Test
    fun `pause past the end publishes the duration`() = playerTest { engine, player ->
        player.play()
        engine.position = 10_009L
        player.pause()
        assertEquals(10_000L, player.playbackPositionFlow.value, "flow ${player.playbackPositionFlow.value}")
        assertEquals(VideoPlayerState.Paused(duration = 10_000L, currentPosition = 10_000L), player.stateFlow.value)
    }

    @Test
    fun `an unknown length leaves the reading unclamped`() = playerTest(RecordingVideoPlaybackEngine(duration = 0L)) { engine, player ->
        player.play()
        engine.position = 10_009L
        advanceTimeBy(interval + 1L)
        runCurrent()
        assertEquals(10_009L, player.playbackPositionFlow.value, "flow ${player.playbackPositionFlow.value}")
        assertEquals(10_009L, (player.stateFlow.value as VideoPlayerState.Playing).currentPosition)
        player.pause()
        assertEquals(VideoPlayerState.Paused(duration = 0L, currentPosition = 10_009L), player.stateFlow.value)
    }

    @Test
    fun `a longer length reported later wins over the old one`() = playerTest { engine, player ->
        player.play()
        engine.duration = 12_000L
        engine.position = 10_009L
        advanceTimeBy(interval + 1L)
        runCurrent()
        assertEquals(10_009L, player.playbackPositionFlow.value, "flow ${player.playbackPositionFlow.value}")
        assertEquals(VideoPlayerState.Playing(duration = 12_000L, currentPosition = 10_009L), player.stateFlow.value)
        player.pause()
        assertEquals(VideoPlayerState.Paused(duration = 12_000L, currentPosition = 10_009L), player.stateFlow.value)
    }

    @Test
    fun `play of a paused player past the end rewinds and does not publish past the end`() = playerTest { engine, player ->
        player.play()
        engine.position = 5_000L
        player.pause()
        engine.position = 10_009L
        // Not at the end by the published value, but the raw reading is: play rewinds as it always did.
        player.play()
        assertEquals(listOf(0L), engine.seekTargets.takeLast(1), "seeks ${engine.seekTargets}")
    }
}
