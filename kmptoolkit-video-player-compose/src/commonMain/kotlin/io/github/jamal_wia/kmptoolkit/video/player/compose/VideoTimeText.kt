package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/**
 * A playback time as digits — `m:ss`, or `h:mm:ss` from one hour — formatted by [formatVideoTime].
 *
 * @param timeMs the time to show: a position, or a duration.
 * @param referenceDurationMs the longest time shown next to this one, normally the duration: when
 *   it reaches an hour, this label uses the `h:mm:ss` form too, so a position and a duration side by
 *   side have the same shape (`0:05:07 / 1:02:03`).
 * @param color text color; [VideoControlsColors.content] by default.
 */
@Composable
public fun VideoTimeText(
    timeMs: Long,
    modifier: Modifier = Modifier,
    referenceDurationMs: Long = timeMs,
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    textStyle: TextStyle = VideoControlsDefaults.ControlTextStyle,
) {
    BasicText(
        text = formatVideoTime(timeMs, referenceDurationMs),
        modifier = modifier,
        style = textStyle.merge(TextStyle(color = colors.content)),
        maxLines = 1,
    )
}

/**
 * Formats a playback time as `m:ss`, or as `h:mm:ss` when [timeMs] or [referenceDurationMs] reaches
 * an hour. Digits and colons only — no units, nothing to translate. Rounds down to the second, so a
 * position never shows a second that has not been reached yet; a negative time is shown as `0:00`.
 *
 * `formatVideoTime(65_000)` is `1:05`; `formatVideoTime(65_000, referenceDurationMs = 3_600_000)` is
 * `0:01:05`; `formatVideoTime(3_723_000)` is `1:02:03`.
 */
public fun formatVideoTime(timeMs: Long, referenceDurationMs: Long = timeMs): String {
    val totalSeconds: Long = timeMs.coerceAtLeast(0L) / 1_000L
    val hours: Long = totalSeconds / 3_600L
    val minutes: Long = (totalSeconds % 3_600L) / 60L
    val seconds: Long = totalSeconds % 60L
    val withHours: Boolean = hours > 0L || referenceDurationMs >= 3_600_000L
    return if (withHours) {
        "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}"
    } else {
        "$minutes:${twoDigits(seconds)}"
    }
}

private fun twoDigits(value: Long): String = if (value < 10L) "0$value" else "$value"
