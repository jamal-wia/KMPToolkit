package io.github.jamal_wia.kmptoolkit.permission

/**
 * What the operating system currently thinks about one [Permission].
 *
 * Seven cases, because that is what it takes to decide the next move without asking the OS a second
 * question: whether you may proceed ([Granted], or [Limited] for a narrower grant), whether asking
 * again would show something ([NotDetermined], [Denied]), whether asking again would do nothing at all
 * ([PermanentlyDenied]), and whether the permission is out of the user's reach ([Restricted]) or does
 * not exist here at all ([Unavailable]). The distinction between [Denied] and [PermanentlyDenied] is
 * the one most hand-rolled permission code gets wrong: it is the difference between a prompt the user
 * sees and a button that silently does nothing.
 *
 * Nothing here is a message. Which words to put in front of a user for each case is the consuming
 * app's decision — see `docs/01-architecture.md`.
 *
 * **This set can grow in a minor release**, like the permission vocabulary: give an exhaustive `when`
 * over it an `else` branch where you do not care about every case.
 */
public sealed interface PermissionStatus {

    /** The app may use the capability now, in full. */
    public data object Granted : PermissionStatus

    /**
     * The app may use the capability, but narrower than it asked for: a user-selected subset of photos
     * or contacts, approximate rather than precise location, notifications delivered quietly
     * (provisional, iOS).
     *
     * Treat it as usable ([isUsable]); whether to offer the user a way to widen it is your call. A
     * request from here asks again where the platform has a dialog for it — Android's photo picker and
     * precise-location upgrade, iOS's full notification prompt — and otherwise returns [Limited] at
     * once; see `docs/kmptoolkit-permission/05-platform-notes.md` per entry.
     */
    public data object Limited : PermissionStatus

    /**
     * The user said no, and asking again would still show the system UI.
     *
     * @param explainFirst whether the platform advises explaining why the app needs this before asking
     *   again: on Android, after a refusal through the dialog; for an Android special access or role,
     *   always, because the request leaves the app; on iOS, for the one-time "always" location upgrade.
     */
    public data class Denied(public val explainFirst: Boolean = false) : PermissionStatus

    /**
     * Asking again would not show anything. Only a trip to system settings can change this.
     *
     * On Android this is a second refusal — the second "Don't allow" on Android 11+, "Don't ask again"
     * before it; a dialog the user merely dismissed is not one. On iOS it is any refusal.
     */
    public data object PermanentlyDenied : PermissionStatus

    /**
     * Never asked — the next request shows the system UI, as far as the app can tell.
     *
     * On Android the same answer also covers a dismissed dialog and a permission the user refused in
     * settings before the app ever asked: the platform reports both exactly like "never asked".
     */
    public data object NotDetermined : PermissionStatus

    /**
     * Withheld by something other than the user's own choice — parental controls, a device-management
     * profile, or an Android device policy that revoked it. The user may not be able to lift it, so a
     * request shows nothing and a settings trip may not help.
     */
    public data object Restricted : PermissionStatus

    /**
     * The permission does not exist here: not on this OS version, not on this device (a role the
     * device does not have), not declared in the app's manifest or `Info.plist`, or a type this
     * controller does not know. A request shows nothing.
     *
     * A permission missing from the manifest or `Info.plist` is a build mistake, and reported as one:
     * the controller logs it, and does not pretend the user refused.
     */
    public data object Unavailable : PermissionStatus
}

/** Whether this is [PermissionStatus.Granted] — the full grant. */
public val PermissionStatus.isGranted: Boolean
    get() = this is PermissionStatus.Granted

/**
 * Whether the capability can be used now: [PermissionStatus.Granted] or [PermissionStatus.Limited].
 * The check most call sites that are about to *use* the capability want.
 */
public val PermissionStatus.isUsable: Boolean
    get() = this is PermissionStatus.Granted || this is PermissionStatus.Limited

/**
 * Whether requesting this permission would put the system UI on screen.
 *
 * True for [PermissionStatus.NotDetermined] and [PermissionStatus.Denied]. False for
 * [PermissionStatus.Granted] (nothing to ask), [PermissionStatus.PermanentlyDenied] (asking is a
 * no-op), [PermissionStatus.Restricted] and [PermissionStatus.Unavailable]. Also false for
 * [PermissionStatus.Limited], whose re-request shows something on some platforms and entries and not
 * on others — see its KDoc. Use it to decide whether an "Enable X" button should prompt or open
 * settings.
 */
public val PermissionStatus.canPrompt: Boolean
    get() = this is PermissionStatus.NotDetermined || this is PermissionStatus.Denied
