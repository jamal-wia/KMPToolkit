package io.github.jamal_wia.kmptoolkit.permission

/** Creates the iOS [SpecialPermissionHandler]. */
public fun createSpecialPermissionHandler(): SpecialPermissionHandler = IOSSpecialPermissionHandler()

/**
 * iOS [SpecialPermissionHandler]: none of [SpecialPermission]'s special accesses exist on iOS, so
 * every one of them is always granted and there is never anything to open. Exact local notifications
 * need only the standard notification authorization ([Permission.NOTIFICATIONS]).
 */
internal class IOSSpecialPermissionHandler : SpecialPermissionHandler {

    override fun isGranted(permission: SpecialPermission): Boolean = true

    // Never reached from a caller that checks isGranted first (it is always true), and nothing is
    // ever opened — so always false.
    override fun requestViaSettings(permission: SpecialPermission): Boolean = false
}
