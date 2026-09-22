package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
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
import platform.UIKit.UIApplication
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
        DisposableEffect(Unit) {
            IdleTimer.acquire()
            onDispose { IdleTimer.release() }
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

/** The app's `UIApplication.idleTimerDisabled`, shared by every surface that wants the screen on. */
internal val IdleTimer: IdleTimerHolds = IdleTimerHolds(
    read = { UIApplication.sharedApplication.idleTimerDisabled },
    write = { disabled -> UIApplication.sharedApplication.idleTimerDisabled = disabled },
)

/**
 * Reference-counted holds on an "idle timer disabled" flag read by [read] and written by [write]:
 * the first holder saves the current value and disables the timer, the last one restores the saved
 * value — so an app that disabled the timer itself keeps it disabled. A [release] without a matching
 * [acquire] does nothing. Main thread only, like composition.
 */
internal class IdleTimerHolds(
    private val read: () -> Boolean,
    private val write: (Boolean) -> Unit,
) {

    private var holders: Int = 0
    private var savedValue: Boolean = false

    fun acquire() {
        if (holders == 0) {
            savedValue = read()
            write(true)
        }
        holders++
    }

    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) write(savedValue)
    }
}

@Composable
internal actual fun platformVideoPlayerFactory(): VideoPlayerFactory? =
    remember { VideoPlayerFactory { config -> createVideoPlayer(config = config) } }
