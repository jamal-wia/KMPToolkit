package io.github.jamal_wia.kmptoolkit.video.player.compose

import android.content.Context
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.media3PlayerOrNull

/**
 * A `SurfaceView` attached to the Media3 player behind [player]. [VideoPlayerSurface] has already
 * sized it to the picture's frame, so the view just fills what it is given.
 *
 * `SurfaceView` rather than `TextureView`: the platform composes it directly, which costs less
 * power and keeps protected and HDR content working. A player with no Media3 player behind it
 * leaves the view blank.
 */
@OptIn(ToolkitInternalApi::class)
@Composable
internal actual fun PlatformVideoSurface(
    player: VideoPlayer,
    modifier: Modifier,
    scaleMode: VideoScaleMode,
    keepScreenOn: Boolean,
) {
    // Main thread only, which composition is on Android.
    val media3: Player? = remember(player) { player.media3PlayerOrNull() }
    AndroidView(
        factory = { context -> PlayerSurfaceView(context) },
        modifier = modifier,
        update = { view ->
            view.attach(media3)
            view.keepScreenOn = keepScreenOn
        },
        onRelease = { view -> view.attach(null) },
    )
}

/** A `SurfaceView` that remembers which player it is attached to, so it can detach from it. */
private class PlayerSurfaceView(context: Context) : SurfaceView(context) {

    private var attached: Player? = null

    fun attach(player: Player?) {
        if (attached === player) return
        attached?.clearVideoSurfaceView(this)
        attached = player
        player?.setVideoSurfaceView(this)
    }
}

@Composable
internal actual fun platformVideoPlayerFactory(): VideoPlayerFactory? {
    val context: Context = LocalContext.current
    return remember(context) { VideoPlayerFactory { config -> createVideoPlayer(context, config) } }
}
