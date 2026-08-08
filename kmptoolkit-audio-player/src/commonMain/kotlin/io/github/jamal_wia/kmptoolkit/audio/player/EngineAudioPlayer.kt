package io.github.jamal_wia.kmptoolkit.audio.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
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
 */
private class EngineAudioPlayer(
    private val engine: PlaybackEngine,
    private val config: AudioPlayerConfig,
    coroutineContext: CoroutineContext,
) : AudioPlayer, PlaybackEngineListener {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + coroutineContext)
    private var positionJob: Job? = null

    // Volatile, and every mutating path re-checks it: release() may land on a different thread than
    // the transport calls, and the point of the flag is that no call after it reaches the engine.
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
            _stateFlow.value = PlayerState.Error(AudioPlayerReleasedException())
            return
        }
        stopPositionUpdates()
        _playbackPositionFlow.value = 0L
        _stateFlow.value = PlayerState.Preparing

        try {
            engine.load(source)
        } catch (cancellation: CancellationException) {
            // A cancelled prepare must not leave a half-loaded engine reachable: drop whatever it
            // got to, report Idle rather than Error (nobody failed — the caller went away), and let
            // the cancellation propagate so structured concurrency still works.
            engine.release()
            if (!released) _stateFlow.value = PlayerState.Idle
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // Deliberately broad: an engine reports every load failure by throwing, and the whole
            // point of PlayerState.Error is to hand that Throwable to the consumer unchanged
            // instead of guessing which platform types are worth catching.
            if (!released) _stateFlow.value = PlayerState.Error(failure)
            return
        }

        if (released) return
        engine.setSpeed(playbackSpeed)
        _stateFlow.value = PlayerState.Ready(engine.durationMs())
    }

    override fun play() {
        if (released) return
        val current: PlayerState = _stateFlow.value
        if (!current.isPlayable || current is PlayerState.Playing) return

        engine.start()
        engine.setSpeed(playbackSpeed)
        _stateFlow.value = PlayerState.Playing(
            duration = engine.durationMs(),
            currentPosition = engine.positionMs(),
        )
        startPositionUpdates()
    }

    override fun pause() {
        if (released) return
        val current: PlayerState = _stateFlow.value
        if (current !is PlayerState.Playing) return

        engine.pause()
        stopPositionUpdates()
        val position: Long = engine.positionMs()
        _playbackPositionFlow.value = position
        _stateFlow.value = PlayerState.Paused(duration = current.duration, currentPosition = position)
    }

    override fun stop() {
        if (released) return
        if (!_stateFlow.value.isPlayable) return

        engine.pause()
        stopPositionUpdates()
        engine.seekTo(0L)
        _playbackPositionFlow.value = 0L
        _stateFlow.value = PlayerState.Ready(engine.durationMs())
    }

    override fun seekTo(positionMs: Long) {
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

    override fun seekForward(amountMs: Long) {
        if (released) return
        if (!_stateFlow.value.isPlayable) return
        seekTo(engine.positionMs() + amountMs)
    }

    override fun seekBackward(amountMs: Long) {
        if (released) return
        if (!_stateFlow.value.isPlayable) return
        seekTo(engine.positionMs() - amountMs)
    }

    override fun replay() {
        if (released) return
        if (!_stateFlow.value.isPlayable) return
        seekTo(0L)
        play()
    }

    override fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(config.minPlaybackSpeed, config.maxPlaybackSpeed)
        if (released) return
        if (_stateFlow.value is PlayerState.Playing) engine.setSpeed(playbackSpeed)
    }

    override fun release() {
        if (released) return
        released = true

        stopPositionUpdates()
        scope.cancel()
        engine.setListener(null)
        engine.release()

        _stateFlow.value = PlayerState.Idle
        _playbackPositionFlow.value = 0L
    }

    override fun onCompleted() {
        if (released) return
        stopPositionUpdates()
        val duration: Long = engine.durationMs()
        _playbackPositionFlow.value = duration
        _stateFlow.value = PlayerState.Completed(duration)
    }

    override fun onFailed(cause: Throwable) {
        if (released) return
        stopPositionUpdates()
        _stateFlow.value = PlayerState.Error(cause)
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionJob = scope.launch {
            while (isActive) {
                delay(config.positionUpdateIntervalMs)
                val current: PlayerState = _stateFlow.value
                // Anything other than Playing means someone else already owns the state — a pause,
                // a completion, a failure. Stop rather than spin; play() restarts the loop.
                if (current !is PlayerState.Playing) break
                val position: Long = engine.positionMs()
                _playbackPositionFlow.value = position
                _stateFlow.value = current.copy(currentPosition = position)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private companion object {
        const val NORMAL_PLAYBACK_SPEED: Float = 1.0f
    }
}
