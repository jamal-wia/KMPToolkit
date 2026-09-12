package io.github.jamal_wia.kmptoolkit.systembars

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.findViewTreeCompositionContext

/**
 * Android: the window's [Recomposer] knows. [Recomposer.hasPendingWork] is true while anything
 * awaits a frame (`withFrameNanos`, which every Compose animation is built on) or an invalidation
 * is pending — so it covers layer-only animations that never redraw the probe's root. The
 * Recomposer is found through the view tree; a host that composes without one gets `null` and the
 * draw-clock gate alone.
 */
@Composable
internal actual fun rememberIsAnimating(): (() -> Boolean)? {
    val view: View = LocalView.current
    val recomposer: Recomposer? = remember(view) { view.findViewTreeCompositionContext() as? Recomposer }
    return remember(recomposer) { recomposer?.let { r -> { r.hasPendingWork } } }
}
