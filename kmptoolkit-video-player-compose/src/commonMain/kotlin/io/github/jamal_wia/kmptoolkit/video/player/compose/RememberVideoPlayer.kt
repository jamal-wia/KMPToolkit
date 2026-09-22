package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlinx.coroutines.isActive

/**
 * A [VideoPlayer] owned by the composition: created on first composition, loaded with [source], and
 * released when this call leaves the composition.
 *
 * - **Creation** goes through [LocalVideoPlayerFactory], or the platform factory where none is
 *   provided. On desktop, where there is no platform factory, a missing provider is an
 *   [IllegalStateException] — see [LocalVideoPlayerFactory].
 * - **Loading.** Each new [source] (compared with `equals`) is prepared, cancelling a prepare of an
 *   older source still in flight. A `null` [source] unloads the player. A failed load is not thrown:
 *   it is the player's [io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState.Error].
 * - **[autoPlay]** plays once each prepare succeeds. Changing it later does not start or stop the
 *   current source; it applies to the next prepare.
 * - **Release** happens when the call leaves the composition. Never release the returned player
 *   yourself, and do not keep it beyond the composition.
 *
 * [config] and the factory are read on first composition only; a new player with different ones
 * needs a new composition — wrap the call in `key(config) { … }`.
 *
 * A player that must survive a configuration change or outlive the screen belongs in a view model
 * instead: create it there with `createVideoPlayer(…)`, release it in `onCleared`, and render it
 * with the `VideoPlayer(player = …)` overload.
 */
@Composable
public fun rememberVideoPlayer(
    source: VideoSource?,
    autoPlay: Boolean = false,
    config: VideoPlayerConfig = VideoPlayerConfig(),
): VideoPlayer {
    val factory: VideoPlayerFactory = LocalVideoPlayerFactory.current
        ?: platformVideoPlayerFactory()
        ?: throw IllegalStateException(MISSING_FACTORY_MESSAGE)
    val player: VideoPlayer = remember { factory.create(config) }
    val latestAutoPlay: State<Boolean> = rememberUpdatedState(autoPlay)

    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(player, source) {
        if (source == null) {
            player.unload()
        } else {
            player.prepare(source)
            // A prepare superseded by a newer source must not start the newer one early.
            if (latestAutoPlay.value && isActive) player.play()
        }
    }
    return player
}
