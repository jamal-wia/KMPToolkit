package io.github.jamal_wia.kmptoolkit.video.player

/**
 * The size of the decoded picture in pixels, as the platform reports it — already rotated for a
 * source recorded in portrait, so [width] and [height] are the displayed orientation.
 *
 * @property width picture width in pixels, positive.
 * @property height picture height in pixels, positive.
 */
public data class VideoSize(val width: Int, val height: Int) {

    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
    }

    /** `width / height` — what a surface sizes itself by. */
    public val aspectRatio: Float get() = width.toFloat() / height.toFloat()
}
