package io.github.jamal_wia.kmptoolkit.platform.accessibility

import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/**
 * Creates the iOS [ReducedMotionProbe], reading `UIAccessibilityIsReduceMotionEnabled()`.
 *
 * That is the direct answer to Settings → Accessibility → Motion → Reduce Motion, so unlike
 * Android there is no inference from an animation scale. No permission is required.
 */
public fun createReducedMotionProbe(): ReducedMotionProbe = IosReducedMotionProbe

private object IosReducedMotionProbe : ReducedMotionProbe {
    override fun isReducedMotionEnabled(): Boolean = UIAccessibilityIsReduceMotionEnabled()
}
