package io.github.jamal_wia.kmptoolkit.audio.player

/**
 * The player's current state, as a closed set of typed cases.
 *
 * Every case carries data rather than a message: [Error] holds the [Throwable] that caused it, not
 * a sentence to show a user. Deciding what a failure looks like on screen — and in which language —
 * belongs to the consuming app, which already owns its copy and its localization pipeline.
 *
 * Positions and durations are milliseconds throughout. A duration of `0` means the platform has not
 * reported one yet (live streams, or a source whose header has not been parsed); it is not an error
 * on its own.
 */
public sealed interface PlayerState {

    /** Nothing is loaded. The state a player starts in, and the state it returns to on release. */
    public data object Idle : PlayerState

    /** A source is being loaded or buffered. No position or duration is known yet. */
    public data object Preparing : PlayerState

    /**
     * A source is loaded and playable but has never started, or was stopped back to the beginning.
     *
     * @property duration total length in milliseconds.
     */
    public data class Ready(val duration: Long) : PlayerState

    /**
     * Playback is running. [currentPosition] is refreshed at
     * [AudioPlayerConfig.positionUpdateIntervalMs].
     *
     * @property duration total length in milliseconds.
     * @property currentPosition playhead position in milliseconds.
     */
    public data class Playing(
        val duration: Long,
        val currentPosition: Long,
    ) : PlayerState

    /**
     * Playback is suspended at [currentPosition] and can be resumed with [AudioPlayer.play].
     *
     * @property duration total length in milliseconds.
     * @property currentPosition playhead position in milliseconds.
     */
    public data class Paused(
        val duration: Long,
        val currentPosition: Long,
    ) : PlayerState

    /**
     * Playback reached the end of the source. The source stays loaded, so [AudioPlayer.replay] or a
     * seek followed by [AudioPlayer.play] works without preparing again.
     *
     * @property duration total length in milliseconds.
     */
    public data class Completed(val duration: Long) : PlayerState

    /**
     * Loading or playback failed. The source is no longer playable; prepare another one to recover.
     *
     * @property cause the platform or library error, e.g. an `IOException` from an unreachable URL,
     *   or [AudioPlayerReleasedException] when the call arrived after the player was released.
     */
    public data class Error(val cause: Throwable) : PlayerState
}

/**
 * Whether a source is loaded and the transport controls ([AudioPlayer.play], [AudioPlayer.seekTo],
 * ...) will act rather than being ignored.
 *
 * True for [PlayerState.Ready], [PlayerState.Playing], [PlayerState.Paused] and
 * [PlayerState.Completed]; false for [PlayerState.Idle], [PlayerState.Preparing] and
 * [PlayerState.Error].
 */
public val PlayerState.isPlayable: Boolean
    get() = this is PlayerState.Ready ||
        this is PlayerState.Playing ||
        this is PlayerState.Paused ||
        this is PlayerState.Completed

/** Whether audio is advancing right now — true only for [PlayerState.Playing]. */
public val PlayerState.isPlaying: Boolean
    get() = this is PlayerState.Playing

/** Total length in milliseconds, or `null` in a state that has no loaded source. */
public val PlayerState.duration: Long?
    get() = when (this) {
        is PlayerState.Ready -> duration
        is PlayerState.Playing -> duration
        is PlayerState.Paused -> duration
        is PlayerState.Completed -> duration
        else -> null
    }

/**
 * Playhead position in milliseconds, or `null` in a state that has no meaningful position.
 *
 * [PlayerState.Ready] returns `null` rather than `0`: "loaded, never started" and "loaded, playhead
 * at zero" are the same position but not the same state, and a progress bar usually wants to render
 * them differently. [PlayerState.Completed] reports its duration, because that is where the
 * playhead actually is.
 */
public val PlayerState.playbackPosition: Long?
    get() = when (this) {
        is PlayerState.Playing -> currentPosition
        is PlayerState.Paused -> currentPosition
        is PlayerState.Completed -> duration
        else -> null
    }

/**
 * Playback progress in `0f..1f`, ready to drive a progress indicator.
 *
 * `0f` whenever there is no duration, no position, or a non-positive duration — so a caller never
 * has to guard against a division by zero or a `NaN` reaching its UI. Positions outside the
 * duration are clamped rather than reported, because platforms do occasionally report a position a
 * few milliseconds past the end.
 */
public val PlayerState.progress: Float
    get() {
        val duration: Long = this.duration ?: return 0f
        if (duration <= 0L) return 0f
        val position: Long = this.playbackPosition ?: return 0f
        return (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }
