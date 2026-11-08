package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A load that finished in the engine but whose prepare was superseded before it could publish Ready:
 * the engine still holds that source. What it reports from then on describes a discarded source and
 * must not become the newer prepare's outcome (the engine contract: events describe a loaded source,
 * and a released engine is silent).
 */
class VideoPlayerSupersededLoadedSourceAdversarialTest {

    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue: ArrayDeque<Runnable> = ArrayDeque()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    /** Engine that hops threads inside load, as Media3 does, and is silent unless a source is loaded. */
    private class HoppingEngine(private val hop: CoroutineDispatcher) : VideoPlaybackEngine {
        private var attached: VideoPlaybackEngineListener? = null
        var loaded: Boolean = false
        override fun setListener(listener: VideoPlaybackEngineListener?) { attached = listener }
        override suspend fun load(source: VideoSource) {
            // Like Media3: the reset of the previous source happens on the engine's thread, a hop later.
            withContext(hop) { loaded = false }
            withContext(hop) { loaded = true }
        }
        fun report(cause: Throwable) {
            if (loaded) attached?.onFailed(cause)
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
        override fun release() { loaded = false }
    }

    @Test
    fun `a failure of a superseded source that was already loaded does not become the newer prepare's outcome`() = runTest {
        val hop = ManualDispatcher()
        val engine = HoppingEngine(hop)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            val callA = launch { player.prepare(VideoSource.Asset("a.mp4")) }
            runCurrent()
            hop.runAll()
            runCurrent()
            hop.runAll() // A's source is now loaded in the engine; A's prepare has not resumed yet.
            val callB = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                player.prepare(VideoSource.Asset("b.mp4"))
            }
            runCurrent() // A resumes, finds itself superseded; B starts its own load.
            // The engine still holds A's source and now reports that it died.
            engine.report(IllegalStateException("A died"))
            repeat(4) {
                hop.runAll()
                runCurrent()
            }
            callA.join()
            callB.join()

            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "B was reported as ${player.stateFlow.value}")
        } finally {
            player.release()
        }
    }

    @Test
    fun `control - without the stale failure the newer prepare settles on Ready`() = runTest {
        val hop = ManualDispatcher()
        val engine = HoppingEngine(hop)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            val callA = launch { player.prepare(VideoSource.Asset("a.mp4")) }
            runCurrent()
            hop.runAll()
            runCurrent()
            hop.runAll()
            val callB = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                player.prepare(VideoSource.Asset("b.mp4"))
            }
            repeat(4) {
                runCurrent()
                hop.runAll()
            }
            runCurrent()
            callA.join()
            callB.join()
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
        } finally {
            player.release()
        }
    }
}
