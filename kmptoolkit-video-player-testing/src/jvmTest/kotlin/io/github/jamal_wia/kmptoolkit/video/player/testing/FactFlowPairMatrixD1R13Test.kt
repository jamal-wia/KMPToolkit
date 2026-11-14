package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D1 round 13, Q2 and N2 of the r12 review: the round 12 pairwise matrix with the collector on a FACT flow (playback
 * position, buffered position, isBuffering, picture size) instead of `stateFlow`. There the running transition's target
 * differs from `stateFlow` (the state is written last), so the clear of the target after the first call's publish is what
 * the second call reads. Same oracle, same cells; a combination that never fires is skipped, the vacuity guard is per flow.
 *
 * Original header (round 12): the pairwise proof of the fix. `04-api-reference.md` "Threading": a collector of a flow of the player on
 * an immediate dispatcher may call the player from inside the update, "the call is never undone", and this holds "for
 * every call a collector makes in one update, in order: once a call has changed the player, the next one acts on the
 * state that call published".
 *
 * Every transition that publishes (pause, stop, seek, play from `Completed`, a completion with the repeat mode `Off` and
 * `One`, a failure, `unload`, `release`, a re-`prepare`, the last step of a first `prepare`) is run with one collector of
 * `stateFlow` that makes a first call F and then a second call S in the same update, for F and S in every call of the
 * public surface that a collector would plausibly make. Oracle, per cell, from the contract and not from the code:
 *
 *  - the second call acts on the state the first one published: no engine call is made for a player that is `Idle`,
 *    `Error` or still `Preparing`, a transport call on a playable player does what it says (a pause pauses a playing
 *    player, a play plays, a stop stops, a seek seeks to its target, a replay starts over, an `unload` and a `release`
 *    end in `Idle`, an awaited `prepare` loads), and a setting reaches the engine exactly when the state is playable
 *    (the speed: when it is `Playing`);
 *  - afterwards `Playing` holds iff the engine was told to start iff the poll reads positions; `Paused`, `Ready`,
 *    `Idle` and `Error` leave the engine stopped; `Idle` leaves every source flow at its initial value; a playable
 *    player's engine holds the flows' repeat mode and volume; nothing reaches the engine after its dispose.
 *
 * The first calls are pinned by the r11 matrices; this class pins every second one. Virtual time, no sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FactFlowPairMatrixD1R13Test {

    private enum class Transition {
        Pause, Stop, Seek, PlayFromCompleted, CompletionOff, CompletionOne, Failure, Unload, Release, RePrepare,
        PrepareFinalStep,
    }

    private enum class Call {
        Pause, Play, Stop, SeekTo, Replay, SeekForward, Unload, Release, Prepare, SetMuted, SetVolume, SetSpeed, SetRepeat,
    }

    private enum class Watch { Position, Buffered, Buffering, Size }

    private val firedPairs: MutableMap<Transition, Int> = mutableMapOf()
    private val firedPerWatch: MutableMap<Watch, Int> = mutableMapOf()

    private suspend fun InlineRigD1R11.make(call: Call) {
        when (call) {
            Call.Pause -> player.pause()
            Call.Play -> player.play()
            Call.Stop -> player.stop()
            Call.SeekTo -> player.seekTo(SEEK_TARGET)
            Call.Replay -> player.replay()
            Call.SeekForward -> player.seekForward(500L)
            Call.Unload -> player.unload()
            Call.Release -> player.release()
            Call.Prepare -> player.prepare(source)
            Call.SetMuted -> player.setMuted(true)
            Call.SetVolume -> player.setVolume(0.5f)
            Call.SetSpeed -> player.setPlaybackSpeed(2f)
            Call.SetRepeat -> player.setRepeatMode(RepeatMode.One)
        }
    }

    /** Puts the player where [transition] starts from and returns what makes the collector fire (the first matching update). */
    private fun InlineRigD1R11.arrange(transition: Transition): (VideoPlayerState) -> Boolean {
        when (transition) {
            Transition.PrepareFinalStep -> return { it is VideoPlayerState.Ready }
            Transition.PlayFromCompleted -> {
                prepareNow()
                player.play()
                engine.position = 10_000L
                engine.playing = false
                checkNotNull(engine.sink).onCompleted()
                checkNotNull(engine.sink).onVideoSizeChanged(VideoSize(640, 360))
            }
            Transition.CompletionOne -> {
                player.setRepeatMode(RepeatMode.One)
                playingStalledWithSize(3_000L)
            }
            else -> playingStalledWithSize(3_000L)
        }
        return { true }
    }

    private fun InlineRigD1R11.trigger(transition: Transition) {
        val sink = engine.sink
        when (transition) {
            Transition.Pause -> {
                // The playhead and the buffer moved since the last tick, so the position and buffered flows get an update.
                engine.position = 3_500L
                engine.buffered = 4_500L
                player.pause()
            }
            Transition.Stop -> player.stop()
            Transition.Seek -> player.seekTo(2_000L)
            Transition.PlayFromCompleted -> player.play()
            Transition.CompletionOff -> {
                engine.playing = false
                engine.position = engine.duration
                checkNotNull(sink).onCompleted()
            }
            Transition.CompletionOne -> {
                engine.position = 0L
                checkNotNull(sink).onCompleted()
            }
            Transition.Failure -> {
                engine.playing = false
                checkNotNull(sink).onFailed(IllegalStateException("scripted failure"))
            }
            Transition.Unload -> player.unload()
            Transition.Release -> player.release()
            Transition.RePrepare, Transition.PrepareFinalStep -> runBlocking { player.prepare(source) }
        }
    }

    private fun isPlayable(state: VideoPlayerState): Boolean =
        state is VideoPlayerState.Ready || state is VideoPlayerState.Playing || state is VideoPlayerState.Paused ||
            state is VideoPlayerState.Completed

    private fun cell(transition: Transition, watch: Watch, first: Call, second: Call): List<String> {
        val rig = InlineRigD1R11()
        val label = "$transition on $watch / $first then $second"
        val problems: MutableList<String> = mutableListOf()
        var fired = false
        var published: VideoPlayerState? = null
        var firstChanged = false
        var delta: List<String> = emptyList()
        var released = false
        try {
            val fires: (VideoPlayerState) -> Boolean = rig.arrange(transition)
            val watched: kotlinx.coroutines.flow.Flow<Any?> = when (watch) {
                Watch.Position -> rig.player.playbackPositionFlow
                Watch.Buffered -> rig.player.bufferedPositionFlow
                Watch.Buffering -> rig.player.isBufferingFlow
                Watch.Size -> rig.player.videoSizeFlow
            }
            rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                watched.drop(1).collect { _ ->
                    if (!fired) {
                        fired = true
                        val beforeFirst: VideoPlayerState = rig.player.stateFlow.value
                        rig.make(first)
                        val afterFirst: VideoPlayerState = rig.player.stateFlow.value
                        firstChanged = afterFirst != beforeFirst
                        published = afterFirst
                        val mark: Int = rig.engine.log.size
                        rig.make(second)
                        delta = rig.engine.log.drop(mark)
                    }
                }
            }
            runBlocking { withTimeout(10_000L) { rig.trigger(transition) } }
            released = transition == Transition.Release || first == Call.Release || second == Call.Release
            if (!fired) return emptyList()
            firedPairs[transition] = (firedPairs[transition] ?: 0) + 1
            firedPerWatch[watch] = (firedPerWatch[watch] ?: 0) + 1

            val state: VideoPlayerState = rig.player.stateFlow.value
            var reads = 0
            rig.engine.positionReading = { reads += 1; rig.engine.position }
            rig.scheduler.advanceTimeBy(500L)
            rig.scheduler.runCurrent()
            val engine: ScriptedEngineD1R11 = rig.engine

            if (rig.escaped.isNotEmpty()) problems += "an exception escaped: ${rig.escaped}"
            if (state is VideoPlayerState.Preparing) problems += "left in Preparing"
            if (engine.callsAfterDispose().isNotEmpty()) problems += "engine called after dispose: ${engine.callsAfterDispose()}"
            if (released) {
                val ok: Boolean = state == VideoPlayerState.Idle ||
                    (state is VideoPlayerState.Error && state.cause is VideoPlayerReleasedException)
                if (!ok) problems += "released but the state is $state"
                return problems.map { "$label: $it" }
            }

            // Playing iff the engine was told to start iff the poll reads positions.
            val playing: Boolean = state is VideoPlayerState.Playing
            if (playing != (reads > 0)) problems += "state $state but the poll read $reads times over 500 ms"
            if (playing && !engine.playing) problems += "state $state but the engine is stopped"
            if (!playing && engine.playing) problems += "state $state but the engine is started"
            if (state == VideoPlayerState.Idle) {
                val player = rig.player
                val initial: Boolean = player.playbackPositionFlow.value == 0L && player.bufferedPositionFlow.value == 0L &&
                    !player.isBufferingFlow.value && player.videoSizeFlow.value == null
                if (!initial) problems += "Idle with non-initial source flows"
            }
            if (isPlayable(state)) {
                val player = rig.player
                if (engine.lastLooping != (player.repeatModeFlow.value == RepeatMode.One)) {
                    problems += "state $state: engine looping ${engine.lastLooping} but the flow says ${player.repeatModeFlow.value}"
                }
                val effective: Float = if (player.isMutedFlow.value) 0f else player.volumeFlow.value
                if (engine.volumes.isNotEmpty() && engine.volumes.last() != effective) {
                    problems += "state $state: engine volume ${engine.volumes.last()} but the flows say $effective"
                }
                if (state is VideoPlayerState.Playing && engine.speeds.last() != player.playbackSpeedFlow.value) {
                    problems += "state $state: engine speed ${engine.speeds.last()} but the flow says ${player.playbackSpeedFlow.value}"
                }
            }

            // The second call acts on the state the first one published.
            val seen: VideoPlayerState? = published
            if (firstChanged && seen != null) {
                val playable: Boolean = isPlayable(seen)
                val transportCalls: List<String> = delta.filter { it in TRANSPORT_AND_SETTING_CALLS }
                when (second) {
                    Call.Unload, Call.Release, Call.Prepare -> Unit
                    else -> if (!playable && transportCalls.isNotEmpty()) {
                        problems += "the second call ($second) acted on a player the first call left $seen: engine calls $transportCalls"
                    }
                }
                if (playable) when (second) {
                    Call.Pause -> if (seen is VideoPlayerState.Playing && state !is VideoPlayerState.Paused) {
                        problems += "pause on a playing player left $state"
                    }
                    Call.Play -> if (state !is VideoPlayerState.Playing) problems += "play on $seen left $state"
                    Call.Stop -> if (state !is VideoPlayerState.Ready) problems += "stop on $seen left $state"
                    Call.Replay -> if (state !is VideoPlayerState.Playing || engine.seekTargets.lastOrNull() != 0L) {
                        problems += "replay on $seen left $state (seeks ${engine.seekTargets.takeLast(3)})"
                    }
                    Call.SeekTo -> {
                        if (engine.seekTargets.lastOrNull() != SEEK_TARGET) problems += "seekTo on $seen: the engine was not sought to the target (seeks ${engine.seekTargets.takeLast(3)})"
                        val shown: Long? = (state as? VideoPlayerState.Playing)?.currentPosition
                            ?: (state as? VideoPlayerState.Paused)?.currentPosition
                        if (shown != null && shown != SEEK_TARGET) problems += "seekTo on $seen: the state shows $state"
                    }
                    Call.SeekForward -> if (!delta.contains("seek")) problems += "seekForward on $seen made no engine seek"
                    Call.SetMuted, Call.SetVolume, Call.SetRepeat -> if (!delta.any { it == "volume" || it == "looping" }) {
                        problems += "$second on $seen did not reach the engine: $delta"
                    }
                    Call.SetSpeed -> if (seen is VideoPlayerState.Playing && !delta.contains("speed")) {
                        problems += "setPlaybackSpeed on $seen did not reach the engine: $delta"
                    }
                    else -> Unit
                }
                if (second == Call.Unload && state != VideoPlayerState.Idle) problems += "unload after $first left $state"
                if (second == Call.Prepare && state !is VideoPlayerState.Ready) problems += "an awaited prepare after $first left $state"
                if (second == Call.SetSpeed && seen !is VideoPlayerState.Playing && delta.contains("speed")) {
                    problems += "setPlaybackSpeed pushed the speed to a player that was $seen"
                }
            }
        } catch (thrown: Throwable) {
            problems += "the cell threw $thrown"
        } finally {
            rig.close()
        }
        return problems.map { "$label: $it" }
    }

    private fun matrix(transitions: List<Transition>): List<String> =
        transitions.flatMap { transition: Transition ->
            Watch.entries.flatMap { watch: Watch ->
                Call.entries.flatMap { first: Call ->
                    Call.entries.flatMap { second: Call -> cell(transition, watch, first, second) }
                }
            }
        }

    @Test
    fun `every second call of a collector chain on a fact flow acts on the state the first call published in every transition`() {
        val problems: List<String> = matrix(Transition.entries)
        println("fired pairs per transition: $firedPairs")
        println("fired pairs per watched flow: $firedPerWatch")
        for (transition: Transition in Transition.entries) {
            assertTrue((firedPairs[transition] ?: 0) >= 20, "vacuity guard: only ${firedPairs[transition]} pairs fired in $transition")
        }
        for (watch: Watch in Watch.entries) {
            assertTrue((firedPerWatch[watch] ?: 0) >= 200, "vacuity guard: only ${firedPerWatch[watch]} pairs fired on $watch")
        }
        assertEquals(emptyList(), problems, "violations of the documented chain contract:\n" + problems.take(40).joinToString("\n") +
            "\n(${problems.size} in all)")
    }

    private companion object {
        const val SEEK_TARGET: Long = 4_321L
        val TRANSPORT_AND_SETTING_CALLS: Set<String> = setOf("start", "pause", "seek", "speed", "volume", "looping")
    }
}
