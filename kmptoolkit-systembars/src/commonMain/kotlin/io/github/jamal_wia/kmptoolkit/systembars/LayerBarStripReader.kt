package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/**
 * The sampler [AutoSystemBarsIconStyle] has always had, as a [BarStripReader]: records the probe's
 * content into [layer], replays the wanted rows into a strip-sized [stripLayer] and reads that back
 * with `toImageBitmap()`. Used on iOS and on Android API 24-25.
 *
 * On Android this replays the recorded content in a second hardware renderer, which breaks a hosted
 * `WebView`; that is why Android API 26+ reads the window instead and API 24-25 guards this reader
 * (see `BarStripReader.android.kt`).
 *
 * Reads are serialized by the caller ([SerialSampler]); [pixelBuffer] is therefore never used by two
 * reads at once.
 */
internal class LayerBarStripReader(
    private val layer: GraphicsLayer,
    private val stripLayer: GraphicsLayer,
    private val density: Density,
    private val layoutDirection: LayoutDirection,
) : BarStripReader {

    // Reusable buffer for the strip bitmap — sized once, expanded in place if the strips grow
    // (rotation etc.).
    private var pixelBuffer: IntArray = IntArray(0)

    override fun ContentDrawScope.drawContentAndCapture() {
        // Record the just-drawn frame into the offscreen layer, then present the layer to the
        // screen. Near-zero-cost pass-through: visible output is identical, but `layer` now holds a
        // sampleable copy of the current frame.
        layer.record { this@drawContentAndCapture.drawContent() }
        drawLayer(layer)
    }

    // Read the sampled rows back through a scratch layer a few rows tall instead of the
    // full-screen one. `toImageBitmap()` rasterises the *whole* layer and `toPixelMap()` then
    // copies the *whole* bitmap back to the CPU — on a 1080 × 2400 screen that is ~10 MB per
    // copy, twice per sample, all on the main thread. The 8 × 2 grid only ever looks at two rows
    // per bar, so the scratch layer holds exactly those rows: each is the display layer redrawn
    // with a one-row clip and shifted so the wanted source row lands there. The display list is
    // replayed, not re-recorded, so the composables' draw lambdas do not run again.
    // One readback at a time: on API 24-27 the readback suspends, so the trigger collector and
    // the periodic tick could otherwise both be inside a sample, re-recording the strip layer
    // under the first one's pending read.
    override suspend fun read(statusPx: Int, navPx: Int): BarStripStyles? {
        // Nothing recorded yet, or no row to read: a skipped sample, not a successful empty one.
        val source: IntSize = layer.size
        if (source.width <= 0 || source.height <= 0) return null
        val rowSources: IntArray = scratchRowSources(statusPx, navPx, source.height)
        if (rowSources.isEmpty()) return null
        val scratchWidth: Float = source.width.toFloat()

        stripLayer.record(density, layoutDirection, IntSize(source.width, rowSources.size)) {
            rowSources.forEachIndexed { row, sourceY ->
                clipRect(0f, row.toFloat(), scratchWidth, (row + 1).toFloat()) {
                    translate(top = (row - sourceY).toFloat()) { drawLayer(layer) }
                }
            }
        }

        val bitmap: ImageBitmap = stripLayer.toImageBitmap()
        val needed: Int = bitmap.width * bitmap.height
        if (pixelBuffer.size < needed) pixelBuffer = IntArray(needed)
        val pixelMap: PixelMap = bitmap.toPixelMap(buffer = pixelBuffer, stride = bitmap.width)
        val statusRows: Int = scratchRowCount(statusPx)

        // Status bar rows come first, the navigation bar's after them.
        return BarStripStyles(
            status = sampleRows(pixelMap, startY = 0, rows = statusRows),
            nav = sampleRows(pixelMap, startY = statusRows, rows = rowSources.size - statusRows),
        )
    }
}

/**
 * A [LayerBarStripReader] over two fresh [GraphicsLayer]s, remembered for the composition and
 * replaced when the density or the layout direction changes (the strip layer records with both).
 */
@Composable
internal fun rememberLayerBarStripReader(): LayerBarStripReader {
    val layer: GraphicsLayer = rememberGraphicsLayer()
    // Strip-sized scratch layer the sampler renders into — see `LayerBarStripReader.read`.
    val stripLayer: GraphicsLayer = rememberGraphicsLayer()
    val density: Density = LocalDensity.current
    val layoutDirection: LayoutDirection = LocalLayoutDirection.current
    return remember(layer, stripLayer, density, layoutDirection) {
        LayerBarStripReader(layer, stripLayer, density, layoutDirection)
    }
}
