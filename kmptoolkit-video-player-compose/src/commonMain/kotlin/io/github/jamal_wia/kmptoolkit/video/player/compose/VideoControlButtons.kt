package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import io.github.jamal_wia.kmptoolkit.video.player.DEFAULT_SEEK_AMOUNT_MS

/**
 * The button every control below is made of: a round, [buttonSize] touch target drawing [icon] at
 * [iconSize], tinted [VideoControlsColors.content] (or [VideoControlsColors.disabledContent] when
 * not [enabled]). Use it for a button of your own that should match the default ones.
 *
 * @param contentDescription what a screen reader announces; see [VideoControlsLabels].
 */
@Composable
public fun VideoControlButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    buttonSize: Dp = VideoControlsDefaults.dimensions().buttonSize,
    iconSize: Dp = VideoControlsDefaults.dimensions().iconSize,
) {
    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(if (enabled) colors.content else colors.disabledContent),
        )
    }
}

/**
 * Play while not [isPlaying], pause while playing. Wire it to [VideoControlsScope.togglePlayPause]
 * (which also replays after completion) or to your own handler.
 *
 * Announced as [VideoControlsLabels.pause] while playing and [VideoControlsLabels.play] otherwise.
 */
@Composable
public fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    buttonSize: Dp = VideoControlsDefaults.dimensions().centerButtonSize,
    iconSize: Dp = VideoControlsDefaults.dimensions().centerIconSize,
    labels: VideoControlsLabels = VideoControlsLabels(),
) {
    VideoControlButton(
        icon = if (isPlaying) VideoControlsIcons.Pause else VideoControlsIcons.Play,
        onClick = onClick,
        contentDescription = if (isPlaying) labels.pause else labels.play,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        buttonSize = buttonSize,
        iconSize = iconSize,
    )
}

/** Starts over from the beginning; what the default controls show after completion. */
@Composable
public fun ReplayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    buttonSize: Dp = VideoControlsDefaults.dimensions().centerButtonSize,
    iconSize: Dp = VideoControlsDefaults.dimensions().centerIconSize,
    labels: VideoControlsLabels = VideoControlsLabels(),
) {
    VideoControlButton(
        icon = VideoControlsIcons.Replay,
        onClick = onClick,
        contentDescription = labels.replay,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        buttonSize = buttonSize,
        iconSize = iconSize,
    )
}

/**
 * Seeks by a fixed step: forward when [forward], backward otherwise. [onClick] receives the signed
 * step — `+stepMs` or `-stepMs` — ready for [VideoControlsScope.seekBy].
 */
@Composable
public fun SeekButton(
    forward: Boolean,
    onClick: (deltaMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    stepMs: Long = DEFAULT_SEEK_AMOUNT_MS,
    enabled: Boolean = true,
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    buttonSize: Dp = VideoControlsDefaults.dimensions().buttonSize,
    iconSize: Dp = VideoControlsDefaults.dimensions().iconSize,
    labels: VideoControlsLabels = VideoControlsLabels(),
) {
    VideoControlButton(
        icon = if (forward) VideoControlsIcons.SeekForward else VideoControlsIcons.SeekBackward,
        onClick = { onClick(if (forward) stepMs else -stepMs) },
        contentDescription = if (forward) labels.seekForward else labels.seekBackward,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        buttonSize = buttonSize,
        iconSize = iconSize,
    )
}

/**
 * Mutes while sound is on, unmutes while [isMuted]. Announced as [VideoControlsLabels.mute] or
 * [VideoControlsLabels.unmute] accordingly.
 */
@Composable
public fun MuteButton(
    isMuted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    buttonSize: Dp = VideoControlsDefaults.dimensions().buttonSize,
    iconSize: Dp = VideoControlsDefaults.dimensions().iconSize,
    labels: VideoControlsLabels = VideoControlsLabels(),
) {
    VideoControlButton(
        icon = if (isMuted) VideoControlsIcons.VolumeOff else VideoControlsIcons.VolumeOn,
        onClick = onClick,
        contentDescription = if (isMuted) labels.unmute else labels.mute,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        buttonSize = buttonSize,
        iconSize = iconSize,
    )
}

/**
 * Asks your app to enter or leave fullscreen. The library implements no fullscreen of its own —
 * rotating, hiding system bars and moving the player to another screen are app decisions — so this
 * button only calls [onClick]; [isFullscreen] picks the icon and the label.
 */
@Composable
public fun FullscreenButton(
    isFullscreen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    buttonSize: Dp = VideoControlsDefaults.dimensions().buttonSize,
    iconSize: Dp = VideoControlsDefaults.dimensions().iconSize,
    labels: VideoControlsLabels = VideoControlsLabels(),
) {
    VideoControlButton(
        icon = if (isFullscreen) VideoControlsIcons.ExitFullscreen else VideoControlsIcons.EnterFullscreen,
        onClick = onClick,
        contentDescription = if (isFullscreen) labels.exitFullscreen else labels.enterFullscreen,
        modifier = modifier,
        colors = colors,
        buttonSize = buttonSize,
        iconSize = iconSize,
    )
}

/**
 * Shows the current [speed] and, on tap, calls [onSpeedChange] with the next entry of [speeds]
 * (wrapping around after the last). A speed not in [speeds] moves to the first entry above it.
 *
 * @param formatSpeed turns a speed into the label drawn on the button;
 *   [VideoControlsDefaults.formatSpeed] by default (`1.5×`).
 * @throws IllegalArgumentException when [speeds] is empty.
 */
@Composable
public fun SpeedButton(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    speeds: List<Float> = VideoControlsDefaults.Speeds,
    enabled: Boolean = true,
    formatSpeed: (Float) -> String = VideoControlsDefaults::formatSpeed,
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    buttonSize: Dp = VideoControlsDefaults.dimensions().buttonSize,
    textStyle: TextStyle = VideoControlsDefaults.ControlTextStyle,
    labels: VideoControlsLabels = VideoControlsLabels(),
) {
    require(speeds.isNotEmpty()) { "speeds must not be empty" }
    val label: String? = labels.playbackSpeed
    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button) { onSpeedChange(nextSpeed(speed, speeds)) }
            .semantics { if (label != null) contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = formatSpeed(speed),
            style = textStyle.merge(
                TextStyle(color = if (enabled) colors.content else colors.disabledContent),
            ),
            maxLines = 1,
        )
    }
}

/** The entry of [speeds] after [current], wrapping; the first entry above [current] if absent. */
internal fun nextSpeed(current: Float, speeds: List<Float>): Float {
    val index: Int = speeds.indexOfFirst { kotlin.math.abs(it - current) < SPEED_EPSILON }
    if (index >= 0) return speeds[(index + 1) % speeds.size]
    return speeds.firstOrNull { it > current } ?: speeds.first()
}

private const val SPEED_EPSILON: Float = 0.001f
