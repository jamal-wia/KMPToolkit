package io.github.jamal_wia.kmptoolkit.audio.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

/**
 * Builds an [AudioPlayer] on top of an arbitrary [PlaybackEngine].
 *
 * Use this overload when you supply the engine yourself — a fake in a test, or your own Media3 or
 * game-audio backend. To play audio on a device, prefer the platform factories, which construct the
 * built-in engine for you: `createAudioPlayer(context)` in `androidMain`, `createAudioPlayer()` in
 * `iosMain`.
 *
 * The returned player takes ownership of [engine]: it installs itself as the engine's listener and
 * calls [PlaybackEngine.release] from its own [AudioPlayer.release]. Do not release the engine
 * yourself, and do not share one engine between two players.
 *
 * @param engine the platform seam to drive.
 * @param config tunables; see [AudioPlayerConfig].
 * @param coroutineContext context for the position-polling coroutine. The default is
 *   [Dispatchers.Default] — polling only reads two numbers and writes two `StateFlow`s, so it has no
 *   reason to occupy the main thread. Pass a `TestDispatcher` to make polling deterministic in
 *   tests.
 * @return a player in [PlayerState.Idle], ready for [AudioPlayer.prepare].
 */
public fun createAudioPlayer(
    engine: PlaybackEngine,
    config: AudioPlayerConfig = AudioPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): AudioPlayer = EngineAudioPlayer(engine, config, coroutineContext)

/**
 * The whole state machine, once, in common code — the only part of this library that is worth
 * testing and the only part that would otherwise be duplicated per platform.
 *
 * The donor implementation this is ported from kept a full copy of these transitions inside each
 * platform engine, which is how the two drifted apart (its Android `stop()` re-prepared the source,
 * its iOS `stop()` released the player). Splitting the seam at [PlaybackEngine] leaves each platform
 * with nothing but API translation.
 *
 * ### One load at a time
 *
 * An engine is never asked to load while another load is still running on it. A [prepare] that
 * arrives mid-load cancels the load in flight and waits for it to finish unwinding before its own
 * begins; [unload] and [release] cancel it too. Without that, two overlapping loads interleave on
 * one native handle — the second one's `release()` tears down the player the first is still
 * awaiting, which on Android leaves the first `prepare` suspended forever and on iOS fails it with
 * an error that then overwrites the second one's state.
 *
 * Only the most recent load writes state. A superseded [prepare] returns quietly: the caller that
 * replaced it now owns the outcome.
 *
 * ### One transition at a time
 *
 * Every transition — a transport call, an engine callback, a poll tick, the steps of [prepare] around
 * the suspended load — runs under one [StateMachineLock], so none can interleave with another. Without
 * it, a poll tick on a background thread that read `Playing` just before the engine reported the end
 * on the main thread would write its stale `Playing` over `Completed`, and the player would claim to
 * be playing forever. Transport calls wait for the lock; engine callbacks and poll ticks never wait —
 * they are handed to the thread holding it. Everything marked "guarded" is touched only under it.
 */
@OptIn(ExperimentalAtomicApi::class)
private class EngineAudioPlayer(
    private val engine: PlaybackEngine,
    private val config: AudioPlayerConfig,
    coroutineContext: CoroutineContext,
) : AudioPlayer, PlaybackEngineListener {

    private val gate: StateMachineLock = StateMachineLock()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + coroutineContext)

    /** The polling coroutine while playing. Guarded. */
    private var positionJob: Job? = null

    /** The load in flight, or the last one to finish. Swapped atomically by [prepare], [unload], [release]. */
    private val currentLoad: AtomicReference<Load?> = AtomicReference(null)

    /**
     * One [prepare] call.
     *
     * @property job parents the engine work, so cancelling it cancels exactly this load.
     * @property finished completes when the call has fully unwound. Waiting on [job] would not do: a
     *   load cancelled while still waiting for its own predecessor has no children yet, so its job
     *   completes at once — and a third prepare waiting on it would start while the first is still
     *   running on the engine.
     */
    private class Load(val job: CompletableJob, val finished: CompletableJob = Job())

    // Written under the lock; volatile so the unlocked fast-path check in prepare() sees it.
    @Volatile
    private var released: Boolean = false

    private val _stateFlow: MutableStateFlow<PlayerState> = MutableStateFlow(PlayerState.Idle)
    override val stateFlow: StateFlow<PlayerState> = _stateFlow.asStateFlow()

    private val _playbackPositionFlow: MutableStateFlow<Long> = MutableStateFlow(0L)
    override val playbackPositionFlow: StateFlow<Long> = _playbackPositionFlow.asStateFlow()

    @Volatile
    override var playbackSpeed: Float = NORMAL_PLAYBACK_SPEED
        private set

    init {
        engine.setListener(this)
    }

    override suspend fun prepare(source: AudioSource) {
        if (released) {
            gate.exclusive { _stateFlow.value = PlayerState.Error(AudioPlayerReleasedException()) }
            return
        }
        // A child of the caller's job, so the caller's cancellation still reaches the engine, and a
        // handle of our own, so a later prepare/unload/release can cancel just this load.
        val load = Load(Job(parent = currentCoroutineContext()[Job]))
        val previous: Load? = currentLoad.exchange(load)
        try {
            previous?.let {
                previous.job.cancel()
                previous.finished.join()
            }
            val started: Boolean = gate.exclusive {
                if (!isCurrent(load)) return@exclusive false
                stopPositionUpdates()
                _playbackPositionFlow.value = 0L
                _stateFlow.value = PlayerState.Preparing
                true
            }
            if (!started) return

            // The failure comes back as a value rather than being thrown out of withContext: an
            // exception crossing that boundary may be replaced by a stack-trace-recovered copy, and
            // PlayerState.Error promises the engine's own Throwable.
            val failure: Throwable? = withContext(load.job) { loadCatching(source) }
            if (failure != null) {
                gate.exclusive { if (isCurrent(load)) _stateFlow.value = PlayerState.Error(failure) }
                return
            }
        } catch (cancellation: CancellationException) {
            if (currentLoad.load() !== load) {
                // Superseded. Whatever replaced this load owns the engine and the state now; touching
                // either here would tear down the newer load. The engine's own cancellation path has
                // already discarded what this one got to.
                if (currentCoroutineContext().isActive) return
                throw cancellation
            }
            // The caller went away. A cancelled prepare must not leave a half-loaded engine
            // reachable: drop whatever it got to, report Idle rather than Error (nobody failed), and
            // let the cancellation propagate so structured concurrency still works.
            //
            // The caller may have been cancelled while still waiting for the load it replaced, which
            // is then still unwinding on the engine: releasing now would tear into it. Wait for it
            // first, and release only if nothing newer took over meanwhile. This load stays current
            // until its finally, so a prepare arriving meanwhile waits for all of it.
            withContext(NonCancellable) { previous?.finished?.join() }
            gate.exclusive {
                if (currentLoad.load() === load) {
                    engine.release()
                    if (!released) _stateFlow.value = PlayerState.Idle
                }
            }
            throw cancellation
        } finally {
            load.job.complete()
            load.finished.complete()
        }

        gate.exclusive {
            if (!isCurrent(load)) return@exclusive
            engine.setSpeed(playbackSpeed)
            _stateFlow.value = PlayerState.Ready(engine.durationMs())
        }
    }

    override fun play(): Unit = gate.exclusive { playLocked() }

    override fun pause(): Unit = gate.exclusive {
        if (released) return@exclusive
        val current: PlayerState = _stateFlow.value
        if (current !is PlayerState.Playing) return@exclusive

        engine.pause()
        stopPositionUpdates()
        val position: Long = engine.positionMs()
        _playbackPositionFlow.value = position
        _stateFlow.value = PlayerState.Paused(duration = current.duration, currentPosition = position)
    }

    override fun stop(): Unit = gate.exclusive {
        if (released) return@exclusive
        if (!_stateFlow.value.isPlayable) return@exclusive

        engine.pause()
        stopPositionUpdates()
        engine.seekTo(0L)
        _playbackPositionFlow.value = 0L
        _stateFlow.value = PlayerState.Ready(engine.durationMs())
    }

    override fun seekTo(positionMs: Long): Unit = gate.exclusive { seekToLocked(positionMs) }

    override fun seekForward(amountMs: Long): Unit = gate.exclusive {
        if (released) return@exclusive
        if (!_stateFlow.value.isPlayable) return@exclusive
        seekToLocked(engine.positionMs() + amountMs)
    }

    override fun seekBackward(amountMs: Long): Unit = gate.exclusive {
        if (released) return@exclusive
        if (!_stateFlow.value.isPlayable) return@exclusive
        seekToLocked(engine.positionMs() - amountMs)
    }

    override fun replay(): Unit = gate.exclusive {
        if (released) return@exclusive
        if (!_stateFlow.value.isPlayable) return@exclusive
        seekToLocked(0L)
        playLocked()
    }

    override fun setPlaybackSpeed(speed: Float): Unit = gate.exclusive {
        playbackSpeed = speed.coerceIn(config.minPlaybackSpeed, config.maxPlaybackSpeed)
        if (released) return@exclusive
        if (_stateFlow.value is PlayerState.Playing) engine.setSpeed(playbackSpeed)
    }

    override fun unload(): Unit = gate.exclusive {
        if (released) return@exclusive
        abandonLoad()
        stopPositionUpdates()

        _stateFlow.value = PlayerState.Idle
        _playbackPositionFlow.value = 0L
    }

    override fun release(): Unit = gate.exclusive {
        if (released) return@exclusive
        released = true

        stopPositionUpdates()
        scope.cancel()
        engine.setListener(null)
        abandonLoad()

        _stateFlow.value = PlayerState.Idle
        _playbackPositionFlow.value = 0L
    }

    // Both callbacks describe a loaded source. One arriving when nothing is loaded — posted by the
    // platform just before an unload or release detached it — is stale and must not resurrect state.
    // They arrive on the engine's thread and are submitted, never waited for.

    override fun onCompleted(): Unit = gate.submit {
        if (released || !_stateFlow.value.isPlayable) return@submit
        stopPositionUpdates()
        val duration: Long = engine.durationMs()
        _playbackPositionFlow.value = duration
        _stateFlow.value = PlayerState.Completed(duration)
    }

    override fun onFailed(cause: Throwable): Unit = gate.submit {
        if (released || !_stateFlow.value.isPlayable) return@submit
        stopPositionUpdates()
        _stateFlow.value = PlayerState.Error(cause)
    }

    // --- Transitions shared by several entry points. Lock held. ---

    private fun playLocked() {
        if (released) return
        val current: PlayerState = _stateFlow.value
        if (!current.isPlayable || current is PlayerState.Playing) return

        if (current is PlayerState.Completed) {
            // From the end there is nothing left to resume. MediaPlayer restarts from 0 on its own
            // while AVPlayer sits at the end without completing again, so rewind explicitly and make
            // both platforms mean the same thing.
            engine.seekTo(0L)
            _playbackPositionFlow.value = 0L
        }
        engine.start()
        engine.setSpeed(playbackSpeed)
        _stateFlow.value = PlayerState.Playing(
            duration = engine.durationMs(),
            currentPosition = engine.positionMs(),
        )
        startPositionUpdates()
    }

    private fun seekToLocked(positionMs: Long) {
        if (released) return
        val current: PlayerState = _stateFlow.value
        if (!current.isPlayable) return

        val duration: Long = engine.durationMs()
        val target: Long = positionMs.coerceIn(0L, maxOf(0L, duration))
        engine.seekTo(target)
        _playbackPositionFlow.value = target

        _stateFlow.value = when (current) {
            is PlayerState.Playing -> current.copy(currentPosition = target)
            is PlayerState.Paused -> current.copy(currentPosition = target)
            // Once the playhead has moved off the end, "completed" is no longer a true description
            // of the player — Paused is, and it is the state play() resumes from correctly.
            is PlayerState.Completed -> PlayerState.Paused(current.duration, target)
            else -> current
        }
    }

    /** Runs the engine's load, returning its failure instead of throwing it. Cancellation still throws. */
    private suspend fun loadCatching(source: AudioSource): Throwable? = try {
        engine.load(source)
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        // Deliberately broad: an engine reports every load failure by throwing, and the whole point
        // of PlayerState.Error is to hand that Throwable to the consumer unchanged instead of
        // guessing which platform types are worth catching.
        failure
    }

    /**
     * Lock held. Ends whatever load is current and frees the engine — but only once that load has
     * finished unwinding, since freeing the engine under a load that is still running on it is exactly
     * the interleaving this class exists to rule out.
     *
     * The load is replaced by an already-cancelled placeholder whose [Load.finished] completes after
     * the engine is freed, so a [prepare] arriving in the meantime still waits for all of it. Swapping
     * in `null` instead would let that prepare start while the abandoned load was still unwinding.
     */
    private fun abandonLoad() {
        val placeholder = Load(Job().apply { cancel() })
        val abandoned: Load? = currentLoad.exchange(placeholder)
        abandoned?.job?.cancel()
        val freeEngine: () -> Unit = {
            engine.release()
            placeholder.finished.complete()
        }
        val unwinding: Job? = abandoned?.finished?.takeUnless { it.isCompleted }
        if (unwinding == null) {
            freeEngine()
        } else {
            // Completes on whichever thread finishes the abandoned prepare; back under the lock
            // from there, like every other engine call.
            unwinding.invokeOnCompletion { gate.submit(freeEngine) }
        }
    }

    private fun isCurrent(load: Load): Boolean = !released && currentLoad.load() === load

    /** Lock held. (Re)starts polling; each tick is submitted, so it is serialized like a callback. */
    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionJob = scope.launch {
            val poller: Job? = currentCoroutineContext()[Job]
            while (isActive) {
                delay(config.positionUpdateIntervalMs)
                gate.submit { pollLocked(poller) }
            }
        }
    }

    /** Lock held. One poll tick of [poller] — a no-op if polling was stopped or restarted since. */
    private fun pollLocked(poller: Job?) {
        if (released || poller == null || positionJob !== poller) return
        val current: PlayerState = _stateFlow.value
        if (current !is PlayerState.Playing) return
        val position: Long = engine.positionMs()
        // Compare-and-set as well as the lock: the tick must only ever refine the Playing state it
        // read, never replace a state another transition wrote.
        if (!_stateFlow.compareAndSet(current, current.copy(currentPosition = position))) return
        _playbackPositionFlow.value = position
    }

    /** Lock held. */
    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private companion object {
        const val NORMAL_PLAYBACK_SPEED: Float = 1.0f
    }
}
