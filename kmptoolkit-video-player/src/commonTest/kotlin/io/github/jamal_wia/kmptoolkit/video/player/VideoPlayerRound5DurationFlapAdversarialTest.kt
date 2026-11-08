package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 04-api-reference: "a later `0` never overwrites a known length". The poll, pause and seek honour it;
 * every other transition that writes a length (play, stop, replay, the end of the source) must too.
 */
class VideoPlayerRound5DurationFlapAdversarialTest {

    private fun playerTest(body: suspend TestScope.(RecordingVideoPlaybackEngine, VideoPlayer) -> Unit): TestResult = runTest {
        val engine = RecordingVideoPlaybackEngine(duration = 10_000L)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            body(engine, player)
        } finally {
            player.release()
        }
    }

    @Test
    fun `play of a Ready player keeps the known length when the engine reports zero`() = playerTest { engine, player ->
        engine.duration = 0L
        player.play()
        assertEquals(10_000L, player.stateFlow.value.duration, "play wrote ${player.stateFlow.value}")
    }

    @Test
    fun `play of a Paused player keeps the known length when the engine reports zero`() = playerTest { engine, player ->
        player.play()
        player.pause()
        engine.duration = 0L
        player.play()
        assertEquals(10_000L, player.stateFlow.value.duration, "play wrote ${player.stateFlow.value}")
    }

    @Test
    fun `stop keeps the known length when the engine reports zero`() = playerTest { engine, player ->
        player.play()
        engine.duration = 0L
        player.stop()
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
    }

    @Test
    fun `replay of a Completed player keeps the known length when the engine reports zero`() = playerTest { engine, player ->
        player.play()
        engine.position = 10_000L
        engine.listener!!.onCompleted()
        engine.duration = 0L
        player.replay()
        assertEquals(10_000L, player.stateFlow.value.duration, "replay wrote ${player.stateFlow.value}")
    }

    @Test
    fun `the restart of a repeating source keeps the known length when the engine reports zero`() = playerTest { engine, player ->
        player.play()
        player.setRepeatMode(RepeatMode.One)
        engine.duration = 0L
        engine.listener!!.onCompleted()
        assertEquals(10_000L, player.stateFlow.value.duration, "the restart wrote ${player.stateFlow.value}")
    }

    @Test
    fun `control - pause and a poll tick keep the known length`() = playerTest { engine, player ->
        player.play()
        engine.duration = 0L
        player.pause()
        assertEquals(10_000L, player.stateFlow.value.duration)
    }

    @Test
    fun `an absolute seek of a Paused player is not clamped to zero when the engine reports zero`() = playerTest { engine, player ->
        player.play()
        player.pause()
        engine.duration = 0L
        player.seekTo(5_000L)
        assertEquals(VideoPlayerState.Paused(10_000L, 5_000L), player.stateFlow.value)
        assertEquals(listOf(5_000L), engine.seekTargets)
    }
}
