package io.github.jamal_wia.kmptoolkit.permission

/**
 * Handler for [SpecialPermission]s — access grants with no in-app request dialog, toggled only from
 * a dedicated system Settings screen. Contrast [PermissionHandler], which drives the runtime
 * permission dialogs.
 *
 * Two operations per permission, mirroring the split described on [SpecialPermission]:
 * [isGranted] answers the permission's own "granted?" check, and [requestViaSettings] opens the
 * relevant Settings screen. There is no result callback for the latter — re-check with [isGranted]
 * once the user returns to your app.
 *
 * Returns [Boolean], not [PermissionStatus]: a special access is simply on or off, with none of the
 * "show rationale" / "permanently denied" nuance a runtime permission has.
 *
 * The concrete instance is built in platform code — `createSpecialPermissionHandler(context, logger)`
 * on Android, `createSpecialPermissionHandler()` on iOS.
 */
public interface SpecialPermissionHandler {

    /** Whether [permission] is currently granted. On a platform where it does not apply, always `true`. */
    public fun isGranted(permission: SpecialPermission): Boolean

    /**
     * Opens the system Settings screen where the user grants [permission]. Best-effort and
     * non-blocking: a no-op where the permission does not apply, or the screen is unavailable.
     *
     * @return whether the screen was actually opened. `false` means nothing was launched (the
     *   permission is not applicable on this platform, or the launch failed) — use it to avoid
     *   recording a "prompt shown" that never actually appeared.
     */
    public fun requestViaSettings(permission: SpecialPermission): Boolean
}
