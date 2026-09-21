package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * A spinning arc: the player is waiting for data. The default controls show it while
 * [VideoControlsScope.isBuffering] or while a source is being prepared, whether or not the rest of
 * the controls are visible.
 *
 * Exposed to accessibility as an indeterminate progress indicator labelled [contentDescription]
 * (pass [VideoControlsLabels.buffering]).
 */
@Composable
public fun BufferingIndicator(
    modifier: Modifier = Modifier,
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    size: Dp = VideoControlsDefaults.dimensions().bufferingIndicatorSize,
    strokeWidth: Dp = VideoControlsDefaults.dimensions().bufferingIndicatorStrokeWidth,
    contentDescription: String? = null,
) {
    val transition: InfiniteTransition = rememberInfiniteTransition(label = "BufferingIndicator")
    val rotation: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(ROTATION_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "BufferingIndicatorRotation",
    )
    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
    ) {
        val strokePx: Float = strokeWidth.toPx()
        val diameter: Float = this.size.minDimension - strokePx
        drawArc(
            color = colors.bufferingIndicator,
            startAngle = rotation.value,
            sweepAngle = SWEEP_DEGREES,
            useCenter = false,
            topLeft = Offset((this.size.width - diameter) / 2f, (this.size.height - diameter) / 2f),
            size = Size(diameter, diameter),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

private const val ROTATION_MS: Int = 1_000
private const val SWEEP_DEGREES: Float = 270f
