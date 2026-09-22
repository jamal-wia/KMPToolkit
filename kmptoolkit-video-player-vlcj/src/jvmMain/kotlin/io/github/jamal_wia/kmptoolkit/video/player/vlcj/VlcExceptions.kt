package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState

/**
 * Carried by [VideoPlayerState.Error] when VLC cannot be used on this machine: libvlc was not
 * found, was built for another CPU architecture than the JVM, or failed to initialise (a broken
 * plugin directory, an unsupported [createVlcjVideoPlayer] `vlcArgs` entry).
 *
 * A type rather than a message, so the app can react with a check — typically by pointing the user
 * at a VLC download. [isVlcAvailable] answers the same question without preparing anything.
 */
public class VlcUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Carried by [VideoPlayerState.Error] when VLC itself reported that it could not open or play a
 * source — an unreachable URL, an HTTP error, a file VLC cannot demux.
 *
 * VLC's error event carries no detail beyond "something failed", so this exception cannot tell those
 * cases apart; VLC's own log (enable it with `vlcArgs = listOf("-vvv")`) can. Failures the library
 * detects before VLC is involved use the standard types instead: a missing file or asset is a
 * `java.io.FileNotFoundException`, an HTTP header VLC cannot send is an `IllegalArgumentException`.
 */
public class VlcPlaybackException(message: String) : IllegalStateException(message)
