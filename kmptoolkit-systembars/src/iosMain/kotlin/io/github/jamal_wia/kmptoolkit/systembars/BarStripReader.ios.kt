package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.Composable

/**
 * iOS replays the probe's own recorded content (a CPU raster replay, no foreign code), so the layer
 * reader is the reader here.
 */
@Composable
internal actual fun rememberBarStripReader(): BarStripReader? = rememberLayerBarStripReader()
