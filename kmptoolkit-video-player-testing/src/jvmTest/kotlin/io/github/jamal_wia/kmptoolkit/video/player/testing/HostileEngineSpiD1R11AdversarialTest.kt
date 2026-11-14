package io.github.jamal_wia.kmptoolkit.video.player.testing


import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * D1 round 11 (breaker ip), Q1: a consumer-written [VideoPlaybackEngine] that breaks the SPI the ways a third party
 * engine will. Every test states the sentence of `04-api-reference.md` or of the [VideoPlaybackEngine] KDoc it checks.
 * A hand-driven scheduler (no thread, no sleep); every player is released on the exit path of its test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostileEngineSpiD1R11AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val scheduler = TestCoroutineScheduler()

    private fun withPlayer(
        engine: ScriptedEngineD1R11,
        context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        body: (VideoPlayer) -> Unit,
    ) {
        val player: VideoPlayer = createVideoPlayer(
            engine,
            VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            StandardTestDispatcher(scheduler) + context,
        )
        try {
            body(player)
        } finally {
            player.release()
        }
    }

    private fun VideoPlayer.prepareNow() {
        val player: VideoPlayer = this
        runBlocking { player.prepare(source) }
    }

    private fun tick() {
        scheduler.advanceTimeBy(150L)
        scheduler.runCurrent()
    }

    @Test
    fun `a load that throws any Throwable kind ends in Error carrying that very instance and the next load works`() {
        val kinds: List<Throwable> = listOf(
            RuntimeException("rt"),
            IllegalStateException("ise"),
            UnsupportedOperationException("uoe"),
            OutOfMemoryError("oom"),
            StackOverflowError(),
            NoClassDefFoundError("ncdfe"),
            ExceptionInInitializerError("eiie"),
            AssertionError("ae"),
            NotImplementedError("nie"),
            object : Throwable("a bare Throwable") {},
        )
        for (kind: Throwable in kinds) {
            val engine = ScriptedEngineD1R11()
            withPlayer(engine) { player: VideoPlayer ->
                engine.loadAction = { throw kind }
                player.prepareNow() // 04: "Does not throw on failure"
                val state: VideoPlayerState = player.stateFlow.value
                assertIs<VideoPlayerState.Error>(state, "04: the platform's Throwable arrives as Error for ${kind::class}")
                assertSame(kind, state.cause, "04: the engine's own Throwable, unchanged, for ${kind::class}")
                engine.loadAction = {}
                player.prepareNow()
                assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "04: the next prepare works after ${kind::class}")
            }
        }
    }

    @Test
    fun `events before a load after a release and from inside every engine call never move a player that has no source`() {
        val engine = ScriptedEngineD1R11()
        withPlayer(engine) { player: VideoPlayer ->
            val sink: VideoPlaybackEngineListener = checkNotNull(engine.sink)
            // Events before any load.
            sink.onCompleted()
            sink.onFailed(IllegalStateException("early"))
            sink.onBufferingChanged(true)
            sink.onVideoSizeChanged(VideoSize(1, 1))
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "an event with nothing loaded changes nothing")
            assertEquals(false, player.isBufferingFlow.value)
            assertEquals(null, player.videoSizeFlow.value)

            player.prepareNow()
            player.play()
            // Duplicates: two completions, two failures.
            engine.position = 10_000L
            sink.onCompleted()
            sink.onCompleted()
            assertEquals(VideoPlayerState.Completed(10_000L), player.stateFlow.value, "two completions are one")
            val first = IllegalStateException("first")
            player.replay()
            sink.onFailed(first)
            sink.onFailed(IllegalStateException("second"))
            assertSame(first, (player.stateFlow.value as VideoPlayerState.Error).cause, "04: the first failure is kept")

            // After release the engine is hostile enough to keep its listener reference and fire everything.
            engine.keepListenerOnDetach = true
            player.release()
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            sink.onCompleted()
            sink.onFailed(IllegalStateException("late"))
            sink.onBufferingChanged(true)
            sink.onVideoSizeChanged(VideoSize(2, 2))
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "04: nothing moves a released player")
            assertEquals(false, player.isBufferingFlow.value)
            assertEquals(null, player.videoSizeFlow.value)
            assertEquals(emptyList(), engine.callsAfterDispose(), "no engine call after dispose")
            player.prepareNow()
            val refused = player.stateFlow.value
            assertIs<VideoPlayerState.Error>(refused)
            assertIs<VideoPlayerReleasedException>(refused.cause, "04: prepare after release is Error(VideoPlayerReleasedException)")
        }
    }

    @Test
    fun `events fired synchronously from inside start pause seek and release land after the transition and corrupt nothing`() {
        val engine = ScriptedEngineD1R11()
        withPlayer(engine) { player: VideoPlayer ->
            player.prepareNow()
            val sink: VideoPlaybackEngineListener = checkNotNull(engine.sink)
            engine.onStart = {
                sink.onBufferingChanged(true)
                sink.onVideoSizeChanged(VideoSize(640, 360))
            }
            engine.onPause = { sink.onBufferingChanged(false) }
            engine.onSeek = { sink.onBufferingChanged(true) }
            player.play()
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "04: an event from inside start() is applied after it")
            assertEquals(true, player.isBufferingFlow.value)
            assertEquals(VideoSize(640, 360), player.videoSizeFlow.value)
            player.seekTo(2_000L)
            assertEquals(2_000L, player.playbackPositionFlow.value)
            player.pause()
            assertEquals(false, player.isBufferingFlow.value, "an event from inside pause() lands after the pause")
            engine.onRelease = {
                sink.onCompleted()
                sink.onFailed(IllegalStateException("from release"))
                sink.onBufferingChanged(true)
            }
            engine.onDispose = { sink.onCompleted() }
            player.unload()
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "04: reports made from inside release() are stale")
            assertEquals(false, player.isBufferingFlow.value)
            player.release()
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertEquals(emptyList(), engine.callsAfterDispose())
        }
    }

    @Test
    fun `an engine that calls the player back from inside its own calls never deadlocks and settles`() {
        val engine = ScriptedEngineD1R11()
        withPlayer(engine) { player: VideoPlayer ->
            player.prepareNow()
            var depth = 0
            engine.onPause = { if (depth++ < 3) player.pause() } // pause() from inside pause(): a no-op the second time
            engine.onStart = { player.pause() } // from inside the start of play(): the player is not Playing yet
            player.play()
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "the nested pause saw Ready and did nothing")
            engine.onSeek = { player.setVolume(0.5f); player.setMuted(true) }
            player.seekTo(500L)
            assertEquals(0.5f, player.volumeFlow.value)
            assertEquals(true, player.isMutedFlow.value)
            engine.onPause = {}
            player.pause()
            assertTrue(player.stateFlow.value is VideoPlayerState.Paused)
            // release() inside the engine's own release(): idempotent, no second dispose.
            engine.onRelease = { player.release() }
            player.release()
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertEquals(1, engine.log.count { it == "dispose" }, "04: dispose exactly once")
            assertEquals(emptyList(), engine.callsAfterDispose())
        }
    }

    @Test
    fun `the engine never sees a volume or a speed outside its range whatever the consumer passes`() {
        val engine = ScriptedEngineD1R11()
        withPlayer(engine) { player: VideoPlayer ->
            player.prepareNow()
            player.play()
            val volumes: List<Float> = listOf(-1f, 0f, 2f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, 1e30f, -1e30f)
            for (v: Float in volumes) {
                player.setVolume(v)
                player.setMuted(v > 0f)
            }
            for (s: Float in listOf(0f, -1f, -0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, 1e-30f, 1e30f)) {
                player.setPlaybackSpeed(s)
            }
            assertTrue(engine.volumes.all { it in 0f..1f }, "04: the volume the engine gets is in 0..1: ${engine.volumes}")
            assertTrue(engine.speeds.all { it in 0.25f..3f }, "04: the speed the engine gets is inside the configured range: ${engine.speeds}")
        }
    }
}
