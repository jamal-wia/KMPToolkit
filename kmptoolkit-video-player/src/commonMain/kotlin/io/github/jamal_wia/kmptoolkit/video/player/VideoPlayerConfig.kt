package io.github.jamal_wia.kmptoolkit.video.player

/**
 * Tunables for a [VideoPlayer]. A plain class rather than a data class so a later release can add a
 * setting without breaking binary compatibility.
 *
 * @property positionUpdateIntervalMs how often [VideoPlayer.playbackPositionFlow] and
 *   [VideoPlayer.bufferedPositionFlow] refresh while playing. Must be positive.
 * @property minPlaybackSpeed lower bound [VideoPlayer.setPlaybackSpeed] clamps to. Must be positive.
 * @property maxPlaybackSpeed upper bound [VideoPlayer.setPlaybackSpeed] clamps to. At least
 *   [minPlaybackSpeed].
 * @throws IllegalArgumentException when a rule above is broken.
 */
public class VideoPlayerConfig(
    public val positionUpdateIntervalMs: Long = DEFAULT_POSITION_UPDATE_INTERVAL_MS,
    public val minPlaybackSpeed: Float = DEFAULT_MIN_PLAYBACK_SPEED,
    public val maxPlaybackSpeed: Float = DEFAULT_MAX_PLAYBACK_SPEED,
) {
    init {
        require(positionUpdateIntervalMs > 0L) {
            "positionUpdateIntervalMs must be positive, was $positionUpdateIntervalMs"
        }
        require(minPlaybackSpeed > 0f) { "minPlaybackSpeed must be positive, was $minPlaybackSpeed" }
        require(maxPlaybackSpeed >= minPlaybackSpeed) {
            "maxPlaybackSpeed ($maxPlaybackSpeed) must be at least minPlaybackSpeed ($minPlaybackSpeed)"
        }
    }

    public companion object {

        /** Four refreshes per second — a seek bar and a "watched 95%" check need no more. */
        public const val DEFAULT_POSITION_UPDATE_INTERVAL_MS: Long = 250L

        /** Slowest rate both ExoPlayer and AVPlayer reproduce without artefacts. */
        public const val DEFAULT_MIN_PLAYBACK_SPEED: Float = 0.25f

        /** Fastest rate both platforms reproduce intelligibly. */
        public const val DEFAULT_MAX_PLAYBACK_SPEED: Float = 3.0f
    }
}
