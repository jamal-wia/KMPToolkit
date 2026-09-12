package io.github.jamal_wia.kmptoolkit.systembars

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Imperative trigger for the [AutoSystemBarsIconStyle] sampler.
 *
 * A screen whose content under the bars changes between frames — a page background switch, a theme
 * toggle, a fullscreen image appearing — calls [triggerRecalculation] to ask for an **immediate**
 * re-sample on the next composed frame, instead of waiting for the periodic tick to catch up.
 *
 * Call this rather than [SystemBarsController.setStatusBarStyle] / `setNavigationBarStyle` from a
 * screen: leave the icon-style decision to the probe, and only signal "the area under the bars just
 * changed; look again". That keeps the decision in one place and stops a screen and the probe from
 * fighting over the controller.
 *
 * Create one with [createStatusBarLuminanceProbe], hold it for as long as [AutoSystemBarsIconStyle]
 * is composed, and pass it to both. Implementations must be safe to call from any thread.
 */
public interface StatusBarLuminanceProbe {

    /**
     * Asks the probe to re-sample the areas under the system bars on the next composed frame, and
     * to update each bar's icon style if the result differs from what it currently holds.
     *
     * Bursts are conflated: calling this ten times in a row costs at most one extra sample, not ten.
     */
    public fun triggerRecalculation()

    /**
     * Consumed by [AutoSystemBarsIconStyle] to drive the sampling loop. Call [triggerRecalculation]
     * instead of collecting this directly.
     */
    public val triggers: SharedFlow<Unit>
}

/** Creates a [StatusBarLuminanceProbe]. Platform-independent — no `Context` needed on either target. */
public fun createStatusBarLuminanceProbe(): StatusBarLuminanceProbe = StatusBarLuminanceProbeImpl()

internal class StatusBarLuminanceProbeImpl : StatusBarLuminanceProbe {

    // extraBufferCapacity = 1 + DROP_OLDEST gives conflation: several tryEmits in flight collapse to
    // one buffered signal, so a burst of background changes results in at most one pending sample.
    // Lock-free, allocation-free tryEmit.
    private val mutableTriggers = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val triggers: SharedFlow<Unit> = mutableTriggers.asSharedFlow()

    override fun triggerRecalculation() {
        mutableTriggers.tryEmit(Unit)
    }
}
