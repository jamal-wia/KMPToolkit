package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A consumer that acts on `stateFlow` inline (an immediate dispatcher), for example a preview that
 * pauses or rewinds at 30 s, runs inside the position poll's own tick. What the tick still writes
 * after the state must not undo what the consumer just did: `playbackPositionFlow` agrees with the
 * state ("set directly by seeks ... the tick must only refine the Playing state it read").
 */
class VideoPlayerRound5PollReentrancyAdversarialTest {

    /** The inline collector; a StateFlow never completes, so each test cancels it or runTest would wait for it. */
    private var collector: Job? = null

    private suspend fun TestScope.scenario(react: (VideoPlayer, RecordingVideoPlaybackEngine) -> Unit): Pair<VideoPlayer, RecordingVideoPlaybackEngine> {
        val engine = RecordingVideoPlaybackEngine(duration = 120_000L)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 250L), StandardTestDispatcher(testScheduler))
        player.prepare(VideoSource.Asset("clip.mp4"))
        player.play()
        collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            player.stateFlow.collect { state ->
                if (state is VideoPlayerState.Playing && state.currentPosition >= 30_000L) react(player, engine)
            }
        }
        engine.position = 30_000L
        advanceTimeBy(300L)
        runCurrent()
        return player to engine
    }

    @Test
    fun `a consumer that pauses inline at 30 seconds leaves the position flow where the pause put it`() = runTest {
        val (player, engine) = scenario { p, e ->
            e.position = 30_050L // the playhead moved on while the consumer reacted
            p.pause()
        }
        try {
            val state: VideoPlayerState = player.stateFlow.value
            assertIs<VideoPlayerState.Paused>(state)
            assertEquals(30_050L, state.currentPosition)
            assertEquals(30_050L, player.playbackPositionFlow.value, "the tick put the stale playhead back over the pause")
        } finally {
            collector?.cancel()
            player.release()
        }
    }

    @Test
    fun `a consumer that rewinds inline at 30 seconds is not undone in the position flow`() = runTest {
        val (player, engine) = scenario { p, _ -> p.seekTo(0L) }
        try {
            assertEquals(0L, engine.position)
            val state: VideoPlayerState = player.stateFlow.value
            assertIs<VideoPlayerState.Playing>(state)
            assertEquals(0L, state.currentPosition)
            assertEquals(0L, player.playbackPositionFlow.value, "the tick put 30 s back over the rewind")
        } finally {
            collector?.cancel()
            player.release()
        }
    }
}
