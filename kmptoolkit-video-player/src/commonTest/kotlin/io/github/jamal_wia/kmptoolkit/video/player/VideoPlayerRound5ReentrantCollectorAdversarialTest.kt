package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A consumer that collects `stateFlow` on an immediate dispatcher (Main.immediate, Unconfined) is run
 * inline, inside the player's own transition, when the state is written: it may call the player back
 * (reentrantly) from there. Whatever the transition still has to do after the state write then lands
 * on a state the consumer has already moved on from.
 */
@OptIn(ExperimentalAtomicApi::class, InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class VideoPlayerRound5ReentrantCollectorAdversarialTest {

    /** A test dispatcher that counts how often a coroutine is resumed on it (a poll tick is one). */
    private class CountingDispatcher(private val inner: TestDispatcher) : CoroutineDispatcher(), Delay by (inner as Delay) {
        val resumes: AtomicInt = AtomicInt(0)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            resumes.incrementAndFetch()
            inner.dispatch(context, block)
        }
    }

    private class StallDuringLoadEngine(private val inner: RecordingVideoPlaybackEngine) : VideoPlaybackEngine by inner {
        override suspend fun load(source: VideoSource) {
            inner.load(source)
            inner.listener?.onBufferingChanged(true)
        }
    }

    private suspend fun TestScope.runPlayerTest(
        engine: VideoPlaybackEngine,
        body: suspend (VideoPlayer, CountingDispatcher) -> Unit,
    ) {
        val dispatcher = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 250L), dispatcher)
        try {
            body(player, dispatcher)
        } finally {
            player.release()
        }
    }

    private fun TestScope.collectInline(player: VideoPlayer, onState: (VideoPlayerState) -> Unit): Job =
        launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            player.stateFlow.collect { onState(it) }
        }

    @Test
    fun `a consumer that pauses inline when it sees Playing leaves no poll running`() = runTest {
        runPlayerTest(RecordingVideoPlaybackEngine()) { player, dispatcher ->
            player.prepare(VideoSource.Asset("clip.mp4"))
            val collector = collectInline(player) { if (it is VideoPlayerState.Playing) player.pause() }
            player.play()
            assertIs<VideoPlayerState.Paused>(player.stateFlow.value)
            runCurrent()
            val before: Int = dispatcher.resumes.load()
            advanceTimeBy(2_000L)
            assertEquals(before, dispatcher.resumes.load(), "the position poll keeps waking a paused player")
            collector.cancel()
        }
    }

    @Test
    fun `a consumer that unloads inline when it sees Playing leaves no poll running`() = runTest {
        runPlayerTest(RecordingVideoPlaybackEngine()) { player, dispatcher ->
            player.prepare(VideoSource.Asset("clip.mp4"))
            val collector = collectInline(player) { if (it is VideoPlayerState.Playing) player.unload() }
            player.play()
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            runCurrent()
            val before: Int = dispatcher.resumes.load()
            advanceTimeBy(2_000L)
            assertEquals(before, dispatcher.resumes.load(), "the position poll keeps waking an idle player")
            collector.cancel()
        }
    }

    @Test
    fun `a consumer that releases inline when it sees Ready leaves no stall on a released player`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        runPlayerTest(StallDuringLoadEngine(inner)) { player, _ ->
            val collector = collectInline(player) { if (it is VideoPlayerState.Ready) player.release() }
            player.prepare(VideoSource.Asset("clip.mp4"))
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertEquals(false, player.isBufferingFlow.value, "a released player says it is buffering")
            collector.cancel()
        }
    }

    @Test
    fun `a consumer that unloads inline when it sees Ready leaves no stall on an idle player`() = runTest {
        val inner = RecordingVideoPlaybackEngine()
        runPlayerTest(StallDuringLoadEngine(inner)) { player, _ ->
            val collector = collectInline(player) { if (it is VideoPlayerState.Ready) player.unload() }
            player.prepare(VideoSource.Asset("clip.mp4"))
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertEquals(false, player.isBufferingFlow.value, "an idle player says it is buffering")
            collector.cancel()
        }
    }

    @Test
    fun `control - a consumer that only reads leaves the poll running while playing`() = runTest {
        runPlayerTest(RecordingVideoPlaybackEngine()) { player, dispatcher ->
            player.prepare(VideoSource.Asset("clip.mp4"))
            player.play()
            runCurrent()
            val before: Int = dispatcher.resumes.load()
            advanceTimeBy(1_000L)
            assertEquals(true, dispatcher.resumes.load() > before, "the control's poll is not running, so the counter proves nothing")
        }
    }
}
