package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * D1 round 11 fix2, the T-matrix. `04-api-reference.md`: a collector of any flow of the player on an immediate dispatcher may
 * call the player from inside the update; such a call sees the state the running transition is establishing and is never
 * undone by the rest of it. Every transition (the nine of the round 11 matrices, a completion in repeat mode One, and the
 * unload, release and re-prepare of a Playing player) times every flow (the four source flows and `stateFlow`) times every
 * reaction a collector can have (release, unload, pause, seek, stop, play): 390 cells, each judged by five predicates.
 *
 * (a) `Playing` iff the engine is started iff the poll runs; (b) a failure wins: `Error` with the cause, unless the reaction
 * released or unloaded; (c) `Idle` implies all four source flows at their initial values; (d) no engine call after dispose;
 * (e) the reaction's own effect is visible at the end. Virtual time, an Unconfined collector, no thread, no sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EstablishedStateMatrixFixD1R11Test {

    private enum class Op {
        Prepare, Pause, Stop, Seek, SeekForward, Replay, Completion, CompletionOne, Failure, PlayFromCompleted,
        Unload, Release, RePrepare,
    }

    private enum class Watched { State, Position, Buffered, Buffering, Size }

    private enum class Reaction { Release, Unload, Pause, Seek, Stop, Play }

    private val a: Long = 1_000L
    private val cause: Throwable = IllegalStateException("failed")

    private class Outcome(val fired: Boolean, val problems: List<String>)

    private fun cell(op: Op, watched: Watched, reaction: Reaction): Outcome {
        val rig = InlineRigD1R11()
        val label = "$op/$watched/$reaction"
        val problems: MutableList<String> = mutableListOf()
        try {
            if (op != Op.Prepare) rig.prepareNow()
            val sink = checkNotNull(rig.engine.sink)
            when (op) {
                Op.Prepare -> Unit
                Op.PlayFromCompleted -> {
                    rig.player.play()
                    rig.engine.position = 10_000L
                    sink.onCompleted()
                    sink.onVideoSizeChanged(VideoSize(640, 360))
                }
                else -> {
                    if (op == Op.CompletionOne) rig.player.setRepeatMode(RepeatMode.One)
                    rig.player.play()
                    rig.engine.position = 3_000L
                    rig.tick()
                    sink.onBufferingChanged(true)
                    sink.onVideoSizeChanged(VideoSize(640, 360))
                }
            }
            rig.engine.position = if (op == Op.Completion || op == Op.CompletionOne || op == Op.PlayFromCompleted) 10_000L else 3_000L
            val flow: Flow<Any?> = when (watched) {
                Watched.State -> rig.player.stateFlow
                Watched.Position -> rig.player.playbackPositionFlow
                Watched.Buffered -> rig.player.bufferedPositionFlow
                Watched.Buffering -> rig.player.isBufferingFlow
                Watched.Size -> rig.player.videoSizeFlow
            }
            val fired: InlineRigD1R11.Fired = rig.reactOnce(flow) {
                when (reaction) {
                    Reaction.Release -> rig.player.release()
                    Reaction.Unload -> rig.player.unload()
                    Reaction.Pause -> rig.player.pause()
                    Reaction.Seek -> rig.player.seekTo(a)
                    Reaction.Stop -> rig.player.stop()
                    Reaction.Play -> rig.player.play()
                }
            }
            when (op) {
                Op.Prepare, Op.RePrepare -> rig.prepareNow()
                Op.Pause -> rig.player.pause()
                Op.Stop -> rig.player.stop()
                Op.Seek -> rig.player.seekTo(2_000L)
                Op.SeekForward -> rig.player.seekForward(500L)
                Op.Replay -> rig.player.replay()
                Op.Completion, Op.CompletionOne -> {
                    // A platform that stops at its end; in repeat mode One the engine loops by itself, so it keeps playing.
                    if (op == Op.Completion) rig.engine.playing = false
                    sink.onCompleted()
                }
                Op.Failure -> {
                    rig.engine.playing = false // a failed engine is not playing
                    sink.onFailed(cause)
                }
                Op.PlayFromCompleted -> rig.player.play()
                Op.Unload -> rig.player.unload()
                Op.Release -> rig.player.release()
            }
            if (!fired.value) return Outcome(false, emptyList()) // this flow does not change under this operation
            val state: VideoPlayerState = rig.player.stateFlow.value
            val position: Long = rig.player.playbackPositionFlow.value
            val playable: Boolean = state is VideoPlayerState.Ready || state is VideoPlayerState.Playing ||
                state is VideoPlayerState.Paused || state is VideoPlayerState.Completed

            // (a) Playing iff the engine is started iff the poll runs.
            var reads = 0
            rig.engine.positionReading = { reads += 1; rig.engine.position }
            rig.scheduler.advanceTimeBy(500L)
            rig.scheduler.runCurrent()
            val playing: Boolean = state is VideoPlayerState.Playing
            if (playing != rig.engine.playing) problems += "$label: state $state but engine started = ${rig.engine.playing}"
            if (playing != (reads > 0)) problems += "$label: state $state but the poll read $reads times over 500 ms"
            // (b) a failure wins.
            if (op == Op.Failure && reaction != Reaction.Release && reaction != Reaction.Unload) {
                if (state != VideoPlayerState.Error(cause)) problems += "$label: the failure was lost, state $state"
            }
            // (c) Idle implies every source flow at its initial value.
            if (state == VideoPlayerState.Idle) {
                if (rig.player.playbackPositionFlow.value != 0L) problems += "$label: Idle with position ${rig.player.playbackPositionFlow.value}"
                if (rig.player.bufferedPositionFlow.value != 0L) problems += "$label: Idle with buffered ${rig.player.bufferedPositionFlow.value}"
                if (rig.player.isBufferingFlow.value) problems += "$label: Idle but buffering"
                if (rig.player.videoSizeFlow.value != null) problems += "$label: Idle with size ${rig.player.videoSizeFlow.value}"
            }
            // (d) no engine call after dispose.
            if (rig.engine.callsAfterDispose().isNotEmpty()) problems += "$label: engine called after dispose ${rig.engine.callsAfterDispose()}"
            // (e) the reaction's own effect is visible at the end. A transport call made while the player is Preparing
            // is a no-op (the player is not playable, whatever it showed before): a prepare's start step, and the
            // Preparing update of a fresh prepare, are such moments, so only the later reactions (release, unload) count there.
            val atPreparing: Boolean = op == Op.RePrepare || (op == Op.Prepare && watched == Watched.State)
            when (reaction) {
                Reaction.Release -> if (state != VideoPlayerState.Idle) problems += "$label: released but state $state"
                Reaction.Unload -> if (state != VideoPlayerState.Idle) problems += "$label: unloaded but state $state"
                Reaction.Pause -> if (playing && !atPreparing) problems += "$label: paused but state $state"
                Reaction.Seek -> if (playable && !atPreparing && position != a) problems += "$label: sought to $a but position $position in $state"
                Reaction.Stop -> if (playable && !atPreparing && state !is VideoPlayerState.Ready) problems += "$label: stopped but state $state"
                Reaction.Play -> if (playable && !atPreparing && !playing) problems += "$label: played but state $state"
            }
            return Outcome(true, problems)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `no transition times flow times reaction cell breaks a predicate of the established state`() {
        val firedPerOp: MutableMap<Op, Int> = Op.entries.associateWith { 0 }.toMutableMap()
        var fired = 0
        val problems: MutableList<String> = mutableListOf()
        for (op: Op in Op.entries) {
            for (watched: Watched in Watched.entries) {
                for (reaction: Reaction in Reaction.entries) {
                    val outcome: Outcome = cell(op, watched, reaction)
                    if (outcome.fired) {
                        fired += 1
                        firedPerOp[op] = firedPerOp.getValue(op) + 1
                    }
                    problems += outcome.problems
                }
            }
        }
        val silent: List<Op> = firedPerOp.filterValues { it == 0 }.keys.toList()
        assertEquals(emptyList(), silent, "vacuity guard: no fired cell for $silent")
        assertTrue(fired >= 60, "vacuity guard: only $fired cells fired")
        assertEquals(emptyList(), problems, "violations:\n" + problems.joinToString("\n"))
    }
}
