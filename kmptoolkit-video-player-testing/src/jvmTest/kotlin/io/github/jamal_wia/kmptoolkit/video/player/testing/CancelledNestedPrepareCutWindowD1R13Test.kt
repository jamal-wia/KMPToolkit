package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout

/**
 * D1 round 13, fix2 (S1 of the review of the batch fix): the cut start step of a `prepare` tells the load that replaced
 * it apart from the placeholder an `unload` or `release` leaves by identity, not by the load's job being active. A real
 * `prepare` whose caller was already cancelled when the cut step looked is still a newer call: the player shows
 * `Preparing` for it until its cancel path publishes `Idle`, instead of keeping the state of a source the engine has
 * already given up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelledNestedPrepareCutWindowD1R13Test {

    private val other: VideoSource = VideoSource.Remote("https://example.test/b.mp4")

    @Test
    fun `a prepare cut by a nested prepare whose caller is already cancelled shows Preparing until the nested one settles`() {
        val rig = InlineRigD1R11()
        // The nested prepare resumes only when the hand-stepped scheduler runs, so its cancel path waits for runCurrent.
        val cancelledScope = CoroutineScope(Job().apply { cancel() } + StandardTestDispatcher(rig.scheduler))
        try {
            rig.prepareNow() // Ready, buffered position 4000
            var nested: Job? = null
            val nestedLaunched: InlineRigD1R11.Fired = rig.reactOnce(rig.player.bufferedPositionFlow) {
                nested = cancelledScope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(other) }
            }
            val states: List<VideoPlayerState> = rig.recordStates()
            val before: VideoPlayerState = rig.player.stateFlow.value
            assertTrue(before is VideoPlayerState.Ready, "the player was prepared: $before")
            val logBefore: Int = rig.engine.log.size

            val outer: Job = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(rig.source) }
            runBlocking { withTimeout(10_000L) { outer.join() } }

            val calls: List<String> = rig.engine.log.drop(logBefore)
            assertTrue(nestedLaunched.value, "the buffered position collector launched the nested prepare: $calls")
            assertTrue("release" in calls, "the first prepare freed the engine's source: $calls")
            // Before the scheduler runs, the nested prepare is still waiting for the first one to unwind.
            assertEquals(
                VideoPlayerState.Preparing,
                rig.player.stateFlow.value,
                "right after the cut prepare returned the state shows the newer call, not the old one: $states",
            )
            assertTrue(
                states.drop(1).none { it is VideoPlayerState.Ready || it is VideoPlayerState.Playing },
                "no state of the dropped source is published after the cut: $states",
            )

            rig.scheduler.runCurrent()
            runBlocking { withTimeout(10_000L) { checkNotNull(nested).join() } }

            assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value, "the cancelled nested prepare settles on Idle: $states")
            assertTrue(rig.engine.log.drop(logBefore).none { it == "load" }, "no load ever ran: ${rig.engine.log.drop(logBefore)}")
            assertEquals(emptyList(), rig.escaped)
        } finally {
            cancelledScope.cancel()
            rig.close()
        }
    }
}
