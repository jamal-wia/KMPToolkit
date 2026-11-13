package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * D1 round 11 (breaker ip), Q1/Q3: `04-api-reference.md` promises that a `stateFlow` collector on an immediate dispatcher
 * "may call the player from inside the update; every transition has finished its other writes by then, so such a call is
 * never undone". This matrix runs every transition (prepare, pause, stop, seek, relative seek, replay, a completion, a
 * failure, play from Completed) with an inline collector that releases, unloads or pauses the player the moment the flow
 * changes, and checks what the sentence says: the player ends Idle (or not Playing over a paused engine), the engine is
 * not called after its dispose, and nothing is left playing. The cell runner also accepts the position, buffered, buffering
 * and picture-size flows; the promise is not written for those and they are not part of this class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InlineCollectorReleaseMatrixD1R11AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")

    private enum class Watched { State, Position, Buffered, Buffering, Size }

    private enum class Reaction { Release, Unload, Pause }

    private var firedCells: Int = 0

    private enum class Op { Prepare, Pause, Stop, Seek, SeekForward, Replay, Completion, Failure, PlayFromCompleted }

    private fun watchedFlow(player: VideoPlayer, watched: Watched): Flow<Any?> = when (watched) {
        Watched.State -> player.stateFlow
        Watched.Position -> player.playbackPositionFlow
        Watched.Buffered -> player.bufferedPositionFlow
        Watched.Buffering -> player.isBufferingFlow
        Watched.Size -> player.videoSizeFlow
    }

    /** Runs one cell; returns a description of every violation, empty when the cell holds. */
    private fun cell(op: Op, watched: Watched, reaction: Reaction): List<String> {
        val scheduler = TestCoroutineScheduler()
        val engine = ScriptedEngineD1R11().apply { buffered = 4_000L }
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L), StandardTestDispatcher(scheduler))
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val problems: MutableList<String> = mutableListOf()
        var fired = false
        try {
            if (op != Op.Prepare) runBlocking { player.prepare(source) }
            val sink = checkNotNull(engine.sink)
            // Put the player in the state the operation starts from, and make every watched flow differ from what the op writes.
            when (op) {
                Op.Prepare -> Unit
                Op.Pause, Op.Stop, Op.Seek, Op.SeekForward, Op.Replay -> {
                    player.play()
                    engine.position = 3_000L
                    scheduler.advanceTimeBy(150L)
                    scheduler.runCurrent()
                    sink.onBufferingChanged(true)
                    sink.onVideoSizeChanged(VideoSize(640, 360))
                }
                Op.Completion, Op.Failure -> {
                    player.play()
                    engine.position = 3_000L
                    scheduler.advanceTimeBy(150L)
                    scheduler.runCurrent()
                    sink.onBufferingChanged(true)
                    sink.onVideoSizeChanged(VideoSize(640, 360))
                }
                Op.PlayFromCompleted -> {
                    player.play()
                    engine.position = 10_000L
                    sink.onCompleted()
                    sink.onVideoSizeChanged(VideoSize(640, 360))
                }
            }
            engine.position = if (op == Op.Completion || op == Op.PlayFromCompleted) 10_000L else 3_000L
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                watchedFlow(player, watched).drop(1).collect {
                    if (!fired) {
                        fired = true
                        when (reaction) {
                            Reaction.Release -> player.release()
                            Reaction.Unload -> player.unload()
                            Reaction.Pause -> player.pause()
                        }
                    }
                }
            }
            when (op) {
                Op.Prepare -> runBlocking { player.prepare(source) }
                Op.Pause -> player.pause()
                Op.Stop -> player.stop()
                Op.Seek -> player.seekTo(2_000L)
                Op.SeekForward -> player.seekForward(500L)
                Op.Replay -> player.replay()
                Op.Completion -> sink.onCompleted()
                Op.Failure -> sink.onFailed(IllegalStateException("failed"))
                Op.PlayFromCompleted -> player.play()
            }
            if (!fired) return emptyList() // this flow does not change under this operation: nothing to check
            firedCells += 1
            val state: VideoPlayerState = player.stateFlow.value
            if (reaction == Reaction.Pause) {
                // The collector paused a player that was Playing: a Playing state over a paused engine is that call undone.
                if (state is VideoPlayerState.Playing && !engine.playing) problems += "state $state while the engine is paused"
                return problems.map { "$op/$watched/$reaction: $it" }
            }
            if (state != VideoPlayerState.Idle && reaction == Reaction.Release) problems += "state $state after the collector released the player"
            if (state != VideoPlayerState.Idle && reaction == Reaction.Unload) problems += "state $state after the collector unloaded the player"
            if (reaction == Reaction.Release && engine.callsAfterDispose().isNotEmpty()) problems += "engine called after dispose: ${engine.callsAfterDispose()}"
            if (engine.playing) problems += "engine still playing"
            scheduler.advanceTimeBy(500L)
            scheduler.runCurrent()
            val settled: VideoPlayerState = player.stateFlow.value
            if (settled != state) problems += "state moved from $state to $settled by itself"
        } finally {
            scope.cancel()
            player.release()
        }
        return problems.map { "$op/$watched/$reaction: $it" }
    }

    private fun matrix(watchedSet: List<Watched>): List<String> =
        Op.entries.flatMap { op: Op ->
            watchedSet.flatMap { watched: Watched ->
                Reaction.entries.flatMap { reaction: Reaction -> cell(op, watched, reaction) }
            }
        }

    @Test
    fun `a stateFlow collector that releases unloads or pauses inline is never undone by the rest of any transition`() {
        // 04: "A stateFlow collector on an immediate dispatcher may call the player from inside the update; every transition
        // has finished its other writes by then, so such a call is never undone."
        val problems: List<String> = matrix(listOf(Watched.State))
        assertTrue(firedCells >= 15, "vacuity guard: only $firedCells cells fired")
        assertEquals(emptyList(), problems, "violations of the documented promise:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `a poll tick whose position or buffered write makes an inline collector pause leaves the player paused`() {
        // EngineVideoPlayer.pollLocked: "Compare-and-set as well as the lock: ... a collector of one of the two flows above
        // re-entered and changed the state, and then that transition's writes stand."
        for (watched: Watched in listOf(Watched.Position, Watched.Buffered)) {
            val scheduler = TestCoroutineScheduler()
            val engine = ScriptedEngineD1R11()
            val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L), StandardTestDispatcher(scheduler))
            val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
            try {
                runBlocking { player.prepare(source) }
                player.play()
                var paused = false
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    watchedFlow(player, watched).drop(1).collect {
                        if (!paused) {
                            paused = true
                            player.pause()
                        }
                    }
                }
                engine.position = 3_000L
                engine.buffered = 4_000L
                scheduler.advanceTimeBy(150L)
                scheduler.runCurrent()
                assertTrue(paused, "the collector of $watched never ran")
                assertTrue(player.stateFlow.value is VideoPlayerState.Paused, "$watched: the tick overwrote the collector's pause: ${player.stateFlow.value}")
                assertEquals(false, engine.playing, "$watched: the engine is paused")
                scheduler.advanceTimeBy(500L)
                scheduler.runCurrent()
                assertTrue(player.stateFlow.value is VideoPlayerState.Paused, "$watched: a later tick moved the paused player: ${player.stateFlow.value}")
            } finally {
                scope.cancel()
                player.release()
            }
        }
    }
}
