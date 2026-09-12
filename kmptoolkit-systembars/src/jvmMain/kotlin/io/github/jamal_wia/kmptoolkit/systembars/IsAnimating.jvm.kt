package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.Composable

/** No public handle on the host's Recomposer here — the draw clock gates alone. */
@Composable
internal actual fun rememberIsAnimating(): (() -> Boolean)? = null
