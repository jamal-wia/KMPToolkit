package io.github.jamal_wia.kmptoolkit.video.player

/**
 * The player's current state, as a closed set of typed cases.
 *
 * The cases and their meaning match `kmptoolkit-audio-player`'s `PlayerState` on purpose, so code
 * that handles one player reads the same against the other. Two things a video screen needs on top
 * are separate flows on [VideoPlayer] rather than extra cases here, because they vary independently
 * of the transport state: whether the player is waiting for data ([VideoPlayer.isBufferingFlow]) and
 * the size of the picture ([VideoPlayer.videoSizeFlow]).
 *
 * Every case carries data rather than a message: [Error] holds the [Throwable] that caused it, not a
 * sentence to show a user. Positions and durations are milliseconds; a duration of `0` means the
 * platform has not reported one (a live stream, or a header not parsed yet), not an error.
 */
public sealed interface VideoPlayerState {

    /** Nothing is loaded. The state a player starts in, and the state it returns to on release. */
    public data object Idle : VideoPlayerState

    /** A source is being loaded. No position or duration is known yet. */
    public data object Preparing : VideoPlayerState

    /**
     * A source is loaded and playable but has never started, or was stopped back to the beginning.
     *
     * @property duration total length in milliseconds.
     */
    public data class Ready(val duration: Long) : VideoPlayerState

    /**
     * Playback is running (or waiting for data while wanting to run — see
     * [VideoPlayer.isBufferingFlow]). [currentPosition] is refreshed at
     * [VideoPlayerConfig.positionUpdateIntervalMs].
     *
     * @property duration total length in milliseconds.
     * @property currentPosition playhead position in milliseconds.
     */
    public data class Playing(
        val duration: Long,
        val currentPosition: Long,
    ) : VideoPlayerState

    /**
     * Playback is suspended at [currentPosition] and can be resumed with [VideoPlayer.play].
     *
     * @property duration total length in milliseconds.
     * @property currentPosition playhead position in milliseconds.
     */
    public data class Paused(
        val duration: Long,
        val currentPosition: Long,
    ) : VideoPlayerState

    /**
     * Playback reached the end of the source and [RepeatMode.Off] is in effect. The source stays
     * loaded, so [VideoPlayer.replay] works without preparing again. Never reached while
     * [RepeatMode.One] is in effect — the source starts over instead.
     *
     * @property duration total length in milliseconds.
     */
    public data class Completed(val duration: Long) : VideoPlayerState

    /**
     * Loading or playback failed. The source is no longer playable; prepare another one to recover.
     *
     * @property cause the platform or library error, e.g. an I/O error from an unreachable URL, or
     *   [VideoPlayerReleasedException] when the call arrived after the player was released.
     */
    public data class Error(val cause: Throwable) : VideoPlayerState
}

/**
 * Whether a source is loaded and the transport calls ([VideoPlayer.play], [VideoPlayer.seekTo], ...)
 * will act rather than being ignored: true for [VideoPlayerState.Ready], [VideoPlayerState.Playing],
 * [VideoPlayerState.Paused] and [VideoPlayerState.Completed].
 */
public val VideoPlayerState.isPlayable: Boolean
    get() = this is VideoPlayerState.Ready ||
        this is VideoPlayerState.Playing ||
        this is VideoPlayerState.Paused ||
        this is VideoPlayerState.Completed

/** Whether playback is running: true only for [VideoPlayerState.Playing]. */
public val VideoPlayerState.isPlaying: Boolean
    get() = this is VideoPlayerState.Playing

/** Total length in milliseconds, or `null` where no source is loaded. */
public val VideoPlayerState.duration: Long?
    get() = when (this) {
        is VideoPlayerState.Ready -> duration
        is VideoPlayerState.Playing -> duration
        is VideoPlayerState.Paused -> duration
        is VideoPlayerState.Completed -> duration
        VideoPlayerState.Idle, VideoPlayerState.Preparing, is VideoPlayerState.Error -> null
    }

/**
 * Playhead in milliseconds for [VideoPlayerState.Playing] and [VideoPlayerState.Paused], the duration
 * for [VideoPlayerState.Completed], and `null` otherwise — including [VideoPlayerState.Ready], where
 * "loaded, never started" is deliberately not reported as position `0`.
 */
public val VideoPlayerState.playbackPosition: Long?
    get() = when (this) {
        is VideoPlayerState.Playing -> currentPosition
        is VideoPlayerState.Paused -> currentPosition
        is VideoPlayerState.Completed -> duration
        VideoPlayerState.Idle, VideoPlayerState.Preparing, is VideoPlayerState.Ready, is VideoPlayerState.Error -> null
    }

/**
 * [playbackPosition] / [duration], clamped to `0f..1f`; `0f` whenever either is missing or the
 * duration is not positive, so it is never `NaN`.
 */
public val VideoPlayerState.progress: Float
    get() {
        val total: Long = duration ?: return 0f
        val position: Long = playbackPosition ?: return 0f
        if (total <= 0L) return 0f
        return (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
