package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * D1 round 11 fix2 (M1). `04-api-reference.md`: `unload` "resets to `Idle` and every source flow to its initial value",
 * `release` ends in `Idle`, a `prepare` resets the playhead, buffered position, buffering and picture size. A collector of
 * a source flow that pauses, seeks, stops or plays from inside that reset must not leave a source flow stale. Started
 * from a Playing player with a stall and a size set, so every reset write is a real change a collector sees.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceFlowResetFixD1R11Test {

    private enum class Op { RePrepare, Unload, Release }

    private enum class Watched { Position, Buffered, Buffering, Size }

    private enum class Reaction { Pause, Seek, Stop, Play }

    private fun cell(op: Op, watched: Watched, reaction: Reaction): Pair<Boolean, List<String>> {
        val rig = InlineRigD1R11()
        val problems: MutableList<String> = mutableListOf()
        val label = "$op/$watched/$reaction"
        try {
            rig.playingStalledWithSize()
            // The second source reports no buffered position and no size, so a re-prepare ends at the initial values.
            rig.engine.buffered = 0L
            val flow: Flow<Any?> = when (watched) {
                Watched.Position -> rig.player.playbackPositionFlow
                Watched.Buffered -> rig.player.bufferedPositionFlow
                Watched.Buffering -> rig.player.isBufferingFlow
                Watched.Size -> rig.player.videoSizeFlow
            }
            val seen: List<VideoPlayerState> = rig.recordStates()
            val fired: InlineRigD1R11.Fired = rig.reactOnce(flow) {
                when (reaction) {
                    Reaction.Pause -> rig.player.pause()
                    Reaction.Seek -> rig.player.seekTo(1_000L)
                    Reaction.Stop -> rig.player.stop()
                    Reaction.Play -> rig.player.play()
                }
            }
            when (op) {
                Op.RePrepare -> rig.prepareNow()
                Op.Unload -> rig.player.unload()
                Op.Release -> rig.player.release()
            }
            if (!fired.value) problems += "$label: the collector never ran (vacuous cell)"
            if (rig.player.playbackPositionFlow.value != 0L) problems += "$label: position ${rig.player.playbackPositionFlow.value}"
            if (rig.player.bufferedPositionFlow.value != 0L) problems += "$label: buffered ${rig.player.bufferedPositionFlow.value}"
            if (rig.player.isBufferingFlow.value) problems += "$label: still buffering"
            if (rig.player.videoSizeFlow.value != null) problems += "$label: size ${rig.player.videoSizeFlow.value}"
            val state: VideoPlayerState = rig.player.stateFlow.value
            when (op) {
                Op.RePrepare -> {
                    if (state != VideoPlayerState.Ready(10_000L)) problems += "$label: state $state after the re-prepare"
                    if (VideoPlayerState.Preparing !in seen) problems += "$label: never Preparing, saw $seen"
                }
                Op.Unload, Op.Release -> if (state != VideoPlayerState.Idle) problems += "$label: state $state"
            }
            if (op == Op.Release && rig.engine.callsAfterDispose().isNotEmpty()) {
                problems += "$label: engine called after dispose ${rig.engine.callsAfterDispose()}"
            }
            return fired.value to problems
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a collector that pauses seeks stops or plays inside the reset of a re-prepare unload or release leaves every source flow at its initial value`() {
        var fired = 0
        val problems: MutableList<String> = mutableListOf()
        for (op: Op in Op.entries) {
            for (watched: Watched in Watched.entries) {
                for (reaction: Reaction in Reaction.entries) {
                    val (ran: Boolean, found: List<String>) = cell(op, watched, reaction)
                    if (ran) fired += 1
                    problems += found
                }
            }
        }
        assertTrue(fired == 48, "vacuity guard: $fired of 48 cells fired")
        assertEquals(emptyList(), problems, "violations:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `an unload whose position collector pauses first still clears the picture size and the buffering flag of the old source`() {
        // The one-cell form of the scenario in the review: Playing, stalled, 640x360, a position collector that pauses.
        val rig = InlineRigD1R11()
        try {
            rig.playingStalledWithSize()
            assertEquals(VideoSize(640, 360), rig.player.videoSizeFlow.value, "the setup set a size")
            rig.reactOnce(rig.player.playbackPositionFlow) { rig.player.pause() }
            rig.player.unload()
            assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value)
            assertEquals(null, rig.player.videoSizeFlow.value, "04: unload resets every source flow")
            assertEquals(false, rig.player.isBufferingFlow.value, "04: isBufferingFlow is false when idle")
        } finally {
            rig.close()
        }
    }
}
