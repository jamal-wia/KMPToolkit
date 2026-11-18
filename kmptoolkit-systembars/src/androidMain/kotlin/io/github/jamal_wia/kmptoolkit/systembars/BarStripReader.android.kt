package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.Composable

/** Android, in this step: the layer reader on every API level. */
@Composable
internal actual fun rememberBarStripReader(): BarStripReader? = rememberLayerBarStripReader()
