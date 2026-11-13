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
 * D1 round 11 fix (F3). `04-api-reference.md`: a collector of `stateFlow`, or of any other flow of the player, on an
 * immediate dispatcher may call the player from inside the update; a transition stops writing as soon as such a call has
 * changed the player, and calls nothing more on the engine, so the call is never undone. This matrix runs every
 * transition with an inline collector of the position, the buffered position, the buffering flag and the picture size that
 * releases, unloads or pauses the player the moment its flow changes (the `stateFlow` row is the committed
 * `InlineCollectorReleaseMatrixD1R11AdversarialTest`). Virtual time, an Unconfined collector, no thread, no sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NonStateFlowCollectorMatrixFixD1R11Test {

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
    fun `a collector of another source flow that releases unloads or pauses inline is never undone by the rest of any transition`() {
        // The same promise for the position, the buffered position, the buffering flag and the picture size: not written in 04
        // (it names stateFlow only), but the one-line variation of a consumer that reacts to its seek bar.
        val problems: List<String> = matrix(listOf(Watched.Position, Watched.Buffered, Watched.Buffering, Watched.Size))
        assertTrue(firedCells >= 15, "vacuity guard: only $firedCells cells fired")
        assertEquals(emptyList(), problems, "violations:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `a position collector that releases the player inline stops a play from Completed before any engine call after the dispose`() {
        val scheduler = TestCoroutineScheduler()
        val engine = ScriptedEngineD1R11()
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L), StandardTestDispatcher(scheduler))
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            runBlocking { player.prepare(source) }
            player.play()
            engine.position = 10_000L
            checkNotNull(engine.sink).onCompleted()
            assertEquals(VideoPlayerState.Completed(10_000L), player.stateFlow.value, "the player has completed")
            var released = false
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.playbackPositionFlow.drop(1).collect {
                    if (!released) {
                        released = true
                        player.release()
                    }
                }
            }
            player.play() // the rewind writes position 0: the collector releases the player from inside that write
            assertTrue(released, "the collector ran")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "the release wins")
            assertEquals(emptyList(), engine.callsAfterDispose(), "04: nothing reaches the engine after the release")
            assertTrue("start" !in engine.log.drop(engine.log.indexOf("dispose") + 1), "no start after dispose: ${engine.log}")
            assertEquals(false, engine.playing, "the engine is not left playing")
        } finally {
            scope.cancel()
            player.release()
        }
    }

    @Test
    fun `a position collector whose pause is a no-op does not stop a seek and the player ends Paused at the target`() {
        val scheduler = TestCoroutineScheduler()
        val engine = ScriptedEngineD1R11()
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L), StandardTestDispatcher(scheduler))
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            runBlocking { player.prepare(source) }
            player.play()
            player.pause()
            assertTrue(player.stateFlow.value is VideoPlayerState.Paused, "the player is Paused")
            var calls = 0
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.playbackPositionFlow.drop(1).collect {
                    calls += 1
                    player.pause() // already Paused: writes nothing, so the seek must go on
                }
            }
            player.seekTo(2_000L)
            assertTrue(calls >= 1, "the collector ran")
            assertEquals(VideoPlayerState.Paused(10_000L, 2_000L), player.stateFlow.value, "04: Paused at the seek target")
            assertEquals(2_000L, player.playbackPositionFlow.value)
            assertEquals(listOf(2_000L), engine.seekTargets, "the engine got the seek")
        } finally {
            scope.cancel()
            player.release()
        }
    }
}
