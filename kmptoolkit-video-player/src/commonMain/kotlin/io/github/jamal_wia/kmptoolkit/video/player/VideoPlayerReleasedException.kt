package io.github.jamal_wia.kmptoolkit.video.player

/**
 * Carried by [VideoPlayerState.Error] when [VideoPlayer.prepare] is called after
 * [VideoPlayer.release]. Never thrown — it exists so "the player is dead" is a type check rather
 * than a string match.
 */
public class VideoPlayerReleasedException : IllegalStateException("The video player was released")
