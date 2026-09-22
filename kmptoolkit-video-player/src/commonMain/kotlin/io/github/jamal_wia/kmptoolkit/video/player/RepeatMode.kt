package io.github.jamal_wia.kmptoolkit.video.player

/** What happens when playback reaches the end of the source. */
public enum class RepeatMode {

    /** Stop at the end: the state becomes [VideoPlayerState.Completed]. The default. */
    Off,

    /** Start the same source over from the beginning, without passing through Completed. */
    One,
}
