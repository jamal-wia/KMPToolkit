package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.Composable

/**
 * Creates the JVM (desktop) [SystemBarsController].
 *
 * A desktop window has no OS status or navigation bar, so there is nothing to push a configuration
 * onto. The controller still tracks one, rather than being absent on this target: a Compose tree
 * shared with a phone build claims and releases overrides the same way on both, and a caller reading
 * [SystemBarsController.config] gets a consistent answer instead of needing a platform check of its
 * own. The layer stack — per-axis ownership, restore-by-removal — behaves identically here; only the
 * final push to the window is a no-op. It is the same controller
 * [createHeadlessSystemBarsController] returns — on this target it is not a stand-in for a window,
 * it is the whole truth.
 *
 * @param initialConfig the base configuration to start from, before your theme sets one.
 */
public fun createSystemBarsController(
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
): SystemBarsController = HeadlessSystemBarsController(initialConfig)

@Composable
internal actual fun applyDialogWindowSystemBars(config: SystemBarsConfig) {
    // Nothing to do, for the same reason as the iOS actual: a desktop dialog window has no system
    // bars of its own to bring in line with the rest of the app.
}
