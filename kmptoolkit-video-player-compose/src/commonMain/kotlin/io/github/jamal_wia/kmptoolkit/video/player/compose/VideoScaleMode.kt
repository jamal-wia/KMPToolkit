package io.github.jamal_wia.kmptoolkit.video.player.compose

/**
 * How [VideoPlayerSurface] fits the picture into the bounds it was given when the two aspect ratios
 * differ.
 */
public enum class VideoScaleMode {

    /**
     * The whole picture is visible, as large as it fits, centred; the rest of the bounds is left
     * empty (letterbox or pillarbox bars). The default.
     */
    Fit,

    /** The picture is stretched to the bounds exactly, distorting it when the ratios differ. */
    Fill,

    /**
     * The picture keeps its ratio and covers the bounds completely, centred; whatever overflows is
     * cropped away.
     */
    Crop,
}

/** A width/height pair in whatever unit the caller works in. */
internal class FrameSize(val width: Float, val height: Float)

/**
 * The size the picture is drawn at inside a [boxWidth] × [boxHeight] area, for a picture whose
 * width/height ratio is [contentAspectRatio]. Centring is the caller's job.
 *
 * A missing or non-positive ratio, or an empty box, means "nothing to fit against": the box itself.
 */
internal fun fitVideoFrame(
    contentAspectRatio: Float?,
    boxWidth: Float,
    boxHeight: Float,
    mode: VideoScaleMode,
): FrameSize {
    if (contentAspectRatio == null || contentAspectRatio <= 0f || !contentAspectRatio.isFinite()) {
        return FrameSize(boxWidth, boxHeight)
    }
    if (boxWidth <= 0f || boxHeight <= 0f) return FrameSize(boxWidth, boxHeight)
    val boxAspectRatio: Float = boxWidth / boxHeight
    return when (mode) {
        VideoScaleMode.Fill -> FrameSize(boxWidth, boxHeight)
        VideoScaleMode.Fit ->
            if (contentAspectRatio > boxAspectRatio) {
                FrameSize(boxWidth, boxWidth / contentAspectRatio)
            } else {
                FrameSize(boxHeight * contentAspectRatio, boxHeight)
            }
        VideoScaleMode.Crop ->
            if (contentAspectRatio > boxAspectRatio) {
                FrameSize(boxHeight * contentAspectRatio, boxHeight)
            } else {
                FrameSize(boxWidth, boxWidth / contentAspectRatio)
            }
    }
}
