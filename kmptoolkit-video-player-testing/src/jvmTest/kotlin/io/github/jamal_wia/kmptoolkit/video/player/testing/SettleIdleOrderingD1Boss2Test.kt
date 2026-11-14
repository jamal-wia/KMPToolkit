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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * D1 boss round 2: two orderings inside the settle steps that no earlier class pinned.
 * 1. `unload()` while a load is still unwinding frees the engine later (queued); a `prepare` waiting for that unload, on
 *    an immediate dispatcher, resumes as soon as the unload's placeholder completes. `04-api-reference.md` ("the older
 *    load is fully unwound before the newer one starts", "nothing reaches the engine" of a dropped source after it): the
 *    engine is freed BEFORE the waiting prepare loads, never after, so the newer source is not released under a `Ready`.
 * 2. A `prepare` called by a collector from inside the cancel reset, itself from an already-cancelled coroutine, owns the
 *    state from then on ("`Preparing`, then its own result", here `Idle`): the old `Paused` is not left showing in
 *    between, where a `play()` would start a freed engine.
 * Virtual time and Unconfined collectors; no thread, no sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettleIdleOrderingD1Boss2Test {

    private val rig: InlineRigD1R11 = InlineRigD1R11()
    private val next: VideoSource = VideoSource.Remote("https://example.test/b.mp4")
    private val other: VideoSource = VideoSource.Remote("https://example.test/c.mp4")
    private val queuedScope: CoroutineScope = CoroutineScope(Job() + StandardTestDispatcher(rig.scheduler))

    @AfterTest
    fun tearDown() {
        queuedScope.cancel()
        rig.close()
    }

    @Test
    fun `a prepare waiting for an unload whose engine release is deferred loads only after that release`() {
        rig.engine.loadAction = { awaitCancellation() }
        val first: Job = queuedScope.launch { rig.player.prepare(rig.source) }
        rig.scheduler.runCurrent()
        assertEquals(VideoPlayerState.Preparing, rig.player.stateFlow.value)
        rig.engine.loadAction = {}
        // The load is still unwinding (its resumption is queued), so the unload's engine release waits for it.
        rig.player.unload()
        assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value)
        val waiting: Job = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(next) }
        assertTrue(waiting.isActive, "the newer prepare waits for the unload's placeholder")
        rig.scheduler.runCurrent()
        assertTrue(first.isCompleted && waiting.isCompleted, "both prepares finished: first ${first.isCompleted}, newer ${waiting.isCompleted}")
        assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value, "log ${rig.engine.log}")
        val log: List<String> = rig.engine.log
        assertTrue(
            log.lastIndexOf("release") < log.lastIndexOf("load"),
            "the dropped source is released before the newer one loads, never after: $log",
        )
        assertEquals(emptyList(), rig.escaped)
    }

    @Test
    fun `a cancelled caller's prepare from a collector inside the cancel reset shows Preparing until it settles on Idle`() {
        rig.prepareNow()
        rig.player.play()
        rig.engine.position = 3_000L
        rig.tick()
        rig.player.pause()
        assertEquals(VideoPlayerState.Paused(10_000L, 3_000L), rig.player.stateFlow.value)
        var nestedThrew = false
        var nested: Job? = null
        val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.playbackPositionFlow) {
            nested = queuedScope.launch(start = CoroutineStart.UNDISPATCHED) {
                coroutineContext.cancel()
                try {
                    rig.player.prepare(other)
                } catch (cancelled: CancellationException) {
                    nestedThrew = true
                    throw cancelled
                }
            }
        }
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
        assertTrue(thrown is CancellationException, "the cancelled caller received $thrown")
        assertTrue(fired.value, "the reset wrote the position, so the collector ran")
        assertEquals(VideoPlayerState.Preparing, rig.player.stateFlow.value, "the old Paused is gone; log ${rig.engine.log}")
        rig.player.play()
        assertEquals(VideoPlayerState.Preparing, rig.player.stateFlow.value)
        assertTrue("start" !in rig.engine.log.dropWhile { it != "release" }, "nothing started on the freed engine: ${rig.engine.log}")
        rig.scheduler.runCurrent()
        assertTrue(checkNotNull(nested).isCompleted && nestedThrew, "the nested prepare ended with its cancellation")
        assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value)
        assertEquals(emptyList(), rig.escaped)
    }
}
