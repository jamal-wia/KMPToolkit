package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 10 (breaker bt), event order: the harness of round 6 (the same seeded script through the shared player over
 * an ideal engine and over VlcjVideoEngine on a model of libvlc) with one more observable: the whole sequence of
 * states a collector on each player's stateFlow sees between two quiet points must be the same, so a transition
 * that is reordered, doubled, skipped or delivered by one engine only is a divergence of its own. Every failure
 * names the seed and the call trace.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjEventOrderDifferentialR10AdversarialTest {

    private class Ideal(private val knownLength: Boolean) : VideoPlaybackEngine {
        var sink: VideoPlaybackEngineListener? = null
        var loaded: Boolean = false
        var duration: Long = DURATION
        var position: Long = 0L
        var playing: Boolean = false
        var rate: Float = 1f
        var level: Float = 1f
        var loops: Boolean = false

        override fun setListener(listener: VideoPlaybackEngineListener?) {
            this.sink = listener
        }

        override suspend fun load(source: VideoSource) {
            loaded = true
            position = 0L
            playing = false
        }

        override fun start() {
            if (loaded) playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun seekTo(positionMs: Long) {
            if (!loaded) return
            position = positionMs.coerceIn(0L, duration)
            if (playing && position >= duration) end()
        }

        override fun setSpeed(speed: Float) {
            this.rate = speed
        }

        override fun setVolume(volume: Float) {
            this.level = volume
        }

        override fun setLooping(looping: Boolean) {
            this.loops = looping
        }

        override fun durationMs(): Long = if (loaded && knownLength) duration else 0L

        override fun positionMs(): Long = if (loaded) position else 0L

        override fun bufferedPositionMs(): Long = durationMs()

        override fun release() {
            loaded = false
            playing = false
            position = 0L
        }

        fun advance(ms: Long) {
            if (!playing) return
            position += (ms * rate).toLong()
            if (position >= duration) end()
        }

        private fun end() {
            if (loops) {
                position = 0L
                return
            }
            position = duration
            playing = false
            sink?.onCompleted()
        }

        fun fail() {
            playing = false
            sink?.onFailed(IllegalStateException("scripted"))
        }
    }

    private class ModelRuntime(val knownLength: Boolean) : VlcRuntime {
        val eventThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "model-vlc-events").apply { isDaemon = true } }
        val taskThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "model-vlc-tasks").apply { isDaemon = true } }

        @Volatile var current: ModelPlayer? = null
        val players: MutableList<ModelPlayer> = java.util.concurrent.CopyOnWriteArrayList()
        val violations: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        val teardownThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "model-vlc-teardown").apply { isDaemon = true } }
        @Volatile var releaseCount: Int = 0

        override fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer =
            ModelPlayer(this, callbacks).also {
                current = it
                players += it
            }

        override fun release() {
            releaseCount++
            if (players.any { it.releaseCalls == 0 }) violations += "libvlc instance released before every native player"
        }

        fun settle() {
            repeat(4) {
                eventThread.submit {}.get(5, TimeUnit.SECONDS)
                taskThread.submit {}.get(5, TimeUnit.SECONDS)
                teardownThread.submit {}.get(5, TimeUnit.SECONDS)
            }
        }

        fun shutdown() {
            eventThread.shutdownNow()
            taskThread.shutdownNow()
            teardownThread.shutdownNow()
        }
    }

    private enum class S { Stopped, Opening, Playing, Paused, Ended }

    private class ModelPlayer(private val runtime: ModelRuntime, private val callbacks: VlcPlayerCallbacks) : VlcNativePlayer {
        @Volatile var state: S = S.Stopped
        @Volatile var clock: Long = 0L
        @Volatile var rateApplied: Float = 1f
        @Volatile var volumeApplied: Int = -1
        @Volatile var repeatFlag: Boolean = false
        @Volatile var pausePending: Boolean = false
        @Volatile var stopCalls: Int = 0
        @Volatile var releaseCalls: Int = 0

        /** A native call made after the player was released would be a use after free in the real library. */
        private fun live(call: String) {
            if (releaseCalls > 0) runtime.violations += "$call on a native player after its release"
        }

        private fun fire(event: VlcPlayerCallbacks.() -> Unit) {
            runtime.eventThread.execute { callbacks.event() }
        }

        override fun prepare(mrl: String, options: List<String>): Boolean {
            live("prepare")
            return true
        }

        override fun parse(): Boolean {
            live("parse")
            fire { parsed(VlcParseStatus.DONE) }
            return true
        }

        override fun parsedInfo(): VlcParsedInfo = VlcParsedInfo(durationMs = if (runtime.knownLength) DURATION else 0L, trackCount = 1, videoSize = null)

        @Synchronized
        override fun play() {
            live("play")
            when (state) {
                S.Stopped -> {
                    state = S.Opening
                    clock = 0L
                    pausePending = false
                }
                S.Paused -> {
                    state = S.Playing
                    fire { playing() }
                }
                S.Opening -> pausePending = false
                S.Playing, S.Ended -> Unit
            }
        }

        @Synchronized
        override fun pause() {
            live("pause")
            when (state) {
                S.Playing -> {
                    state = S.Paused
                    fire { paused() }
                }
                S.Opening -> pausePending = true
                else -> Unit
            }
        }

        @Synchronized
        override fun setTime(timeMs: Long) {
            live("setTime")
            if (state != S.Playing && state != S.Paused) return
            clock = timeMs.coerceIn(0L, DURATION)
            val reading: Long = clock
            fire { timeChanged(reading) }
            if (clock >= DURATION && state == S.Playing) end()
        }

        @Synchronized
        override fun time(): Long = if (releaseCalls > 0) {
            runtime.violations += "time on a native player after its release"
            -1L
        } else if (state == S.Stopped || state == S.Opening) -1L else clock

        @Synchronized
        override fun setRate(rate: Float) {
            live("setRate")
            if (state == S.Playing || state == S.Paused) rateApplied = rate
        }

        @Synchronized
        override fun setVolume(percent: Int) {
            live("setVolume")
            if (state == S.Playing || state == S.Paused) volumeApplied = percent
        }

        override fun rate(): Float = rateApplied

        override fun volume(): Int = volumeApplied

        override fun setRepeat(repeat: Boolean) {
            repeatFlag = repeat
        }

        override fun repeat(): Boolean = repeatFlag

        override fun submit(task: () -> Unit) {
            runtime.taskThread.execute(task)
        }

        override fun stopParsing() = Unit

        @Synchronized
        override fun stop() {
            live("stop")
            stopCalls++
            state = S.Stopped
            clock = 0L
        }

        override fun release() {
            if (releaseCalls++ > 0) runtime.violations += "a native player released twice"
            if (stopCalls == 0) runtime.violations += "a native player released without being stopped"
        }

        /** libvlc reports Playing for a requested play. */
        @Synchronized
        fun outputUp() {
            if (state != S.Opening) return
            clock = 0L
            if (pausePending) {
                pausePending = false
                state = S.Paused
                fire { playing() }
                fire { paused() }
            } else {
                state = S.Playing
                fire { playing() }
            }
        }

        @Synchronized
        fun advance(ms: Long) {
            if (state != S.Playing) return
            val next: Long = clock + (ms * rateApplied).toLong()
            if (next >= DURATION) {
                end()
            } else {
                clock = next
                fire { timeChanged(next) }
            }
        }

        /** EndReached: VLCJ's reset (or its repeat replay) is queued on the task thread before our handler runs. */
        private fun end() {
            state = S.Ended
            clock = DURATION
            val repeating: Boolean = repeatFlag
            runtime.eventThread.execute {
                runtime.taskThread.execute {
                    synchronized(this) {
                        if (repeating) {
                            state = S.Opening
                            clock = 0L
                        } else {
                            state = S.Stopped
                            clock = 0L
                        }
                    }
                }
                callbacks.timeChanged(DURATION)
                callbacks.finished()
            }
        }

        fun error() {
            fire { error() }
        }

        @Suppress("unused")
        fun frame(buffer: ByteBuffer) = Unit
    }

    private class Divergence(val key: String, message: String) : AssertionError(message)

    private fun norm(state: VideoPlayerState, withPosition: Boolean = true): String = when (state) {
        is VideoPlayerState.Playing -> "Playing(${state.duration})"
        is VideoPlayerState.Paused -> if (withPosition) "Paused(${state.duration},${state.currentPosition})" else "Paused(${state.duration})"
        is VideoPlayerState.Ready -> "Ready(${state.duration})"
        is VideoPlayerState.Completed -> "Completed(${state.duration})"
        is VideoPlayerState.Error -> "Error"
        else -> state.toString()
    }

    private fun runSeed(seed: Int, steps: Int, knownLength: Boolean) {
        val random = Random(seed)
        val ideal = Ideal(knownLength)
        val idealPlayer: VideoPlayer = createVideoPlayer(ideal, VideoPlayerConfig(positionUpdateIntervalMs = 3_600_000L))
        val runtime = ModelRuntime(knownLength)
        val virtualNanos = AtomicLong(0L)
        val vlc = VlcjVideoEngine(
            vlcArgs = emptyList(),
            discover = { true },
            createRuntime = { runtime },
            teardown = runtime.teardownThread,
            nanoTime = { virtualNanos.get() },
        )
        val vlcPlayer: VideoPlayer = createVideoPlayer(vlc, VideoPlayerConfig(positionUpdateIntervalMs = 3_600_000L))
        val trace = StringBuilder()
        val idealSeen: MutableList<VideoPlayerState> = java.util.concurrent.CopyOnWriteArrayList()
        val vlcSeen: MutableList<VideoPlayerState> = java.util.concurrent.CopyOnWriteArrayList()
        var idealMark = 0
        var vlcMark = 0
        val collectors: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        collectors.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { idealPlayer.stateFlow.collect { idealSeen += it } }
        collectors.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { vlcPlayer.stateFlow.collect { vlcSeen += it } }
        var released = false
        // libvlc has no clock between the end of a looping source and the restart's Playing, so the engine keeps
        // the last reading (the end); positions taken in that gap differ from an ideal engine's 0 until an
        // absolute seek. Reported separately (r6-D2-G1); not what this differential judges.
        var positionsUnreliable = false
        try {
            fun both(name: String, action: (VideoPlayer) -> Unit) {
                trace.append(name).append(' ')
                action(idealPlayer)
                action(vlcPlayer)
            }

            fun compare(after: String) {
                runtime.settle()
                val model: ModelPlayer? = runtime.current
                // Output the model has been asked for arrives with a Playing event, usually at once, sometimes later.
                if (model != null && model.state == S.Opening && random.nextInt(10) < 7) {
                    model.outputUp()
                    runtime.settle()
                }
                virtualNanos.addAndGet(2_000_000_000L)
                if (runtime.violations.isNotEmpty()) throw Divergence("native: ${runtime.violations.first()}", "seed $seed after [$after]: ${runtime.violations}; trace: $trace")
                // Every native player of a source that is no longer the loaded one is stopped and freed, once.
                val sessionOpen: Boolean = idealPlayer.stateFlow.value.let { it !is VideoPlayerState.Idle }
                for ((index: Int, native: ModelPlayer) in runtime.players.withIndex()) {
                    val newest: Boolean = index == runtime.players.lastIndex
                    if ((!newest || !sessionOpen) && native.releaseCalls != 1) {
                        throw Divergence("leak newest=$newest open=$sessionOpen", "seed $seed after [$after]: native player #$index released ${native.releaseCalls} times; trace: $trace")
                    }
                }
                val where = "seed $seed after [$after]; trace: $trace"
                if (model != null && model.state == S.Opening && model.repeatFlag) positionsUnreliable = true
                // Output the player was asked to start has not begun: a seek onto the end, or a pause, is applied
                // when it does, so the two may legitimately differ until then.
                if (model != null && model.state == S.Opening) {
                    if (model.repeatFlag != ideal.loops) throw Divergence("repeat", "$where: repeat ideal=${ideal.loops} vlc=${model.repeatFlag}")
                    return
                }
                val i: String = norm(idealPlayer.stateFlow.value, !positionsUnreliable)
                val v: String = norm(vlcPlayer.stateFlow.value, !positionsUnreliable)
                if (i != v) throw Divergence("state ${i.substringBefore('(')}/${v.substringBefore('(')} model=${model?.state}", "$where: state ideal=$i vlc=$v")
                run {
                    val idealNew: List<String> = idealSeen.drop(idealMark).map { norm(it, false) }
                    val vlcNew: List<String> = vlcSeen.drop(vlcMark).map { norm(it, false) }
                    idealMark = idealSeen.size
                    vlcMark = vlcSeen.size
                    // Distinct consecutive states only: a poll tick or a position refresh may repeat a state class.
                    fun collapse(l: List<String>): List<String> = l.fold(mutableListOf<String>()) { acc, e -> if (acc.lastOrNull() != e) acc += e; acc }
                    if (collapse(idealNew) != collapse(vlcNew)) {
                        throw Divergence("order ${collapse(idealNew).joinToString(">")} / ${collapse(vlcNew).joinToString(">")}", "$where: states seen since the last quiet point: ideal=$idealNew vlc=$vlcNew")
                    }
                    if (collapse(idealNew).count { it.startsWith("Completed") } > 1) {
                        throw Divergence("completed twice", "$where: Completed delivered more than once between quiet points: $idealNew")
                    }
                }
                if (!positionsUnreliable && idealPlayer.playbackPositionFlow.value != vlcPlayer.playbackPositionFlow.value) {
                    throw Divergence("posflow ${i.substringBefore('(')}", "$where: position flow ideal=${idealPlayer.playbackPositionFlow.value} vlc=${vlcPlayer.playbackPositionFlow.value} (state $i)")
                }
                val state: VideoPlayerState = idealPlayer.stateFlow.value
                if (!(state is VideoPlayerState.Ready || state is VideoPlayerState.Playing || state is VideoPlayerState.Paused ||
                        state is VideoPlayerState.Completed) || model == null
                ) return
                if (!positionsUnreliable && ideal.positionMs() != vlc.positionMs()) {
                    throw Divergence("enginepos ${i.substringBefore('(')} model=${model.state}", "$where: engine position ideal=${ideal.positionMs()} vlc=${vlc.positionMs()} (state $i, model ${model.state})")
                }
                val vlcRuns: Boolean = model.state == S.Playing || (model.state == S.Opening && !model.pausePending)
                if (ideal.playing != vlcRuns) throw Divergence("output ${i.substringBefore('(')} model=${model.state}", "$where: output ideal=${ideal.playing} vlc=$vlcRuns (model ${model.state}, pausePending ${model.pausePending}, state $i)")
                if (model.repeatFlag != ideal.loops) throw Divergence("repeat", "$where: repeat ideal=${ideal.loops} vlc=${model.repeatFlag}")
                if (model.state == S.Playing || model.state == S.Paused) {
                    val wantVolume: Int = volumePercent(ideal.level)
                    if (model.volumeApplied != wantVolume) throw Divergence("volume ${model.state}", "$where: volume ideal=$wantVolume vlc=${model.volumeApplied} (model ${model.state})")
                }
                if (model.state == S.Playing) {
                    val wantRate: Float = ideal.rate.coerceIn(1f / 32f, 32f)
                    if (model.rateApplied != wantRate) throw Divergence("rate", "$where: rate ideal=$wantRate vlc=${model.rateApplied}")
                }
            }

            val src: VideoSource = VideoSource.File(TestClip.file.path)
            fun prepareBoth() {
                trace.append("prepare ")
                runBlocking {
                    withTimeout(5_000L) { idealPlayer.prepare(src) }
                    withTimeout(5_000L) { vlcPlayer.prepare(src) }
                }
            }
            prepareBoth()
            compare("prepare")
            fun outputBeforeSeekToTheEnd(reachesEnd: Boolean) {
                // A seek onto the end made before output begins completes the source only once output does; an
                // ideal engine completes it at once. Bring output up first so the two see the same order.
                val model: ModelPlayer? = runtime.current
                if (reachesEnd && model != null && model.state == S.Opening) {
                    model.outputUp()
                    runtime.settle()
                }
            }
            for (step: Int in 0 until steps) {
                val op: Int = random.nextInt(21)
                val name: String
                when (op) {
                    0, 1, 2 -> { name = "play"; both(name) { it.play() } }
                    3, 4 -> { name = "pause"; both(name) { it.pause() } }
                    5 -> { name = "stop"; both(name) { it.stop() } }
                    6, 7 -> {
                        val target: Long = random.nextLong(-100L, 2_300L)
                        name = "seekTo($target)"
                        outputBeforeSeekToTheEnd(target >= DURATION)
                        both(name) { it.seekTo(target) }
                    }
                    8 -> {
                        val amount: Long = random.nextLong(1L, 1_500L)
                        name = "seekForward($amount)"
                        if (knownLength) {
                            outputBeforeSeekToTheEnd(ideal.positionMs() + amount >= DURATION)
                            both(name) { it.seekForward(amount) }
                        } else {
                            // Without a length a relative skip can overshoot an end nobody but the stream knows; the SPI lets
                            // an engine clamp it or ignore it, and libvlc is simply asked: nothing here is comparable.
                            trace.append("seekForward-skipped ")
                        }
                    }
                    9 -> {
                        val amount: Long = random.nextLong(1L, 1_500L)
                        name = "seekBackward($amount)"
                        both(name) { it.seekBackward(amount) }
                    }
                    10 -> { name = "replay"; both(name) { it.replay() } }
                    11 -> {
                        val speed: Float = listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 3f, 10f).random(random)
                        name = "speed($speed)"
                        both(name) { it.setPlaybackSpeed(speed) }
                    }
                    12 -> {
                        val volume: Float = listOf(0f, 0.3f, 0.5f, 0.995f, 1f, 1.5f, -1f).random(random)
                        name = "volume($volume)"
                        both(name) { it.setVolume(volume) }
                    }
                    13 -> {
                        val muted: Boolean = random.nextBoolean()
                        name = "mute($muted)"
                        both(name) { it.setMuted(muted) }
                    }
                    14 -> {
                        val mode: RepeatMode = if (random.nextBoolean()) RepeatMode.One else RepeatMode.Off
                        name = "repeat($mode)"
                        both(name) { it.setRepeatMode(mode) }
                    }
                    15, 16 -> {
                        val ms: Long = listOf(100L, 200L, 400L, 800L).random(random)
                        val model: ModelPlayer? = runtime.current
                        name = "advance($ms)"
                        if (model != null && model.state == S.Playing && ideal.playing) {
                            trace.append(name).append(' ')
                            ideal.advance(ms)
                            model.advance(ms)
                        } else {
                            trace.append("advance-skipped ")
                        }
                    }
                    17 -> {
                        name = "unload"
                        both(name) { it.unload() }
                    }
                    18 -> {
                        name = "reprepare"
                        prepareBoth()
                    }
                    19 -> {
                        name = "flush"
                        runtime.current?.outputUp()
                    }
                    else -> {
                        if (random.nextInt(8) == 0) {
                            name = "fail"
                            trace.append(name).append(' ')
                            val model: ModelPlayer? = runtime.current
                            ideal.fail()
                            model?.error()
                        } else {
                            name = "seekToEnd"
                            outputBeforeSeekToTheEnd(true)
                            both(name) { it.seekTo(DURATION) }
                        }
                    }
                }
                if (name.startsWith("seekTo(") || name == "stop" || name == "replay" || name == "reprepare" || name == "unload" || name == "seekToEnd") {
                    val before: ModelPlayer? = runtime.current
                    if (before == null || before.state != S.Opening || !before.repeatFlag) positionsUnreliable = false
                }
                compare(name)
                val s: VideoPlayerState = idealPlayer.stateFlow.value
                if (s is VideoPlayerState.Error || s is VideoPlayerState.Idle) {
                    // The only way out of these is a new prepare.
                    prepareBoth()
                    compare("recover")
                }
            }
            trace.append("release ")
            idealPlayer.release()
            vlcPlayer.release()
            released = true
            runtime.settle()
            val i: String = norm(idealPlayer.stateFlow.value)
            val v: String = norm(vlcPlayer.stateFlow.value)
            if (i != v) throw Divergence("release", "seed $seed after release: ideal=$i vlc=$v; trace: $trace")
            if (vlc.positionMs() != 0L || vlc.durationMs() != 0L || vlc.bufferedPositionMs() != 0L) {
                throw Divergence("getters after release", "seed $seed: position ${vlc.positionMs()} duration ${vlc.durationMs()} buffered ${vlc.bufferedPositionMs()} after release; trace: $trace")
            }
            // Calls after the final release change nothing (and reach no native player).
            vlcPlayer.play()
            vlcPlayer.seekTo(10L)
            vlcPlayer.setPlaybackSpeed(2f)
            vlcPlayer.release()
            runtime.settle()
            if (runtime.violations.isNotEmpty()) throw Divergence("native after release: ${runtime.violations.first()}", "seed $seed: ${runtime.violations}; trace: $trace")
            if (runtime.players.any { it.releaseCalls != 1 || it.stopCalls != 1 }) {
                throw Divergence("final leak", "seed $seed: players (release, stop) = ${runtime.players.map { it.releaseCalls to it.stopCalls }}; trace: $trace")
            }
            if (runtime.players.isNotEmpty() && runtime.releaseCount != 1) {
                throw Divergence("instance", "seed $seed: libvlc instance released ${runtime.releaseCount} times; trace: $trace")
            }
        } finally {
            collectors.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            if (!released) {
                idealPlayer.release()
                vlcPlayer.release()
            }
            runtime.shutdown()
        }
    }

    @Test
    fun `the vlc engine on a model of libvlc is indistinguishable from an ideal engine through the player`() = differ(knownLength = true)

    @Test
    fun `the same for a source whose length libvlc does not know`() = differ(knownLength = false)

    private fun differ(knownLength: Boolean) {
        val firstOfKind: MutableMap<String, String> = linkedMapOf()
        val counts: MutableMap<String, Int> = linkedMapOf()
        for (seed: Int in 1..SEEDS) {
            try {
                runSeed(seed, steps = 80, knownLength = knownLength)
            } catch (divergence: Divergence) {
                counts[divergence.key] = (counts[divergence.key] ?: 0) + 1
                firstOfKind.putIfAbsent(divergence.key, divergence.message.orEmpty())
            }
        }
        if (firstOfKind.isNotEmpty()) {
            fail(firstOfKind.entries.joinToString("\n\n", prefix = "${counts.values.sum()} diverging seeds of $SEEDS, ${firstOfKind.size} kinds:\n") { (key, text) -> "[${counts[key]}x] $key -> $text" })
        }
    }

    private companion object {
        const val DURATION: Long = 2_000L
        const val SEEDS: Int = 1500
    }
}
