package io.github.jamal_wia.kmptoolkit.permission

import kotlinx.coroutines.flow.Flow

/**
 * The one engine for every [Permission]: check it, request it, watch it, open its settings.
 *
 * This is the type shared code depends on. The concrete instance is built in platform code —
 * `createPermissionController(context, storage, …)` on Android, `createPermissionController()` on
 * iOS — because the two platforms need different things to construct it, which is why there is no
 * `expect fun` here (see `docs/01-architecture.md`).
 *
 * Every kind of permission goes through the same four calls: a runtime permission, an Android special
 * access granted on a Settings screen, an Android role. Only the system UI a request shows differs —
 * a dialog, a Settings screen, a role chooser — and a request returns once that UI has been answered
 * and the status re-read, whichever it is.
 *
 * **Contract:**
 * - **Nothing throws.** A missing activity, a screen no device app handles, a platform call that
 *   fails — all of it comes back as a [PermissionStatus] or as `false`.
 * - **[check] never shows UI**, and is safe on the main thread. It suspends: on Android it reads the
 *   controller's stored flags from a `KeyValueStorage`, which moves to an I/O dispatcher, and on iOS
 *   some authorizations answer only asynchronously. Code that needs a status without suspending —
 *   on every frame, from a synchronous callback — collects [observe] into a `StateFlow` with
 *   `stateIn` rather than blocking on [check].
 * - **[request] shows the system UI at most once**, and returns only after it has been answered.
 *   When the current status is [PermissionStatus.Granted], [PermissionStatus.PermanentlyDenied],
 *   [PermissionStatus.Restricted] or [PermissionStatus.Unavailable] it shows nothing and returns that
 *   status, because the OS would show nothing either.
 * - **Requests are serialized.** Neither platform shows two permission prompts at once, so a request
 *   made while another is in flight — from any coroutine, for any permission — waits for it to finish
 *   before its own UI is shown.
 * - **Cancelling the coroutine that called [request] abandons the result**, not the UI: the dialog or
 *   Settings screen stays on screen and the user's answer lands in the OS regardless. The next
 *   [check], and every [observe], sees it.
 *
 * Most screens should not drive this directly — [PermissionRequestFlow] wraps it in the
 * check → rationale → request → settings decision a real screen needs.
 */
public interface PermissionController {

    /**
     * The current status of [permission], without showing anything to the user.
     *
     * @return [PermissionStatus.Unavailable] for a permission this platform does not have, on this OS
     *   version or at all.
     */
    public suspend fun check(permission: Permission): PermissionStatus

    /**
     * Shows the system UI for [permission] once and suspends until it has been answered: the runtime
     * permission dialog; for a permission only Settings can grant, the Settings screen — returning
     * after the app is in the foreground again; for an Android role, the role dialog.
     *
     * @return the status after the answer, re-read from the OS. It can still be anything but
     *   [PermissionStatus.NotDetermined] after a dialog was shown; a request is a question, not an
     *   outcome.
     */
    public suspend fun request(permission: Permission): PermissionStatus

    /**
     * Requests several permissions as one step, and returns every one's final status.
     *
     * The Android runtime permissions among them that can still prompt share **one** system dialog,
     * as Android itself allows; everything else — background location, special accesses, roles, and
     * every iOS authorization — is requested one after another, in iteration order, each waiting for
     * the previous one. A permission listed twice is requested once.
     *
     * Cancellation mid-batch abandons the permissions not reached yet; the ones already answered are
     * in the OS and visible to [check] and [observe].
     */
    public suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus>

    /**
     * The status of [permission], now and whenever it may have changed.
     *
     * Emits the current status when collected, then again — only when it differs — at the points where
     * a platform can change it behind the app's back or where this controller changed it: on Android
     * every activity resume, on iOS every time the app becomes active, and on both after every request
     * through this controller that covered [permission]. Never shows UI.
     */
    public fun observe(permission: Permission): Flow<PermissionStatus>

    /**
     * Opens the most specific Settings screen there is for [permission]: the app's notification
     * settings for [CommonPermission.Notifications], a special access's own screen, the default-apps
     * screen for a role, and otherwise the app's own page in system settings.
     *
     * The app is backgrounded by this; nothing tells you what the user did there. [observe] — or
     * [PermissionRequestFlow.refresh] when the screen comes back — picks it up.
     *
     * @return `false` when no screen could be opened, which includes a [permission] this controller
     *   does not know. `true` means the OS accepted the request, not that the user changed anything —
     *   nor even that the page appeared: Android has blocked activity starts from the background
     *   silently since API 29.
     */
    public fun openSettings(permission: Permission): Boolean
}
