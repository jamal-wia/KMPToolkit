package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 10 (breaker bt), state-machine differential on REAL JavaFX: the reference model of the documented
 * playback state machine (the one the fake-engine test in -testing runs) drives the same seeded scripts of
 * public calls through the shared player over [JavaFxVideoEngine] and the 3 s test clip. Real playback has a
 * clock, so a position read while playing is an interval, not a number, and the end of the clip is awaited
 * (never assumed). Checked after every call: state and duration, settings flows, the Paused position (exact
 * after a seek, inside the interval after a pause), the engine's own readbacks; over the whole script: the order
 * and count of the states a collector saw (Completed once per play-through and never with RepeatMode.One,
 * Preparing once per prepare, nothing after the release but the documented Error of a late prepare).
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxReferenceModelDifferentialR10AdversarialTest {

    private enum class Kind { IDLE, READY, PLAYING, PAUSED, COMPLETED, ERROR }

    private val duration: Long = TestClip.DURATION_MS
    private val KEY_FRAME_SLACK_MS: Long = 1_100L

    private class Model(val duration: Long) {
        var kind: Kind = Kind.IDLE
        var posLo: Long = 0L
        var posHi: Long = 0L
        var speed: Float = 1f
        var volume: Float = 1f
        var muted: Boolean = false
        var repeat: RepeatMode = RepeatMode.Off
        var released: Boolean = false
        var completions: Int = 0
        var playStartNs: Long = 0L
        var failures: Int = 0
        var loopedUnknown: Boolean = false

        val playable: Boolean get() = kind == Kind.READY || kind == Kind.PLAYING || kind == Kind.PAUSED || kind == Kind.COMPLETED
        val exact: Boolean get() = posLo == posHi

        fun setPos(p: Long) {
            loopedUnknown = false
            posLo = p
            posHi = p
        }

        fun startPlaying() {
            kind = Kind.PLAYING
            playStartNs = System.nanoTime()
        }

        /** An estimate of how far the playhead can have moved since play, never below what the clock allows. */
        fun upperNow(): Long {
            if (loopedUnknown) return duration
            val ms: Long = (System.nanoTime() - playStartNs) / 1_000_000L
            return minOf(duration, posLo + (ms * maxOf(speed, 1f)).toLong() + 400L)
        }

        fun prepare(fail: Boolean) {
            if (released) {
                kind = Kind.ERROR
                return
            }
            setPos(0L)
            if (fail) {
                kind = Kind.ERROR
                failures++
            } else {
                kind = Kind.READY
            }
        }

        fun play() {
            if (released || !playable || kind == Kind.PLAYING) return
            if (kind == Kind.COMPLETED || (exact && posLo >= duration)) setPos(0L)
            startPlaying()
        }

        fun pause() {
            if (released || kind != Kind.PLAYING) return
            posHi = upperNow()
            kind = Kind.PAUSED
        }

        fun stop() {
            if (released || !playable) return
            kind = Kind.READY
            setPos(0L)
        }

        fun seek(target: Long) {
            if (released || !playable) return
            setPos(target.coerceIn(0L, duration))
            if (kind == Kind.COMPLETED) kind = Kind.PAUSED
            if (kind == Kind.PLAYING) playStartNs = System.nanoTime()
        }

        fun unload() {
            if (released) return
            kind = Kind.IDLE
            setPos(0L)
        }

        fun release() {
            if (released) return
            released = true
            kind = Kind.IDLE
            setPos(0L)
        }
    }

    private fun describe(state: VideoPlayerState): String = when (state) {
        is VideoPlayerState.Playing -> "Playing(${state.duration})"
        is VideoPlayerState.Paused -> "Paused(${state.duration})"
        is VideoPlayerState.Ready -> "Ready(${state.duration})"
        is VideoPlayerState.Completed -> "Completed(${state.duration})"
        is VideoPlayerState.Error -> "Error(${state.cause::class.simpleName})"
        else -> state.toString()
    }

    private fun pausedAt(state: VideoPlayerState): Long = (state as VideoPlayerState.Paused).currentPosition

    private fun runScript(seed: Int, steps: Int) {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 40L))
        val model = Model(duration)
        val emitted: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { emitted += it } }
        val random = Random(seed)
        val trace: MutableList<String> = mutableListOf()
        val missing: File = File(File(System.getProperty("java.io.tmpdir")), "kmptoolkit-r10-missing-$seed.mp4")
        var emittedAtRelease = -1
        try {
            for (step: Int in 0 until steps) {
                val op: String = pickOp(random, model)
                trace += "$step:$op"
                val where = "seed=$seed step=$step op=$op trace=${trace.takeLast(14)}"
                if (model.kind == Kind.PLAYING && model.posLo + (System.nanoTime() - model.playStartNs) / 1_000_000L * model.speed.coerceAtLeast(1f) > duration - 700L) {
                    awaitEnd(player, model, emitted, where + " (before the op, near the end)")
                }
                applyOp(op, random, player, model, missing, where)
                if (op == "release") emittedAtRelease = emitted.size
                check(player, model, emitted, engine, where)
                Thread.sleep(random.nextLong(5L, 120L))
            }
            if (!model.released) {
                player.release()
                model.release()
                emittedAtRelease = emitted.size
            }
            Thread.sleep(150L)
            val late: List<VideoPlayerState> = emitted.drop(emittedAtRelease)
            if (late.any { it !is VideoPlayerState.Error || it.cause !is VideoPlayerReleasedException }) {
                fail("seed=$seed: state delivered after the release: $late; trace=${trace.takeLast(14)}")
            }
            checkOrder(emitted, model, seed, trace)
        } finally {
            scope.cancel()
            player.release()
        }
    }

    private fun pickOp(random: Random, model: Model): String {
        val ops: List<String> = buildList {
            add("prepare"); add("prepare"); add("prepareFail")
            add("play"); add("play"); add("play"); add("pause"); add("pause"); add("stop"); add("replay")
            add("seek"); add("seek"); add("seekRelative")
            add("speed"); add("volume"); add("mute"); add("repeat")
            add("unload")
            if (random.nextInt(70) == 0) add("release")
        }
        return ops[random.nextInt(ops.size)]
    }

    private fun applyOp(op: String, random: Random, player: VideoPlayer, model: Model, missing: File, where: String) {
        when (op) {
            "prepare" -> {
                runBlocking { withTimeout(25_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                model.prepare(fail = false)
            }
            "prepareFail" -> {
                runBlocking { withTimeout(25_000L) { player.prepare(VideoSource.File(missing.path)) } }
                model.prepare(fail = true)
            }
            "play" -> { player.play(); model.play() }
            "pause" -> { player.pause(); model.pause() }
            "stop" -> { player.stop(); model.stop() }
            "replay" -> {
                player.replay()
                if (!model.released && model.playable) {
                    model.seek(0L)
                    model.play()
                }
            }
            "seek" -> {
                val target: Long = listOf(-5L, 0L, 1L, duration / 3, duration / 2, duration - 1_000L, duration, duration + 1, Long.MAX_VALUE)[random.nextInt(9)]
                // A seek to the very end is a seek the clip answers with its end: awaited by the caller below.
                val wasPlaying: Boolean = model.kind == Kind.PLAYING
                player.seekTo(target)
                model.seek(target)
                if (wasPlaying && model.exact && model.posLo >= duration) model.kind = Kind.PLAYING
            }
            "seekRelative" -> {
                // Only from a position the model knows exactly and not while playing.
                if (model.kind != Kind.PLAYING && model.exact) {
                    val amount: Long = listOf(0L, 1L, 700L, 1_500L, 10_000L)[random.nextInt(5)]
                    if (random.nextBoolean()) {
                        player.seekForward(amount)
                        model.seek(model.posLo + amount)
                    } else {
                        player.seekBackward(amount)
                        model.seek(model.posLo - amount)
                    }
                }
            }
            "speed" -> {
                val value: Float = listOf(Float.NaN, 0.1f, 0.5f, 1f, 2f, 3f, 10f)[random.nextInt(7)]
                player.setPlaybackSpeed(value)
                if (!value.isNaN()) model.speed = value.coerceIn(0.25f, 3f)
                // the playhead of a play already running is now a new interval
                if (model.kind == Kind.PLAYING) {
                    model.posHi = model.upperNow()
                    model.posLo = model.posLo
                }
            }
            "volume" -> {
                val value: Float = listOf(Float.NaN, -1f, 0f, 0.3f, 1f, 7f)[random.nextInt(6)]
                player.setVolume(value)
                if (!value.isNaN()) model.volume = value.coerceIn(0f, 1f)
            }
            "mute" -> { val m: Boolean = random.nextBoolean(); player.setMuted(m); model.muted = m }
            "repeat" -> { val m: RepeatMode = RepeatMode.entries[random.nextInt(2)]; player.setRepeatMode(m); model.repeat = m }
            "unload" -> { player.unload(); model.unload() }
            "release" -> { player.release(); model.release() }
            else -> error(op)
        }
    }

    /**
     * The clip is about to reach its end while Playing: with Off the player must read Completed (once) within a
     * few seconds, with One it must keep Playing and never emit Completed.
     */
    private fun awaitEnd(player: VideoPlayer, model: Model, emitted: List<VideoPlayerState>, where: String) {
        if (model.repeat == RepeatMode.Off) {
            val before: Int = emitted.count { it is VideoPlayerState.Completed }
            val deadline: Long = System.nanoTime() + 9_000L * 1_000_000L
            while (player.stateFlow.value is VideoPlayerState.Playing && System.nanoTime() < deadline) Thread.sleep(15L)
            if (player.stateFlow.value !is VideoPlayerState.Completed) {
                fail("$where: Playing near the end with RepeatMode.Off never completed: ${player.stateFlow.value}")
            }
            if (emitted.count { it is VideoPlayerState.Completed } != before + 1) fail("$where: Completed not delivered exactly once for this play-through")
            model.kind = Kind.COMPLETED
            model.setPos(duration)
            model.completions++
        } else {
            Thread.sleep(700L)
            if (player.stateFlow.value !is VideoPlayerState.Playing) fail("$where: RepeatMode.One must keep Playing across the end, state ${player.stateFlow.value}")
            model.posLo = 0L
            model.posHi = duration
            model.loopedUnknown = true
            model.playStartNs = System.nanoTime()
        }
    }

    private fun check(player: VideoPlayer, model: Model, emitted: List<VideoPlayerState>, engine: JavaFxVideoEngine, where: String) {
        val state: VideoPlayerState = player.stateFlow.value
        // A seek to the end while Playing: the clip answers with its end.
        if (model.kind == Kind.PLAYING && model.exact && model.posLo >= duration) awaitEnd(player, model, emitted, where)
        val finalState: VideoPlayerState = player.stateFlow.value
        val expected: String = when (model.kind) {
            Kind.IDLE -> "Idle"
            Kind.READY -> "Ready($duration)"
            Kind.PLAYING -> "Playing($duration)"
            Kind.PAUSED -> "Paused($duration)"
            Kind.COMPLETED -> "Completed($duration)"
            Kind.ERROR -> "Error"
        }
        val actual: String = describe(finalState).let { if (it.startsWith("Error")) "Error" else it }
        if (expected != actual) fail("$where: state $actual, model $expected (before the end wait: $state)")
        if (model.released && where.contains("op=prepare") && (finalState !is VideoPlayerState.Error || finalState.cause !is VideoPlayerReleasedException)) {
            fail("$where: prepare after release must report VideoPlayerReleasedException, got $finalState")
        }
        if (player.playbackSpeedFlow.value != model.speed) fail("$where: speed ${player.playbackSpeedFlow.value} model ${model.speed}")
        if (player.volumeFlow.value != model.volume) fail("$where: volume")
        if (player.isMutedFlow.value != model.muted) fail("$where: muted")
        if (player.repeatModeFlow.value != model.repeat) fail("$where: repeat")
        if (model.kind == Kind.PAUSED) {
            val at: Long = pausedAt(finalState)
            // 05-platform-notes (JavaFX): a seek snaps to the nearest key frame and may land a little before the
            // target; the test clip has a key frame every second, so up to a second before the interval's start.
            if (at < model.posLo - KEY_FRAME_SLACK_MS || at > model.posHi) fail("$where: Paused at $at outside the model's interval ${model.posLo}..${model.posHi}")
            // After a pause the playhead is held: it stays put (the contract says Paused is suspended at currentPosition).
            Thread.sleep(60L)
            val later: Long = pausedAt(player.stateFlow.value)
            if (later != at) fail("$where: a Paused playhead moved from $at to $later")
            if (model.exact && player.playbackPositionFlow.value != at) fail("$where: position flow ${player.playbackPositionFlow.value} vs Paused($at)")
            model.posLo = at
            model.posHi = at
        }
        if (model.kind == Kind.IDLE || model.kind == Kind.READY) {
            // nothing plays
            if (player.playbackPositionFlow.value != 0L && model.kind == Kind.IDLE) fail("$where: position flow ${player.playbackPositionFlow.value} while Idle")
        }
        val completedNow: Int = emitted.count { it is VideoPlayerState.Completed }
        if (completedNow != model.completions) fail("$where: Completed emitted $completedNow times, model ${model.completions}: ${emitted.map(::describe).takeLast(8)}")
        val scriptedFailures: Int = emitted.count { it is VideoPlayerState.Error && it.cause !is VideoPlayerReleasedException }
        if (scriptedFailures != model.failures) fail("$where: Error emitted $scriptedFailures times, model ${model.failures}")
    }

    private fun checkOrder(seq: List<VideoPlayerState>, model: Model, seed: Int, trace: List<String>) {
        for (index: Int in 1 until seq.size) {
            if (seq[index] == seq[index - 1]) fail("seed=$seed: duplicate consecutive emission ${describe(seq[index])}; trace=${trace.takeLast(14)}")
        }
        for (index: Int in 0 until seq.size - 1) {
            if (seq[index] == VideoPlayerState.Preparing) {
                val next: VideoPlayerState = seq[index + 1]
                if (next !is VideoPlayerState.Ready && next !is VideoPlayerState.Error && next != VideoPlayerState.Idle) {
                    fail("seed=$seed: Preparing followed by ${describe(next)}")
                }
            }
        }
        for (index: Int in 1 until seq.size) {
            if (seq[index] is VideoPlayerState.Completed && seq[index - 1] !is VideoPlayerState.Playing) {
                fail("seed=$seed: Completed after ${describe(seq[index - 1])}")
            }
        }
    }

    @Test
    fun `seeded scripts of public calls agree with the reference model on real JavaFX`() {
        assumeJavaFxMedia()
        val seeds: Int = System.getProperty("kmptoolkit.test.r10.seeds")?.toIntOrNull() ?: 5
        for (seed: Int in 100 until 100 + seeds) runScript(seed, steps = 45)
    }
}
