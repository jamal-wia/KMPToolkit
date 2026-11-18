package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.Composable

/**
 * Desktop has no system bars, so its insets are zero and the probe never samples; a `null` reader
 * says so explicitly and skips creating two unused layers.
 */
@Composable
internal actual fun rememberBarStripReader(): BarStripReader? = null
