package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * D1 boss round 1, attack B1: a [io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer.prepare] whose caller is
 * already cancelled when it is called over a source that is loaded and playing (or paused). `04-api-reference.md`: a
 * cancelled prepare frees the engine and settles on Idle; the class KDoc of `EngineVideoPlayer`: a collector's call
 * made from inside a transition's writes acts on the state that transition is establishing. The cancellation step
 * of prepare is not wrapped in `establishing(Idle)` because "every consumer call treats Preparing like the Idle this
 * closes on", but when the caller was cancelled before the start step, the state is still the old source's Playing
 * or Paused, not Preparing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelledCallerPrepareOverLoadedSourceD1Boss1Test {

    private val rig: InlineRigD1R11 = InlineRigD1R11()
    private val next: VideoSource = VideoSource.Remote("https://example.test/b.mp4")

    @AfterTest
    fun tearDown() {
        rig.close()
    }

    /** Calls prepare from a coroutine that cancelled itself first; returns whether prepare threw a CancellationException. */
    private fun prepareFromCancelledCaller(): Boolean {
        var cancelled = false
        runBlocking {
            launch {
                coroutineContext.cancel()
                try {
                    rig.player.prepare(next)
                } catch (expected: CancellationException) {
                    cancelled = true
                }
            }.join()
        }
        return cancelled
    }

    private fun playingAt3000() {
        rig.prepareNow()
        rig.player.play()
        rig.engine.position = 3_000L
        rig.tick()
        assertEquals(3_000L, rig.player.playbackPositionFlow.value)
    }

    @Test
    fun `a cancelled caller over a playing source settles on Idle with the engine freed and nothing restarted`() {
        playingAt3000()
        assertTrue(prepareFromCancelledCaller(), "the cancelled caller receives the cancellation")
        assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value)
        assertEquals("release", rig.engine.log.last(), "engine log: ${rig.engine.log}")
    }

    @Test
    fun `a collector that plays from inside the reset of a cancelled prepare over a paused source does not restart a freed engine`() {
        playingAt3000()
        rig.player.pause()
        rig.engine.log.clear()
        val fired = rig.reactOnce(rig.player.playbackPositionFlow) { rig.player.play() }
        prepareFromCancelledCaller()
        assertTrue(fired.value, "the reset wrote the position, so the collector ran")
        val afterRelease: List<String> = rig.engine.log.dropWhile { it != "release" }.drop(1)
        assertTrue(
            "start" !in afterRelease,
            "the engine's source was released and then started by the collector's play: ${rig.engine.log}; " +
                "state ${rig.player.stateFlow.value}",
        )
        assertEquals(
            VideoPlayerState.Idle,
            rig.player.stateFlow.value,
            "a cancelled prepare settles on Idle; engine log ${rig.engine.log}",
        )
    }

    @Test
    fun `a collector that pauses from inside the reset of a cancelled prepare over a playing source leaves the player Idle`() {
        playingAt3000()
        rig.engine.log.clear()
        val fired = rig.reactOnce(rig.player.playbackPositionFlow) { rig.player.pause() }
        prepareFromCancelledCaller()
        assertTrue(fired.value, "the reset wrote the position, so the collector ran")
        assertEquals(
            VideoPlayerState.Idle,
            rig.player.stateFlow.value,
            "a cancelled prepare settles on Idle; engine log ${rig.engine.log}",
        )
    }

    @Test
    fun `the polling of the old source stops when a cancelled caller's prepare settles on Idle`() {
        playingAt3000()
        prepareFromCancelledCaller()
        assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value)
        val field = rig.player.javaClass.getDeclaredField("positionJob").apply { isAccessible = true }
        val job: Job? = field.get(rig.player) as Job?
        assertTrue(job == null || !job.isActive, "the old source's poll is still running while Idle: $job")
    }
}
