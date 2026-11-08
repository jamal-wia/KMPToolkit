package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A completion that the platform posted before a transport call, applied after it (the player queues
 * an event that arrives while a transition runs). Contract: "Playing -> end of source -> Completed";
 * a seek or pause the user made after the platform's report must not be undone by it, and a
 * completion must not start a paused player.
 */
class VideoPlayerRound5StaleCompletionAdversarialTest {

    /** Fires [fire] from inside the next seekTo or pause, as an event queued by another thread. */
    private class EchoEngine(private val inner: RecordingVideoPlaybackEngine) : VideoPlaybackEngine by inner {
        var fire: ((VideoPlaybackEngineListener) -> Unit)? = null
        private fun echo() {
            val action = fire
            fire = null
            if (action != null) inner.listener?.let(action)
        }
        override fun seekTo(positionMs: Long) {
            inner.seekTo(positionMs)
            echo()
        }
        override fun pause() {
            inner.pause()
            echo()
        }
    }

    @Test
    fun `a completion queued behind a user seek away from the end does not make the player Completed`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = EchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            player.play()
            inner.position = 9_990L
            engine.fire = { it.onCompleted() }
            player.seekTo(2_000L)
            assertEquals(
                VideoPlayerState.Playing(10_000L, 2_000L),
                player.stateFlow.value,
                "the stale completion replaced the seek: ${player.stateFlow.value}, engine playhead ${inner.position}",
            )
        } finally {
            player.release()
        }
    }

    @Test
    fun `a completion reported while paused with repeat one does not start playback`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = createVideoPlayer(inner, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            player.play()
            inner.position = 4_000L
            player.pause()
            player.setRepeatMode(RepeatMode.One)
            inner.listener!!.onCompleted()
            assertIs<VideoPlayerState.Paused>(player.stateFlow.value, "a paused player was started by a completion: ${player.stateFlow.value}")
            assertEquals(0, inner.started - 1, "the engine was started again")
        } finally {
            player.release()
        }
    }

    @Test
    fun `a completion reported from inside a seek onto the end completes the player`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = EchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            player.play()
            engine.fire = { it.onCompleted() }
            player.seekTo(10_000L)
            assertEquals(VideoPlayerState.Completed(10_000L), player.stateFlow.value)
        } finally {
            player.release()
        }
    }

    @Test
    fun `a completion reported while paused with repeat one rewinds and stays paused`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = createVideoPlayer(inner, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            player.play()
            inner.position = 4_000L
            player.pause()
            player.setRepeatMode(RepeatMode.One)
            inner.listener!!.onCompleted()
            assertEquals(VideoPlayerState.Paused(10_000L, 0L), player.stateFlow.value)
            assertEquals(0L, player.playbackPositionFlow.value)
            assertEquals(0L, inner.position)
        } finally {
            player.release()
        }
    }

    @Test
    fun `a completion reported while Ready with repeat one is ignored`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = createVideoPlayer(inner, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            player.setRepeatMode(RepeatMode.One)
            inner.listener!!.onCompleted()
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
            assertEquals(0, inner.started)
        } finally {
            player.release()
        }
    }
}
