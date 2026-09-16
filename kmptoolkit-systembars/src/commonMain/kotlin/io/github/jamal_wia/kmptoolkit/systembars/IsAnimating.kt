package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.Composable

/**
 * The platform's own view of whether the UI is producing frames right now — an animation the root
 * draw pass alone cannot see, such as a child animating only its layer transform, or a tween resting
 * at an endpoint while others still run.
 *
 * Returns `null` where the platform exposes no such signal; [AutoSystemBarsIconStyle]'s draw clock
 * then gates the periodic sample alone.
 */
@Composable
internal expect fun rememberIsAnimating(): (() -> Boolean)?
