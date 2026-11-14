package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * D1 boss round 1: the cancel path of `prepare` settles through the same step as `unload` and `release`
 * (`04-api-reference.md`: a cancelled prepare frees the engine and settles on Idle; a `prepare` a collector calls from
 * inside the reset owns the state from then on and shows `Preparing` until it runs). The caller is cancelled before the
 * start step, over a source that is playing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelPathSettleIdleD1Boss1Test {

    private val rig: InlineRigD1R11 = InlineRigD1R11()
    private val next: VideoSource = VideoSource.Remote("https://example.test/b.mp4")
    private val other: VideoSource = VideoSource.Remote("https://example.test/c.mp4")
    private val queuedScope: CoroutineScope = CoroutineScope(Job() + StandardTestDispatcher(rig.scheduler))

    @AfterTest
    fun tearDown() {
        queuedScope.cancel()
        rig.close()
    }

    /** Calls prepare from a coroutine that cancelled itself first; returns what it threw. */
    private fun prepareFromCancelledCaller(): Throwable? {
        var thrown: Throwable? = null
        runBlocking {
            launch {
                coroutineContext.cancel()
                try {
                    rig.player.prepare(next)
                } catch (caught: CancellationException) {
                    thrown = caught
                }
            }.join()
        }
        return thrown
    }

    private fun playingAt3000() {
        rig.prepareNow()
        rig.player.play()
        rig.engine.position = 3_000L
        rig.tick()
        assertEquals(3_000L, rig.player.playbackPositionFlow.value)
    }

    private fun stateNames(states: List<VideoPlayerState>): List<String> = states.map { it::class.simpleName.orEmpty() }

    /** Launches `prepare(other)` on a dispatcher that runs only when the test says so, so it waits for the call it replaces. */
    private fun launchQueuedPrepare(): Job =
        queuedScope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(other) }

    @Test
    fun `a prepare called from a collector during the cancel reset shows Preparing at once and then loads`() {
        playingAt3000()
        val states: List<VideoPlayerState> = rig.recordStates()
        var nested: Job? = null
        val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.playbackPositionFlow) { nested = launchQueuedPrepare() }
        val thrown: Throwable? = prepareFromCancelledCaller()
        assertTrue(thrown is CancellationException, "the cancelled caller received $thrown")
        assertTrue(fired.value, "the reset wrote the position, so the collector ran")
        // Before the nested prepare resumes: it waits for the cancelled one, and the old Playing is already gone.
        assertEquals(VideoPlayerState.Preparing, rig.player.stateFlow.value, "states $states; engine log ${rig.engine.log}")
        rig.scheduler.runCurrent()
        assertTrue(checkNotNull(nested).isCompleted, "the nested prepare finished")
        assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value)
        assertEquals(listOf("Playing", "Preparing", "Ready"), stateNames(states), "states $states")
        val afterRelease: List<String> = rig.engine.log.dropWhile { it != "release" }.drop(1)
        assertEquals(listOf("load"), afterRelease.filter { it != "volume" && it != "looping" && it != "speed" }, "engine log ${rig.engine.log}")
        assertEquals(emptyList(), rig.escaped)
    }

    @Test
    fun `an engine release that throws on the cancel path ends Idle and rides on the cancellation as suppressed`() {
        playingAt3000()
        val refused: IllegalStateException = IllegalStateException("release refused")
        var releases = 0
        rig.engine.onRelease = { if (++releases == 1) throw refused }
        val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.playbackPositionFlow) { rig.player.play() }
        val thrown: Throwable? = prepareFromCancelledCaller()
        assertTrue(thrown is CancellationException, "the cancelled caller received $thrown")
        assertTrue(thrown.suppressed.any { it === refused }, "the engine's exception is attached: ${thrown.suppressed.toList()}")
        assertTrue(fired.value, "the reset wrote the position, so the collector ran")
        assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value, "engine log ${rig.engine.log}")
        assertTrue("start" !in rig.engine.log.dropWhile { it != "release" }, "nothing starts after the release: ${rig.engine.log}")
        assertEquals(emptyList(), rig.escaped)
    }

    @Test
    fun `an engine release that throws on the cancel path still shows Preparing for a prepare called from a collector`() {
        playingAt3000()
        val refused: IllegalStateException = IllegalStateException("release refused")
        var releases = 0
        rig.engine.onRelease = { if (++releases == 1) throw refused }
        val states: List<VideoPlayerState> = rig.recordStates()
        var nested: Job? = null
        rig.reactOnce(rig.player.playbackPositionFlow) { nested = launchQueuedPrepare() }
        val thrown: Throwable? = prepareFromCancelledCaller()
        assertTrue(thrown is CancellationException, "the cancelled caller received $thrown")
        assertTrue(thrown.suppressed.any { it === refused }, "the engine's exception is attached: ${thrown.suppressed.toList()}")
        assertEquals(VideoPlayerState.Preparing, rig.player.stateFlow.value, "states $states")
        rig.scheduler.runCurrent()
        assertTrue(checkNotNull(nested).isCompleted, "the nested prepare finished")
        assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value)
        assertEquals(emptyList(), rig.escaped)
    }
}
