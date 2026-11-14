package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runMainLooperUntil
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

/**
 * D1 round 13, Q1: the REAL Media3 engine (Robolectric, the test ExoPlayer with a fake source) behind the shared player,
 * driven by consumers whose collectors call the player re-entrantly. Seeded: two or three Unconfined collectors on random
 * flows (state, position, buffered, buffering, size, repeat mode, speed, volume, mute) each react once or twice with a random
 * call (play, pause, stop, seeks, replay, the three settings, unload, an asynchronous prepare) from inside the update,
 * between top-level calls and looper pumps. After everything settled the ExoPlayer must agree with the player's flows:
 * `playWhenReady` iff `Playing` (Idle, Ready, Paused), a playable player holds exactly one item and the flows' volume and
 * repeat mode (and the speed while `Playing`), an `Idle` player holds none, nothing is left `Preparing`, no exception escaped.
 * A second oracle, off by default (`-Pkmptoolkit.test.fuzz.sourceOracle=on`): no transport or settings call reaches the
 * engine between its release and the next load (the SPI tolerates such calls; D1 r13 N1 is the one known way to make them).
 */
@RunWith(AndroidJUnit4::class)
class Media3ReentrantConsumersD1R13AdversarialTest {

    private enum class Op {
        Play, Pause, Stop, Seek, SeekForward, Replay, SetRepeat, SetSpeed, SetVolume, SetMuted, Unload, Prepare,
    }

    private enum class Watched { State, Position, Buffered, Buffering, Size, Repeat, Speed, Volume, Muted }

    /** Records the engine calls of the shared player, so "a call with no source held" is checkable. */
    private class LoggingEngine(private val inner: Media3VideoEngine) : VideoPlaybackEngine by inner {
        val log: MutableList<String> = mutableListOf()
        override suspend fun load(source: VideoSource) {
            log += "load"
            inner.load(source)
        }
        override fun start() { log += "start"; inner.start() }
        override fun pause() { log += "pause"; inner.pause() }
        override fun seekTo(positionMs: Long) { log += "seek"; inner.seekTo(positionMs) }
        override fun setSpeed(speed: Float) { log += "speed"; inner.setSpeed(speed) }
        override fun setVolume(volume: Float) { log += "volume"; inner.setVolume(volume) }
        override fun setLooping(looping: Boolean) { log += "looping"; inner.setLooping(looping) }
        override fun release() { log += "release"; inner.release() }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Remote("https://example.test/x.mp4")

    private inner class Scenario(val seed: Long) {
        val random: Random = Random(seed)
        var exo: ExoPlayer? = null
        val media3: Media3VideoEngine = newEngine(context, created = { exo = it })
        val engine: LoggingEngine = LoggingEngine(media3)
        val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined)
        val scope: CoroutineScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val trace: MutableList<String> = mutableListOf()
        val escaped: MutableList<Throwable> = mutableListOf()
        val prepares: MutableList<Job> = mutableListOf()
        val states: MutableList<VideoPlayerState> = mutableListOf()
        var armed: Boolean = true
        var depth: Int = 0

        fun fail(message: String): Nothing = throw AssertionError(
            "seed $seed: $message\ntrace:\n" + trace.joinToString("\n") + "\nstates: $states\nengine log: ${engine.log}",
        )
    }

    private val nestedFired: MutableMap<Op, Int> = mutableMapOf()
    private val finalStates: MutableMap<String, Int> = mutableMapOf()

    private fun exec(s: Scenario, op: Op) {
        if (s.depth > 0) nestedFired[op] = (nestedFired[op] ?: 0) + 1
        val player: VideoPlayer = s.player
        val pad: String = "  ".repeat(s.depth)
        when (op) {
            Op.Play -> { s.trace += "${pad}play"; player.play() }
            Op.Pause -> { s.trace += "${pad}pause"; player.pause() }
            Op.Stop -> { s.trace += "${pad}stop"; player.stop() }
            Op.Seek -> { val t: Long = s.random.nextLong(-100L, 6_000L); s.trace += "${pad}seekTo($t)"; player.seekTo(t) }
            Op.SeekForward -> { val t: Long = s.random.nextLong(0L, 3_000L); s.trace += "${pad}seekForward($t)"; player.seekForward(t) }
            Op.Replay -> { s.trace += "${pad}replay"; player.replay() }
            Op.SetRepeat -> {
                val mode: RepeatMode = if (s.random.nextBoolean()) RepeatMode.One else RepeatMode.Off
                s.trace += "${pad}setRepeatMode($mode)"; player.setRepeatMode(mode)
            }
            Op.SetSpeed -> { val v: Float = 0.5f + s.random.nextInt(4) * 0.5f; s.trace += "${pad}setPlaybackSpeed($v)"; player.setPlaybackSpeed(v) }
            Op.SetVolume -> { val v: Float = s.random.nextInt(5) / 4f; s.trace += "${pad}setVolume($v)"; player.setVolume(v) }
            Op.SetMuted -> { val m: Boolean = s.random.nextBoolean(); s.trace += "${pad}setMuted($m)"; player.setMuted(m) }
            Op.Unload -> { s.trace += "${pad}unload"; player.unload() }
            Op.Prepare -> {
                s.trace += "${pad}prepare (async)"
                s.prepares += s.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        player.prepare(source)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (thrown: Throwable) {
                        s.escaped += thrown
                    }
                }
            }
        }
    }

    private fun install(s: Scenario) {
        repeat(2 + s.random.nextInt(2)) {
            val watched: Watched = Watched.entries[s.random.nextInt(Watched.entries.size)]
            val budget: Int = 1 + s.random.nextInt(2)
            val flow: Flow<Any?> = when (watched) {
                Watched.State -> s.player.stateFlow
                Watched.Position -> s.player.playbackPositionFlow
                Watched.Buffered -> s.player.bufferedPositionFlow
                Watched.Buffering -> s.player.isBufferingFlow
                Watched.Size -> s.player.videoSizeFlow
                Watched.Repeat -> s.player.repeatModeFlow
                Watched.Speed -> s.player.playbackSpeedFlow
                Watched.Volume -> s.player.volumeFlow
                Watched.Muted -> s.player.isMutedFlow
            }
            s.trace += "collector on $watched, budget $budget"
            var fires = 0
            s.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                flow.drop(1).collect {
                    if (s.armed && s.depth < 3 && fires < budget && s.random.nextInt(100) < 85) {
                        fires++
                        s.depth++
                        try {
                            exec(s, Op.entries[s.random.nextInt(Op.entries.size)])
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (thrown: Throwable) {
                            s.escaped += thrown
                        } finally {
                            s.depth--
                        }
                    }
                }
            }
        }
    }

    private fun judge(s: Scenario, sourceOracle: Boolean) {
        val state: VideoPlayerState = s.player.stateFlow.value
        finalStates[state::class.simpleName ?: "?"] = (finalStates[state::class.simpleName ?: "?"] ?: 0) + 1
        val exo: ExoPlayer = checkNotNull(s.exo) { "no ExoPlayer was built" }
        if (s.escaped.isNotEmpty()) s.fail("an exception escaped: ${s.escaped}")
        if (state is VideoPlayerState.Preparing) s.fail("left in Preparing")
        val playing: Boolean = state is VideoPlayerState.Playing
        val playable: Boolean = playing || state is VideoPlayerState.Ready || state is VideoPlayerState.Paused ||
            state is VideoPlayerState.Completed
        if (state !is VideoPlayerState.Completed && state !is VideoPlayerState.Error && exo.playWhenReady != playing) {
            s.fail("state $state but playWhenReady=${exo.playWhenReady}")
        }
        if (state == VideoPlayerState.Idle && exo.mediaItemCount != 0) s.fail("Idle but the ExoPlayer holds ${exo.mediaItemCount} items")
        if (playable && exo.mediaItemCount != 1) s.fail("state $state but the ExoPlayer holds ${exo.mediaItemCount} items")
        if (playable) {
            val effective: Float = if (s.player.isMutedFlow.value) 0f else s.player.volumeFlow.value
            if (exo.volume != effective) s.fail("state $state: ExoPlayer volume ${exo.volume} but the flows say $effective")
            val expectedRepeat: Int = if (s.player.repeatModeFlow.value == RepeatMode.One) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            if (exo.repeatMode != expectedRepeat) s.fail("state $state: ExoPlayer repeat mode ${exo.repeatMode} but the flow says ${s.player.repeatModeFlow.value}")
            if (playing && exo.playbackParameters.speed != s.player.playbackSpeedFlow.value) {
                s.fail("state $state: ExoPlayer speed ${exo.playbackParameters.speed} but the flow says ${s.player.playbackSpeedFlow.value}")
            }
        }
        if (sourceOracle) {
            var holds = false
            val calls: Set<String> = setOf("start", "pause", "seek", "speed", "volume", "looping")
            for ((index: Int, entry: String) in s.engine.log.withIndex()) {
                when {
                    entry == "load" -> holds = true
                    entry == "release" -> holds = false
                    entry in calls && !holds -> s.fail("engine call '$entry' with no source held at log index $index")
                }
            }
        }
    }

    private fun run(seed: Long, sourceOracle: Boolean) {
        val s: Scenario = Scenario(seed)
        try {
            runSuspending { s.player.prepare(source) }.getOrThrow()
            Media3TestSupport.attachSurface(requireNotNull(s.exo))
            scenarioSetup(s)
            repeat(3 + s.random.nextInt(5)) {
                if (s.random.nextInt(100) < 30) {
                    s.trace += "pump"
                    ShadowLooper.idleMainLooper()
                } else {
                    exec(s, Op.entries[s.random.nextInt(Op.entries.size)])
                }
            }
            s.armed = false
            runMainLooperUntil { s.prepares.none { it.isActive } }
            ShadowLooper.idleMainLooper()
            judge(s, sourceOracle)
        } catch (thrown: AssertionError) {
            throw thrown
        } catch (thrown: Throwable) {
            s.fail("scenario threw $thrown")
        } finally {
            s.scope.cancel()
            s.player.release()
            ShadowLooper.idleMainLooper()
        }
    }

    private fun scenarioSetup(s: Scenario) {
        s.scope.launch(start = CoroutineStart.UNDISPATCHED) { s.player.stateFlow.collect { s.states += it } }
        if (s.random.nextBoolean()) s.player.play()
        ShadowLooper.idleMainLooper()
        install(s)
    }

    @Test
    fun `seeded re-entrant consumers over the real Media3 engine leave the ExoPlayer in agreement with the flows`() {
        val base: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: 13_013L
        val count: Int = System.getProperty("kmptoolkit.test.fuzz.count")?.toIntOrNull() ?: 60
        val oracle: Boolean = System.getProperty("kmptoolkit.test.fuzz.sourceOracle") == "on"
        val collect: Boolean = System.getProperty("kmptoolkit.test.fuzz.collect") == "on"
        var red = 0
        for (i in 0 until count) {
            if (!collect) { run(base + i, oracle); continue }
            try {
                run(base + i, oracle)
            } catch (failure: AssertionError) {
                red++
                println("FUZZRED " + failure.message!!.lineSequence().first())
            }
        }
        println("media3 fuzz stats: nested reactions $nestedFired, final states $finalStates")
        if (collect) return
        // Vacuity guard: every kind of reaction fired from inside an update, and the scenarios ended in several states.
        val missing: List<Op> = Op.entries.filter { (nestedFired[it] ?: 0) < count / 20 }
        assertTrue(missing.isEmpty(), "reactions that fired too rarely: $missing in $nestedFired")
        assertTrue(finalStates.size >= 3, "the scenarios ended in too few different states: $finalStates")
        assertEquals(0, red)
    }
}
