package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 5 probes that the shared state machine answers as documented: a very long chain of replaced
 * prepares, unload racing a load and the next prepare, a poll at the shortest interval under churn.
 */
class VideoPlayerRound5ProbesAdversarialTest {

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

    /** An engine whose every load waits for [open], so a test decides when loads end. */
    private class GatedEngine : VideoPlaybackEngine {
        val open: CompletableDeferred<Unit> = CompletableDeferred()
        var attached: VideoPlaybackEngineListener? = null
        var active: Int = 0
        var maxActive: Int = 0
        var loads: Int = 0
        var releases: Int = 0
        var disposes: Int = 0
        override fun setListener(listener: VideoPlaybackEngineListener?) { attached = listener }
        override suspend fun load(source: VideoSource) {
            loads++
            active++
            maxActive = maxOf(maxActive, active)
            try {
                open.await()
            } finally {
                active--
            }
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
        override fun release() { releases++ }
        override fun dispose() { disposes++ }
    }

    @Test
    fun `five thousand prepares each replaced by the next unwind without a stack overflow and the last one wins`() = runTest {
        val engine = GatedEngine()
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            withTimeout(60_000) {
                val calls: MutableList<Job> = mutableListOf()
                repeat(CHAIN) { index: Int ->
                    calls += launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        player.prepare(VideoSource.Asset("clip-$index.mp4"))
                    }
                }
                engine.open.complete(Unit)
                calls.forEach { it.join() }
            }
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
            assertEquals(1, engine.maxActive, "two loads ran on the engine at once")
        } finally {
            player.release()
        }
        assertEquals(1, engine.disposes)
    }

    @Test
    fun `unload during a load followed at once by a prepare never loads under the unwinding load`() = runTest {
        val engine = GatedEngine()
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            val first = launch { player.prepare(VideoSource.Asset("a.mp4")) }
            runCurrent()
            player.unload()
            val second = launch { player.prepare(VideoSource.Asset("b.mp4")) }
            runCurrent()
            engine.open.complete(Unit)
            runCurrent()
            first.join()
            second.join()
            assertEquals(1, engine.maxActive, "the second load started while the first still unwound")
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
        } finally {
            player.release()
        }
    }

    @Test
    fun `a poll at the shortest interval survives rapid play and pause and leaves a consistent state`() = playerTest(
        config = VideoPlayerConfig(positionUpdateIntervalMs = 1L),
    ) { engine, player ->
        player.prepare(VideoSource.Asset("clip.mp4"))
        repeat(200) { index: Int ->
            player.play()
            engine.position = (index * 37L) % 10_000L
            advanceTimeBy(3L)
            if (index % 3 == 0) player.pause()
            advanceTimeBy(1L)
        }
        player.pause()
        val atPause: VideoPlayerState = player.stateFlow.value
        advanceTimeBy(50L)
        assertEquals(atPause, player.stateFlow.value, "a poll tick changed a paused player")
        assertTrue(atPause is VideoPlayerState.Paused)
    }

    private companion object {
        const val CHAIN: Int = 5_000
    }
}
