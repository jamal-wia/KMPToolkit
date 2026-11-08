package io.github.jamal_wia.kmptoolkit.permission

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The check → rationale → request → settings decision for one [Permission], as an explicit state
 * machine and nothing else.
 *
 * A [PermissionController] alone leaves the interesting part to the caller, and in practice the caller
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
 * val flow = PermissionRequestFlow(CommonPermission.Microphone, controller)
 *
 * // On a "record" tap:
 * when (flow.start()) {
 *     PermissionFlowState.Granted, PermissionFlowState.Limited -> startRecording()
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
 * | any but `Requesting` | `start()`, status `Limited` | `Limited` |
 * | any but `Requesting` | `start()`, status `PermanentlyDenied` | `AwaitingSettings` |
 * | any but `Requesting` | `start()`, status `Restricted` or `Unavailable` | `Unavailable` |
 * | any but `Requesting` | `start()`, status `Denied(explainFirst = true)` | `AwaitingRationale` |
 * | any but `Requesting` | `start()`, status `Denied(explainFirst = false)` or `NotDetermined` | `Requesting`, then the request outcome |
 * | `Requesting` | request granted | `Granted` |
 * | `Requesting` | request limited | `Limited` |
 * | `Requesting` | request permanently denied | `AwaitingSettings` |
 * | `Requesting` | request restricted or unavailable | `Unavailable` |
 * | `Requesting` | request denied or unanswered | `Denied` |
 * | `Requesting` | the calling coroutine is cancelled, or the controller throws | `Idle` (nothing was decided; the exception reaches the caller; `refresh()` reads the real choice) |
 * | `AwaitingRationale` | `rationaleAcknowledged()` | `Requesting`, then the request outcome |
 * | `AwaitingRationale` | `rationaleDismissed()` | `Denied` |
 * | `AwaitingSettings` | `openSettings()` | `AwaitingSettings` (the app is backgrounded) |
 * | `AwaitingSettings` | `settingsDeclined()` | `Denied` |
 * | any but `Requesting` | `refresh()` | the OS's current view: `Granted` / `Limited` / `AwaitingSettings` / `Unavailable` / `AwaitingRationale` / `Idle` |
 * | any but `Requesting` | `reset()` | `Idle` |
 *
 * Every method that does not apply to the current state is a **no-op that returns the unchanged
 * state**, rather than an exception. A permission flow is driven by user taps and lifecycle
 * callbacks, both of which arrive out of order and twice — a double-tapped button must not crash
 * an app.
 *
 * **Drive one flow from one coroutine**, normally the UI one. A [refresh] on resume and a [start] on
 * tap are two coroutines in practice, and the flow tolerates that: every transition that follows a
 * suspension publishes only if the state is still the one it started from (a compare-and-set on the
 * identity of the last publish, not on its value, so a state that was left and came back — a `start()`
 * that went `Requesting` and was cancelled back to `Idle` — is still a newer state), so a stale result
 * never overwrites a live [PermissionFlowState.Requesting], its outcome, or the state a round trip
 * returned to — the newer state wins and is what the call returns. The synchronous transitions ([reset], [rationaleDismissed],
 * [settingsDeclined]) check and write in one atomic step too, so none of them can overwrite a
 * `Requesting` published at the same moment. The controller underneath serializes requests, so no two
 * dialogs ever overlap. If the calling coroutine is cancelled while the dialog is up, or the
 * controller throws, the flow goes
 * back to [PermissionFlowState.Idle] (nothing was decided) instead of staying `Requesting` forever;
 * the next [refresh] reads what the user actually chose.
 *
 * The instance is cheap and holds no platform resource — there is nothing to release. Keep one per
 * screen, or build one per tap; both are fine.
 *
 * @param permission the single permission this flow is about — common or platform-specific. A flow
 *   is deliberately not a multi-permission batch: the rationale and settings decisions differ per
 *   permission, and a batch would have to collapse them into one answer. For a batch without those
 *   decisions, call [PermissionController.request] with a collection.
 * @param controller the platform engine it drives.
 */
public class PermissionRequestFlow(
    public val permission: Permission,
    private val controller: PermissionController,
) {

    /**
     * What is published: the state plus the identity of this very publish. A plain class, not a data
     * class, so `compareAndSet` on the flow compares identity and a round trip back to an equal state
     * is still a different value (the ABA case a value compare cannot see). D-21/D-24/D-25: a
     * MutableStateFlow identity CAS, no Atomic*.
     */
    private class Published(val state: PermissionFlowState)

    private val mutableState: MutableStateFlow<Published> =
        MutableStateFlow(Published(PermissionFlowState.Idle))

    /**
     * The current state, observable.
     *
     * Starts at [PermissionFlowState.Idle] — construction asks the OS nothing, so building a flow
     * is free and safe outside a coroutine. Call [refresh] or [start] to find out where you
     * actually stand.
     */
    public val state: StateFlow<PermissionFlowState> = StateOf(mutableState)

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
        val before: Published = mutableState.value
        if (before.state == PermissionFlowState.Requesting) return PermissionFlowState.Requesting
        return when (val status: PermissionStatus = controller.check(permission)) {
            is PermissionStatus.Granted -> moveFrom(before, PermissionFlowState.Granted)
            is PermissionStatus.Limited -> moveFrom(before, PermissionFlowState.Limited)
            is PermissionStatus.PermanentlyDenied -> moveFrom(before, PermissionFlowState.AwaitingSettings)
            is PermissionStatus.Restricted, is PermissionStatus.Unavailable ->
                moveFrom(before, PermissionFlowState.Unavailable)

            is PermissionStatus.Denied ->
                if (status.explainFirst) {
                    moveFrom(before, PermissionFlowState.AwaitingRationale)
                } else {
                    prompt(before)
                }

            is PermissionStatus.NotDetermined -> prompt(before)
        }
    }

    /**
     * The user has seen your explanation and agreed to be asked: shows the system dialog.
     *
     * A no-op returning the unchanged state unless the flow is in
     * [PermissionFlowState.AwaitingRationale].
     */
    public suspend fun rationaleAcknowledged(): PermissionFlowState {
        val before: Published = mutableState.value
        return if (before.state == PermissionFlowState.AwaitingRationale) prompt(before) else before.state
    }

    /**
     * The user dismissed your explanation without agreeing: the flow ends at
     * [PermissionFlowState.Denied], with no system dialog shown.
     *
     * A no-op returning the unchanged state unless the flow is in
     * [PermissionFlowState.AwaitingRationale].
     */
    public fun rationaleDismissed(): PermissionFlowState =
        moveWhen(PermissionFlowState.Denied) { it == PermissionFlowState.AwaitingRationale }

    /**
     * Sends the user to the most specific system settings screen for [permission] —
     * [PermissionController.openSettings].
     *
     * The state stays [PermissionFlowState.AwaitingSettings], because nothing has been decided —
     * the app is merely in the background now. Call [refresh] when your screen returns to the
     * foreground to find out what the user did.
     *
     * @return `false` when the flow is not in [PermissionFlowState.AwaitingSettings] (nothing is
     *   opened), or when the platform could not open the settings screen at all.
     */
    public fun openSettings(): Boolean =
        mutableState.value.state == PermissionFlowState.AwaitingSettings && controller.openSettings(permission)

    /**
     * The user declined the trip to settings: the flow ends at [PermissionFlowState.Denied].
     *
     * A no-op returning the unchanged state unless the flow is in
     * [PermissionFlowState.AwaitingSettings].
     */
    public fun settingsDeclined(): PermissionFlowState =
        moveWhen(PermissionFlowState.Denied) { it == PermissionFlowState.AwaitingSettings }

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
        val before: Published = mutableState.value
        if (before.state == PermissionFlowState.Requesting) return PermissionFlowState.Requesting
        return moveFrom(
            before,
            when (val status: PermissionStatus = controller.check(permission)) {
                is PermissionStatus.Granted -> PermissionFlowState.Granted
                is PermissionStatus.Limited -> PermissionFlowState.Limited
                is PermissionStatus.PermanentlyDenied -> PermissionFlowState.AwaitingSettings
                is PermissionStatus.Restricted, is PermissionStatus.Unavailable -> PermissionFlowState.Unavailable
                is PermissionStatus.Denied ->
                    if (status.explainFirst) {
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
        moveWhen(PermissionFlowState.Idle) { it != PermissionFlowState.Requesting }

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
    private suspend fun prompt(from: Published): PermissionFlowState {
        // Only from the state the caller observed: if another coroutine moved the flow while the
        // caller was suspended in check(), theirs is the newer word and no second prompt starts.
        val requesting = Published(PermissionFlowState.Requesting)
        if (!mutableState.compareAndSet(from, requesting)) return mutableState.value.state
        val answer: PermissionStatus = try {
            controller.request(permission)
        } catch (failure: Throwable) {
            // The caller went away with the dialog up, or the controller failed: either way nothing was
            // decided and nobody is left to receive an answer. Left at Requesting, every method would
            // refuse to move the flow for the life of its holder. CAS, so a concurrent writer is not
            // overwritten; the failure still reaches the caller.
            mutableState.compareAndSet(requesting, Published(PermissionFlowState.Idle))
            throw failure
        }
        // CAS from Requesting, like every other transition after a suspension: a state written in the
        // meantime is the newer word and is returned instead.
        return moveFrom(
            requesting,
            when (answer) {
                is PermissionStatus.Granted -> PermissionFlowState.Granted
                is PermissionStatus.Limited -> PermissionFlowState.Limited
                is PermissionStatus.PermanentlyDenied -> PermissionFlowState.AwaitingSettings
                is PermissionStatus.Restricted, is PermissionStatus.Unavailable -> PermissionFlowState.Unavailable
                is PermissionStatus.Denied, is PermissionStatus.NotDetermined ->
                    PermissionFlowState.Denied
            },
        )
    }

    /** Publishes [next] only if nothing was published since [before]; otherwise returns the newer state. */
    private fun moveFrom(before: Published, next: PermissionFlowState): PermissionFlowState =
        if (mutableState.compareAndSet(before, Published(next))) next else mutableState.value.state

    /**
     * Atomically moves to [next] when the *current* state satisfies [allowed]; otherwise returns the
     * state as it is. The check and the write are one compare-and-set, so a concurrent [start] that
     * has just published `Requesting` can never be overwritten by a transition that checked earlier.
     */
    private inline fun moveWhen(next: PermissionFlowState, allowed: (PermissionFlowState) -> Boolean): PermissionFlowState {
        while (true) {
            val current: Published = mutableState.value
            if (!allowed(current.state)) return current.state
            if (mutableState.compareAndSet(current, Published(next))) return next
        }
    }

    /**
     * The read-only view of [mutableState] as plain states. It re-emits only when the state itself
     * changes, so a publish of an equal state (which has a new identity) is not seen as a change by
     * collectors, exactly like a `StateFlow` of the states would behave.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class StateOf(private val source: StateFlow<Published>) : StateFlow<PermissionFlowState> {
        override val value: PermissionFlowState get() = source.value.state

        override val replayCache: List<PermissionFlowState> get() = listOf(value)

        override suspend fun collect(collector: FlowCollector<PermissionFlowState>): Nothing {
            var last: PermissionFlowState? = null
            source.collect { published: Published ->
                val next: PermissionFlowState = published.state
                if (next != last) {
                    last = next
                    collector.emit(next)
                }
            }
        }
    }
}
