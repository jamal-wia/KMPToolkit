package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * Claims the axes of [override] on [controller] for as long as this composable is in composition,
 * and gives them back when it leaves.
 *
 * This is the intended way to use [SystemBarsController] from a screen. Put it anywhere inside the
 * screen's composable:
 *
 * ```kotlin
 * @Composable
 * fun PhotoViewerScreen(controller: SystemBarsController) {
 *     SystemBarsEffect(controller, SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
 *     // ...
 * }
 * ```
 *
 * Navigating away releases the claim, and the status bar returns to whatever the layers underneath
 * say **at that moment** — the app theme, or another screen that is still on screen and has its own
 * claim. Nothing is snapshotted and written back, so this cannot undo a theme change that happened
 * while the screen was open.
 *
 * Changing [override] across recompositions updates the existing layer in place and does not
 * re-order it against other layers.
 *
 * Two effects composed at the same time are ordered by composition: the one composed later wins any
 * axis they both claim, and axes only one of them claims are unaffected. Composing this effect
 * twice in the *same* composable is not useful — merge them into one override.
 */
@Composable
public fun SystemBarsEffect(
    controller: SystemBarsController,
    override: SystemBarsOverride,
) {
    // The handle is created in a DisposableEffect rather than in `remember`, so a composition that
    // is started and then abandoned cannot leave a layer pinned on the controller: effects only run
    // for compositions that were actually applied, and every one that runs is disposed.
    val holder: HandleHolder = remember(controller) { HandleHolder() }
    DisposableEffect(controller) {
        val handle: SystemBarsOverrideHandle = controller.applyOverride(SystemBarsOverride.None)
        holder.handle = handle
        onDispose {
            holder.handle = null
            handle.release()
        }
    }
    // Runs after the DisposableEffect above in the same apply pass — Compose executes both in
    // declaration order — so the handle is always in place by the time this needs it. Pushing the
    // override from here rather than keying the DisposableEffect on it is what keeps a changed
    // override from jumping to the top of the stack.
    SideEffect { holder.handle?.update(override) }
}

/**
 * Convenience overload naming the axes directly. `null` leaves an axis to whoever owns it — see
 * [SystemBarsOverride].
 */
@Composable
public fun SystemBarsEffect(
    controller: SystemBarsController,
    statusBarIcons: SystemBarIconStyle? = null,
    navigationBarIcons: SystemBarIconStyle? = null,
    visibility: SystemBarsVisibility? = null,
) {
    val override: SystemBarsOverride = remember(statusBarIcons, navigationBarIcons, visibility) {
        SystemBarsOverride(
            statusBarIcons = statusBarIcons,
            navigationBarIcons = navigationBarIcons,
            visibility = visibility,
        )
    }
    SystemBarsEffect(controller = controller, override = override)
}

/**
 * Applies [controller]'s current configuration to a Compose surface that renders into its own
 * platform window — `Dialog`, `Popup`, `ModalBottomSheet`, `BasicAlertDialog`.
 *
 * On Android such a surface gets a window, and therefore an insets controller, of its own; the
 * appearance the controller set on the activity's window never reaches it. Without this the bars
 * over an open bottom sheet revert to the platform default — dark icons — which is invisible in a
 * light app and unreadable in a dark one. Call it once, at the top of the dialog's content lambda.
 *
 * No-op on iOS, where a sheet shares the app's one status bar and the controller already drives it.
 */
@Composable
public fun DialogWindowSystemBarsEffect(controller: SystemBarsController) {
    val config: SystemBarsConfig by controller.config.collectAsState()
    applyDialogWindowSystemBars(config)
}

/** Holds the current handle for [SystemBarsEffect]; not snapshot state, nothing recomposes on it. */
private class HandleHolder {
    var handle: SystemBarsOverrideHandle? = null
}

@Composable
internal expect fun applyDialogWindowSystemBars(config: SystemBarsConfig)
