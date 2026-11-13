package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * D1 round 11 fix (F2a poll tick, F2b seek cap). A reading that throws during a position poll, which has no caller,
 * settles the player on `Error` carrying it; the engine never receives a seek target above `Long.MAX_VALUE / 1_000_000`
 * whatever length it reports (the `VideoPlaybackEngine.seekTo` KDoc and `04-api-reference.md`). Virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostileEngineNumbersFixD1R11Test {

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()
    private val escaped: MutableList<Throwable> = mutableListOf()

    private fun withPlayer(engine: ScriptedEngineD1R11, body: (VideoPlayer) -> Unit) {
        val handler = CoroutineExceptionHandler { _, thrown -> escaped += thrown }
        val player: VideoPlayer = createVideoPlayer(
            engine,
            VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            StandardTestDispatcher(scheduler) + handler,
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
    fun `a length above the range the engine seek contract names still reaches the engine inside that range`() {
        // VideoPlaybackEngine.seekTo KDoc: positionMs is kept in 0..Long.MAX_VALUE / 1_000_000 "so that it converts to
        // microseconds or nanoseconds without overflowing"; a length above that bound must not widen the range.
        val engine = ScriptedEngineD1R11().apply { duration = Long.MAX_VALUE }
        withPlayer(engine) { player: VideoPlayer ->
            player.prepareNow()
            player.seekTo(Long.MAX_VALUE)
            player.play()
            player.seekForward(Long.MAX_VALUE)
            player.seekTo(Long.MAX_VALUE - 1)
            val bound: Long = Long.MAX_VALUE / 1_000_000L
            assertTrue(engine.seekTargets.isNotEmpty())
            assertTrue(engine.seekTargets.all { it in 0L..bound }, "engine seek targets ${engine.seekTargets} outside 0..$bound")
        }
    }

    @Test
    fun `a positionMs that throws from a poll tick settles the player on Error carrying it and nothing reaches the handler`() {
        val engine = ScriptedEngineD1R11()
        withPlayer(engine) { player: VideoPlayer ->
            player.prepareNow()
            player.play()
            val failure = IllegalStateException("position read failed")
            engine.positionReading = { throw failure }
            tick()
            tick()
            val state: VideoPlayerState = player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state, "was $state")
            assertSame(failure, state.cause, "04: the engine's own exception, unchanged")
            assertEquals(emptyList(), escaped, "no exception reaches the coroutine handler")
            // The poll is dead for good: a later healthy reading does not move the position of an Error player.
            engine.positionReading = { 777L }
            tick()
            assertTrue(player.stateFlow.value is VideoPlayerState.Error, "the player stays in Error, was ${player.stateFlow.value}")
        }
    }

    /** The scripted engine with two readings that can be made to throw once the player is playing. */
    private class ThrowingReadingsEngine(private val inner: ScriptedEngineD1R11) : VideoPlaybackEngine by inner {
        var failure: Throwable? = null
        var failBuffered: Boolean = false
        var failDuration: Boolean = false

        override fun bufferedPositionMs(): Long {
            if (failBuffered) throw checkNotNull(failure)
            return inner.bufferedPositionMs()
        }

        override fun durationMs(): Long {
            if (failDuration) throw checkNotNull(failure)
            return inner.durationMs()
        }
    }

    @Test
    fun `a bufferedPositionMs or durationMs that throws from a poll tick settles the player on Error carrying it`() {
        for (which: String in listOf("buffered", "duration")) {
            val wrapper = ThrowingReadingsEngine(ScriptedEngineD1R11())
            val handler = CoroutineExceptionHandler { _, thrown -> escaped += thrown }
            val player: VideoPlayer = createVideoPlayer(
                wrapper,
                VideoPlayerConfig(positionUpdateIntervalMs = 100L),
                StandardTestDispatcher(scheduler) + handler,
            )
            try {
                player.prepareNow()
                player.play()
                val failure = IllegalStateException("$which read failed")
                wrapper.failure = failure
                if (which == "buffered") wrapper.failBuffered = true else wrapper.failDuration = true
                tick()
                val state: VideoPlayerState = player.stateFlow.value
                assertIs<VideoPlayerState.Error>(state, "$which: was $state")
                assertSame(failure, state.cause, "$which: the engine's own exception")
                assertEquals(emptyList(), escaped, "$which: no exception reaches the coroutine handler")
            } finally {
                wrapper.failBuffered = false
                wrapper.failDuration = false
                player.release()
            }
        }
    }
}
