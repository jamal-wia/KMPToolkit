package io.github.jamal_wia.kmptoolkit.video.player.compose

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.duration
import io.github.jamal_wia.kmptoolkit.video.player.isPlayable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A scriptable [VideoPlayer] for this module's UI tests: records every call, and follows the
 * transport contract closely enough for the controls to react (play → Playing, pause → Paused…).
 * Not the library's state machine — the compose layer only needs to see calls arrive and flows move.
 */
class FakeVideoPlayer(private val preparedDurationMs: Long = 100_000L) : VideoPlayer {

    val state: MutableStateFlow<VideoPlayerState> = MutableStateFlow(VideoPlayerState.Idle)
    val position: MutableStateFlow<Long> = MutableStateFlow(0L)
    val buffered: MutableStateFlow<Long> = MutableStateFlow(0L)
    val buffering: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val size: MutableStateFlow<VideoSize?> = MutableStateFlow(null)
    val speed: MutableStateFlow<Float> = MutableStateFlow(1f)
    val volume: MutableStateFlow<Float> = MutableStateFlow(1f)
    val muted: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val repeat: MutableStateFlow<RepeatMode> = MutableStateFlow(RepeatMode.Off)

    override val stateFlow: MutableStateFlow<VideoPlayerState> get() = state
    override val playbackPositionFlow: MutableStateFlow<Long> get() = position
    override val bufferedPositionFlow: MutableStateFlow<Long> get() = buffered
    override val isBufferingFlow: MutableStateFlow<Boolean> get() = buffering
    override val videoSizeFlow: MutableStateFlow<VideoSize?> get() = size
    override val playbackSpeedFlow: MutableStateFlow<Float> get() = speed
    override val volumeFlow: MutableStateFlow<Float> get() = volume
    override val isMutedFlow: MutableStateFlow<Boolean> get() = muted
    override val repeatModeFlow: MutableStateFlow<RepeatMode> get() = repeat

    /** Every call, in order, as `name` or `name(argument)`. */
    val calls: MutableList<String> = mutableListOf()

    /** Sources passed to [prepare], in order, including ones later cancelled. */
    val prepared: MutableList<VideoSource> = mutableListOf()

    /** How many [prepare] calls were cancelled while suspended on [prepareGate]. */
    var cancelledPrepares: Int = 0

    /** When set, [prepare] suspends until it completes. */
    var prepareGate: CompletableDeferred<Unit>? = null

    var releaseCount: Int = 0

    /** Whether [seekTo] moves [position] at once; `false` models a platform still seeking. */
    var seekMovesPosition: Boolean = true

    override suspend fun prepare(source: VideoSource) {
        calls += "prepare($source)"
        prepared += source
        state.value = VideoPlayerState.Preparing
        position.value = 0L
        try {
            prepareGate?.await()
        } catch (e: CancellationException) {
            cancelledPrepares++
            throw e
        }
        state.value = VideoPlayerState.Ready(preparedDurationMs)
    }

    /** Puts the player straight into [VideoPlayerState.Playing] without a call being recorded. */
    fun startPlaying(durationMs: Long = preparedDurationMs, positionMs: Long = 0L) {
        position.value = positionMs
        state.value = VideoPlayerState.Playing(durationMs, positionMs)
    }

    /** Puts the player straight into [VideoPlayerState.Paused] without a call being recorded. */
    fun startPaused(durationMs: Long = preparedDurationMs, positionMs: Long = 0L) {
        position.value = positionMs
        state.value = VideoPlayerState.Paused(durationMs, positionMs)
    }

    override fun play() {
        calls += "play"
        val current: VideoPlayerState = state.value
        if (current.isPlayable) state.value = VideoPlayerState.Playing(current.duration ?: 0L, position.value)
    }

    override fun pause() {
        calls += "pause"
        val current: VideoPlayerState = state.value
        if (current is VideoPlayerState.Playing) {
            state.value = VideoPlayerState.Paused(current.duration, position.value)
        }
    }

    override fun stop() {
        calls += "stop"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo($positionMs)"
        if (seekMovesPosition) position.value = positionMs
    }

    override fun seekForward(amountMs: Long) {
        calls += "seekForward($amountMs)"
    }

    override fun seekBackward(amountMs: Long) {
        calls += "seekBackward($amountMs)"
    }

    override fun replay() {
        calls += "replay"
    }

    override fun setPlaybackSpeed(speed: Float) {
        calls += "setPlaybackSpeed($speed)"
        this.speed.value = speed
    }

    override fun setVolume(volume: Float) {
        calls += "setVolume($volume)"
        this.volume.value = volume
    }

    override fun setMuted(muted: Boolean) {
        calls += "setMuted($muted)"
        this.muted.value = muted
    }

    override fun setRepeatMode(mode: RepeatMode) {
        calls += "setRepeatMode($mode)"
        repeat.value = mode
    }

    override fun unload() {
        calls += "unload"
        state.value = VideoPlayerState.Idle
    }

    override fun release() {
        calls += "release"
        releaseCount++
        state.value = VideoPlayerState.Idle
    }
}
