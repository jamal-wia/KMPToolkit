package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colors of the default controls and of every building block. A plain class rather than a data
 * class, so a later release can add a color without breaking binary compatibility; build one with
 * [VideoControlsDefaults.colors] and override only what you need.
 *
 * @property content icons, time text and the speed label.
 * @property disabledContent the same, for a control that cannot act (nothing loaded yet).
 * @property scrim drawn over the picture while the controls are visible, so white icons stay
 *   legible over a white frame. [Color.Transparent] for none.
 * @property seekTrack the seek bar's full-length track.
 * @property seekBuffered the part of the track already buffered.
 * @property seekProgress the part of the track already played.
 * @property seekThumb the seek bar's handle.
 * @property bufferingIndicator the spinner shown while waiting for data.
 */
@Immutable
public class VideoControlsColors(
    public val content: Color,
    public val disabledContent: Color,
    public val scrim: Color,
    public val seekTrack: Color,
    public val seekBuffered: Color,
    public val seekProgress: Color,
    public val seekThumb: Color,
    public val bufferingIndicator: Color,
)

/**
 * Sizes of the default controls and of every building block. A plain class for the same reason as
 * [VideoControlsColors]; build one with [VideoControlsDefaults.dimensions].
 *
 * @property buttonSize touch target of a regular button. Keep it at 48.dp or more: that is the
 *   minimum both Android and iOS accessibility guidelines ask for.
 * @property iconSize icon drawn inside a regular button.
 * @property centerButtonSize touch target of the large play/pause/replay button in the middle.
 * @property centerIconSize icon drawn inside it.
 * @property seekBarHeight touch height of the seek bar; the track is drawn centred in it.
 * @property seekTrackHeight thickness of the seek bar's track.
 * @property seekThumbRadius radius of the seek bar's handle; grows by half while dragged.
 * @property bufferingIndicatorSize diameter of the buffering spinner.
 * @property bufferingIndicatorStrokeWidth line width of the buffering spinner.
 * @property contentPadding padding between the controls and the edge of the player.
 */
@Immutable
public class VideoControlsDimensions(
    public val buttonSize: Dp,
    public val iconSize: Dp,
    public val centerButtonSize: Dp,
    public val centerIconSize: Dp,
    public val seekBarHeight: Dp,
    public val seekTrackHeight: Dp,
    public val seekThumbRadius: Dp,
    public val bufferingIndicatorSize: Dp,
    public val bufferingIndicatorStrokeWidth: Dp,
    public val contentPadding: Dp,
)

/**
 * Accessibility labels of the controls — the only text they carry, since the controls draw icons,
 * digits and a speed multiplier and nothing else.
 *
 * **Every label is `null` by default, and an app should pass all of them, localized.** This library
 * ships no user-facing text in any language (see `docs/01-architecture.md`), so a default English
 * string would be exactly the copy it must not own. With a `null` label a screen reader announces the
 * control by role only ("button"), which is usable but poor; with your `stringResource(…)` it reads
 * "Pause, button".
 *
 * Labels name the action a control performs *now*: [pause] while playing, [play] otherwise; [mute]
 * while sound is on, [unmute] while muted.
 *
 * A plain class rather than a data class, so a later release can add a label without breaking
 * binary compatibility.
 */
@Immutable
public class VideoControlsLabels(
    public val play: String? = null,
    public val pause: String? = null,
    public val replay: String? = null,
    public val seekForward: String? = null,
    public val seekBackward: String? = null,
    public val mute: String? = null,
    public val unmute: String? = null,
    public val seekBar: String? = null,
    public val playbackSpeed: String? = null,
    public val enterFullscreen: String? = null,
    public val exitFullscreen: String? = null,
    public val buffering: String? = null,
)

/** Defaults for the controls: colors, sizes, timings, speeds and formatting. */
public object VideoControlsDefaults {

    /** How long the controls stay visible while playing without interaction: three seconds. */
    public const val AutoHideDelayMs: Long = 3_000L

    /** Speeds [SpeedButton] cycles through by default. */
    public val Speeds: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Text style of [VideoTimeText] and [SpeedButton]: small, medium weight. */
    public val ControlTextStyle: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)

    /**
     * Colors for controls drawn over video: white content over a translucent black scrim, which
     * reads on bright and dark frames alike.
     */
    public fun colors(
        content: Color = Color.White,
        disabledContent: Color = Color.White.copy(alpha = 0.38f),
        scrim: Color = Color.Black.copy(alpha = 0.4f),
        seekTrack: Color = Color.White.copy(alpha = 0.24f),
        seekBuffered: Color = Color.White.copy(alpha = 0.48f),
        seekProgress: Color = Color.White,
        seekThumb: Color = Color.White,
        bufferingIndicator: Color = Color.White,
    ): VideoControlsColors = VideoControlsColors(
        content = content,
        disabledContent = disabledContent,
        scrim = scrim,
        seekTrack = seekTrack,
        seekBuffered = seekBuffered,
        seekProgress = seekProgress,
        seekThumb = seekThumb,
        bufferingIndicator = bufferingIndicator,
    )

    /** Sizes that meet the 48.dp minimum touch target. */
    public fun dimensions(
        buttonSize: Dp = 48.dp,
        iconSize: Dp = 24.dp,
        centerButtonSize: Dp = 72.dp,
        centerIconSize: Dp = 44.dp,
        seekBarHeight: Dp = 32.dp,
        seekTrackHeight: Dp = 4.dp,
        seekThumbRadius: Dp = 6.dp,
        bufferingIndicatorSize: Dp = 48.dp,
        bufferingIndicatorStrokeWidth: Dp = 4.dp,
        contentPadding: Dp = 8.dp,
    ): VideoControlsDimensions = VideoControlsDimensions(
        buttonSize = buttonSize,
        iconSize = iconSize,
        centerButtonSize = centerButtonSize,
        centerIconSize = centerIconSize,
        seekBarHeight = seekBarHeight,
        seekTrackHeight = seekTrackHeight,
        seekThumbRadius = seekThumbRadius,
        bufferingIndicatorSize = bufferingIndicatorSize,
        bufferingIndicatorStrokeWidth = bufferingIndicatorStrokeWidth,
        contentPadding = contentPadding,
    )

    /**
     * `1×`, `1.5×`, `0.75×`: the speed rounded to two decimals, trailing zeros dropped, a `.`
     * decimal separator and a multiplication sign. The default for [SpeedButton]'s `formatSpeed`;
     * pass your own for a locale whose decimal separator is not `.`.
     */
    public fun formatSpeed(speed: Float): String {
        val hundredths: Long = kotlin.math.round(speed.toDouble() * 100.0).toLong().coerceAtLeast(0L)
        val whole: Long = hundredths / 100L
        val fraction: Long = hundredths % 100L
        val digits: String = when {
            fraction == 0L -> "$whole"
            fraction % 10L == 0L -> "$whole.${fraction / 10L}"
            else -> "$whole.${fraction.toString().padStart(2, '0')}"
        }
        return "$digits×"
    }
}
