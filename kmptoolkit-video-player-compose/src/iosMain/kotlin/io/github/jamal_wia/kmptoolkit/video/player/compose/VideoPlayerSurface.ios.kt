package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.github.jamal_wia.kmptoolkit.core.IdleTimerHold
import io.github.jamal_wia.kmptoolkit.core.SharedIdleTimer
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.avPlayerOrNull
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVLayerVideoGravity
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRectZero
import platform.QuartzCore.CATransaction
import platform.UIKit.UIView

/**
 * A `UIView` hosting an `AVPlayerLayer` for the `AVPlayer` behind [player], resized with the view.
 * [VideoPlayerSurface] has already sized the view to the picture's frame; the layer's
 * `videoGravity` still follows [scaleMode] so the picture is right before the size is known.
 */
@OptIn(ToolkitInternalApi::class, ExperimentalForeignApi::class)
@Composable
internal actual fun PlatformVideoSurface(
    player: VideoPlayer,
    modifier: Modifier,
    scaleMode: VideoScaleMode,
    keepScreenOn: Boolean,
) {
    val avPlayer: AVPlayer? = remember(player) { player.avPlayerOrNull() }
    val gravity: AVLayerVideoGravity = when (scaleMode) {
        VideoScaleMode.Fit -> AVLayerVideoGravityResizeAspect
        VideoScaleMode.Fill -> AVLayerVideoGravityResize
        VideoScaleMode.Crop -> AVLayerVideoGravityResizeAspectFill
    }
    UIKitView(
        factory = { PlayerLayerView() },
        modifier = modifier,
        update = { view ->
            if (view.playerLayer.player !== avPlayer) view.playerLayer.player = avPlayer
            view.playerLayer.videoGravity = gravity
        },
        onRelease = { view -> view.playerLayer.player = null },
        // Touches belong to the Compose controls drawn over the picture, not to the view.
        properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
    )
    if (keepScreenOn) {
        // The suite's one holder of `UIApplication.idleTimerDisabled`, shared with the system-bars
        // wake lock. Each surface owns one hold, so a surface leaving never lets the screen sleep
        // while another surface or the wake lock still wants it on.
        DisposableEffect(Unit) {
            val hold: IdleTimerHold = SharedIdleTimer.acquire()
            onDispose { hold.release() }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PlayerLayerView : UIView(frame = CGRectZero.readValue()) {

    val playerLayer: AVPlayerLayer = AVPlayerLayer()

    init {
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // Without this the layer animates to its new frame, lagging a resize by a quarter second.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}

@Composable
internal actual fun platformVideoPlayerFactory(): VideoPlayerFactory? =
    remember { VideoPlayerFactory { config -> createVideoPlayer(config = config) } }
