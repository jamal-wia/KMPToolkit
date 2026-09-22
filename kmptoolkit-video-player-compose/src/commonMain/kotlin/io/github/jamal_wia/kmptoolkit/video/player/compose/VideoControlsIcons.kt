package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The icons the controls draw, as 24×24 [ImageVector]s drawn in black — tint them when drawing
 * (the building blocks tint with [VideoControlsColors.content]). Public so a button of your own can
 * match the default ones; drawn by this library, so the module needs no icon dependency.
 */
public object VideoControlsIcons {

    /** A right-pointing triangle. */
    public val Play: ImageVector by lazy {
        icon("Play") { filled { moveTo(8f, 5f); lineTo(8f, 19f); lineTo(19f, 12f); close() } }
    }

    /** Two vertical bars. */
    public val Pause: ImageVector by lazy {
        icon("Pause") {
            filled {
                moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
                moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
            }
        }
    }

    /** A circular arrow. */
    public val Replay: ImageVector by lazy {
        icon("Replay") {
            stroked {
                moveTo(5f, 13f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 12f, y1 = 6f)
            }
            filled { moveTo(12f, 2.5f); lineTo(16f, 6f); lineTo(12f, 9.5f); close() }
        }
    }

    /** Two triangles pointing forward. */
    public val SeekForward: ImageVector by lazy {
        icon("SeekForward") {
            filled {
                moveTo(4f, 6f); lineTo(4f, 18f); lineTo(12f, 12f); close()
                moveTo(12f, 6f); lineTo(12f, 18f); lineTo(20f, 12f); close()
            }
        }
    }

    /** Two triangles pointing backward. */
    public val SeekBackward: ImageVector by lazy {
        icon("SeekBackward") {
            filled {
                moveTo(20f, 6f); lineTo(20f, 18f); lineTo(12f, 12f); close()
                moveTo(12f, 6f); lineTo(12f, 18f); lineTo(4f, 12f); close()
            }
        }
    }

    /** A speaker with sound waves. */
    public val VolumeOn: ImageVector by lazy {
        icon("VolumeOn") {
            speaker()
            stroked {
                moveTo(15.5f, 8.5f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15.5f, y1 = 15.5f)
                moveTo(18f, 5.5f)
                arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18f, y1 = 18.5f)
            }
        }
    }

    /** A speaker struck through with a cross. */
    public val VolumeOff: ImageVector by lazy {
        icon("VolumeOff") {
            speaker()
            stroked {
                moveTo(15f, 9f); lineTo(21f, 15f)
                moveTo(21f, 9f); lineTo(15f, 15f)
            }
        }
    }

    /** Four corners pointing outward. */
    public val EnterFullscreen: ImageVector by lazy {
        icon("EnterFullscreen") {
            stroked {
                moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
                moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
                moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
                moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
            }
        }
    }

    /** Four corners pointing inward. */
    public val ExitFullscreen: ImageVector by lazy {
        icon("ExitFullscreen") {
            stroked {
                moveTo(9f, 4f); lineTo(9f, 9f); lineTo(4f, 9f)
                moveTo(20f, 9f); lineTo(15f, 9f); lineTo(15f, 4f)
                moveTo(15f, 20f); lineTo(15f, 15f); lineTo(20f, 15f)
                moveTo(4f, 15f); lineTo(9f, 15f); lineTo(9f, 20f)
            }
        }
    }

    private fun ImageVector.Builder.speaker() {
        filled {
            moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 4f); lineTo(12f, 20f); lineTo(7f, 15f)
            lineTo(3f, 15f); close()
        }
    }

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "VideoControls.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private inline fun ImageVector.Builder.filled(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
    }

    private inline fun ImageVector.Builder.stroked(block: PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }
}
