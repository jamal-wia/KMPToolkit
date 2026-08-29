package io.github.jamal_wia.kmptoolkit.systembars

import kotlinx.coroutines.flow.StateFlow

/**
 * The single owner of the status bar and navigation bar for one app process.
 *
 * Create one — `createSystemBarsController(context)` on Android,
 * `createSystemBarsController()` on iOS — hold it for as long as the UI lives, and pass it to
 * whoever needs it. Nothing here is global: two controllers driving the same window would fight,
 * which is the exact failure this module exists to prevent.
 *
 * ### One base, many overrides
 *
 * The effective [config] is a **base configuration** with a stack of [SystemBarsOverride]s applied
 * on top, newest last:
 *
 * - The **base** belongs to exactly one writer — your theme. It is the answer to "what should the
 *   bars look like when no screen has an opinion", and it changes when the app switches between
 *   light and dark. Set it with [setBaseConfig].
 * - An **override** belongs to a screen, and claims only the axes that screen cares about. Push it
 *   with [applyOverride], drop it with the returned handle. Between those two calls the axes it
 *   claims are its own; the rest keep following the layers underneath, including later changes to
 *   the base.
 *
 * That layering is what makes "restore the previous state" correct rather than approximately
 * correct. A screen that snapshots the current configuration and writes it back on exit restores a
 * value that may be stale by then — it will happily undo a theme change, or a second screen's
 * override, that happened while it was on screen. Releasing a layer cannot do that: it removes one
 * claim and recomputes, and every other claim survives untouched.
 *
 * ### Threading
 *
 * State transitions are lock-free and atomic (compare-and-set on the whole layer stack), so a
 * background writer and the UI thread can never interleave into a lost axis. Every method here is
 * safe to call from any thread; the platform-side work is moved to the main thread by the
 * implementation. Prefer the main thread anyway: it is where composition runs, and it makes the
 * *order* of two writes predictable, which atomicity alone does not give you.
 */
public interface SystemBarsController {

    /**
     * The effective configuration — base plus every live override — recomputed on every change.
     *
     * Distinct values only: re-applying an override that changes nothing does not emit. Reading
     * [StateFlow.value] is always in sync with the last completed mutation.
     */
    public val config: StateFlow<SystemBarsConfig>

    /** The effective configuration right now. Shorthand for `config.value`. */
    public val currentConfig: SystemBarsConfig
        get() = config.value

    /**
     * Replaces the base configuration — the state that shows through wherever no override claims
     * an axis.
     *
     * This is the theme's call to make, and no one else's. A screen that wants to change the bars
     * for the time it is on screen wants [applyOverride]: writing the base from a screen leaves
     * that value behind when the screen is gone.
     *
     * Applying a base equal to the current one is a no-op.
     */
    public fun setBaseConfig(config: SystemBarsConfig)

    /**
     * Reads the current base and replaces it with the result of [transform], atomically.
     *
     * Use this instead of `setBaseConfig(currentConfig.copy(...))` when two writers can run at
     * once: the read and the write in that expression are two separate steps, and the second
     * writer's copy can be built from a base the first writer has already replaced. Here
     * [transform] is retried against the winning value instead, so no axis is lost.
     *
     * [transform] must be pure — it may run more than once.
     */
    public fun updateBaseConfig(transform: (SystemBarsConfig) -> SystemBarsConfig)

    /**
     * Pushes [override] onto the top of the layer stack and returns the handle that owns it.
     *
     * The layer's position is fixed at the moment of this call, so the last screen to push wins
     * any axis it shares with an earlier one. Whoever calls this **must** release the handle;
     * `SystemBarsEffect` does it for you when a composable leaves composition.
     */
    public fun applyOverride(override: SystemBarsOverride): SystemBarsOverrideHandle

    /**
     * Drops every override, resets the base to [SystemBarsConfig] defaults, and detaches from the
     * platform (on Android, unsubscribes from the activity lifecycle).
     *
     * A controller that lives as long as the process never needs this. It exists for tests and for
     * a host that tears down its whole object graph without ending the process. Idempotent; the
     * controller must not be used afterwards.
     */
    public fun release()
}

/**
 * The right to one layer on a [SystemBarsController]'s stack, returned by
 * [SystemBarsController.applyOverride].
 *
 * Losing a handle without releasing it pins that layer for the lifetime of the controller, and
 * every axis it claims stays claimed. Prefer `SystemBarsEffect`, which ties the handle to a
 * composition and cannot forget.
 */
public interface SystemBarsOverrideHandle {

    /**
     * Replaces this layer's override **in place**, keeping its position in the stack.
     *
     * That distinction matters: releasing and re-pushing would move the layer to the top and let a
     * screen quietly overtake one that pushed after it. A screen whose override changes over time
     * (a scroll-driven icon style, say) should always go through here.
     *
     * A no-op after [release].
     */
    public fun update(override: SystemBarsOverride)

    /**
     * Removes this layer. The axes it claimed fall back to whatever the layers underneath say
     * *now* — not to what they said when the layer was pushed.
     *
     * Idempotent.
     */
    public fun release()
}
