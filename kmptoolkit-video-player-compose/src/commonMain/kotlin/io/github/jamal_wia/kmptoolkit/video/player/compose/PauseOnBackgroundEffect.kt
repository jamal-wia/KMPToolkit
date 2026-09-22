package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState

/**
 * Pauses [player] when the host goes to the background — the `ON_STOP` event of
 * [LocalLifecycleOwner]: the activity stopped on Android, the app backgrounded on iOS, the window
 * minimized on desktop — if it is playing at that moment. [VideoPlayer] runs this for you; call it
 * yourself only in a layout of your own built on [VideoPlayerSurface].
 *
 * It does **not** resume on return: a video that restarts on its own when the user comes back is
 * rarely wanted, and the paused state already shows the play button. To resume, observe the
 * lifecycle yourself and call [VideoPlayer.play].
 *
 * @param enabled `false` does nothing — for an app that keeps playing in the background, which also
 *   needs platform setup this library does not do (see the platform notes).
 */
@Composable
public fun PauseOnBackgroundEffect(player: VideoPlayer, enabled: Boolean = true) {
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && player.stateFlow.value is VideoPlayerState.Playing) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
