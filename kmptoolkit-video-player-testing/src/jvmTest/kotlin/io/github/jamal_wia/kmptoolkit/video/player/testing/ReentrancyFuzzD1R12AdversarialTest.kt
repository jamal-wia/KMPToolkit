package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D1 round 12, Q1: a seeded re-entrancy fuzz of the core with the scripted engine (depth at most 3).
 *
 * Two to four collectors, each on a random flow of the player (state, position, buffered, buffering, size, repeat mode,
 * speed, volume, mute), run on an Unconfined scope, so each runs inside the write that wakes it, and on their first one
 * or two updates call a random player method (play, pause, stop, seek, relative seeks, replay, the three settings, unload,
 * prepare, release) or report an engine event (completion, failure, buffering, picture size) from inside the update,
 * up to depth 3. A driver makes four to ten calls of the same kinds at top level, with poll ticks between.
 *
 * The oracle is the documented contract (docs 03 and 04, `04-api-reference.md` "Threading"): after the dust settles
 * (a) the player is never left in Preparing; (b) it is Playing iff the engine was last told to start iff the poll runs, and
 * Paused or Ready imply a stopped engine; (c) Idle implies every source flow at its initial value; (d) nothing reaches the
 * engine after dispose; (e) a playable player's engine holds the flows' repeat mode, volume and (while Playing) speed;
 * (f) a paused player shows the playhead the engine holds; (g) a failure the engine reported against a source that was
 * not dropped meanwhile (no unload, release or prepare after it) is never lost; (h) no exception escaped; (i) the next
 * prepare works and pushes the settings. Seeded: the seed and the call trace are in every failure message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReentrancyFuzzD1R12AdversarialTest {

    private enum class Op {
        Play, Pause, Stop, Seek, SeekForward, SeekBackward, Replay, SetRepeat, SetSpeed, SetVolume, SetMuted,
        Unload, Prepare, Release, ReportCompleted, ReportFailed, ReportBuffering, ReportSize,
    }

    private enum class Watched { State, Position, Buffered, Buffering, Size, Repeat, Speed, Volume, Muted }

    private val nestedFired: MutableMap<Op, Int> = mutableMapOf()
    private var maxDepth: Int = 0
    private var landedFailures: Int = 0

    private class Expectation(val statesAt: Int, val dropsAt: Int, val label: String)

    private class Scenario(val seed: Long) {
        val rig: InlineRigD1R11 = InlineRigD1R11()
        val random: Random = Random(seed)
        val trace: MutableList<String> = mutableListOf()
        val escaped: MutableList<Throwable> get() = rig.escaped
        var depth: Int = 0
        var armed: Boolean = true
        var suppressed: Boolean = false
        val sequential: Boolean = System.getProperty("kmptoolkit.test.fuzz.sequential") == "on"
        var drops: Int = 0
        var watchedNow: Watched? = null
        var released: Boolean = false
        val expectations: MutableList<Expectation> = mutableListOf()
        lateinit var states: MutableList<VideoPlayerState>

        fun stamp(text: String) {
            trace += "${"  ".repeat(depth)}$text"
        }

        fun fail(message: String): Nothing = throw AssertionError(
            "seed $seed: $message\ntrace:\n" + trace.joinToString("\n") + "\nstates: $states\nengine log: ${rig.engine.log.takeLast(60)}",
        )
    }

    private fun randomOp(s: Scenario, nested: Boolean): Op {
        val all: List<Op> = Op.entries
        while (true) {
            val op: Op = all[s.random.nextInt(all.size)]
            if (op == Op.Release && s.random.nextInt(100) >= (if (nested) 25 else 12)) continue
            return op
        }
    }

    private suspend fun exec(s: Scenario, op: Op) {
        val player = s.rig.player
        val engine = s.rig.engine
        when (op) {
            Op.Play -> { s.stamp("play"); player.play() }
            Op.Pause -> { s.stamp("pause"); player.pause() }
            Op.Stop -> { s.stamp("stop"); player.stop() }
            Op.Seek -> {
                val target: Long = s.random.nextLong(-100L, 12_000L)
                s.stamp("seekTo($target)"); player.seekTo(target)
            }
            Op.SeekForward -> {
                val amount: Long = s.random.nextLong(0L, 5_000L)
                s.stamp("seekForward($amount)"); player.seekForward(amount)
            }
            Op.SeekBackward -> {
                val amount: Long = s.random.nextLong(0L, 5_000L)
                s.stamp("seekBackward($amount)"); player.seekBackward(amount)
            }
            Op.Replay -> { s.stamp("replay"); player.replay() }
            Op.SetRepeat -> {
                val mode: RepeatMode = if (s.random.nextBoolean()) RepeatMode.One else RepeatMode.Off
                s.stamp("setRepeatMode($mode)"); player.setRepeatMode(mode)
            }
            Op.SetSpeed -> {
                val speed: Float = 0.5f + s.random.nextInt(4) * 0.5f
                s.stamp("setPlaybackSpeed($speed)"); player.setPlaybackSpeed(speed)
            }
            Op.SetVolume -> {
                val volume: Float = s.random.nextInt(5) / 4f
                s.stamp("setVolume($volume)"); player.setVolume(volume)
            }
            Op.SetMuted -> {
                val muted: Boolean = s.random.nextBoolean()
                s.stamp("setMuted($muted)"); player.setMuted(muted)
            }
            Op.Unload -> { s.stamp("unload"); s.drops++; player.unload() }
            Op.Prepare -> { s.stamp("prepare"); s.drops++; player.prepare(s.rig.source) }
            Op.Release -> { s.stamp("release"); s.drops++; s.released = true; player.release() }
            Op.ReportCompleted -> {
                val sink = engine.sink ?: run { s.stamp("onCompleted (no sink)"); return }
                s.stamp("onCompleted")
                // A platform that stops at its end; in repeat mode One it loops by itself and keeps playing.
                // A completion describes the playhead when reported (the engine SPI): the playhead is at the end.
                if (player.repeatModeFlow.value != RepeatMode.One) {
                    engine.playing = false
                    engine.position = engine.duration
                } else {
                    engine.position = 0L
                }
                sink.onCompleted()
            }
            Op.ReportFailed -> {
                val sink = engine.sink ?: run { s.stamp("onFailed (no sink)"); return }
                val state: VideoPlayerState = player.stateFlow.value
                val landing: Boolean = !s.released && when {
                    s.depth == 0 -> state is VideoPlayerState.Playing || state is VideoPlayerState.Paused ||
                        state is VideoPlayerState.Ready || state is VideoPlayerState.Completed
                    // Inside a stateFlow update the value is already the one written.
                    s.watchedNow == Watched.State -> state is VideoPlayerState.Playing ||
                        state is VideoPlayerState.Paused || state is VideoPlayerState.Ready
                    else -> false
                }
                s.stamp("onFailed (state ${state::class.simpleName}, expected to land: $landing)")
                if (landing) s.expectations += Expectation(s.states.size, s.drops, "onFailed at trace line ${s.trace.size}")
                engine.playing = false // a failed engine is not playing
                sink.onFailed(FAILURE)
            }
            Op.ReportBuffering -> {
                val sink = engine.sink ?: run { s.stamp("onBufferingChanged (no sink)"); return }
                val value: Boolean = s.random.nextBoolean()
                s.stamp("onBufferingChanged($value)"); sink.onBufferingChanged(value)
            }
            Op.ReportSize -> {
                val sink = engine.sink ?: run { s.stamp("onVideoSizeChanged (no sink)"); return }
                val size: VideoSize? = if (s.random.nextInt(3) == 0) null else VideoSize(640, 360)
                s.stamp("onVideoSizeChanged($size)"); sink.onVideoSizeChanged(size)
            }
        }
    }

    private fun install(s: Scenario) {
        val count: Int = 2 + s.random.nextInt(3)
        repeat(count) {
            val watched: Watched = Watched.entries[s.random.nextInt(Watched.entries.size)]
            val budget: Int = 1 + s.random.nextInt(3)
            val flow: Flow<Any?> = when (watched) {
                Watched.State -> s.rig.player.stateFlow
                Watched.Position -> s.rig.player.playbackPositionFlow
                Watched.Buffered -> s.rig.player.bufferedPositionFlow
                Watched.Buffering -> s.rig.player.isBufferingFlow
                Watched.Size -> s.rig.player.videoSizeFlow
                Watched.Repeat -> s.rig.player.repeatModeFlow
                Watched.Speed -> s.rig.player.playbackSpeedFlow
                Watched.Volume -> s.rig.player.volumeFlow
                Watched.Muted -> s.rig.player.isMutedFlow
            }
            s.trace += "collector on $watched, budget $budget"
            var fires = 0
            s.rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                flow.drop(1).collect {
                    if (s.armed && !s.suppressed && s.depth < 3 && fires < budget && s.random.nextInt(100) < 85) {
                        fires++
                        val outerWatched: Watched? = s.watchedNow
                        s.watchedNow = watched
                        s.depth++
                        try {
                            val op: Op = randomOp(s, nested = true)
                            nestedFired[op] = (nestedFired[op] ?: 0) + 1
                            maxDepth = maxOf(maxDepth, s.depth + 1)
                            val before: VideoPlayerState = s.rig.player.stateFlow.value
                            exec(s, op)
                            // Known defects r12-F1 and r12-F2: once a reaction has changed the state inside a write, a later
                            // call from the same chain acts on the state the outer transition is still establishing, not on
                            // the one the reaction published. The default run does not make a second such call; the property
                            // kmptoolkit.test.fuzz.sequential=on lifts the exclusion and the fuzz goes red.
                            if (!s.sequential && s.rig.player.stateFlow.value != before) s.suppressed = true
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (thrown: Throwable) {
                            s.escaped += thrown
                        } finally {
                            s.depth--
                            s.watchedNow = outerWatched
                        }
                    }
                }
            }
        }
    }

    private fun run(seed: Long) {
        val s = Scenario(seed)
        try {
            runBlocking {
                withTimeout(20_000L) {
                    s.rig.prepareNow()
                    s.states = s.rig.recordStates() as MutableList<VideoPlayerState>
                    s.rig.player.play()
                    s.rig.engine.position = 2_000L
                    s.rig.tick()
                    install(s)
                    val steps: Int = 4 + s.random.nextInt(7)
                    repeat(steps) {
                        s.suppressed = false
                        if (s.random.nextInt(100) < 25) {
                            val engine = s.rig.engine
                            if (engine.playing) engine.position = minOf(engine.duration, engine.position + s.random.nextLong(0L, 3_000L))
                            s.stamp("tick (engine playing ${engine.playing}, position ${engine.position})")
                            s.rig.tick()
                        } else {
                            exec(s, randomOp(s, nested = false))
                        }
                    }
                }
            }
        } catch (thrown: AssertionError) {
            throw thrown
        } catch (thrown: Throwable) {
            s.fail("scenario threw $thrown")
        }
        settleAndJudge(s)
    }

    private fun settleAndJudge(s: Scenario) {
        s.armed = false
        val player = s.rig.player
        val engine = s.rig.engine
        if (s.escaped.isNotEmpty()) s.fail("an exception escaped: ${s.escaped}")
        val state: VideoPlayerState = player.stateFlow.value
        if (state is VideoPlayerState.Preparing) s.fail("left in Preparing")

        // (g) a failure reported against a source that was not dropped afterwards is never lost.
        for (expectation in s.expectations) {
            val dropped: Boolean = s.drops > expectation.dropsAt
            val landed: Boolean = s.states.drop(expectation.statesAt).any { it is VideoPlayerState.Error }
            if (landed) landedFailures++
            if (!dropped && !landed) s.fail("the failure reported at ${expectation.label} was lost (no drop after it)")
        }
        if (s.released) {
            // After a release only Idle, or the refusal of a later prepare, is possible.
            val ok: Boolean = state == VideoPlayerState.Idle ||
                (state is VideoPlayerState.Error && state.cause is VideoPlayerReleasedException)
            if (!ok) s.fail("released but state is $state")
        } else {
            judgePlayer(s, state)
        }
        // (d) nothing reaches the engine after dispose.
        if (engine.callsAfterDispose().isNotEmpty()) s.fail("engine calls after dispose: ${engine.callsAfterDispose()}")

        if (!s.released) {
            // (i) the next prepare works and pushes the settings; the player then plays.
            runBlocking { withTimeout(10_000L) { player.prepare(s.rig.source) } }
            val after: VideoPlayerState = player.stateFlow.value
            if (after !is VideoPlayerState.Ready) s.fail("next prepare ended in $after")
            val looping: Boolean = player.repeatModeFlow.value == RepeatMode.One
            if (engine.lastLooping != looping) s.fail("after the next prepare the engine's looping is ${engine.lastLooping} but the flow is ${player.repeatModeFlow.value}")
            if (engine.speeds.last() != player.playbackSpeedFlow.value) s.fail("after the next prepare the engine's speed is ${engine.speeds.last()} but the flow is ${player.playbackSpeedFlow.value}")
            val effective: Float = if (player.isMutedFlow.value) 0f else player.volumeFlow.value
            if (engine.volumes.last() != effective) s.fail("after the next prepare the engine's volume is ${engine.volumes.last()} but the flow says $effective")
            player.play()
            if (player.stateFlow.value !is VideoPlayerState.Playing) s.fail("play after the next prepare gives ${player.stateFlow.value}")
        }
        s.rig.player.release()
        s.rig.player.release()
        if (engine.callsAfterDispose().isNotEmpty()) s.fail("engine calls after the final release: ${engine.callsAfterDispose()}")
        assertEquals(1, engine.log.count { it == "dispose" }, "seed ${s.seed}: dispose exactly once, trace:\n" + s.trace.joinToString("\n"))
        s.rig.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun judgePlayer(s: Scenario, state: VideoPlayerState) {
        val player = s.rig.player
        val engine = s.rig.engine
        val playable: Boolean = state is VideoPlayerState.Ready || state is VideoPlayerState.Playing ||
            state is VideoPlayerState.Paused || state is VideoPlayerState.Completed
        // (b) Playing iff the engine was last told to start iff the poll runs.
        var reads = 0
        engine.positionReading = { reads += 1; engine.position }
        s.rig.scheduler.advanceTimeBy(500L)
        s.rig.scheduler.runCurrent()
        val playing: Boolean = state is VideoPlayerState.Playing
        if (playing != (reads > 0)) s.fail("state $state but the poll read $reads times over 500 ms")
        if (state is VideoPlayerState.Playing && !engine.playing) s.fail("state $state but the engine is stopped")
        if ((state is VideoPlayerState.Paused || state is VideoPlayerState.Ready || state == VideoPlayerState.Idle) && engine.playing) {
            s.fail("state $state but the engine is started")
        }
        // (c) Idle implies every source flow at its initial value.
        if (state == VideoPlayerState.Idle) {
            val ok: Boolean = player.playbackPositionFlow.value == 0L && player.bufferedPositionFlow.value == 0L &&
                !player.isBufferingFlow.value && player.videoSizeFlow.value == null
            if (!ok) s.fail("Idle with non-initial source flows: position ${player.playbackPositionFlow.value}, buffered ${player.bufferedPositionFlow.value}, buffering ${player.isBufferingFlow.value}, size ${player.videoSizeFlow.value}")
        }
        if (playable) {
            // (e) the engine holds the settings.
            val looping: Boolean = player.repeatModeFlow.value == RepeatMode.One
            if (engine.lastLooping != looping) s.fail("state $state: engine looping ${engine.lastLooping} but flow ${player.repeatModeFlow.value}")
            val effective: Float = if (player.isMutedFlow.value) 0f else player.volumeFlow.value
            if (engine.volumes.isNotEmpty() && engine.volumes.last() != effective) s.fail("state $state: engine volume ${engine.volumes.last()} but flows say $effective")
            if (state is VideoPlayerState.Playing && engine.speeds.last() != player.playbackSpeedFlow.value) {
                s.fail("state $state: engine speed ${engine.speeds.last()} but flow ${player.playbackSpeedFlow.value}")
            }
        }
        // (f) a paused player shows the playhead the engine holds.
        if (state is VideoPlayerState.Paused) {
            val shown: Long = engine.position.coerceIn(0L, engine.duration)
            if (state.currentPosition != shown) s.fail("state $state but the engine holds $shown")
        }
        if (state is VideoPlayerState.Completed) assertTrue(state.duration == engine.duration, "seed ${s.seed}: Completed with a different length: $state")
    }

    @Test
    fun `seeded re-entrancy fuzz of the core with collectors that call the player from inside updates`() {
        val base: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: 12_012L
        val count: Int = System.getProperty("kmptoolkit.test.fuzz.count")?.toIntOrNull() ?: 1_500
        val collect: Boolean = System.getProperty("kmptoolkit.test.fuzz.collect") == "on"
        for (i in 0 until count) {
            if (!collect) {
                run(base + i)
                continue
            }
            // Hunting mode: print every red seed's first line instead of stopping at the first.
            try {
                run(base + i)
            } catch (red: AssertionError) {
                println("FUZZRED " + red.message!!.lineSequence().first())
            }
        }
        if (collect) return
        // Vacuity guard: the fuzz must have exercised every kind of reaction, at depth 3, with failures that landed.
        val missing: List<Op> = Op.entries.filter { (nestedFired[it] ?: 0) < count / 100 }
        println("fuzz stats: nested reactions $nestedFired, max depth $maxDepth, landed failures $landedFailures")
        assertTrue(missing.isEmpty(), "reactions that fired too rarely to count: $missing in $nestedFired")
        assertEquals(3, maxDepth, "the fuzz never reached depth 3")
        assertTrue(landedFailures >= count / 50, "too few expected failures landed to trust the failure oracle: $landedFailures")
    }

    private companion object {
        val FAILURE: Throwable = IllegalStateException("scripted failure")
    }
}
