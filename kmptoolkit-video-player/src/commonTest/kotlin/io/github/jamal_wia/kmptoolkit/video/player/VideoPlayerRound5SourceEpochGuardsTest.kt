package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards for the rule "a report of a source the player has told the engine to drop can never become
 * the next prepare's outcome" on the paths the breaker's tests do not reach: a release the player
 * defers until an unwinding load has finished, and a re-prepare after a source that failed.
 */
class VideoPlayerRound5SourceEpochGuardsTest {

    /** Fires [fire] from inside the next release(), before the engine drops its source. */
    private class ReleaseEchoEngine(private val inner: RecordingVideoPlaybackEngine) : VideoPlaybackEngine by inner {
        var fire: ((VideoPlaybackEngineListener) -> Unit)? = null
        override fun release() {
            val action = fire
            fire = null
            if (action != null) inner.listener?.let(action)
            inner.release()
        }
    }

    @Test
    fun `a report made inside the deferred release of an unloaded load does not fail the prepare that follows`() = runTest {
        val inner = RecordingVideoPlaybackEngine().apply {
            loadDelayMs = 1_000L
            unwindDelayMs = 500L
        }
        val engine = ReleaseEchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            val first = launch { player.prepare(VideoSource.Asset("a.mp4")) }
            runCurrent()
            assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
            engine.fire = { it.onFailed(IllegalStateException("A died")) }
            player.unload()
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            runCurrent() // the cancelled load is now unwinding (virtual time)
            inner.loadDelayMs = 0L
            inner.unwindDelayMs = 0L // the next load unwinds at once: Unconfined would delay in real time
            // Unconfined: resumes inline, inside the deferred release, when the unwinding load finishes.
            val second = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                player.prepare(VideoSource.Asset("b.mp4"))
            }
            advanceTimeBy(700L)
            runCurrent()
            withTimeout(5_000L) {
                first.join()
                second.join()
            }
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "B was reported as ${player.stateFlow.value}")
        } finally {
            player.release()
        }
    }

    @Test
    fun `after a failed load the next prepare frees the engine exactly once before loading and drops the failed source's report`() = runTest {
        val inner = RecordingVideoPlaybackEngine().apply { loadFailure = IllegalStateException("A refused") }
        val engine = ReleaseEchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(VideoSource.Asset("a.mp4"))
            assertEquals(true, player.stateFlow.value is VideoPlayerState.Error)
            assertEquals(0, inner.releaseCount)
            inner.loadFailure = null
            engine.fire = { it.onFailed(IllegalStateException("A died again")) }
            player.prepare(VideoSource.Asset("b.mp4"))
            assertEquals(1, inner.releaseCount, "the engine was not freed before the next load")
            assertEquals(
                listOf("load", "release", "load"),
                inner.calls.filter { it == "load" || it == "release" },
            )
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "B was reported as ${player.stateFlow.value}")
        } finally {
            player.release()
        }
    }
}
