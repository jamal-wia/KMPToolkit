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
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * D1 round 10 (breaker ij), Q1 and Q2: a seeded, stateful, model-based fuzz of the player over the shared
 * fake engine, with the coroutines run on a hand-stepped dispatcher (a queue and a virtual clock, no thread, no
 * wall clock, no sleep), so a test can stop a `prepare` at any suspension point and let the next call run there.
 *
 * Two drivers over the same operation alphabet (load, prepare, play, pause, stop, seek, speed, volume, mute,
 * repeat, unload, release, engine events: completion, failure, buffering, size, a length learned, position ticks):
 *
 *  - SETTLED: every operation is followed by a full drain, and a small reference model written from
 *    04-api-reference.md (Transport, Settings, Lifecycle) must agree with the player after every step: the state
 *    kind, the settings flows, the values pushed to the engine, a failure kept while Preparing, a settings change
 *    made before the load applied once Ready, no engine call after the release.
 *  - CHAOS: between two operations a random number of single tasks is run, so prepares are cut at every point (waiting
 *    for the load it replaced, waiting for the engine, resuming after it, cancelled, superseded, released under).
 *    Invariants only (no model: the interleaving is the point): a legal state at every step, nothing but Idle and
 *    Error(VideoPlayerReleasedException) after a release, a playhead within the published length, the engine plays only
 *    while the player says Playing, no engine call after the release has settled, and at the end: the engine disposed
 *    exactly once, no listener, no load pending, no task or job left, no exception escaped into the caller's scope.
 *
 * A failure message names the driver, the seed and the last operations; every seed is fixed.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class, DelicateCoroutinesApi::class)
class VideoPlayerLifecycleChaosFuzzD1R10AdversarialTest {

    /** A queue of tasks with a virtual clock; it is also the [Delay] of everything started on it. */
    private class StepDispatcher : CoroutineDispatcher(), Delay {
        private class Task(val time: Long, val order: Long, val block: Runnable)

        var now: Long = 0L
            private set
        private var counter: Long = 0L
        private val queue: PriorityQueue<Task> = PriorityQueue(compareBy<Task>({ it.time }, { it.order }))

        val pending: Int get() = queue.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(Task(now, counter++, block))
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val task = Task(now + timeMillis, counter++, Runnable { continuation.resume(Unit) })
            queue.add(task)
            continuation.invokeOnCancellation { queue.remove(task) }
        }

        /** Runs the next task that is due; false when none is. */
        fun step(): Boolean {
            val next: Task = queue.peek() ?: return false
            if (next.time > now) return false
            queue.poll()
            next.block.run()
            return true
        }

        fun drain(limit: Int = 100_000) {
            var steps = 0
            while (step()) {
                check(++steps < limit) { "the dispatcher never became idle: a task keeps queueing itself" }
            }
        }

        fun advance(ms: Long) {
            now += ms
            drain()
        }
    }

    /** Counts every call, so "no engine call after release" is one number. */
    private class CountingEngine(val inner: FakeVideoPlaybackEngine) : VideoPlaybackEngine {
        var calls: Int = 0
        override fun setListener(listener: VideoPlaybackEngineListener?) { calls++; inner.setListener(listener) }
        override suspend fun load(source: VideoSource) { calls++; inner.load(source) }
        override fun start() { calls++; inner.start() }
        override fun pause() { calls++; inner.pause() }
        override fun seekTo(positionMs: Long) { calls++; inner.seekTo(positionMs) }
        override fun setSpeed(speed: Float) { calls++; inner.setSpeed(speed) }
        override fun setVolume(volume: Float) { calls++; inner.setVolume(volume) }
        override fun setLooping(looping: Boolean) { calls++; inner.setLooping(looping) }
        override fun durationMs(): Long { calls++; return inner.durationMs() }
        override fun positionMs(): Long { calls++; return inner.positionMs() }
        override fun bufferedPositionMs(): Long { calls++; return inner.bufferedPositionMs() }
        override fun release() { calls++; inner.release() }
        override fun dispose() { calls++; inner.dispose() }
    }

    private enum class Kind { IDLE, PREPARING, READY, PLAYING, PAUSED, COMPLETED, ERROR, REFUSED }

    private class Call(val job: Job, val fails: Boolean, val cause: Throwable)

    private class Model(val lo: Float, val hi: Float) {
        var kind: Kind = Kind.IDLE
        var released: Boolean = false
        var pending: Call? = null
        var speed: Float = 1f
        var volume: Float = 1f
        var muted: Boolean = false
        var repeat: RepeatMode = RepeatMode.Off
        var buffering: Boolean = false
        var keptFailure: Throwable? = null
        var keptBuffering: Boolean = false
        var errorCause: Throwable? = null

        /** The speed last pushed to the engine: at a load, at a play that starts, and by a setting made while Playing. */
        var engineSpeed: Float = 1f

        /** Whether the engine may hold a source, and how many times the player must have released it so far. */
        var holds: Boolean = false
        var releases: Int = 0

        val playable: Boolean get() = kind == Kind.READY || kind == Kind.PLAYING || kind == Kind.PAUSED || kind == Kind.COMPLETED
        val effectiveVolume: Float get() = if (muted) 0f else volume

        fun sourceReset() { buffering = false; keptFailure = null; keptBuffering = false }
    }

    private class Harness(val seed: Int, val mode: String, durationMs: Long) {
        val dispatcher = StepDispatcher()
        val fake: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(durationMs).also { it.suspendLoads = true }
        val engine = CountingEngine(fake)
        val escaped: MutableList<Throwable> = mutableListOf()
        val scope: CoroutineScope = CoroutineScope(dispatcher + CoroutineExceptionHandler { _, e -> escaped += e })
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L, minPlaybackSpeed = 0.25f, maxPlaybackSpeed = 3f), dispatcher)
        val jobs: MutableList<Job> = mutableListOf()
        val emitted: MutableList<VideoPlayerState> = mutableListOf()
        val trace: MutableList<String> = mutableListOf()
        val collector: Job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            player.stateFlow.collect { emitted += it }
        }
        var released: Boolean = false
        var callsAtSettledRelease: Int = -1
        var emittedAtRelease: Int = -1

        fun where(): String = "$mode seed=$seed ops=${trace.takeLast(14)}"
    }

    /** What the seeds actually reached: a fuzz that never gets to the interesting states proves nothing. */
    private val coverage: MutableMap<String, Int> = sortedMapOf()

    private fun cover(key: String) {
        coverage[key] = (coverage[key] ?: 0) + 1
    }

    private fun requireCovered(vararg minimums: Pair<String, Int>) {
        println("COVERAGE $coverage")
        for ((key: String, minimum: Int) in minimums) {
            val reached: Int = coverage[key] ?: 0
            if (reached < minimum) fail("vacuous fuzz: '$key' reached $reached times, expected at least $minimum; $coverage")
        }
    }

    private val sources: List<VideoSource> = listOf(VideoSource.Asset("a.mp4"), VideoSource.Remote("https://example.test/b.mp4"))
    private val speeds: List<Float> = listOf(Float.NaN, 0f, 0.1f, 0.25f, 1f, 1.5f, 3f, 10f, Float.POSITIVE_INFINITY, -2f)
    private val volumes: List<Float> = listOf(Float.NaN, -1f, 0f, 0.3f, 1f, 7f, Float.NEGATIVE_INFINITY)
    private val seekTargets: List<Long> = listOf(-5L, 0L, 1L, 1_500L, 4_999L, 5_000L, 5_001L, 8_000L, Long.MAX_VALUE, Long.MIN_VALUE)
    private val amounts: List<Long> = listOf(0L, 1L, 700L, 4_000L, Long.MAX_VALUE, -3_000L)

    // ------------------------------------------------------------------ operations shared by both drivers

    private class Op(val name: String, val run: (Harness, Random, Model?) -> Unit)

    /** The release has weight 0 in the table: each seed releases once, at a step it draws up front (or at the end). */
    private fun ops(): List<Pair<Int, Op>> = listOf(
        6 to Op("prepare") { h, r, m -> startPrepare(h, r, m, fail = false) },
        1 to Op("prepareFailing") { h, r, m -> startPrepare(h, r, m, fail = true) },
        9 to Op("finishLoad") { h, _, m -> finishLoad(h, m) },
        4 to Op("cancelCaller") { h, r, m -> cancelCaller(h, r, m) },
        7 to Op("play") { h, _, m ->
            val before: VideoPlayerState = h.player.stateFlow.value
            h.player.play()
            m?.let(::modelPlay)
            if (m != null && !m.released && before is VideoPlayerState.Completed) {
                // 03-guide: playing a completed source starts from 0.
                if (h.fake.positionMs != 0L || h.player.playbackPositionFlow.value != 0L) {
                    fail("play() from Completed did not rewind: engine ${h.fake.positionMs} flow ${h.player.playbackPositionFlow.value}; ${h.where()}")
                }
            }
        },
        6 to Op("pause") { h, _, m -> h.player.pause(); m?.let { if (!it.released && it.kind == Kind.PLAYING) it.kind = Kind.PAUSED } },
        1 to Op("stop") { h, _, m -> h.player.stop(); m?.let { if (!it.released && it.playable) it.kind = Kind.READY } },
        1 to Op("replay") { h, _, m -> h.player.replay(); m?.let { if (!it.released && it.playable) { modelSeek(it); modelPlay(it) } } },
        3 to Op("seekTo") { h, r, m -> h.player.seekTo(seekTargets[r.nextInt(seekTargets.size)]); m?.let(::modelSeek) },
        2 to Op("seekForward") { h, r, m -> h.player.seekForward(amounts[r.nextInt(amounts.size)]); m?.let(::modelSeek) },
        1 to Op("seekBackward") { h, r, m -> h.player.seekBackward(amounts[r.nextInt(amounts.size)]); m?.let(::modelSeek) },
        2 to Op("setSpeed") { h, r, m -> speeds[r.nextInt(speeds.size)].let { v -> h.player.setPlaybackSpeed(v); m?.let { if (!v.isNaN()) { it.speed = v.coerceIn(it.lo, it.hi); if (!it.released && it.kind == Kind.PLAYING) it.engineSpeed = it.speed } } } },
        2 to Op("setVolume") { h, r, m -> volumes[r.nextInt(volumes.size)].let { v -> h.player.setVolume(v); m?.let { if (!v.isNaN()) it.volume = v.coerceIn(0f, 1f) } } },
        2 to Op("setMuted") { h, r, m -> r.nextBoolean().let { v -> h.player.setMuted(v); m?.muted = v } },
        2 to Op("setRepeat") { h, r, m -> RepeatMode.entries[r.nextInt(2)].let { v -> h.player.setRepeatMode(v); m?.repeat = v } },
        1 to Op("unload") { h, _, m -> h.player.unload(); m?.let { if (!it.released) { it.kind = Kind.IDLE; it.pending = null; it.sourceReset(); it.releases++; it.holds = false } } },
        0 to Op("release") { h, _, m ->
            cover(if (h.fake.isLoadPending) "release/load-in-flight" else if (h.jobs.any { it.isActive }) "release/prepare-active-no-load" else "release/idle")
            h.player.release()
            h.released = true
            m?.let { if (!it.released) { it.released = true; it.kind = Kind.IDLE; it.pending = null; it.sourceReset(); it.releases++; it.holds = false } }
        },
        3 to Op("tick") { h, r, _ -> h.fake.advancePositionBy(r.nextLong(0, 900)); h.dispatcher.advance(100L * r.nextInt(1, 4)) },
        2 to Op("playToEnd") { h, _, m ->
            h.fake.advancePositionTo(h.fake.durationMs)
            h.fake.completePlayback()
            m?.let { if (!it.released && it.playable && it.repeat == RepeatMode.Off) { it.kind = Kind.COMPLETED; it.buffering = false } }
        },
        1 to Op("failPlayback") { h, _, m ->
            val cause = IllegalStateException("scripted failure")
            h.fake.failPlayback(cause)
            m?.let {
                if (it.released) return@let
                if (it.kind == Kind.PREPARING) { if (it.keptFailure == null) it.keptFailure = cause }
                else if (it.playable) { it.kind = Kind.ERROR; it.errorCause = cause; it.buffering = false }
            }
        },
        3 to Op("buffering") { h, r, m ->
            val on: Boolean = r.nextBoolean()
            h.fake.reportBuffering(on)
            m?.let {
                if (it.released) return@let
                if (it.kind == Kind.PREPARING) it.keptBuffering = on
                else if (it.playable && it.kind != Kind.COMPLETED) it.buffering = on
            }
        },
        2 to Op("overshoot") { h, r, _ -> h.fake.advancePositionTo(h.fake.durationMs + r.nextLong(1, 700)) },
        2 to Op("lengthKnown") { h, r, _ -> h.fake.durationMs = listOf(3_000L, 5_000L, 8_000L)[r.nextInt(3)] },
    )

    /** The one release of a seed: at the step drawn up front, and for every other seed only while a load is in flight. */
    private fun releaseDue(h: Harness, step: Int, releaseAt: Int, seed: Int): Boolean =
        step >= releaseAt && !h.released && (seed % 2 == 1 || h.fake.isLoadPending)

    private fun startPrepare(h: Harness, r: Random, m: Model?, fail: Boolean) {
        val cause = IllegalStateException("scripted load failure #${h.jobs.size}")
        val source: VideoSource = sources[r.nextInt(sources.size)]
        if (h.fake.isLoadPending) cover("prepare/while-load-in-flight")
        if (h.player.stateFlow.value is VideoPlayerState.Playing) cover("prepare/while-playing")
        if (h.player.stateFlow.value is VideoPlayerState.Preparing) cover("prepare/while-preparing")
        val job: Job = h.scope.launch { h.player.prepare(source) }
        h.jobs += job
        if (m != null) {
            if (m.released) {
                m.kind = Kind.REFUSED
            } else {
                if (m.holds) m.releases++ // 04: a prepare frees the previous source first, whatever state it left the player in
                m.holds = true
                m.kind = Kind.PREPARING
                m.pending = Call(job, fail, cause)
                m.sourceReset()
            }
        }
    }

    private fun finishLoad(h: Harness, m: Model?) {
        val call: Call? = m?.pending
        if (m == null) {
            // Chaos: no model, the outcome is a coin of the seed's own sequence (the job count).
            if (h.jobs.size % 3 == 0) h.fake.failLoad(IllegalStateException("scripted load failure")) else h.fake.finishLoad()
            return
        }
        if (call == null) { h.fake.finishLoad(); return }
        if (call.fails) h.fake.failLoad(call.cause) else h.fake.finishLoad()
        h.dispatcher.drain()
        m.pending = null
        when {
            call.fails -> { m.kind = Kind.ERROR; m.errorCause = call.cause; m.sourceReset() }
            m.keptFailure != null -> { m.kind = Kind.ERROR; m.errorCause = m.keptFailure; m.sourceReset() }
            else -> {
                m.kind = Kind.READY; m.buffering = m.keptBuffering; m.keptBuffering = false; m.engineSpeed = m.speed
                // 03-guide, "Every setting outlives the source": what was set before or during the load is on the engine once Ready.
                val where: String = h.where()
                if (h.fake.appliedSpeed != m.speed) fail("a fresh load got speed ${h.fake.appliedSpeed}, the player says ${m.speed}; $where")
                if (h.fake.appliedVolume != m.effectiveVolume) fail("a fresh load got volume ${h.fake.appliedVolume}, expected ${m.effectiveVolume}; $where")
                if (h.fake.isLooping != (m.repeat == RepeatMode.One)) fail("a fresh load got looping ${h.fake.isLooping} with repeat ${m.repeat}; $where")
            }
        }
    }

    private fun cancelCaller(h: Harness, r: Random, m: Model?) {
        val active: List<Job> = h.jobs.filter { it.isActive }
        val target: Job? = if (m != null) m.pending?.job else if (active.isNotEmpty() && r.nextInt(4) > 0) active[r.nextInt(active.size)] else h.jobs.takeIf { it.isNotEmpty() }?.let { it[r.nextInt(it.size)] }
        if (target != null) cover(if (target.isActive) "cancel/active-prepare" else "cancel/finished-prepare")
        target?.cancel()
        if (m != null && m.pending != null) { m.kind = Kind.IDLE; m.pending = null; m.sourceReset(); m.releases++; m.holds = false }
    }

    private fun modelPlay(m: Model) {
        if (m.released || !m.playable) return
        if (m.kind != Kind.PLAYING) m.engineSpeed = m.speed
        m.kind = Kind.PLAYING
    }

    private fun modelSeek(m: Model) {
        if (!m.released && m.kind == Kind.COMPLETED) m.kind = Kind.PAUSED
    }

    // ------------------------------------------------------------------ invariants

    private fun kindOf(state: VideoPlayerState): Kind = when (state) {
        VideoPlayerState.Idle -> Kind.IDLE
        VideoPlayerState.Preparing -> Kind.PREPARING
        is VideoPlayerState.Ready -> Kind.READY
        is VideoPlayerState.Playing -> Kind.PLAYING
        is VideoPlayerState.Paused -> Kind.PAUSED
        is VideoPlayerState.Completed -> Kind.COMPLETED
        is VideoPlayerState.Error -> Kind.ERROR
    }

    private fun checkCommon(h: Harness, released: Boolean) {
        val state: VideoPlayerState = h.player.stateFlow.value
        val where: String = h.where()
        val length: Long? = when (state) {
            is VideoPlayerState.Ready -> state.duration
            is VideoPlayerState.Playing -> state.duration
            is VideoPlayerState.Paused -> state.duration
            is VideoPlayerState.Completed -> state.duration
            else -> null
        }
        val position: Long = h.player.playbackPositionFlow.value
        if (position < 0L) fail("position $position below 0, state $state; $where")
        if (length != null && length > 0L && position > length) fail("position $position beyond the published length $length, state $state; $where")
        if (state is VideoPlayerState.Playing && state.currentPosition > state.duration && state.duration > 0L) fail("Playing position beyond length: $state; $where")
        if (state is VideoPlayerState.Paused && state.currentPosition > state.duration && state.duration > 0L) fail("Paused position beyond length: $state; $where")
        if (h.fake.isPlaying && state !is VideoPlayerState.Playing) fail("the engine plays while the player says $state; $where")
        if (state is VideoPlayerState.Playing && !h.fake.isPlaying) fail("Playing but the engine is not playing; $where")
        // Buffering is reported for a loaded, unfinished source only.
        if (h.player.isBufferingFlow.value && !(state is VideoPlayerState.Ready || state is VideoPlayerState.Playing || state is VideoPlayerState.Paused)) {
            fail("buffering while $state; $where")
        }
        val speed: Float = h.player.playbackSpeedFlow.value
        if (!(speed in 0.25f..3f)) fail("speed $speed outside the range; $where")
        val volume: Float = h.player.volumeFlow.value
        if (!(volume in 0f..1f)) fail("volume $volume outside 0..1; $where")
        if (released) {
            if (!(state is VideoPlayerState.Idle || (state is VideoPlayerState.Error && state.cause is VideoPlayerReleasedException))) {
                fail("state $state after release; $where")
            }
            if (h.player.isBufferingFlow.value) fail("buffering after release; $where")
        }
    }

    private fun checkSettled(h: Harness, m: Model) {
        checkCommon(h, m.released)
        val state: VideoPlayerState = h.player.stateFlow.value
        val where: String = h.where()
        val expected: Kind = if (m.released) (if (m.kind == Kind.REFUSED) Kind.ERROR else Kind.IDLE) else if (m.kind == Kind.REFUSED) Kind.ERROR else m.kind
        if (kindOf(state) != expected) fail("state $state but the model says ${m.kind} (released=${m.released}); $where")
        if (state is VideoPlayerState.Error && !m.released) {
            val cause: Throwable? = m.errorCause
            if (cause != null && state.cause !== cause) fail("the Error carries ${state.cause}, not the engine's own $cause; $where")
        }
        if (h.player.playbackSpeedFlow.value != m.speed) fail("speed flow ${h.player.playbackSpeedFlow.value} model ${m.speed}; $where")
        if (h.player.volumeFlow.value != m.volume) fail("volume flow ${h.player.volumeFlow.value} model ${m.volume}; $where")
        if (h.player.isMutedFlow.value != m.muted) fail("muted flow ${h.player.isMutedFlow.value} model ${m.muted}; $where")
        if (h.player.repeatModeFlow.value != m.repeat) fail("repeat flow ${h.player.repeatModeFlow.value} model ${m.repeat}; $where")
        if (!m.released && h.player.isBufferingFlow.value != m.buffering) fail("buffering flow ${h.player.isBufferingFlow.value} model ${m.buffering}; $where")
        if (h.fake.releaseCount != m.releases) fail("engine released ${h.fake.releaseCount} times, the contract says ${m.releases}; $where")
        if (h.fake.isLoadPending != (m.pending != null)) fail("load pending ${h.fake.isLoadPending} model ${m.pending != null}; $where")
        if (!m.released && m.playable) {
            if (h.fake.appliedVolume != m.effectiveVolume) fail("engine volume ${h.fake.appliedVolume} model ${m.effectiveVolume}; $where")
            if (h.fake.isLooping != (m.repeat == RepeatMode.One)) fail("engine looping ${h.fake.isLooping} model ${m.repeat}; $where")
            // 04, Settings: a speed reaches the platform at once only while Playing, otherwise on the next play() or load.
            if (h.fake.appliedSpeed != m.engineSpeed) fail("engine speed ${h.fake.appliedSpeed}, expected ${m.engineSpeed} (player says ${m.speed}, ${m.kind}); $where")
        }
        val scheduled: Int = h.dispatcher.pending
        if (scheduled != (if (state is VideoPlayerState.Playing) 1 else 0)) fail("$scheduled scheduled tasks in $state (the poll runs only while Playing); $where")
        if (m.pending == null && h.jobs.isNotEmpty() && h.jobs.any { it.isActive }) fail("a prepare job is still active with no load in flight; $where")
        if (m.released && h.callsAtSettledRelease >= 0 && h.engine.calls != h.callsAtSettledRelease) {
            fail("engine called after the release settled: ${h.engine.calls - h.callsAtSettledRelease} calls; $where")
        }
    }

    private fun finish(h: Harness) {
        val where: String = h.where()
        if (h.emittedAtRelease < 0) h.emittedAtRelease = h.emitted.size
        h.player.release()
        h.dispatcher.drain()
        h.jobs.forEach { it.cancel() }
        h.dispatcher.drain()
        h.dispatcher.advance(1_000L)
        if (h.fake.disposeCount != 1) fail("disposed ${h.fake.disposeCount} times, not once; $where")
        if (h.fake.hasListener) fail("a listener is still attached; $where")
        if (h.fake.isLoadPending) fail("a load is still pending; $where")
        if (h.fake.isPlaying) fail("the engine still plays; $where")
        if (h.dispatcher.pending != 0) fail("${h.dispatcher.pending} tasks are still queued (a poll or a job left running); $where")
        h.jobs.firstOrNull { !it.isCompleted }?.let { fail("a prepare job never completed: $it; $where") }
        if (h.escaped.isNotEmpty()) fail("an exception escaped into the caller's scope: ${h.escaped}; $where")
        runBlocking { h.collector.cancelAndJoin() }
        val late: List<VideoPlayerState> = h.emitted.drop(h.emittedAtRelease.coerceAtLeast(0))
        if (late.any { !(it is VideoPlayerState.Idle || (it is VideoPlayerState.Error && it.cause is VideoPlayerReleasedException)) }) {
            fail("states published after the release: $late; $where")
        }
    }

    // ------------------------------------------------------------------ drivers

    private fun runSettled(seed: Int, steps: Int) {
        val h = Harness(seed, "settled", if (seed % 2 == 0) 5_000L else 0L)
        val m = Model(0.25f, 3f)
        val random = Random(seed)
        val table: List<Pair<Int, Op>> = ops()
        val total: Int = table.sumOf { it.first }
        val releaseOp: Op = table.first { it.second.name == "release" }.second
        val releaseAt: Int = random.nextInt(steps * 4)
        try {
            repeat(steps) { step: Int ->
                var pick: Int = random.nextInt(total)
                val op: Op = if (releaseDue(h, step, releaseAt, seed)) releaseOp else table.first { (weight: Int, _) -> pick -= weight; pick < 0 }.second
                h.trace += "$step:${op.name}"
                op.run(h, random, m)
                h.dispatcher.drain()
                if (m.released && h.callsAtSettledRelease < 0) {
                    h.callsAtSettledRelease = h.engine.calls
                    h.emittedAtRelease = h.emitted.size
                }
                checkSettled(h, m)
                cover("kind/${m.kind}")
            }
            finish(h)
        } finally {
            h.player.release()
        }
    }

    private fun runChaos(seed: Int, steps: Int) {
        val h = Harness(seed, "chaos", if (seed % 2 == 0) 5_000L else 0L)
        val random = Random(seed * 7919L)
        val table: List<Pair<Int, Op>> = ops()
        val total: Int = table.sumOf { it.first }
        val releaseOp: Op = table.first { it.second.name == "release" }.second
        val releaseAt: Int = random.nextInt(steps * 4)
        var released = false
        try {
            repeat(steps) { step: Int ->
                var pick: Int = random.nextInt(total)
                val op: Op = if (releaseDue(h, step, releaseAt, seed)) releaseOp else table.first { (weight: Int, _) -> pick -= weight; pick < 0 }.second
                h.trace += "$step:${op.name}"
                op.run(h, random, null)
                // Cut the coroutines mid-flight: run a few single tasks, not all of them.
                repeat(random.nextInt(0, 4)) { h.dispatcher.step() }
                if (op.name == "release") {
                    released = true
                    h.dispatcher.drain()
                    h.callsAtSettledRelease = h.engine.calls
                    h.emittedAtRelease = h.emitted.size
                } else if (released) {
                    h.dispatcher.drain()
                }
                checkCommon(h, released)
                cover("state/${h.player.stateFlow.value::class.simpleName}")
                if (released && h.engine.calls != h.callsAtSettledRelease) {
                    fail("engine called after the release settled: ${h.engine.calls - h.callsAtSettledRelease}; ${h.where()}")
                }
            }
            finish(h)
        } finally {
            h.player.release()
        }
    }

    @Test
    fun `settled driver with a reference model agrees with the player after every step on forty seeds`() {
        for (seed in 1..40) runSettled(seed, steps = 160)
        requireCovered(
            "kind/PLAYING" to 150, "kind/PAUSED" to 100, "kind/COMPLETED" to 50, "kind/ERROR" to 100, "kind/PREPARING" to 150, "kind/READY" to 100, "kind/IDLE" to 100,
            "prepare/while-load-in-flight" to 50, "cancel/active-prepare" to 30, "release/load-in-flight" to 5,
        )
    }

    @Test
    fun `chaos driver cutting prepares at every suspension point keeps every invariant and leaks nothing on eighty seeds`() {
        for (seed in 1..80) runChaos(seed, steps = 140)
        requireCovered(
            "state/Playing" to 200, "state/Preparing" to 400, "state/Completed" to 30, "state/Error" to 400,
            "prepare/while-load-in-flight" to 100, "prepare/while-preparing" to 100, "cancel/active-prepare" to 100,
            "release/load-in-flight" to 8,
        )
    }
}
