package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToLong

/**
 * A seek bar: a track, the buffered part, the played part and a draggable handle.
 *
 * **Seeking.** Dragging moves the handle without seeking; [onSeek] is called once, with the
 * position under the finger, when the drag ends. A tap seeks straight to the tapped position.
 * While dragging, [onScrub] reports the position under the finger (for a time label, or to hold
 * the controls open) and `null` when the drag ends or is cancelled.
 *
 * **It does not fight position updates.** While dragging, [positionMs] updates are ignored. After
 * a seek the handle stays at the target until [positionMs] next changes, so it does not jump back
 * to the old position while the player is still seeking.
 *
 * Laid out left to right in LTR and mirrored in RTL. Disabled — drawn, but not draggable — while
 * [durationMs] is not positive (nothing loaded, or a live stream) or [enabled] is `false`.
 * Accessibility: exposed as a progress bar over `0..1` that a screen reader can set, labelled
 * [contentDescription].
 *
 * @param positionMs current playhead.
 * @param durationMs total length; `0` or less when unknown.
 * @param bufferedPositionMs buffered-ahead position, drawn between the played part and the track.
 */
@Composable
public fun VideoSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (positionMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    bufferedPositionMs: Long = 0L,
    enabled: Boolean = true,
    onScrub: (positionMs: Long?) -> Unit = {},
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    height: Dp = VideoControlsDefaults.dimensions().seekBarHeight,
    trackHeight: Dp = VideoControlsDefaults.dimensions().seekTrackHeight,
    thumbRadius: Dp = VideoControlsDefaults.dimensions().seekThumbRadius,
    contentDescription: String? = null,
) {
    val active: Boolean = enabled && durationMs > 0L
    val state: SeekBarState = remember { SeekBarState() }
    val latestDuration: State<Long> = rememberUpdatedState(durationMs)
    val latestOnSeek: State<(Long) -> Unit> = rememberUpdatedState(onSeek)
    val latestOnScrub: State<(Long?) -> Unit> = rememberUpdatedState(onScrub)
    val latestPosition: State<Long> = rememberUpdatedState(positionMs)
    val rtl: Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl

    LaunchedEffect(positionMs) {
        val pending: PendingSeek? = state.pendingSeek
        if (pending != null && positionMs != pending.positionAtSeekMs) state.pendingSeek = null
    }

    fun commit(fraction: Float) {
        val target: Long = (fraction.coerceIn(0f, 1f) * latestDuration.value).roundToLong()
        state.dragFraction = null
        state.pendingSeek = PendingSeek(target, latestPosition.value)
        latestOnScrub.value(null)
        latestOnSeek.value(target)
    }

    val displayedFraction: Float = state.dragFraction
        ?: state.pendingSeek?.let { fractionOf(it.targetMs, durationMs) }
        ?: fractionOf(positionMs, durationMs)
    val bufferedFraction: Float = fractionOf(bufferedPositionMs, durationMs)

    val gestures: Modifier = if (!active) {
        Modifier
    } else {
        Modifier
            .pointerInput(rtl, thumbRadius) {
                detectTapGestures { offset ->
                    commit(fractionAt(offset.x, size.width.toFloat(), thumbRadius.toPx(), rtl))
                }
            }
            .pointerInput(rtl, thumbRadius) {
                val padding: Float = thumbRadius.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val fraction: Float = fractionAt(offset.x, size.width.toFloat(), padding, rtl)
                        state.dragFraction = fraction
                        latestOnScrub.value((fraction * latestDuration.value).roundToLong())
                    },
                    onDragEnd = { state.dragFraction?.let(::commit) },
                    onDragCancel = {
                        state.dragFraction = null
                        latestOnScrub.value(null)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val fraction: Float =
                            fractionAt(change.position.x, size.width.toFloat(), padding, rtl)
                        state.dragFraction = fraction
                        latestOnScrub.value((fraction * latestDuration.value).roundToLong())
                    },
                )
            }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(displayedFraction, 0f..1f)
                if (active) {
                    setProgress { value ->
                        commit(value)
                        true
                    }
                } else {
                    disabled()
                }
            }
            .then(gestures)
            .drawBehind {
                drawSeekBar(
                    playedFraction = displayedFraction,
                    bufferedFraction = bufferedFraction,
                    dragging = state.dragFraction != null,
                    rtl = rtl,
                    trackHeightPx = trackHeight.toPx(),
                    thumbRadiusPx = thumbRadius.toPx(),
                    colors = colors,
                    active = active,
                )
            },
    )
}

private class PendingSeek(val targetMs: Long, val positionAtSeekMs: Long)

private class SeekBarState {
    var dragFraction: Float? by mutableStateOf(null)
    var pendingSeek: PendingSeek? by mutableStateOf(null)
}

internal fun fractionOf(valueMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (valueMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

/** Where [x] falls on a track inset by [padding] at both ends of a [width]-wide bar, as `0..1`. */
internal fun fractionAt(x: Float, width: Float, padding: Float, rtl: Boolean): Float {
    val trackWidth: Float = width - 2f * padding
    if (trackWidth <= 0f) return 0f
    val fraction: Float = ((x - padding) / trackWidth).coerceIn(0f, 1f)
    return if (rtl) 1f - fraction else fraction
}

private fun DrawScope.drawSeekBar(
    playedFraction: Float,
    bufferedFraction: Float,
    dragging: Boolean,
    rtl: Boolean,
    trackHeightPx: Float,
    thumbRadiusPx: Float,
    colors: VideoControlsColors,
    active: Boolean,
) {
    val start: Float = thumbRadiusPx
    val end: Float = size.width - thumbRadiusPx
    if (end <= start) return
    val y: Float = size.height / 2f
    fun xAt(fraction: Float): Float {
        val ltr: Float = start + (end - start) * fraction
        return if (rtl) size.width - ltr else ltr
    }
    fun line(color: Color, fraction: Float) {
        if (fraction <= 0f) return
        drawLine(color, Offset(xAt(0f), y), Offset(xAt(fraction), y), trackHeightPx, StrokeCap.Round)
    }
    line(colors.seekTrack, 1f)
    line(colors.seekBuffered, bufferedFraction.coerceAtLeast(playedFraction))
    line(colors.seekProgress, playedFraction)
    if (active) {
        val radius: Float = if (dragging) thumbRadiusPx * 1.5f else thumbRadiusPx
        drawCircle(colors.seekThumb, radius, Offset(xAt(playedFraction), y))
    }
}
