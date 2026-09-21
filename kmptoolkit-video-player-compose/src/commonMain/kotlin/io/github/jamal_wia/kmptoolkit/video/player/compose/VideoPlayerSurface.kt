package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import kotlin.math.roundToInt

/**
 * Draws [player]'s picture — nothing else: no controls, no gestures, no background. Use it on its own
 * when you build the whole player UI yourself; [VideoPlayer] wraps it with controls.
 *
 * **Sizing.** The surface takes the bounds its [modifier] gives it and never asks for a size of its
 * own, then places the picture inside them by [scaleMode], using the aspect ratio from
 * [VideoPlayer.videoSizeFlow]. Only a dimension left unbounded (`fillMaxWidth()` inside a vertically
 * scrolling column, say) is derived from that ratio; until the ratio is known such a dimension is
 * its minimum, usually `0`. Give the surface a size — `fillMaxSize()`, or
 * `fillMaxWidth().aspectRatio(16f / 9f)` — rather than relying on that.
 *
 * **Platforms.** Android attaches a `SurfaceView` to the Media3 player behind [player]; iOS hosts
 * an `AVPlayerLayer` for its `AVPlayer`; desktop draws the frames a memory-rendering engine
 * (`kmptoolkit-video-player-vlcj`, `kmptoolkit-video-player-javafx`) produces. A player over any
 * other engine — a test fake, your own [io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine]
 * — has no picture to draw, and the surface stays empty rather than failing.
 *
 * The surface never releases [player] and never changes its state. Swapping [player] for another
 * detaches the old one from the view and attaches the new one.
 *
 * @param scaleMode how the picture fits the bounds when the ratios differ.
 * @param keepScreenOn whether to keep the display awake while [player] is
 *   [VideoPlayerState.Playing] — `View.keepScreenOn` on Android, `UIApplication.idleTimerDisabled`
 *   on iOS (restored when the last surface asking for it stops), nothing on desktop.
 */
@Composable
public fun VideoPlayerSurface(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    scaleMode: VideoScaleMode = VideoScaleMode.Fit,
    keepScreenOn: Boolean = true,
) {
    val videoSize: State<VideoSize?> = player.videoSizeFlow.collectAsState()
    val state: State<VideoPlayerState> = player.stateFlow.collectAsState()
    val aspectRatio: Float? = videoSize.value?.aspectRatio
    val screenOn: Boolean = keepScreenOn && state.value is VideoPlayerState.Playing
    val platformSurface: PlatformSurfaceContent? = LocalPlatformSurfaceOverride.current

    Layout(
        content = {
            if (platformSurface != null) {
                platformSurface(player, Modifier, scaleMode, screenOn)
            } else {
                PlatformVideoSurface(player, Modifier, scaleMode, screenOn)
            }
        },
        modifier = modifier.clipToBounds(),
    ) { measurables, constraints ->
        val boxWidth: Int
        val boxHeight: Int
        when {
            constraints.hasBoundedWidth && constraints.hasBoundedHeight -> {
                boxWidth = constraints.maxWidth
                boxHeight = constraints.maxHeight
            }
            constraints.hasBoundedWidth -> {
                boxWidth = constraints.maxWidth
                boxHeight = if (aspectRatio != null && aspectRatio > 0f) {
                    constraints.constrainHeight((boxWidth / aspectRatio).roundToInt())
                } else {
                    constraints.minHeight
                }
            }
            constraints.hasBoundedHeight -> {
                boxHeight = constraints.maxHeight
                boxWidth = if (aspectRatio != null && aspectRatio > 0f) {
                    constraints.constrainWidth((boxHeight * aspectRatio).roundToInt())
                } else {
                    constraints.minWidth
                }
            }
            else -> {
                boxWidth = constraints.minWidth
                boxHeight = constraints.minHeight
            }
        }
        val frame: FrameSize =
            fitVideoFrame(aspectRatio, boxWidth.toFloat(), boxHeight.toFloat(), scaleMode)
        val frameWidth: Int = frame.width.roundToInt().coerceAtLeast(0)
        val frameHeight: Int = frame.height.roundToInt().coerceAtLeast(0)
        val placeables = measurables.map { it.measure(Constraints.fixed(frameWidth, frameHeight)) }
        layout(boxWidth, boxHeight) {
            // Negative for Crop: the overflow is centred and clipped by clipToBounds().
            placeables.forEach { it.place((boxWidth - frameWidth) / 2, (boxHeight - frameHeight) / 2) }
        }
    }
}

/**
 * The platform view or drawing for one player, filling exactly the frame [VideoPlayerSurface]
 * measured for it. [keepScreenOn] is already folded with "is playing".
 */
@Composable
internal expect fun PlatformVideoSurface(
    player: VideoPlayer,
    modifier: Modifier,
    scaleMode: VideoScaleMode,
    keepScreenOn: Boolean,
)

internal typealias PlatformSurfaceContent =
    @Composable (player: VideoPlayer, modifier: Modifier, scaleMode: VideoScaleMode, keepScreenOn: Boolean) -> Unit

/**
 * Replaces [PlatformVideoSurface] in this module's own UI tests, where a fake player has no
 * platform picture to attach. Never set outside tests; `null` in production.
 */
internal val LocalPlatformSurfaceOverride: ProvidableCompositionLocal<PlatformSurfaceContent?> =
    staticCompositionLocalOf { null }
