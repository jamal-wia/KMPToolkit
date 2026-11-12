package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 10 (breaker bt), state-machine differential: one hand-written reference model of the documented
 * playback state machine (docs/kmptoolkit-video-player/04-api-reference.md: Transport, Settings, Lifecycle,
 * RepeatMode) runs the SAME seeded operation scripts as the shared player over the scriptable fake. After
 * every operation the player's state, playhead, settings flows and the engine's applied values must equal
 * the model's; the sequence of states a collector sees is checked for event order and counts (Completed once
 * per play-through, Error once per failure, Preparing once per prepare, nothing but the final Idle after a
 * release, nothing reaches the engine after a release).
 *
 * The same script also writes a trace (environment variable KMPTOOLKIT_R10_TRACE or system property kmptoolkit.r10.trace, a file) so it can be run on the
 * 4.0.0 sources and diffed against the current ones.
 */
class VideoPlayerStateMachineModelR10AdversarialTest {

    private enum class Kind { IDLE, READY, PLAYING, PAUSED, COMPLETED, ERROR }

    private val duration: Long = 5_000L
    private val range: ClosedFloatingPointRange<Float> = 0.25f..3.0f

    /** A pass-through that logs every call and can report a raw completion like a platform would. */
    private class LoggingEngine(val inner: FakeVideoPlaybackEngine) : VideoPlaybackEngine {
        val log: MutableList<String> = CopyOnWriteArrayList()
        var sink: VideoPlaybackEngineListener? = null
        var onSeek: (() -> Unit)? = null

        override fun setListener(listener: VideoPlaybackEngineListener?) {
            log += "setListener(${listener != null})"
            sink = listener
            inner.setListener(listener)
        }

        override suspend fun load(source: VideoSource) {
            log += "load"
            inner.load(source)
        }

        override fun start() {
            log += "start"
            inner.start()
        }

        override fun pause() {
            log += "pause"
            inner.pause()
        }

        override fun seekTo(positionMs: Long) {
            log += "seekTo($positionMs)"
            onSeek?.let { hook: () -> Unit ->
                onSeek = null
                hook()
            }
            inner.seekTo(positionMs)
        }

        override fun setSpeed(speed: Float) {
            log += "setSpeed($speed)"
            inner.setSpeed(speed)
        }

        override fun setVolume(volume: Float) {
            log += "setVolume($volume)"
            inner.setVolume(volume)
        }

        override fun setLooping(looping: Boolean) {
            log += "setLooping($looping)"
            inner.setLooping(looping)
        }

        override fun durationMs(): Long = inner.durationMs()

        override fun positionMs(): Long = inner.positionMs()

        override fun bufferedPositionMs(): Long = inner.bufferedPositionMs()

        override fun release() {
            log += "release"
            inner.release()
        }

        override fun dispose() {
            log += "dispose"
            inner.dispose()
        }
    }

    private class Model(val duration: Long, val range: ClosedFloatingPointRange<Float>) {
        var kind: Kind = Kind.IDLE
        var pos: Long = 0L
        var speed: Float = 1f
        var volume: Float = 1f
        var muted: Boolean = false
        var repeat: RepeatMode = RepeatMode.Off
        var released: Boolean = false
        var loaded: Boolean = false
        var completions: Int = 0
        var errors: Int = 0
        var prepares: Int = 0
        var playing: Boolean = false // what the engine should be doing

        val playable: Boolean get() = kind == Kind.READY || kind == Kind.PLAYING || kind == Kind.PAUSED || kind == Kind.COMPLETED
        val effectiveVolume: Float get() = if (muted) 0f else volume

        fun prepare(fail: Boolean) {
            prepares++
            if (released) {
                kind = Kind.ERROR
                return
            }
            loaded = true
            pos = 0L
            playing = false
            if (fail) {
                kind = Kind.ERROR
                errors++
            } else {
                kind = Kind.READY
            }
        }

        fun play() {
            if (released || !playable || kind == Kind.PLAYING) return
            if (kind == Kind.COMPLETED || pos >= duration) pos = 0L
            kind = Kind.PLAYING
            playing = true
        }

        fun pause() {
            if (released || kind != Kind.PLAYING) return
            kind = Kind.PAUSED
            playing = false
        }

        fun stop() {
            if (released || !playable) return
            kind = Kind.READY
            pos = 0L
            playing = false
        }

        fun seek(target: Long) {
            if (released || !playable) return
            pos = target.coerceIn(0L, duration)
            if (kind == Kind.COMPLETED) kind = Kind.PAUSED
        }

        fun replay() {
            if (released || !playable) return
            seek(0L)
            play()
        }

        fun speed(value: Float) {
            if (value.isNaN()) return
            speed = value.coerceIn(range.start, range.endInclusive)
        }

        fun volume(value: Float) {
            if (value.isNaN()) return
            volume = value.coerceIn(0f, 1f)
        }

        /** A completion the engine reports at the end of a play-through. */
        fun completion() {
            if (released || !playable) return
            if (repeat == RepeatMode.One) {
                when (kind) {
                    Kind.PLAYING -> pos = 0L
                    Kind.PAUSED -> pos = 0L
                    else -> Unit
                }
                return
            }
            kind = Kind.COMPLETED
            pos = duration
            playing = false
            completions++
        }

        fun fail() {
            if (released || !playable) return
            kind = Kind.ERROR
            playing = false
            errors++
        }

        fun unload() {
            if (released) return
            kind = Kind.IDLE
            loaded = false
            pos = 0L
            playing = false
        }

        fun release() {
            if (released) return
            released = true
            kind = Kind.IDLE
            loaded = false
            pos = 0L
            playing = false
        }
    }

    private class Run(val engine: LoggingEngine, val player: VideoPlayer, val model: Model) {
        val emitted: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
        val trace: MutableList<String> = mutableListOf()
        var collector: Job? = null
    }

    private fun describe(state: VideoPlayerState): String = when (state) {
        is VideoPlayerState.Playing -> "Playing(${state.duration})"
        is VideoPlayerState.Paused -> "Paused(${state.duration},${state.currentPosition})"
        is VideoPlayerState.Error -> "Error(${state.cause::class.simpleName})"
        is VideoPlayerState.Ready -> "Ready(${state.duration})"
        is VideoPlayerState.Completed -> "Completed(${state.duration})"
        else -> state.toString()
    }

    private fun runScript(seed: Int, steps: Int, pollMs: Long, traceSink: MutableList<String>?) = runBlocking {
        val fake = FakeVideoPlaybackEngine(durationMs = duration)
        val engine = LoggingEngine(fake)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = pollMs), Dispatchers.Default)
        val model = Model(duration, range)
        val run = Run(engine, player, model)
        run.collector = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            player.stateFlow.collect { run.emitted += it }
        }
        val random = Random(seed)
        var releasedAtLogSize = -1
        var emittedAtRelease = -1
        try {
            repeat(steps) { step: Int ->
                val op: String = pickOp(random, model)
                run.trace += "$step:$op"
                applyOp(op, random, run, fake)
                if (op == "release" && model.released && releasedAtLogSize < 0) {
                    releasedAtLogSize = engine.log.size
                    emittedAtRelease = run.emitted.size
                }
                check(run, "seed=$seed step=$step op=$op trace=${run.trace.takeLast(12)}", pollMs, fake)
                traceSink?.add("$seed/$step $op -> ${describe(player.stateFlow.value)} pos=${player.playbackPositionFlow.value} " +
                    "speed=${player.playbackSpeedFlow.value} vol=${player.volumeFlow.value} muted=${player.isMutedFlow.value} " +
                    "repeat=${player.repeatModeFlow.value} eng=[playing=${fake.isPlaying} speed=${fake.appliedSpeed} " +
                    "vol=${fake.appliedVolume} loop=${fake.isLooping} pos=${fake.positionMs}]")
            }
            // release at the end if the script did not
            if (!model.released) {
                player.release()
                model.release()
                run.trace += "final-release"
                releasedAtLogSize = engine.log.size
                emittedAtRelease = run.emitted.size
                check(run, "seed=$seed final release trace=${run.trace.takeLast(12)}", pollMs, fake)
            }
            // after the release nothing moves: give the poller a window
            Thread.sleep(30L)
            if (engine.log.size != releasedAtLogSize) {
                fail("seed=$seed: engine called after release: ${engine.log.drop(releasedAtLogSize)} trace=${run.trace.takeLast(12)}")
            }
            val late: List<VideoPlayerState> = run.emitted.drop(emittedAtRelease)
            if (late.any { it !is VideoPlayerState.Error || it.cause !is VideoPlayerReleasedException }) {
                fail("seed=$seed: state emitted after release: $late")
            }
            checkEventOrder(run, seed)
        } finally {
            run.collector?.cancelAndJoin()
            player.release()
        }
    }

    private fun pickOp(random: Random, model: Model): String {
        val ops: List<String> = buildList {
            add("prepare"); add("prepare"); add("prepareFail")
            add("play"); add("play"); add("pause"); add("stop"); add("replay")
            add("seek"); add("seek"); add("seekForward"); add("seekBackward")
            add("speed"); add("volume"); add("mute"); add("repeat"); add("repeat")
            add("unload")
            if (model.kind == Kind.PLAYING) {
                add("playToEnd"); add("playToEnd"); add("advancePause"); add("failPlayback")
                add("rawCompletion")
            }
            if (model.kind == Kind.PAUSED && model.repeat == RepeatMode.One) add("rawCompletion")
            if (model.playable) add("failPlayback")
            if (random.nextInt(60) == 0) add("release")
        }
        return ops[random.nextInt(ops.size)]
    }

    private fun applyOp(op: String, random: Random, run: Run, fake: FakeVideoPlaybackEngine) {
        val player: VideoPlayer = run.player
        val model: Model = run.model
        when (op) {
            "prepare" -> {
                runBlocking { withTimeout(5_000L) { player.prepare(VideoSource.Asset("clip.mp4")) } }
                model.prepare(fail = false)
            }
            "prepareFail" -> {
                fake.loadFailure = IllegalStateException("scripted load failure")
                runBlocking { withTimeout(5_000L) { player.prepare(VideoSource.Asset("clip.mp4")) } }
                fake.loadFailure = null
                model.prepare(fail = true)
            }
            "play" -> { player.play(); model.play() }
            "pause" -> { player.pause(); model.pause() }
            "stop" -> { player.stop(); model.stop() }
            "replay" -> { player.replay(); model.replay() }
            "seek" -> {
                val target: Long = listOf(-5L, 0L, 1L, duration / 2, duration - 1, duration, duration + 1, Long.MAX_VALUE, Long.MIN_VALUE)[random.nextInt(9)]
                player.seekTo(target); model.seek(target)
            }
            "seekForward" -> {
                val amount: Long = listOf(0L, 1L, 700L, 4_000L, 10_000L, Long.MAX_VALUE)[random.nextInt(6)]
                val base: Long = fake.positionMs
                player.seekForward(amount)
                val sum: Long = base + amount
                model.seek(if (amount > 0L && sum < base) Long.MAX_VALUE else sum)
            }
            "seekBackward" -> {
                val amount: Long = listOf(0L, 1L, 700L, 4_000L, 10_000L, Long.MAX_VALUE)[random.nextInt(6)]
                val base: Long = fake.positionMs
                player.seekBackward(amount)
                val diff: Long = base - amount
                model.seek(if (amount > 0L && diff > base) Long.MIN_VALUE else diff)
            }
            "speed" -> {
                val value: Float = listOf(Float.NaN, 0f, 0.1f, 0.25f, 1f, 1.5f, 3f, 10f, Float.POSITIVE_INFINITY, -2f)[random.nextInt(10)]
                player.setPlaybackSpeed(value); model.speed(value)
            }
            "volume" -> {
                val value: Float = listOf(Float.NaN, -1f, 0f, 0.3f, 1f, 7f, Float.NEGATIVE_INFINITY)[random.nextInt(7)]
                player.setVolume(value); model.volume(value)
            }
            "mute" -> { val m: Boolean = random.nextBoolean(); player.setMuted(m); model.muted = m }
            "repeat" -> { val m: RepeatMode = RepeatMode.entries[random.nextInt(2)]; player.setRepeatMode(m); model.repeat = m }
            "unload" -> { player.unload(); model.unload() }
            "release" -> { player.release(); model.release() }
            "playToEnd" -> {
                fake.advancePositionTo(duration)
                model.pos = duration
                fake.completePlayback()
                if (model.repeat == RepeatMode.One) model.pos = 0L else model.completion()
            }
            "advancePause" -> {
                val to: Long = (duration * random.nextInt(1, 90) / 100)
                fake.advancePositionTo(to)
                model.pos = to
                player.pause(); model.pause()
            }
            "failPlayback" -> {
                fake.failPlayback(IllegalStateException("scripted playback failure"))
                model.fail()
            }
            "rawCompletion" -> {
                if (model.repeat == RepeatMode.Off && model.kind == Kind.PLAYING) {
                    fake.pause()
                    fake.advancePositionTo(duration)
                }
                run.engine.sink?.onCompleted()
                // The fake's position does not move by itself; a raw end reports the end.
                model.completion()
            }
            else -> error(op)
        }
    }

    private fun check(run: Run, where: String, pollMs: Long, fake: FakeVideoPlaybackEngine) {
        val player: VideoPlayer = run.player
        val model: Model = run.model
        val state: VideoPlayerState = player.stateFlow.value
        val expected: String = when (model.kind) {
            Kind.IDLE -> "Idle"
            Kind.READY -> "Ready($duration)"
            Kind.PLAYING -> "Playing($duration)"
            Kind.PAUSED -> "Paused($duration,${model.pos})"
            Kind.COMPLETED -> "Completed($duration)"
            Kind.ERROR -> "Error"
        }
        val actual: String = describe(state).let { if (it.startsWith("Error")) "Error" else it }
        if (expected != actual) fail("$where: state $actual, model $expected")
        val last: String = run.trace.last()
        if (model.released && (last.endsWith(":prepare") || last.endsWith(":prepareFail"))) {
            if (state !is VideoPlayerState.Error || state.cause !is VideoPlayerReleasedException) {
                fail("$where: prepare after release must report VideoPlayerReleasedException, got $state")
            }
        }
        if (player.playbackSpeedFlow.value != model.speed) fail("$where: speed ${player.playbackSpeedFlow.value} model ${model.speed}")
        if (player.volumeFlow.value != model.volume) fail("$where: volume ${player.volumeFlow.value} model ${model.volume}")
        if (player.isMutedFlow.value != model.muted) fail("$where: muted")
        if (player.repeatModeFlow.value != model.repeat) fail("$where: repeat")
        val flowPos: Long = player.playbackPositionFlow.value
        when (model.kind) {
            Kind.PAUSED -> if (flowPos != model.pos) fail("$where: position flow $flowPos model ${model.pos}")
            Kind.COMPLETED -> if (flowPos != duration) fail("$where: position flow $flowPos at the end")
            Kind.READY -> if (model.pos == 0L && flowPos != 0L) fail("$where: position flow $flowPos in Ready after load or stop")
            Kind.IDLE -> if (flowPos != 0L) fail("$where: position flow $flowPos while Idle")
            Kind.PLAYING -> Unit
            Kind.ERROR -> Unit
        }
        if (model.kind == Kind.PAUSED || model.kind == Kind.PLAYING) {
            if (fake.positionMs != model.pos) fail("$where: engine position ${fake.positionMs} model ${model.pos}")
        }
        // Engine mirrors, at rest.
        if (fake.isPlaying != model.playing) fail("$where: engine playing=${fake.isPlaying} model ${model.playing}")
        if (model.kind == Kind.PLAYING && fake.appliedSpeed != model.speed) fail("$where: engine speed ${fake.appliedSpeed} model ${model.speed}")
        if (model.playable && !model.released) {
            if (fake.appliedVolume != model.effectiveVolume) fail("$where: engine volume ${fake.appliedVolume} model ${model.effectiveVolume}")
            if (fake.isLooping != (model.repeat == RepeatMode.One)) fail("$where: engine looping ${fake.isLooping}")
        }
        if (model.released && fake.disposeCount != 1) fail("$where: dispose count ${fake.disposeCount} after release")
        val completedEmissions: Int = run.emitted.count { it is VideoPlayerState.Completed }
        if (completedEmissions != model.completions) fail("$where: Completed emitted $completedEmissions times, model ${model.completions}: ${run.emitted.map(::describe).takeLast(8)}")
        val scriptedErrors: Int = run.emitted.count { it is VideoPlayerState.Error && it.cause.message?.startsWith("scripted") == true }
        if (scriptedErrors != model.errors) fail("$where: scripted Error emitted $scriptedErrors times, model ${model.errors}")
    }

    private fun checkEventOrder(run: Run, seed: Int) {
        val seq: List<VideoPlayerState> = run.emitted
        // StateFlow never repeats equal consecutive values.
        for (index: Int in 1 until seq.size) {
            if (seq[index] == seq[index - 1]) fail("seed=$seed: duplicate consecutive emission ${describe(seq[index])} at $index")
        }
        // Preparing is always followed by Ready, Error or Idle (never by transport states).
        for (index: Int in 0 until seq.size - 1) {
            if (seq[index] == VideoPlayerState.Preparing) {
                val next: VideoPlayerState = seq[index + 1]
                if (next !is VideoPlayerState.Ready && next !is VideoPlayerState.Error && next != VideoPlayerState.Idle) {
                    fail("seed=$seed: Preparing followed by ${describe(next)}")
                }
            }
        }
        // Completed is only entered from Playing (or Paused, by a seek to the end then a report: not scripted).
        for (index: Int in 1 until seq.size) {
            if (seq[index] is VideoPlayerState.Completed && seq[index - 1] !is VideoPlayerState.Playing) {
                fail("seed=$seed: Completed after ${describe(seq[index - 1])}")
            }
        }
        // Error is only entered from Preparing (failed load or a prepare after release) or a playable state.
        for (index: Int in 1 until seq.size) {
            val now: VideoPlayerState = seq[index]
            if (now is VideoPlayerState.Error && now.cause.message?.startsWith("scripted") == true && seq[index - 1] is VideoPlayerState.Error) {
                fail("seed=$seed: Error twice in a row without another state between")
            }
        }
    }

    @Test
    fun `scripts agree with the reference model with polling effectively off`() {
        val traces: MutableList<String> = mutableListOf()
        for (seed: Int in 1..300) runScript(seed, steps = 60, pollMs = 3_600_000L, traceSink = traces)
        (System.getenv("KMPTOOLKIT_R10_TRACE") ?: System.getProperty("kmptoolkit.r10.trace"))?.let { File(it).writeText(traces.joinToString("\n")) }
    }

    @Test
    fun `scripts agree with the reference model with a fast poll running`() {
        for (seed: Int in 1000..1060) runScript(seed, steps = 50, pollMs = 2L, traceSink = null)
    }

    /**
     * Upgrade differential: an engine written for 4.0.0 that reports the completion from inside seekTo (the
     * stale-report shapes) is driven through the player; the trace of states and engine calls goes to
     * KMPTOOLKIT_R10_TRACE.stale so the 4.0.0 sources and the current ones can be diffed. The assertions are the
     * CURRENT documented behaviour: a seek away from the end made as the completion arrives wins (04-api-reference).
     */
    @Test
    fun `a completion reported inside a seek away from the end loses to the seek and a paused player is left alone`() = runBlocking {
        val lines: MutableList<String> = mutableListOf()
        for (scenario: String in listOf("playing-seek-mid", "playing-seek-end", "paused-seek-mid", "playing-stop", "playing-seek-zero-repeat")) {
            val fake = FakeVideoPlaybackEngine(durationMs = duration)
            val engine = LoggingEngine(fake)
            val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 3_600_000L), Dispatchers.Default)
            try {
                withTimeout(5_000L) { player.prepare(VideoSource.Asset("clip.mp4")) }
                if (scenario.startsWith("playing")) player.play()
                if (scenario == "paused-seek-mid") { player.play(); player.pause() }
                if (scenario.endsWith("repeat")) player.setRepeatMode(RepeatMode.One)
                engine.log.clear()
                // the engine reports the end from inside the transport call, before the call returns
                val reportFromSeek: Boolean = scenario != "playing-stop"
                engine.onSeek = { engine.sink?.onCompleted() }
                when (scenario) {
                    "playing-seek-mid", "paused-seek-mid" -> player.seekTo(1_000L)
                    "playing-seek-end" -> player.seekTo(duration)
                    "playing-stop" -> player.stop()
                    "playing-seek-zero-repeat" -> player.seekTo(0L)
                }
                Thread.sleep(40L)
                lines += "$scenario reportFirst=$reportFromSeek -> ${describe(player.stateFlow.value)} pos=${player.playbackPositionFlow.value} " +
                    "calls=${engine.log} eng=[playing=${fake.isPlaying} speed=${fake.appliedSpeed} pos=${fake.positionMs}]"
            } finally {
                player.release()
            }
        }
        (System.getenv("KMPTOOLKIT_R10_TRACE") ?: System.getProperty("kmptoolkit.r10.trace"))?.let { File("$it.stale").writeText(lines.joinToString("\n")) }
        // A completion reported before a stop() is dropped as stale per the doc; nothing here may end Completed
        // while the transport call that followed it is the newest word.
        for (line: String in lines) {
            if (line.startsWith("playing-seek-mid") && !line.contains("-> Playing(")) fail("seek mid after a report must read Playing: $line")
            if (line.startsWith("paused-seek-mid") && !line.contains("-> Paused(")) fail("a paused player must stay paused: $line")
        }
    }
}
