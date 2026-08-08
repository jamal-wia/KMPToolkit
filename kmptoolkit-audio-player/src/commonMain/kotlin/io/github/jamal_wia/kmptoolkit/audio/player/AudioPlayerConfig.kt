package io.github.jamal_wia.kmptoolkit.audio.player

/**
 * Tunables shared by every [AudioPlayer] instance built from it.
 *
 * The defaults match what a media-player UI normally wants; override them where your screen differs
 * — a waveform scrubber wants a shorter [positionUpdateIntervalMs] than a single progress bar, and a
 * podcast player wants a wider speed range than a sound-effect player.
 *
 * Validated in `init`, so a nonsensical configuration fails where it is written rather than as
 * silently wrong playback later.
 *
 * @property positionUpdateIntervalMs how often [AudioPlayer.playbackPositionFlow] and the
 *   [PlayerState.Playing.currentPosition] of the current state are refreshed while playing. Must be
 *   positive. Lower values cost one platform position read per tick.
 * @property minPlaybackSpeed lower bound [AudioPlayer.setPlaybackSpeed] clamps to. Must be positive
 *   — `0f` is not "paused", it is a rate the platforms reject.
 * @property maxPlaybackSpeed upper bound [AudioPlayer.setPlaybackSpeed] clamps to. Must be at least
 *   [minPlaybackSpeed].
 */
public data class AudioPlayerConfig(
    val positionUpdateIntervalMs: Long = DEFAULT_POSITION_UPDATE_INTERVAL_MS,
    val minPlaybackSpeed: Float = DEFAULT_MIN_PLAYBACK_SPEED,
    val maxPlaybackSpeed: Float = DEFAULT_MAX_PLAYBACK_SPEED,
) {
    init {
        require(positionUpdateIntervalMs > 0L) {
            "positionUpdateIntervalMs must be positive, was $positionUpdateIntervalMs"
        }
        require(minPlaybackSpeed > 0f) {
            "minPlaybackSpeed must be positive, was $minPlaybackSpeed"
        }
        require(maxPlaybackSpeed >= minPlaybackSpeed) {
            "maxPlaybackSpeed ($maxPlaybackSpeed) must be at least " +
                "minPlaybackSpeed ($minPlaybackSpeed)"
        }
    }

    public companion object {

        /** Ten refreshes per second — smooth enough for a progress bar, cheap enough to poll. */
        public const val DEFAULT_POSITION_UPDATE_INTERVAL_MS: Long = 100L

        /** Slowest rate both `MediaPlayer` and `AVPlayer` reproduce without artefacts. */
        public const val DEFAULT_MIN_PLAYBACK_SPEED: Float = 0.25f

        /** Fastest rate both platforms reproduce intelligibly. */
        public const val DEFAULT_MAX_PLAYBACK_SPEED: Float = 3.0f
    }
}
