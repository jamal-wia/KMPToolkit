package io.github.jamal_wia.kmptoolkit.permission

/**
 * The platform seam: one permission checked, one permission requested, one trip to system
 * settings.
 *
 * This is the type shared code depends on. The concrete instance is built in platform code —
 * `createPermissionHandler(context, host, activityAccess, storage)` on Android,
 * `createPermissionHandler()` on iOS — because the two platforms genuinely need different things
 * to construct it, which is why there is no `expect fun` here (see `docs/01-architecture.md`).
 *
 * **Contract:**
 * - **Nothing throws.** A missing activity, a chooser that cannot be launched, a settings screen
 *   no device app handles — all of it comes back as a [PermissionStatus] or as `false`.
 * - **[check] never shows UI.** It is safe to call on every screen entry, in a loop, from a
 *   composition. It is suspending only because iOS answers the notification question
 *   asynchronously; on Android it returns without suspending.
 * - **[request] shows the system dialog at most once**, and returns only after the user has
 *   answered it. When the current status is already [PermissionStatus.Granted] or
 *   [PermissionStatus.PermanentlyDenied] it shows nothing and returns that status, because in both
 *   cases the OS would show nothing either.
 * - **One request at a time.** Neither platform will show two permission dialogs at once. Drive a
 *   handler from a single coroutine; two concurrent [request] calls for different permissions are
 *   not serialized for you.
 * - **Cancelling the coroutine that called [request] abandons the result**, not the dialog: the
 *   system dialog stays on screen and the user's answer lands in the OS regardless. The next
 *   [check] sees it.
 *
 * Most callers should not use this directly — [PermissionRequestFlow] wraps it in the
 * check → rationale → request → settings decision that a real screen needs.
 */
public interface PermissionHandler {

    /**
     * The current status of [permission], without showing anything to the user.
     *
     * @return [PermissionStatus.Granted] when the capability is usable right now. See
     *   [PermissionStatus] for what the other three mean and how they differ per platform.
     */
    public suspend fun check(permission: Permission): PermissionStatus

    /**
     * Shows the system permission dialog for [permission] and suspends until the user answers.
     *
     * @return the status after the answer. It can still be [PermissionStatus.Denied] or
     *   [PermissionStatus.PermanentlyDenied]; a request is a question, not an outcome.
     */
    public suspend fun request(permission: Permission): PermissionStatus

    /**
     * Opens this app's page in the system settings, where the user can grant a permanently denied
     * permission by hand.
     *
     * The app is backgrounded by this; nothing tells you what the user did there. Re-run [check]
     * — or [PermissionRequestFlow.refresh] — when your screen comes back to the foreground.
     *
     * @return `false` when the settings screen could not be opened at all, which is rare enough to
     *   be a device oddity rather than a case to design a UI around. `true` means the OS accepted
     *   the request, not that the user changed anything.
     */
    public fun openAppSettings(): Boolean
}
