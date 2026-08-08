package io.github.jamal_wia.kmptoolkit.permission

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The check → rationale → request → settings decision for one [Permission], as an explicit state
 * machine and nothing else.
 *
 * A [PermissionHandler] alone leaves the interesting part to the caller, and in practice the caller
 * drops it: code calls `request()`, gets [PermissionStatus.PermanentlyDenied] back, and shows
 * nothing — so the user taps a button that will never work again for as long as the app is
 * installed. This type is that missing part, made testable by having no UI in it at all.
 *
 * **Headless by design.** It decides *what* the user should be shown; it never decides what the
 * words are. Observe [state], render your own dialog for [PermissionFlowState.AwaitingRationale]
 * and [PermissionFlowState.AwaitingSettings], and report the user's answer back through the
 * matching method. See `docs/kmptoolkit-permission/03-guide.md`.
 *
 * ```kotlin
 * val flow = PermissionRequestFlow(Permission.MICROPHONE, handler)
 *
 * // On a "record" tap:
 * when (flow.start()) {
 *     PermissionFlowState.Granted -> startRecording()
 *     PermissionFlowState.AwaitingRationale -> showWhyWeNeedTheMic()
 *     PermissionFlowState.AwaitingSettings -> offerToOpenSettings()
 *     else -> Unit
 * }
 * ```
 *
 * **The whole transition table**, which is also what the test suite pins:
 *
 * | From | Event | To |
 * |---|---|---|
 * | any but `Requesting` | `start()`, status `Granted` | `Granted` |
 * | any but `Requesting` | `start()`, status `PermanentlyDenied` | `AwaitingSettings` |
 * | any but `Requesting` | `start()`, status `Denied(rationale = true)` | `AwaitingRationale` |
 * | any but `Requesting` | `start()`, status `Denied(rationale = false)` or `NotDetermined` | `Requesting`, then the request outcome |
 * | `Requesting` | request granted | `Granted` |
 * | `Requesting` | request permanently denied | `AwaitingSettings` |
 * | `Requesting` | request denied or unanswered | `Denied` |
 * | `AwaitingRationale` | `rationaleAcknowledged()` | `Requesting`, then the request outcome |
 * | `AwaitingRationale` | `rationaleDismissed()` | `Denied` |
 * | `AwaitingSettings` | `openSettings()` | `AwaitingSettings` (the app is backgrounded) |
 * | `AwaitingSettings` | `settingsDeclined()` | `Denied` |
 * | any but `Requesting` | `refresh()` | the OS's current view: `Granted` / `AwaitingSettings` / `AwaitingRationale` / `Idle` |
 * | any but `Requesting` | `reset()` | `Idle` |
 *
 * Every method that does not apply to the current state is a **no-op that returns the unchanged
 * state**, rather than an exception. A permission flow is driven by user taps and lifecycle
 * callbacks, both of which arrive out of order and twice — a double-tapped button must not crash
 * an app.
 *
 * **Drive one flow from one coroutine**, normally the UI one. It holds mutable state and does not
 * synchronize; two coroutines calling [start] concurrently can both get past the
 * [PermissionFlowState.Requesting] guard. This mirrors the single-dialog-at-a-time reality of both
 * platforms rather than papering over it.
 *
 * The instance is cheap and holds no platform resource — there is nothing to release. Keep one per
 * screen, or build one per tap; both are fine.
 *
 * @param permission the single permission this flow is about. A flow is deliberately not a
 *   multi-permission batch: the rationale and settings decisions differ per permission, and a
 *   batch would have to collapse them into one answer.
 * @param handler the platform seam it drives.
 */
public class PermissionRequestFlow(
    public val permission: Permission,
    private val handler: PermissionHandler,
) {

    private val mutableState: MutableStateFlow<PermissionFlowState> =
        MutableStateFlow(PermissionFlowState.Idle)

    /**
     * The current state, observable.
     *
     * Starts at [PermissionFlowState.Idle] — construction asks the OS nothing, so building a flow
     * is free and safe outside a coroutine. Call [refresh] or [start] to find out where you
     * actually stand.
     */
    public val state: StateFlow<PermissionFlowState> = mutableState.asStateFlow()

    /**
     * Runs the decision from the top: check the OS, then either finish immediately, ask you for a
     * rationale, or show the system dialog.
     *
     * Safe to call for an already-granted permission — it returns [PermissionFlowState.Granted]
     * without showing anything. Safe to call again after a [PermissionFlowState.Denied]: the OS
     * decides whether that produces another dialog or a rationale.
     *
     * @return the state reached, identical to [state]'s value when this returns.
     */
    public suspend fun start(): PermissionFlowState {
        if (mutableState.value == PermissionFlowState.Requesting) return PermissionFlowState.Requesting
        return when (val status: PermissionStatus = handler.check(permission)) {
            is PermissionStatus.Granted -> moveTo(PermissionFlowState.Granted)
            is PermissionStatus.PermanentlyDenied -> moveTo(PermissionFlowState.AwaitingSettings)
            is PermissionStatus.Denied ->
                if (status.shouldShowRationale) {
                    moveTo(PermissionFlowState.AwaitingRationale)
                } else {
                    prompt()
                }

            is PermissionStatus.NotDetermined -> prompt()
        }
    }

    /**
     * The user has seen your explanation and agreed to be asked: shows the system dialog.
     *
     * A no-op returning the unchanged state unless the flow is in
     * [PermissionFlowState.AwaitingRationale].
     */
    public suspend fun rationaleAcknowledged(): PermissionFlowState =
        if (mutableState.value == PermissionFlowState.AwaitingRationale) prompt() else mutableState.value

    /**
     * The user dismissed your explanation without agreeing: the flow ends at
     * [PermissionFlowState.Denied], with no system dialog shown.
     *
     * A no-op returning the unchanged state unless the flow is in
     * [PermissionFlowState.AwaitingRationale].
     */
    public fun rationaleDismissed(): PermissionFlowState =
        if (mutableState.value == PermissionFlowState.AwaitingRationale) {
            moveTo(PermissionFlowState.Denied)
        } else {
            mutableState.value
        }

    /**
     * Sends the user to the system settings page for this app.
     *
     * The state stays [PermissionFlowState.AwaitingSettings], because nothing has been decided —
     * the app is merely in the background now. Call [refresh] when your screen returns to the
     * foreground to find out what the user did.
     *
     * @return `false` when the flow is not in [PermissionFlowState.AwaitingSettings] (nothing is
     *   opened), or when the platform could not open the settings screen at all.
     */
    public fun openSettings(): Boolean =
        mutableState.value == PermissionFlowState.AwaitingSettings && handler.openAppSettings()

    /**
     * The user declined the trip to settings: the flow ends at [PermissionFlowState.Denied].
     *
     * A no-op returning the unchanged state unless the flow is in
     * [PermissionFlowState.AwaitingSettings].
     */
    public fun settingsDeclined(): PermissionFlowState =
        if (mutableState.value == PermissionFlowState.AwaitingSettings) {
            moveTo(PermissionFlowState.Denied)
        } else {
            mutableState.value
        }

    /**
     * Re-derives the state from what the OS says right now, without ever showing a dialog.
     *
     * This is the method for coming back to the foreground — after a settings trip, after the app
     * was backgrounded, after anything at all. It is what catches a permission the user revoked
     * while you were away, and a permission they granted in settings without telling you.
     *
     * It restates the OS's view, which means it **clears** a [PermissionFlowState.Denied] reached
     * earlier in this flow: `Denied` records a choice the user made in your UI, and the OS has no
     * memory of it. A permission that can simply be asked for again lands back on
     * [PermissionFlowState.Idle].
     *
     * A no-op while the system dialog is up ([PermissionFlowState.Requesting]).
     */
    public suspend fun refresh(): PermissionFlowState {
        if (mutableState.value == PermissionFlowState.Requesting) return PermissionFlowState.Requesting
        return moveTo(
            when (val status: PermissionStatus = handler.check(permission)) {
                is PermissionStatus.Granted -> PermissionFlowState.Granted
                is PermissionStatus.PermanentlyDenied -> PermissionFlowState.AwaitingSettings
                is PermissionStatus.Denied ->
                    if (status.shouldShowRationale) {
                        PermissionFlowState.AwaitingRationale
                    } else {
                        PermissionFlowState.Idle
                    }

                is PermissionStatus.NotDetermined -> PermissionFlowState.Idle
            },
        )
    }

    /**
     * Forgets everything this flow decided and returns to [PermissionFlowState.Idle], asking the
     * OS nothing.
     *
     * For reusing one flow across a screen that can be entered repeatedly. A no-op while the system
     * dialog is up ([PermissionFlowState.Requesting]) — abandoning a flow whose dialog is still on
     * screen would leave the answer nowhere to land.
     */
    public fun reset(): PermissionFlowState =
        if (mutableState.value == PermissionFlowState.Requesting) {
            PermissionFlowState.Requesting
        } else {
            moveTo(PermissionFlowState.Idle)
        }

    /**
     * Shows the system dialog and maps the answer.
     *
     * [PermissionFlowState.Requesting] is published *before* suspending, so an observer sees the
     * dialog go up rather than only its result, and so a second [start] on the same coroutine is
     * refused while one is in flight.
     *
     * A request that comes back [PermissionStatus.Denied] lands on [PermissionFlowState.Denied]
     * even when the OS now asks for a rationale — going straight back to
     * [PermissionFlowState.AwaitingRationale] would loop the user through an explanation for a
     * dialog they just answered. The next [start] picks that rationale up.
     */
    private suspend fun prompt(): PermissionFlowState {
        moveTo(PermissionFlowState.Requesting)
        return moveTo(
            when (handler.request(permission)) {
                is PermissionStatus.Granted -> PermissionFlowState.Granted
                is PermissionStatus.PermanentlyDenied -> PermissionFlowState.AwaitingSettings
                is PermissionStatus.Denied, is PermissionStatus.NotDetermined ->
                    PermissionFlowState.Denied
            },
        )
    }

    private fun moveTo(next: PermissionFlowState): PermissionFlowState {
        mutableState.value = next
        return next
    }
}
