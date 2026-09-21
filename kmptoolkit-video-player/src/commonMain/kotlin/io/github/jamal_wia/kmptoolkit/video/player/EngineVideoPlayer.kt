package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
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
 * An engine that holds something [VideoPlaybackEngine.release] deliberately keeps: the platform
 * player object a surface is attached to, which survives an unload so the surface does not have to
 * re-attach for every source. [dispose] frees it for good; the player calls it once, from its own
 * [VideoPlayer.release], after the engine's last [VideoPlaybackEngine.release].
 *
 * Internal because it describes the built-in engines' shape, not a promise to consumers: a
 * consumer-supplied engine frees everything in [VideoPlaybackEngine.release], as the SPI says.
 */
internal interface DisposableVideoPlaybackEngine {

    /** Frees the platform player itself. Called at most once, after the final release. */
    fun dispose()
}

/**
 * The whole [VideoPlayer] state machine, once, in common code — every platform engine only
 * translates calls. Modelled on `kmptoolkit-audio-player`'s `EngineAudioPlayer` and keeping its
 * guarantees: one load at a time on the engine, a newer [prepare] replaces an older one and waits for
 * it to unwind, [prepare] never throws on failure, a cancelled [prepare] frees the engine and settles
 * on Idle, [release] is idempotent and final, [unload] keeps the settings.
 *
 * On top of that it owns what a video needs: buffering, buffered position (polled with the
 * playhead, only while playing), picture size, volume and mute (folded into one engine volume, `0f`
 * while muted) and the repeat mode (engine looping). Settings are the player's, not the engine's:
 * they are pushed to the engine whenever a source is loaded and whenever they change while one is,
 * so an engine that forgets them on a new load cannot lose them.
 *
 * ### Threading
 *
 * Every transition — a transport or settings call, an engine callback, a poll tick, the steps of
 * [prepare] around the suspended load — runs under one [StateMachineLock], so each one reads the
 * state, calls the engine and writes the state without another interleaving. API calls wait for the
 * lock; engine callbacks and poll ticks never wait, they are handed to the thread holding it (see
 * [StateMachineLock] for why). Everything marked "guarded" below is touched only under that lock.
 *
 * Internal rather than private so the platform accessors (`media3PlayerOrNull`,
 * `avPlayerOrNull`, `frameSourceOrNull`) can reach [engine].
 */
@OptIn(ExperimentalAtomicApi::class, ToolkitInheritanceApi::class)
internal class EngineVideoPlayer(
    internal val engine: VideoPlaybackEngine,
    private val config: VideoPlayerConfig,
    coroutineContext: CoroutineContext,
) : VideoPlayer, VideoPlaybackEngineListener {

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
     * @property finished completes when the call has fully unwound — see `EngineAudioPlayer` for why
     *   waiting on [job] alone would let a third prepare start while the first still runs.
     */
    private class Load(val job: CompletableJob, val finished: CompletableJob = Job())

    // Written under the lock; volatile so the unlocked fast-path checks in prepare() see it.
    @Volatile
    private var released: Boolean = false

    private val _stateFlow: MutableStateFlow<VideoPlayerState> = MutableStateFlow(VideoPlayerState.Idle)
    override val stateFlow: StateFlow<VideoPlayerState> = _stateFlow.asStateFlow()

    private val _playbackPositionFlow: MutableStateFlow<Long> = MutableStateFlow(0L)
    override val playbackPositionFlow: StateFlow<Long> = _playbackPositionFlow.asStateFlow()

    private val _bufferedPositionFlow: MutableStateFlow<Long> = MutableStateFlow(0L)
    override val bufferedPositionFlow: StateFlow<Long> = _bufferedPositionFlow.asStateFlow()

    private val _isBufferingFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isBufferingFlow: StateFlow<Boolean> = _isBufferingFlow.asStateFlow()

    private val _videoSizeFlow: MutableStateFlow<VideoSize?> = MutableStateFlow(null)
    override val videoSizeFlow: StateFlow<VideoSize?> = _videoSizeFlow.asStateFlow()

    private val _playbackSpeedFlow: MutableStateFlow<Float> = MutableStateFlow(NORMAL_PLAYBACK_SPEED)
    override val playbackSpeedFlow: StateFlow<Float> = _playbackSpeedFlow.asStateFlow()

    private val _volumeFlow: MutableStateFlow<Float> = MutableStateFlow(FULL_VOLUME)
    override val volumeFlow: StateFlow<Float> = _volumeFlow.asStateFlow()

    private val _isMutedFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isMutedFlow: StateFlow<Boolean> = _isMutedFlow.asStateFlow()

    private val _repeatModeFlow: MutableStateFlow<RepeatMode> = MutableStateFlow(RepeatMode.Off)
    override val repeatModeFlow: StateFlow<RepeatMode> = _repeatModeFlow.asStateFlow()

    /** The volume the engine should output: [volumeFlow], or `0f` while muted. */
    private val effectiveVolume: Float
        get() = if (_isMutedFlow.value) MUTED_VOLUME else _volumeFlow.value

    init {
        engine.setListener(this)
    }

    override suspend fun prepare(source: VideoSource) {
        if (released) {
            gate.exclusive { _stateFlow.value = VideoPlayerState.Error(VideoPlayerReleasedException()) }
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
                resetSourceFlows()
                _stateFlow.value = VideoPlayerState.Preparing
                true
            }
            if (!started) return

            // The failure comes back as a value rather than being thrown out of withContext: an
            // exception crossing that boundary may be replaced by a stack-trace-recovered copy, and
            // VideoPlayerState.Error promises the engine's own Throwable.
            val failure: Throwable? = withContext(load.job) { loadCatching(source) }
            if (failure != null) {
                gate.exclusive {
                    if (isCurrent(load)) {
                        resetSourceFlows()
                        _stateFlow.value = VideoPlayerState.Error(failure)
                    }
                }
                return
            }
        } catch (cancellation: CancellationException) {
            if (currentLoad.load() !== load) {
                // Superseded. Whatever replaced this load owns the engine and the state now.
                if (currentCoroutineContext().isActive) return
                throw cancellation
            }
            // The caller went away: drop whatever the load got to, report Idle rather than Error
            // (nobody failed), and let the cancellation propagate. Wait for a load this one replaced
            // to finish unwinding first — releasing under it is the interleaving ruled out above.
            withContext(NonCancellable) { previous?.finished?.join() }
            gate.exclusive {
                if (currentLoad.load() === load) {
                    engine.release()
                    if (!released) {
                        resetSourceFlows()
                        _stateFlow.value = VideoPlayerState.Idle
                    }
                }
            }
            throw cancellation
        } finally {
            load.job.complete()
            load.finished.complete()
        }

        gate.exclusive {
            if (!isCurrent(load)) return@exclusive
            // Settings belong to the player and outlive every source; push them onto the fresh load.
            engine.setVolume(effectiveVolume)
            engine.setLooping(_repeatModeFlow.value == RepeatMode.One)
            engine.setSpeed(_playbackSpeedFlow.value)
            _bufferedPositionFlow.value = engine.bufferedPositionMs().coerceAtLeast(0L)
            _stateFlow.value = VideoPlayerState.Ready(engine.durationMs())
        }
    }

    override fun play(): Unit = gate.exclusive { playLocked() }

    override fun pause(): Unit = gate.exclusive {
        if (released) return@exclusive
        val current: VideoPlayerState = _stateFlow.value
        if (current !is VideoPlayerState.Playing) return@exclusive

        engine.pause()
        stopPositionUpdates()
        val position: Long = engine.positionMs()
        _playbackPositionFlow.value = position
        _bufferedPositionFlow.value = engine.bufferedPositionMs().coerceAtLeast(0L)
        _stateFlow.value = VideoPlayerState.Paused(duration = current.duration, currentPosition = position)
    }

    override fun stop(): Unit = gate.exclusive {
        if (released) return@exclusive
        if (!_stateFlow.value.isPlayable) return@exclusive

        engine.pause()
        stopPositionUpdates()
        engine.seekTo(0L)
        _playbackPositionFlow.value = 0L
        _stateFlow.value = VideoPlayerState.Ready(engine.durationMs())
    }

    override fun seekTo(positionMs: Long): Unit = gate.exclusive { seekToLocked(positionMs) }

    override fun seekForward(amountMs: Long): Unit = gate.exclusive {
        if (released) return@exclusive
        if (!_stateFlow.value.isPlayable) return@exclusive
        seekToLocked(saturatingAdd(engine.positionMs(), amountMs))
    }

    override fun seekBackward(amountMs: Long): Unit = gate.exclusive {
        if (released) return@exclusive
        if (!_stateFlow.value.isPlayable) return@exclusive
        seekToLocked(saturatingAdd(engine.positionMs(), -amountMs.coerceAtLeast(-Long.MAX_VALUE)))
    }

    override fun replay(): Unit = gate.exclusive {
        if (released) return@exclusive
        if (!_stateFlow.value.isPlayable) return@exclusive
        seekToLocked(0L)
        playLocked()
    }

    override fun setPlaybackSpeed(speed: Float) {
        // NaN has no place in a range; it would reach the engine as a rate no platform accepts.
        if (speed.isNaN()) return
        gate.exclusive {
            _playbackSpeedFlow.value = speed.coerceIn(config.minPlaybackSpeed, config.maxPlaybackSpeed)
            if (released) return@exclusive
            // Only while playing, like the audio player: on AVPlayer a non-zero rate on a paused
            // player resumes it. A paused or ready player picks the speed up in play().
            if (_stateFlow.value is VideoPlayerState.Playing) engine.setSpeed(_playbackSpeedFlow.value)
        }
    }

    override fun setVolume(volume: Float) {
        if (volume.isNaN()) return
        gate.exclusive {
            _volumeFlow.value = volume.coerceIn(MUTED_VOLUME, FULL_VOLUME)
            pushVolume()
        }
    }

    override fun setMuted(muted: Boolean): Unit = gate.exclusive {
        _isMutedFlow.value = muted
        pushVolume()
    }

    override fun setRepeatMode(mode: RepeatMode): Unit = gate.exclusive {
        _repeatModeFlow.value = mode
        if (released) return@exclusive
        if (_stateFlow.value.isPlayable) engine.setLooping(mode == RepeatMode.One)
    }

    override fun unload(): Unit = gate.exclusive {
        if (released) return@exclusive
        abandonLoad(dispose = false)
        stopPositionUpdates()

        resetSourceFlows()
        _stateFlow.value = VideoPlayerState.Idle
    }

    override fun release(): Unit = gate.exclusive {
        if (released) return@exclusive
        released = true

        stopPositionUpdates()
        scope.cancel()
        engine.setListener(null)
        abandonLoad(dispose = true)

        resetSourceFlows()
        _stateFlow.value = VideoPlayerState.Idle
    }

    // Engine callbacks arrive on the engine's own thread, often while it holds a lock of its own:
    // they are submitted, never waited for. Completion and failure describe a loaded source; one
    // arriving when nothing is loaded — posted by the platform just before an unload or release
    // detached it — is stale and must not resurrect state.

    override fun onCompleted(): Unit = gate.submit {
        if (released || !_stateFlow.value.isPlayable) return@submit
        val duration: Long = engine.durationMs()
        if (_repeatModeFlow.value == RepeatMode.One) {
            // An engine should loop on its own and never report this while looping, but the mode can
            // change just as the platform posts the end. Completed is never reached in One: start
            // over, which is what the platform would have done.
            engine.seekTo(0L)
            engine.start()
            _playbackPositionFlow.value = 0L
            _stateFlow.value = VideoPlayerState.Playing(duration = duration, currentPosition = 0L)
            startPositionUpdates()
            return@submit
        }
        stopPositionUpdates()
        _isBufferingFlow.value = false
        _playbackPositionFlow.value = duration
        _stateFlow.value = VideoPlayerState.Completed(duration)
    }

    override fun onFailed(cause: Throwable): Unit = gate.submit {
        if (released || !_stateFlow.value.isPlayable) return@submit
        stopPositionUpdates()
        _isBufferingFlow.value = false
        _videoSizeFlow.value = null
        _bufferedPositionFlow.value = 0L
        _stateFlow.value = VideoPlayerState.Error(cause)
    }

    override fun onBufferingChanged(isBuffering: Boolean): Unit = gate.submit {
        // Waiting for data while preparing is what Preparing already says; buffering is reported for a
        // loaded source only, so a stale report cannot leave an idle player "buffering".
        if (released || !_stateFlow.value.isPlayable) return@submit
        _isBufferingFlow.value = isBuffering
    }

    override fun onVideoSizeChanged(size: VideoSize?): Unit = gate.submit {
        // Unlike buffering, the size is usually learned while preparing, so it is accepted then too.
        if (released) return@submit
        val current: VideoPlayerState = _stateFlow.value
        if (current != VideoPlayerState.Preparing && !current.isPlayable) return@submit
        _videoSizeFlow.value = size
    }

    // --- Transitions shared by several entry points. Lock held. ---

    private fun playLocked() {
        if (released) return
        val current: VideoPlayerState = _stateFlow.value
        if (!current.isPlayable || current is VideoPlayerState.Playing) return

        if (current is VideoPlayerState.Completed) {
            // From the end there is nothing left to resume: rewind explicitly so every platform
            // means the same thing by "play a completed source".
            engine.seekTo(0L)
            _playbackPositionFlow.value = 0L
        }
        engine.start()
        engine.setSpeed(_playbackSpeedFlow.value)
        _stateFlow.value = VideoPlayerState.Playing(
            duration = engine.durationMs(),
            currentPosition = engine.positionMs(),
        )
        startPositionUpdates()
    }

    private fun seekToLocked(positionMs: Long) {
        if (released) return
        val current: VideoPlayerState = _stateFlow.value
        if (!current.isPlayable) return

        val duration: Long = engine.durationMs()
        val target: Long = positionMs.coerceIn(0L, maxOf(0L, duration))
        engine.seekTo(target)
        _playbackPositionFlow.value = target

        _stateFlow.value = when (current) {
            is VideoPlayerState.Playing -> current.copy(currentPosition = target)
            is VideoPlayerState.Paused -> current.copy(currentPosition = target)
            // Once the playhead has moved off the end, Paused is the true description — and the
            // state play() resumes from correctly.
            is VideoPlayerState.Completed -> VideoPlayerState.Paused(current.duration, target)
            else -> current
        }
    }

    private fun pushVolume() {
        if (released) return
        if (_stateFlow.value.isPlayable) engine.setVolume(effectiveVolume)
    }

    /** Resets everything that describes a source — never the settings, which outlive sources. */
    private fun resetSourceFlows() {
        _playbackPositionFlow.value = 0L
        _bufferedPositionFlow.value = 0L
        _isBufferingFlow.value = false
        _videoSizeFlow.value = null
    }

    /** Runs the engine's load, returning its failure instead of throwing it. Cancellation still throws. */
    private suspend fun loadCatching(source: VideoSource): Throwable? = try {
        engine.load(source)
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        // Deliberately broad: an engine reports every load failure by throwing, and
        // VideoPlayerState.Error hands that Throwable to the consumer unchanged.
        failure
    }

    /**
     * Lock held. Ends whatever load is current and frees the engine — but only once that load has
     * finished unwinding. The load is replaced by an already-cancelled placeholder whose
     * [Load.finished] completes after the engine is freed, so a [prepare] arriving meanwhile still
     * waits for all of it.
     *
     * @param dispose also free the platform player behind a [DisposableVideoPlaybackEngine] — only on
     *   the final [release].
     */
    private fun abandonLoad(dispose: Boolean) {
        val placeholder = Load(Job().apply { cancel() })
        val abandoned: Load? = currentLoad.exchange(placeholder)
        abandoned?.job?.cancel()
        val freeEngine: () -> Unit = {
            engine.release()
            if (dispose) (engine as? DisposableVideoPlaybackEngine)?.dispose()
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
        val current: VideoPlayerState = _stateFlow.value
        if (current !is VideoPlayerState.Playing) return
        val position: Long = engine.positionMs()
        // Compare-and-set as well as the lock: the tick must only ever refine the Playing state it
        // read, never replace a state another transition wrote.
        if (!_stateFlow.compareAndSet(current, current.copy(currentPosition = position))) return
        _playbackPositionFlow.value = position
        _bufferedPositionFlow.value = engine.bufferedPositionMs().coerceAtLeast(0L)
    }

    /** Lock held. */
    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private companion object {
        const val NORMAL_PLAYBACK_SPEED: Float = 1.0f
        const val FULL_VOLUME: Float = 1.0f
        const val MUTED_VOLUME: Float = 0.0f

        /** `a + b` without wrapping around, so a huge seek step clamps instead of flipping sign. */
        fun saturatingAdd(a: Long, b: Long): Long {
            val sum: Long = a + b
            // Overflow happened iff both operands share a sign the result does not.
            return if ((a xor sum) and (b xor sum) < 0L) {
                if (a < 0L) Long.MIN_VALUE else Long.MAX_VALUE
            } else {
                sum
            }
        }
    }
}
