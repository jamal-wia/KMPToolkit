package io.github.jamal_wia.kmptoolkit.permission

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The platform seam: one permission checked, one permission requested, one trip to system
 * settings.
 *
 * This is the type shared code depends on. The concrete instance is built in platform code —
 * `createPermissionHandler(context, host, storage[, activityAccess])` or
 * `createPermissionHandlerWithLauncher(...)` on Android,
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
     * On Android the page is opened through a `SystemScreenLauncher` (from `kmptoolkit-activity`),
     * with one or more candidate screens, most specific first — currently only the app-details page.
     *
     * @return `false` when the settings screen could not be opened at all, which is rare enough to
     *   be a device oddity rather than a case to design a UI around. `true` means the OS accepted
     *   the request, not that the user changed anything — nor even that the page appeared: Android
     *   has blocked activity starts from the background silently since API 29.
     */
    public fun openAppSettings(): Boolean

    /**
     * The status of [permission], now and whenever it may have changed.
     *
     * Emits the current status when collected, then again — only when it differs — at the points where
     * a platform can change it behind the app's back or where this handler changed it:
     *
     * - **Android:** every activity resume (a trip to system settings, the system auto-resetting an
     *   unused app's permissions) and every [request] through this handler.
     * - **iOS:** every time the app becomes active, and every [request] through this handler.
     *
     * Never shows UI. The default implementation emits the current status once and completes, so a
     * handler written before this member existed still answers correctly for the moment it is asked.
     *
     * @since 1.5.0
     */
    public fun observe(permission: Permission): Flow<PermissionStatus> = flow { emit(check(permission)) }
}
