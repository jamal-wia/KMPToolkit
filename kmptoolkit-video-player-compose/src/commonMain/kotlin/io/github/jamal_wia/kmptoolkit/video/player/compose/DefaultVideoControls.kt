package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.jamal_wia.kmptoolkit.video.player.DEFAULT_SEEK_AMOUNT_MS
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.isPlayable

/**
 * The ready-made controls: a large play/pause (replay after completion) button between seek-back
 * and seek-forward buttons in the middle; the position, a seek bar, the duration, and mute, speed
 * and fullscreen buttons along the bottom; a buffering spinner in the middle while waiting for data.
 * They fade in and out with [VideoControlsScope.controlsVisible], over a scrim.
 *
 * Built only from public pieces — [PlayPauseButton], [ReplayButton], [SeekButton], [VideoSeekBar],
 * [VideoTimeText], [MuteButton], [SpeedButton], [FullscreenButton], [BufferingIndicator] — so when
 * a parameter here is not enough, copy this function's layout and change it; nothing it does is
 * out of your reach. See `docs/kmptoolkit-video-player-compose/03-guide.md`.
 *
 * @param labels accessibility labels. All `null` by default — pass localized ones; see
 *   [VideoControlsLabels].
 * @param onFullscreenClick shows [FullscreenButton] and is called when it is tapped. `null` (the
 *   default) hides the button: fullscreen is your app's to implement.
 * @param isFullscreen picks the fullscreen button's icon and label.
 * @param speeds speeds [SpeedButton] cycles through.
 * @param seekStepMs step of the seek-back and seek-forward buttons.
 * @param showSeekButtons, showMuteButton, showSpeedButton, showTime hide individual parts.
 * @param formatSpeed the speed button's label; see [SpeedButton].
 */
@Composable
public fun VideoControlsScope.DefaultVideoControls(
    modifier: Modifier = Modifier,
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    dimensions: VideoControlsDimensions = VideoControlsDefaults.dimensions(),
    labels: VideoControlsLabels = VideoControlsLabels(),
    textStyle: TextStyle = VideoControlsDefaults.ControlTextStyle,
    onFullscreenClick: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    speeds: List<Float> = VideoControlsDefaults.Speeds,
    seekStepMs: Long = DEFAULT_SEEK_AMOUNT_MS,
    showSeekButtons: Boolean = true,
    showMuteButton: Boolean = true,
    showSpeedButton: Boolean = true,
    showTime: Boolean = true,
    formatSpeed: (Float) -> String = VideoControlsDefaults::formatSpeed,
) {
    val controls: VideoControlsScope = this
    val state: VideoPlayerState = playerState
    val playable: Boolean = state.isPlayable
    val waiting: Boolean = isBuffering || state is VideoPlayerState.Preparing
    var scrubMs: Long? by remember { mutableStateOf(null) }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize().background(colors.scrim)) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showSeekButtons) {
                        SeekButton(
                            forward = false,
                            onClick = controls::seekBy,
                            stepMs = seekStepMs,
                            enabled = playable,
                            colors = colors,
                            buttonSize = dimensions.buttonSize,
                            iconSize = dimensions.iconSize,
                            labels = labels,
                        )
                    }
                    Box(Modifier.size(dimensions.centerButtonSize), contentAlignment = Alignment.Center) {
                        // The spinner takes the centre while waiting; the button would only flicker.
                        if (!waiting) {
                            if (state is VideoPlayerState.Completed) {
                                ReplayButton(
                                    onClick = controls::replay,
                                    colors = colors,
                                    buttonSize = dimensions.centerButtonSize,
                                    iconSize = dimensions.centerIconSize,
                                    labels = labels,
                                )
                            } else {
                                PlayPauseButton(
                                    isPlaying = isPlaying,
                                    onClick = controls::togglePlayPause,
                                    enabled = playable,
                                    colors = colors,
                                    buttonSize = dimensions.centerButtonSize,
                                    iconSize = dimensions.centerIconSize,
                                    labels = labels,
                                )
                            }
                        }
                    }
                    if (showSeekButtons) {
                        SeekButton(
                            forward = true,
                            onClick = controls::seekBy,
                            stepMs = seekStepMs,
                            enabled = playable,
                            colors = colors,
                            buttonSize = dimensions.buttonSize,
                            iconSize = dimensions.iconSize,
                            labels = labels,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(dimensions.contentPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showTime) {
                        VideoTimeText(
                            timeMs = scrubMs ?: positionMs,
                            referenceDurationMs = durationMs,
                            colors = colors,
                            textStyle = textStyle,
                        )
                    }
                    VideoSeekBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onSeek = controls::seekTo,
                        modifier = Modifier.weight(1f),
                        bufferedPositionMs = bufferedPositionMs,
                        enabled = playable,
                        onScrub = { position ->
                            scrubMs = position
                            setInteracting(position != null)
                        },
                        colors = colors,
                        height = dimensions.seekBarHeight,
                        trackHeight = dimensions.seekTrackHeight,
                        thumbRadius = dimensions.seekThumbRadius,
                        contentDescription = labels.seekBar,
                    )
                    if (showTime) {
                        VideoTimeText(
                            timeMs = durationMs,
                            referenceDurationMs = durationMs,
                            colors = colors,
                            textStyle = textStyle,
                        )
                    }
                    if (showMuteButton) {
                        MuteButton(
                            isMuted = isMuted,
                            onClick = controls::toggleMute,
                            colors = colors,
                            buttonSize = dimensions.buttonSize,
                            iconSize = dimensions.iconSize,
                            labels = labels,
                        )
                    }
                    if (showSpeedButton) {
                        SpeedButton(
                            speed = playbackSpeed,
                            onSpeedChange = controls::setPlaybackSpeed,
                            speeds = speeds,
                            enabled = playable,
                            formatSpeed = formatSpeed,
                            colors = colors,
                            buttonSize = dimensions.buttonSize,
                            textStyle = textStyle,
                            labels = labels,
                        )
                    }
                    if (onFullscreenClick != null) {
                        FullscreenButton(
                            isFullscreen = isFullscreen,
                            onClick = onFullscreenClick,
                            colors = colors,
                            buttonSize = dimensions.buttonSize,
                            iconSize = dimensions.iconSize,
                            labels = labels,
                        )
                    }
                }
            }
        }
        if (waiting) {
            BufferingIndicator(
                modifier = Modifier.align(Alignment.Center),
                colors = colors,
                size = dimensions.bufferingIndicatorSize,
                strokeWidth = dimensions.bufferingIndicatorStrokeWidth,
                contentDescription = labels.buffering,
            )
        }
    }
}
