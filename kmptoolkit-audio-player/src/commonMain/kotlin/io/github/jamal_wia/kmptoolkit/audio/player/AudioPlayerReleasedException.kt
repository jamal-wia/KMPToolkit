package io.github.jamal_wia.kmptoolkit.audio.player

/**
 * Reported through [PlayerState.Error] when [AudioPlayer.prepare] is called on a player that has
 * already been released.
 *
 * It is never thrown — a released player stays inert rather than crashing the caller (see
 * [AudioPlayer]'s lifecycle contract). It exists so that "nothing loaded because the player is
 * dead" is distinguishable from "nothing loaded because the file was missing" with a type check
 * instead of string matching.
 */
public class AudioPlayerReleasedException : IllegalStateException(
    "The AudioPlayer has been released and cannot load another source."
)
