package io.github.jamal_wia.kmptoolkit.permission

/**
 * Where a [PermissionRequestFlow] currently stands.
 *
 * Three of these six states are **questions asked of you**: [AwaitingRationale] and
 * [AwaitingSettings] mean the flow has stopped and will not move until your UI shows something and
 * reports back, and [Requesting] means the OS has the screen. The other three — [Idle], [Granted],
 * [Denied] — are resting points where nothing is pending.
 *
 * The states carry no text, and deliberately so: *whether* to explain yourself is a platform fact
 * this module can determine, *what to say* is copy the consuming app owns. See
 * `docs/01-architecture.md`.
 */
public sealed interface PermissionFlowState {

    /**
     * Nothing is pending, and nothing is known to block a request.
     *
     * The starting state, and also where [PermissionRequestFlow.refresh] lands when the OS reports
     * the permission can simply be asked for again — including after the user revoked it in
     * settings, or after Android auto-reset it for an unused app.
     */
    public data object Idle : PermissionFlowState

    /**
     * The system dialog is on screen. Show nothing of your own, and do not drive the flow further
     * until it resolves.
     */
    public data object Requesting : PermissionFlowState

    /**
     * The OS wants the user told why this permission is needed before it is asked for again.
     *
     * Show your own explanation, then call [PermissionRequestFlow.rationaleAcknowledged] if the
     * user agreed to continue, or [PermissionRequestFlow.rationaleDismissed] if they did not. The
     * flow stays here until one of those arrives.
     */
    public data object AwaitingRationale : PermissionFlowState

    /**
     * The permission is permanently denied: requesting it again would show nothing at all.
     *
     * Show your own prompt offering a trip to system settings, then call
     * [PermissionRequestFlow.openSettings] or [PermissionRequestFlow.settingsDeclined]. After the
     * user comes back from settings, call [PermissionRequestFlow.refresh] — the flow is not told
     * what happened there.
     */
    public data object AwaitingSettings : PermissionFlowState

    /** The permission is granted. Proceed with whatever needed it. */
    public data object Granted : PermissionFlowState

    /**
     * The user declined, and no further step of this flow can change that right now.
     *
     * Reachable three ways: refusing the system dialog, dismissing your rationale, or declining
     * the settings prompt. It is a decision made in *this* flow rather than a fact about the OS,
     * which is why [PermissionRequestFlow.refresh] replaces it with whatever the OS now says.
     */
    public data object Denied : PermissionFlowState
}
