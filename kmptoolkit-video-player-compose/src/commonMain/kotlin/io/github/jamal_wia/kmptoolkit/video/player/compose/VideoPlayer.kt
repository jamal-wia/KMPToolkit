package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.duration
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A complete video player for [player]: the picture ([VideoPlayerSurface]) with controls on top,
 * shown and hidden by a tap on the picture.
 *
 * **Controls** are the [controls] slot, run in a [VideoControlsScope]. The default,
 * [DefaultVideoControls], is the quick start; pass `{ DefaultVideoControls(colors = …) }` to
 * restyle it, compose your own from the public building blocks, or pass something entirely your
 * own — `{}` for none. The scope shows the controls whenever playback stops running and hides them
 * [controlsAutoHideDelayMs] after the last interaction while playing.
 *
 * **Sizing** follows [VideoPlayerSurface]: the player fills the bounds [modifier] gives it, and
 * derives a dimension left unbounded from the picture's ratio. `Modifier.fillMaxWidth()
 * .aspectRatio(16f / 9f)` is a good default for a screen whose layout must not jump when the
 * picture size becomes known.
 *
 * **Ownership.** This overload never releases [player] — you created it, you release it. The
 * `VideoPlayer(source = …)` overload creates and releases its own.
 *
 * @param scaleMode how the picture fits when its ratio differs from the bounds'; see [VideoScaleMode].
 * @param keepScreenOn keep the display awake while playing; see [VideoPlayerSurface].
 * @param pauseOnBackground pause when the host goes to the background, if playing; see
 *   [PauseOnBackgroundEffect]. Playback does not resume on return.
 * @param backgroundColor drawn behind the picture, and so in the bars [VideoScaleMode.Fit] leaves.
 * @param controlsAutoHideDelayMs see [rememberVideoControlsScope]. Must be positive.
 */
@Composable
public fun VideoPlayer(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    scaleMode: VideoScaleMode = VideoScaleMode.Fit,
    keepScreenOn: Boolean = true,
    pauseOnBackground: Boolean = true,
    backgroundColor: Color = Color.Black,
    controlsAutoHideDelayMs: Long = VideoControlsDefaults.AutoHideDelayMs,
    controls: @Composable VideoControlsScope.() -> Unit = { DefaultVideoControls() },
) {
    PauseOnBackgroundEffect(player, pauseOnBackground)
    val scope: VideoControlsScope = rememberVideoControlsScope(player, controlsAutoHideDelayMs)
    Box(modifier = modifier.background(backgroundColor).clipToBounds()) {
        // No size modifier: the surface takes the largest size the constraints allow, and derives
        // an unbounded dimension from the picture ratio, so this Box sizes to it.
        VideoPlayerSurface(
            player = player,
            scaleMode = scaleMode,
            keepScreenOn = keepScreenOn,
        )
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(scope) { detectTapGestures { scope.toggleControls() } },
        )
        Box(Modifier.matchParentSize()) { scope.controls() }
    }
}

/**
 * The quick start: a player for [source] that this composable creates, loads, and releases when it
 * leaves the composition — the shape of a "play this URL here" screen.
 *
 * ```kotlin
 * VideoPlayer(
 *     source = VideoSource.Remote(url),
 *     modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
 *     autoPlay = true,
 *     onProgress = { positionMs, durationMs -> viewModel.onProgress(positionMs, durationMs) },
 * )
 * ```
 *
 * The player comes from [rememberVideoPlayer]: a new [source] is prepared in place of the old one,
 * [config] is read once, and on desktop a [LocalVideoPlayerFactory] must be provided. A player
 * that has to survive a configuration change, or that a view model drives, belongs in the view
 * model — use the `VideoPlayer(player = …)` overload for it.
 *
 * @param autoPlay start playing once [source] is prepared.
 * @param onProgress called with the playhead and the duration, in milliseconds, whenever either
 *   changes — about every [VideoPlayerConfig.positionUpdateIntervalMs] while playing — but only
 *   while the duration is known (positive); a live stream never reports.
 * @see VideoPlayer the overload taking a player, for every other parameter.
 */
@Composable
public fun VideoPlayer(
    source: VideoSource,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
    onProgress: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
    scaleMode: VideoScaleMode = VideoScaleMode.Fit,
    keepScreenOn: Boolean = true,
    pauseOnBackground: Boolean = true,
    backgroundColor: Color = Color.Black,
    controlsAutoHideDelayMs: Long = VideoControlsDefaults.AutoHideDelayMs,
    config: VideoPlayerConfig = VideoPlayerConfig(),
    controls: @Composable VideoControlsScope.() -> Unit = { DefaultVideoControls() },
) {
    val player: VideoPlayer = rememberVideoPlayer(source, autoPlay, config)
    if (onProgress != null) ProgressEffect(player, onProgress)
    VideoPlayer(
        player = player,
        modifier = modifier,
        scaleMode = scaleMode,
        keepScreenOn = keepScreenOn,
        pauseOnBackground = pauseOnBackground,
        backgroundColor = backgroundColor,
        controlsAutoHideDelayMs = controlsAutoHideDelayMs,
        controls = controls,
    )
}

@Composable
private fun ProgressEffect(player: VideoPlayer, onProgress: (positionMs: Long, durationMs: Long) -> Unit) {
    val latest: State<(Long, Long) -> Unit> = rememberUpdatedState(onProgress)
    LaunchedEffect(player) {
        combine(player.playbackPositionFlow, player.stateFlow) { position, state ->
            ProgressSample(position, state.duration ?: 0L)
        }
            .distinctUntilChanged()
            .collect { sample ->
                if (sample.durationMs > 0L) latest.value(sample.positionMs, sample.durationMs)
            }
    }
}

private class ProgressSample(val positionMs: Long, val durationMs: Long) {
    override fun equals(other: Any?): Boolean =
        other is ProgressSample && other.positionMs == positionMs && other.durationMs == durationMs

    override fun hashCode(): Int = 31 * positionMs.hashCode() + durationMs.hashCode()
}
