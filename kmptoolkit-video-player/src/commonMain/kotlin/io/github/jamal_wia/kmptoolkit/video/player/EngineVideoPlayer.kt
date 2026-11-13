package io.github.jamal_wia.kmptoolkit.video.player

import io.github.jamal_wia.kmptoolkit.core.StateMachineLock
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlin.coroutines.CoroutineContext
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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * **Every transition writes [stateFlow] last.** A collector on an immediate dispatcher runs inside that
 * write and may call the player back; nothing of the transition may run after it, or it would undo what
 * the collector did. A composite call ([replay]: seek, then play) re-reads the state between its steps.
 *
 * ### Dropped sources
 *
 * Whether a report belongs to the source the player now shows is not read off the published state;
 * two facts the player sets itself decide it. [engineHoldsSource] says whether the engine may still
 * hold a source (so a [prepare] frees it, whatever state it left the player in), and [sourceEpoch] is
 * bumped each time the player tells the engine to drop its source (and when a newer source begins).
 * Every listener method stamps the epoch on the reporting thread and the queued action is dropped when
 * the epoch has moved, so a report of a dropped source can never become the next [prepare]'s outcome,
 * including one the engine made from inside the `release()` that dropped it. The one window left is a
 * report whose call started before `release()` returned but whose thread had not yet read the epoch;
 * the engine contract rules it out (a call already running on another thread has returned before
 * `release` returns).
 *
 * Internal rather than private so the platform accessors (`media3PlayerOrNull`,
 * `avPlayerOrNull`, `frameSourceOrNull`) can reach [engine].
 */
@OptIn(ToolkitInheritanceApi::class, ToolkitInternalApi::class)
internal class EngineVideoPlayer(
    internal val engine: VideoPlaybackEngine,
    private val config: VideoPlayerConfig,
    coroutineContext: CoroutineContext,
) : VideoPlayer, VideoPlaybackEngineListener {

    private val gate: StateMachineLock = StateMachineLock()

    // A Job in the consumer's context would replace the SupervisorJob and make release() cancel the
    // consumer's own Job (a viewModelScope's, say), so it is stripped; everything else is kept.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))

    /** The polling coroutine while playing. Guarded. */
    private var positionJob: Job? = null

    /**
     * The load in flight, or the last one to finish. Not guarded by [gate]: [prepare] swaps it outside the
     * gate, so it can join the load it replaced without holding a blocking lock, while
     * unload/release (non-suspending) swap it under the gate. A [MutableStateFlow] only as an atomic
     * cell with `getAndUpdate`; nothing collects it (D-21, D-24). [Load] compares by identity.
     */
    private val currentLoad: MutableStateFlow<Load?> = MutableStateFlow(null)

    /**
     * One [prepare] call.
     *
     * @property job parents the engine work, so cancelling it cancels exactly this load.
     * @property finished completes when the call has fully unwound — see `EngineAudioPlayer` for why
     *   waiting on [job] alone would let a third prepare start while the first still runs.
     */
    private class Load(val job: CompletableJob, val finished: CompletableJob = Job())

    /**
     * What the engine reported about its source while [prepare] was still publishing it: the first
     * failure, and the last buffering value. A platform engine finishes its load on its own thread and
     * [prepare] resumes on the caller's a moment later, so a failure or stall of the loaded source can
     * land in between; applying them at [prepare]'s final step, in order, keeps them from being lost
     * (error wins over Ready, buffering is kept). Cleared with every source reset. Guarded.
     */
    private var preparingFailure: Throwable? = null
    private var preparingBuffering: Boolean = false

    /**
     * Whether the engine may hold a source: set when [prepare] hands a load to it (a load that fails
     * or is cancelled may still leave something behind), cleared whenever the player calls
     * `engine.release()`. The start of a [prepare] releases iff this is set. Guarded.
     */
    private var engineHoldsSource: Boolean = false

    /**
     * Bumped after every `engine.release()` the player makes and when a new source begins; a report
     * stamped with an older value belongs to a dropped source. Written under the lock, read without it:
     * a [MutableStateFlow] only as an atomic cell (D-21, D-24).
     */
    private val sourceEpoch: MutableStateFlow<Long> = MutableStateFlow(0L)

    /**
     * Bumped, after the engine call, by every transition that moves the playhead to a position that is
     * not the end (a seek away from the end, [stop], the rewind of a play from the end). A completion
     * stamps it on the reporting thread and is dropped when it moved: it described a playhead the
     * transition has since left. Written under the lock, read without it, like [sourceEpoch].
     */
    private val transportEpoch: MutableStateFlow<Long> = MutableStateFlow(0L)

    /**
     * Set once by [release]; writes are guarded. A [MutableStateFlow] only as an atomic cell (D-21,
     * D-24): [isReleased] is read without the lock by the platform accessors on the main thread, which
     * must not wait behind a transition and must see a [release] that has returned.
     */
    private val releasedCell: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private var released: Boolean
        get() = releasedCell.value
        set(value) {
            releasedCell.value = value
        }

    /** Whether [release] has returned (or is running), readable from any thread without the lock. */
    internal val isReleased: Boolean get() = releasedCell.value

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

    // Clamped like every later value: a configured range that excludes 1.0 must not start outside itself.
    private val _playbackSpeedFlow: MutableStateFlow<Float> =
        MutableStateFlow(NORMAL_PLAYBACK_SPEED.coerceIn(config.minPlaybackSpeed, config.maxPlaybackSpeed))
    override val playbackSpeedFlow: StateFlow<Float> = _playbackSpeedFlow.asStateFlow()

    override val playbackSpeedRange: ClosedFloatingPointRange<Float> = config.minPlaybackSpeed..config.maxPlaybackSpeed

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
        val refused: Boolean = gate.exclusive {
            if (released) _stateFlow.value = VideoPlayerState.Error(VideoPlayerReleasedException())
            released
        }
        if (refused) return
        // A child of the caller's job, so the caller's cancellation still reaches the engine, and a
        // handle of our own, so a later prepare/unload/release can cancel just this load.
        val load = Load(Job(parent = currentCoroutineContext()[Job]))
        val previous: Load? = currentLoad.getAndUpdate { load }
        try {
            previous?.let {
                previous.job.cancel()
                previous.finished.join()
            }
            val started: Boolean = gate.exclusive {
                if (!isCurrent(load)) return@exclusive false
                stopPositionUpdates()
                // Free whatever the engine still holds (see "Dropped sources"); a new source begins either way.
                if (engineHoldsSource) {
                    try {
                        freeEngineSource()
                    } catch (@Suppress("TooGenericExceptionCaught") cause: Throwable) {
                        // An engine that cannot drop its source is broken, but prepare never throws on
                        // failure: it settles on Error (the epoch was bumped, so its reports are stale).
                        resetSourceFlows()
                        _stateFlow.value = VideoPlayerState.Error(cause)
                        return@exclusive false
                    }
                } else {
                    sourceEpoch.update { it + 1 }
                }
                resetSourceFlows()
                engineHoldsSource = true
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
            if (currentLoad.value !== load) {
                // Superseded. Whatever replaced this load owns the engine and the state now.
                if (currentCoroutineContext().isActive) return
                throw cancellation
            }
            // The caller went away: drop whatever the load got to, report Idle rather than Error
            // (nobody failed), and let the cancellation propagate. Wait for a load this one replaced
            // to finish unwinding first — releasing under it is the interleaving ruled out above.
            withContext(NonCancellable) { previous?.finished?.join() }
            gate.exclusive {
                // isCurrent, not just identity: release() may have run completely between this call's
                // released check and its installing itself in currentLoad, so the identity holds again
                // here while the engine is already released and disposed, and nothing of it may be
                // called after dispose.
                if (isCurrent(load)) {
                    freeEngineSource()
                    resetSourceFlows()
                    _stateFlow.value = VideoPlayerState.Idle
                }
            }
            throw cancellation
        } finally {
            load.job.complete()
            // Finished is transitive: a load that was superseded and cancelled while still waiting
            // for the one it replaced must not report finished before that one has, or the load
            // queued behind it would start while the first is still unwinding in the engine.
            withContext(NonCancellable) { previous?.finished?.join() }
            load.finished.complete()
        }

        gate.exclusive {
            if (!isCurrent(load)) return@exclusive
            val failed: Throwable? = preparingFailure
            if (failed != null) {
                // The loaded source failed before it could be published: the failure wins.
                resetSourceFlows()
                _stateFlow.value = VideoPlayerState.Error(failed)
                return@exclusive
            }
            val stalled: Boolean = preparingBuffering
            val buffered: Long
            val duration: Long
            try {
                // Settings belong to the player and outlive every source; push them onto the fresh load.
                engine.setVolume(effectiveVolume)
                engine.setLooping(_repeatModeFlow.value == RepeatMode.One)
                engine.setSpeed(_playbackSpeedFlow.value)
                buffered = engine.bufferedPositionMs().coerceAtLeast(0L)
                duration = engine.durationMs()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") cause: Throwable) {
                // A setting the engine refuses is not left as Preparing forever: free the source and settle on Error.
                try {
                    freeEngineSource()
                } catch (@Suppress("TooGenericExceptionCaught") releaseFailure: Throwable) {
                    cause.addSuppressed(releaseFailure)
                }
                resetSourceFlows()
                _stateFlow.value = VideoPlayerState.Error(cause)
                return@exclusive
            }
            _bufferedPositionFlow.value = buffered
            _isBufferingFlow.value = stalled
            _stateFlow.value = VideoPlayerState.Ready(duration)
        }
    }

    override fun play(): Unit = gate.exclusive { playLocked() }

    override fun pause(): Unit = gate.exclusive {
        if (released) return@exclusive
        val current: VideoPlayerState = _stateFlow.value
        if (current !is VideoPlayerState.Playing) return@exclusive

        engine.pause()
        stopPositionUpdates()
        val duration: Long = freshDuration(current.duration)
        val position: Long = shownPosition(engine.positionMs(), duration)
        _playbackPositionFlow.value = position
        _bufferedPositionFlow.value = engine.bufferedPositionMs().coerceAtLeast(0L)
        _stateFlow.value = VideoPlayerState.Paused(
            duration = duration,
            currentPosition = position,
        )
    }

    override fun stop(): Unit = gate.exclusive {
        if (released) return@exclusive
        val current: VideoPlayerState = _stateFlow.value
        if (!current.isPlayable) return@exclusive

        engine.pause()
        stopPositionUpdates()
        engine.seekTo(0L)
        transportEpoch.update { it + 1 }
        _playbackPositionFlow.value = 0L
        _stateFlow.value = VideoPlayerState.Ready(freshDuration(current.duration ?: 0L))
    }

    override fun seekTo(positionMs: Long): Unit = gate.exclusive { seekToLocked(positionMs) }

    override fun seekForward(amountMs: Long): Unit = gate.exclusive {
        if (released) return@exclusive
        if (!_stateFlow.value.isPlayable) return@exclusive
        seekToLocked(saturatingAdd(engine.positionMs(), amountMs), relative = true)
    }

    override fun seekBackward(amountMs: Long): Unit = gate.exclusive {
        if (released) return@exclusive
        if (!_stateFlow.value.isPlayable) return@exclusive
        seekToLocked(
            saturatingAdd(engine.positionMs(), -amountMs.coerceAtLeast(-Long.MAX_VALUE)),
            relative = true,
        )
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
        stopPositionUpdates()
        // Idle however the engine's release goes: an engine that throws is rethrown after the reset, and
        // abandonLoad has already completed the placeholder, so the next prepare is not left waiting.
        try {
            abandonLoad(dispose = false)
        } finally {
            resetSourceFlows()
            _stateFlow.value = VideoPlayerState.Idle
        }
    }

    override fun release(): Unit = gate.exclusive {
        if (released) return@exclusive
        released = true

        stopPositionUpdates()
        scope.cancel()
        // Disposed and Idle however the engine's own calls go; the engine's exception is rethrown after.
        try {
            try {
                engine.setListener(null)
            } finally {
                abandonLoad(dispose = true)
            }
        } finally {
            resetSourceFlows()
            _stateFlow.value = VideoPlayerState.Idle
        }
    }

    // Engine callbacks arrive on the engine's own thread, often while it holds a lock of its own:
    // they are submitted, never waited for. Completion and failure describe a loaded source; one
    // arriving when nothing is loaded — posted by the platform just before an unload or release
    // detached it — is stale and must not resurrect state.

    override fun onCompleted() {
        val source: Long = sourceEpoch.value
        val transport: Long = transportEpoch.value
        gate.submit {
            if (source != sourceEpoch.value) return@submit
            if (transport == transportEpoch.value) completedLocked() else staleCompletionLocked()
        }
    }

    /**
     * A completion that a transport call made after it was reported superseded. The engine reached its
     * end before that call (a seek that was being applied as the end came), so the call wins and the
     * completion is dropped. A platform that stops at its end (libvlc, JavaFX, AVPlayer) does not resume
     * by itself on a seek made around it, so a player that is Playing asserts the start again: the same
     * pair [playLocked] makes. A no-op on an engine that is already playing. A player that is not
     * Playing is left alone: a paused one must not start.
     */
    private fun staleCompletionLocked() {
        if (released || _stateFlow.value !is VideoPlayerState.Playing) return
        engine.start()
        engine.setSpeed(_playbackSpeedFlow.value)
    }

    private fun completedLocked() {
        val current: VideoPlayerState = _stateFlow.value
        if (released || !current.isPlayable) return
        val known: Long = freshDuration(current.duration ?: 0L)
        if (_repeatModeFlow.value == RepeatMode.One) {
            // An engine should loop on its own and never report this while looping, but the mode can
            // change just as the platform posts the end. Completed is never reached in One: start
            // over, which is what the platform would have done, but only a source that was playing.
            // A paused one is rewound and stays paused (the looping platform would have wrapped to 0
            // just before the pause); a Ready or Completed one has nothing to restart.
            when (current) {
                is VideoPlayerState.Playing -> {
                    engine.seekTo(0L)
                    engine.start()
                    _playbackPositionFlow.value = 0L
                    startPositionUpdates()
                    _stateFlow.value = VideoPlayerState.Playing(duration = known, currentPosition = 0L)
                }
                is VideoPlayerState.Paused -> {
                    engine.seekTo(0L)
                    _playbackPositionFlow.value = 0L
                    _stateFlow.value = VideoPlayerState.Paused(duration = known, currentPosition = 0L)
                }
                else -> Unit
            }
            return
        }
        stopPositionUpdates()
        _isBufferingFlow.value = false
        // A source of unknown length that played to its end has a length now: where it ended.
        val end: Long = if (known > 0L) known else engine.positionMs().takeIf { it > 0L } ?: _playbackPositionFlow.value
        _playbackPositionFlow.value = end
        _stateFlow.value = VideoPlayerState.Completed(end)
    }

    override fun onFailed(cause: Throwable) {
        val stamp: Long = sourceEpoch.value
        gate.submit { if (stamp == sourceEpoch.value) failedLocked(cause) }
    }

    private fun failedLocked(cause: Throwable) {
        if (released) return
        if (_stateFlow.value == VideoPlayerState.Preparing) {
            // Between the engine's load and prepare's publishing Ready: kept for prepare's last step.
            if (preparingFailure == null) preparingFailure = cause
            return
        }
        if (!_stateFlow.value.isPlayable) return
        stopPositionUpdates()
        _isBufferingFlow.value = false
        _videoSizeFlow.value = null
        _bufferedPositionFlow.value = 0L
        _stateFlow.value = VideoPlayerState.Error(cause)
    }

    override fun onBufferingChanged(isBuffering: Boolean) {
        val stamp: Long = sourceEpoch.value
        gate.submit { if (stamp == sourceEpoch.value) bufferingChangedLocked(isBuffering) }
    }

    private fun bufferingChangedLocked(isBuffering: Boolean) {
        // Buffering is reported for a loaded source only, so a stale report cannot leave an idle
        // player "buffering". One that arrives while prepare still publishes the load is kept for
        // its last step (the engine reports each change once).
        if (released) return
        if (_stateFlow.value == VideoPlayerState.Preparing) {
            preparingBuffering = isBuffering
            return
        }
        val current: VideoPlayerState = _stateFlow.value
        // A stall after the end describes nothing the viewer waits for: Completed is never buffering.
        if (!current.isPlayable || current is VideoPlayerState.Completed) return
        _isBufferingFlow.value = isBuffering
    }

    override fun onVideoSizeChanged(size: VideoSize?) {
        val stamp: Long = sourceEpoch.value
        gate.submit { if (stamp == sourceEpoch.value) videoSizeChangedLocked(size) }
    }

    private fun videoSizeChangedLocked(size: VideoSize?) {
        // Unlike buffering, the size is usually learned while preparing, so it is accepted then too.
        if (released) return
        val current: VideoPlayerState = _stateFlow.value
        if (current != VideoPlayerState.Preparing && !current.isPlayable) return
        _videoSizeFlow.value = size
    }

    // --- Transitions shared by several entry points. Lock held. ---

    private fun playLocked() {
        if (released) return
        val current: VideoPlayerState = _stateFlow.value
        if (!current.isPlayable || current is VideoPlayerState.Playing) return

        val duration: Long = freshDuration(current.duration ?: 0L)
        // A seek onto the end of a completed source leaves Paused with the playhead at the end, which
        // is the end just the same.
        val atEnd: Boolean = duration > 0L && engine.positionMs() >= duration
        if (current is VideoPlayerState.Completed || atEnd) {
            // From the end there is nothing left to resume: rewind explicitly so every platform
            // means the same thing by "play a completed source" (AVPlayer sits at the end without
            // completing again).
            engine.seekTo(0L)
            transportEpoch.update { it + 1 }
            _playbackPositionFlow.value = 0L
        }
        engine.start()
        engine.setSpeed(_playbackSpeedFlow.value)
        val position: Long = shownPosition(engine.positionMs(), duration)
        // Polling first: a collector that pauses from inside the state write stops the poll it finds.
        startPositionUpdates()
        _stateFlow.value = VideoPlayerState.Playing(duration = duration, currentPosition = position)
    }

    private fun seekToLocked(positionMs: Long, relative: Boolean = false) {
        if (released) return
        val current: VideoPlayerState = _stateFlow.value
        if (!current.isPlayable) return

        val duration: Long = freshDuration(current.duration ?: 0L)
        // A length of 0 means the platform has not reported one yet (a live stream, a header not parsed).
        // An absolute seek still clamps to 0..0 then, but a relative skip has no end to clamp to: moving
        // from 30 s by +/-5 s must not land on 0, so its target goes to the engine, which knows better.
        // It is still capped at MAX_ENGINE_POSITION_MS, because platforms convert to microseconds
        // (ExoPlayer, libvlc) and add offsets of their own, and a larger value wraps.
        val target: Long = if (relative && duration <= 0L) {
            positionMs.coerceIn(0L, MAX_ENGINE_POSITION_MS)
        } else {
            positionMs.coerceIn(0L, maxOf(0L, duration))
        }
        engine.seekTo(target)
        // A seek onto the end does not make an earlier completion stale; a seek anywhere else does.
        if (duration <= 0L || target < duration) transportEpoch.update { it + 1 }
        _playbackPositionFlow.value = target

        _stateFlow.value = when (current) {
            is VideoPlayerState.Playing -> current.copy(duration = duration, currentPosition = target)
            is VideoPlayerState.Paused -> current.copy(duration = duration, currentPosition = target)
            // A Ready player picks up a length the platform learned since the load.
            is VideoPlayerState.Ready -> VideoPlayerState.Ready(duration)
            // Once the playhead has moved off the end, Paused is the true description — and the
            // state play() resumes from correctly.
            is VideoPlayerState.Completed -> VideoPlayerState.Paused(duration, target)
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
        preparingFailure = null
        preparingBuffering = false
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
     * @param dispose also [VideoPlaybackEngine.dispose] the engine — only on the final [release].
     */
    private fun abandonLoad(dispose: Boolean) {
        val placeholder = Load(Job().apply { cancel() })
        val abandoned: Load? = currentLoad.getAndUpdate { placeholder }
        abandoned?.job?.cancel()
        // Whatever the engine throws, it is disposed (when asked) and the placeholder completes, or the
        // next prepare would wait for it forever.
        val freeEngine: () -> Unit = {
            try {
                freeEngineSource()
            } finally {
                try {
                    if (dispose) engine.dispose()
                } finally {
                    placeholder.finished.complete()
                }
            }
        }
        val unwinding: Job? = abandoned?.finished?.takeUnless { it.isCompleted }
        if (unwinding == null) {
            freeEngine()
        } else {
            // Completes on whichever thread finishes the abandoned prepare; back under the lock
            // from there, like every other engine call.
            unwinding.invokeOnCompletion {
                gate.submit {
                    try {
                        freeEngine()
                    } catch (@Suppress("TooGenericExceptionCaught") ignored: Throwable) {
                        // A submitted action must not throw (StateMachineLock), and nobody is waiting to
                        // receive it here; the cleanup in freeEngine has completed either way.
                    }
                }
            }
        }
    }

    /**
     * Lock held. The only way the player frees the engine's source; every report made before this is stale.
     */
    private fun freeEngineSource() {
        try {
            engine.release()
        } finally {
            engineHoldsSource = false
            sourceEpoch.update { it + 1 }
        }
    }

    private fun isCurrent(load: Load): Boolean = !released && currentLoad.value === load

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
        val duration: Long = freshDuration(current.duration)
        val position: Long = shownPosition(engine.positionMs(), duration)
        val buffered: Long = engine.bufferedPositionMs().coerceAtLeast(0L)
        val refreshed: VideoPlayerState.Playing = current.copy(duration = duration, currentPosition = position)
        // The flows first and the state last, like every transition: a collector on an immediate
        // dispatcher that pauses or rewinds from inside the state write must not be overwritten by
        // this tick's older reading afterwards.
        _playbackPositionFlow.value = position
        _bufferedPositionFlow.value = buffered
        // Compare-and-set as well as the lock: under the lock it can only fail because a collector of one
        // of the two flows above re-entered and changed the state, and then that transition's writes stand.
        _stateFlow.compareAndSet(current, refreshed)
    }

    /**
     * Lock held. The length to publish: a positive platform value wins, because a length reported after
     * playback started replaces the `0` of "not known yet"; `0` never overwrites a length already known,
     * so a live stream that flaps back to `0` does not reset a progress bar. [reported] is a reading the
     * caller already took.
     */
    private fun freshDuration(known: Long, reported: Long = engine.durationMs()): Long =
        if (reported > 0L) reported else known

    /**
     * Lock held. The playhead to publish: a platform can read a few milliseconds past the end of the
     * source (ExoPlayer's last sample, AVPlayer's `currentTime`), so a known [duration] caps it; an
     * unknown length (`0`) leaves the reading as it is. Completion is detected from the raw engine
     * reading, never from this value.
     */
    private fun shownPosition(raw: Long, duration: Long): Long =
        if (duration > 0L) raw.coerceIn(0L, duration) else raw

    /** Lock held. */
    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private companion object {
        const val NORMAL_PLAYBACK_SPEED: Float = 1.0f
        const val FULL_VOLUME: Float = 1.0f
        const val MUTED_VOLUME: Float = 0.0f

        /**
         * The largest position handed to [VideoPlaybackEngine.seekTo], about 292 years: in nanoseconds it
         * still fits a [Long], which leaves platforms that convert to microseconds room for the offsets they
         * add (ExoPlayer adds the window's offset into its period and a renderer offset, unchecked), and it
         * converts to a [Double] exactly.
         */
        const val MAX_ENGINE_POSITION_MS: Long = Long.MAX_VALUE / 1_000_000L

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
