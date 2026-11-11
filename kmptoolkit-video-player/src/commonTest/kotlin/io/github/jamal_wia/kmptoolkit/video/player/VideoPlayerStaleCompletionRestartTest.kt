package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * D2 round 5, S5 (ruling D-16): when the player drops a completion as stale while it is Playing, the
 * transport call that superseded it wins, and a platform that stops at its end does not resume by itself:
 * the player asserts the start again (start, then the speed, as play does). A player that is not Playing,
 * and a completion of a dropped source, start nothing.
 */
class VideoPlayerStaleCompletionRestartTest {

    /** Fires [fire] from inside the next seekTo, pause or release, as an event queued by another thread. */
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
        override fun release() {
            echo()
            inner.release()
        }
    }

    @Test
    fun `a completion dropped while playing asserts the start and the speed again`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = EchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            player.setPlaybackSpeed(1.5f)
            player.play()
            val startedBefore: Int = inner.started
            inner.position = 9_990L
            engine.fire = { it.onCompleted() }
            player.seekTo(2_000L)
            assertEquals(VideoPlayerState.Playing(10_000L, 2_000L), player.stateFlow.value)
            assertEquals(startedBefore + 1, inner.started, "the engine was not started again after the stale completion")
            assertEquals(1.5f, inner.appliedSpeed, "the speed was not applied again with the start")
            assertEquals(listOf("start", "start"), inner.calls.filter { it == "start" }, "start calls: ${inner.calls}")
        } finally {
            player.release()
        }
    }

    @Test
    fun `a completion dropped while paused starts nothing`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = EchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            player.play()
            player.pause()
            val startedBefore: Int = inner.started
            engine.fire = { it.onCompleted() }
            player.seekTo(2_000L)
            assertIs<VideoPlayerState.Paused>(player.stateFlow.value)
            assertEquals(startedBefore, inner.started, "a paused player was started by a stale completion")
        } finally {
            player.release()
        }
    }

    @Test
    fun `a completion stamped before a stop starts nothing`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = EchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            player.play()
            val startedBefore: Int = inner.started
            engine.fire = { it.onCompleted() }
            player.stop()
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
            assertEquals(startedBefore, inner.started, "a stopped player was started by a stale completion")
        } finally {
            player.release()
        }
    }

    @Test
    fun `a completion of a dropped source starts nothing`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        val engine = EchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("clip.mp4"))
            player.play()
            val startedBefore: Int = inner.started
            engine.fire = { it.onCompleted() }
            player.unload()
            player.prepare(VideoSource.Asset("other.mp4"))
            assertEquals(startedBefore, inner.started, "a completion of a dropped source started the engine")
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        } finally {
            player.release()
        }
    }
}
