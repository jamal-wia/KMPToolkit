package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A platform engine finishes its load on its own thread and the caller's `prepare` resumes on the
 * caller's dispatcher a moment later. A failure the engine reports in that moment — after the load
 * succeeded, before `prepare` published Ready — is an event of a loaded source ("Playback failed
 * after loading succeeded"), and the player must not lose it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerFailureBetweenLoadAndReadyAdversarialTest {

    private class HoppingEngine : VideoPlaybackEngine {
        var attached: VideoPlaybackEngineListener? = null
        val loadBodyDone: CountDownLatch = CountDownLatch(1)

        override fun setListener(listener: VideoPlaybackEngineListener?) {
            attached = listener
        }

        // Like the Media3 engine: the load runs on the engine's own thread, then the caller resumes.
        override suspend fun load(source: VideoSource) {
            withContext(Dispatchers.Default) { loadBodyDone.countDown() }
        }

        override fun start() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setLooping(looping: Boolean) = Unit
        override fun durationMs(): Long = 10_000L
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L
        override fun release() = Unit
    }

    @Test
    fun `a failure reported after the load succeeded and before prepare resumed is not lost`() {
        val scheduler = TestCoroutineScheduler()
        val callerDispatcher = StandardTestDispatcher(scheduler)
        val engine = HoppingEngine()
        val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = callerDispatcher)
        val cause = IllegalStateException("decoder failed right after the source became ready")
        val call: Job = CoroutineScope(callerDispatcher).launch { player.prepare(VideoSource.Remote("https://example.test/a.mp4")) }
        try {
            scheduler.runCurrent()
            if (!engine.loadBodyDone.await(5, TimeUnit.SECONDS)) fail("the engine's load did not run")
            // The load body is over; prepare has not resumed yet (the test dispatcher holds it).
            assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
            engine.attached?.onFailed(cause)
            scheduler.runCurrent()
            assertTrue(call.isCompleted, "prepare did not finish")
            val state: VideoPlayerState = player.stateFlow.value
            assertTrue(
                state is VideoPlayerState.Error && state.cause === cause,
                "a failure of the loaded source was lost: the player says $state",
            )
        } finally {
            call.cancel()
            player.release()
        }
    }

    @Test
    fun `a stall reported after the load succeeded and before prepare resumed is not lost`() {
        val scheduler = TestCoroutineScheduler()
        val callerDispatcher = StandardTestDispatcher(scheduler)
        val engine = HoppingEngine()
        val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = callerDispatcher)
        val call: Job = CoroutineScope(callerDispatcher).launch { player.prepare(VideoSource.Remote("https://example.test/a.mp4")) }
        try {
            scheduler.runCurrent()
            if (!engine.loadBodyDone.await(5, TimeUnit.SECONDS)) fail("the engine's load did not run")
            assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
            // The engine now waits for data (it reports each change once and will not repeat it).
            engine.attached?.onBufferingChanged(true)
            scheduler.runCurrent()
            assertTrue(call.isCompleted, "prepare did not finish")
            assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "state ${player.stateFlow.value}")
            assertTrue(player.isBufferingFlow.value, "the stall of the loaded source was lost: the player says it is not buffering")
        } finally {
            call.cancel()
            player.release()
        }
    }
}
