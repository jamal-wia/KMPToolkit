package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * D1 boss round 2: collector chains and hostile engines on the cancel path of `prepare` (`04-api-reference.md`: a
 * cancelled prepare frees the engine and ends in `Idle`, leaving the state to a `prepare` called from inside its reset;
 * a `prepare` after `release()` publishes `Error(VideoPlayerReleasedException)`; when the engine's `release()` throws on
 * the cancel path the player still settles on `Idle` and the engine's exception is attached as suppressed to the
 * `CancellationException` the call received). Virtual time and Unconfined collectors; the only real time is a hang bound.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelPathChainsD1Boss2Test {

    private val rig: InlineRigD1R11 = InlineRigD1R11()
    private val next: VideoSource = VideoSource.Remote("https://example.test/b.mp4")
    private val other: VideoSource = VideoSource.Remote("https://example.test/c.mp4")

    @AfterTest
    fun tearDown() {
        rig.close()
    }

    private fun prepareFromCancelledCaller(target: InlineRigD1R11 = rig): Throwable? {
        var thrown: Throwable? = null
        runBlocking {
            launch {
                coroutineContext.cancel()
                try {
                    target.player.prepare(next)
                } catch (caught: CancellationException) {
                    thrown = caught
                }
            }.join()
        }
        return thrown
    }

    private fun pausedAt3000() {
        rig.prepareNow()
        rig.player.play()
        rig.engine.position = 3_000L
        rig.tick()
        rig.player.pause()
        assertEquals(VideoPlayerState.Paused(10_000L, 3_000L), rig.player.stateFlow.value)
    }

    @Test
    fun `a release and then a refused prepare from a collector inside the cancel reset leave the refusal standing`() {
        pausedAt3000()
        val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.playbackPositionFlow) {
            rig.player.release()
            rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(other) }
        }
        val thrown: Throwable? = prepareFromCancelledCaller()
        assertTrue(thrown is CancellationException, "the cancelled caller received $thrown")
        assertTrue(fired.value, "the reset wrote the position, so the collector ran")
        val state: VideoPlayerState = rig.player.stateFlow.value
        assertTrue(
            state is VideoPlayerState.Error && state.cause is VideoPlayerReleasedException,
            "the refused prepare's Error is not overwritten by the cancelled call it ran inside: $state; log ${rig.engine.log}",
        )
        assertEquals(1, rig.engine.log.count { it == "dispose" }, "dispose once: ${rig.engine.log}")
        assertEquals(emptyList(), rig.engine.callsAfterDispose())
        assertEquals(emptyList(), rig.escaped)
    }

    @Test
    fun `an engine release that throws the very cancellation the call received still settles on Idle`() {
        rig.prepareNow()
        var seen: CancellationException? = null
        rig.engine.loadAction = {
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                seen = cancelled
                throw cancelled
            }
        }
        var releases = 0
        rig.engine.onRelease = { if (++releases == 2) seen?.let { throw it } }
        var thrown: Throwable? = null
        runBlocking {
            withTimeout(10_000L) {
                val caller: Job = launch {
                    try {
                        rig.player.prepare(next)
                    } catch (caught: CancellationException) {
                        thrown = caught
                        throw caught
                    }
                }
                while (rig.engine.log.count { it == "load" } < 2) yield()
                caller.cancel()
                caller.join()
            }
        }
        assertTrue(thrown is CancellationException, "the cancelled caller received $thrown (engine threw $seen)")
        assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value, "log ${rig.engine.log}")
        assertTrue(releases >= 2, "the cancel path called the engine's release: $releases")
        println("boss2-probe same-instance=${thrown === seen}")
        assertEquals(emptyList(), rig.escaped)
    }

    @Test
    fun `two players cancelled under one scope each carry their own engine's release failure`() {
        val second = InlineRigD1R11()
        try {
            rig.prepareNow()
            second.prepareNow()
            val failures: List<IllegalStateException> = listOf(IllegalStateException("A refused"), IllegalStateException("B refused"))
            val rigs: List<InlineRigD1R11> = listOf(rig, second)
            rigs.forEachIndexed { index, each ->
                var releases = 0
                each.engine.loadAction = { awaitCancellation() }
                each.engine.onRelease = { if (++releases == 2) throw failures[index] }
            }
            val thrown: Array<Throwable?> = arrayOfNulls(2)
            runBlocking {
                withTimeout(10_000L) {
                    val scope = Job()
                    rigs.forEachIndexed { index, each ->
                        launch(scope) {
                            try {
                                each.player.prepare(next)
                            } catch (caught: CancellationException) {
                                thrown[index] = caught
                            }
                        }
                    }
                    while (rigs.any { each -> each.engine.log.count { it == "load" } < 2 }) yield()
                    scope.cancel()
                    scope.join()
                }
            }
            rigs.forEachIndexed { index, each ->
                val caught: Throwable? = thrown[index]
                assertTrue(caught is CancellationException, "player $index: $caught")
                assertTrue(caught.suppressed.any { it === failures[index] }, "player $index: ${caught.suppressed.toList()}")
                assertEquals(VideoPlayerState.Idle, each.player.stateFlow.value)
            }
            println("boss2-probe shared-cause=${thrown[0] === thrown[1]} suppressedA=${thrown[0]?.suppressed?.size}")
            assertEquals(emptyList(), rig.escaped + second.escaped)
        } finally {
            second.close()
        }
    }
}
