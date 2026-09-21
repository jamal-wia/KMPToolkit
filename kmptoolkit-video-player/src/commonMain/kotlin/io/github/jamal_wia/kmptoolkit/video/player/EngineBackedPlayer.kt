package io.github.jamal_wia.kmptoolkit.video.player

/**
 * A [VideoPlayer] that drives a [VideoPlaybackEngine] — what lets a platform accessor such as
 * `avPlayerOrNull()` reach the platform player behind a [VideoPlayer] without knowing the concrete
 * player class. Implemented by the shared state machine; not part of the public API.
 */
internal interface EngineBackedPlayer {

    /** The engine this player owns and drives. */
    val engine: VideoPlaybackEngine
}
