package io.github.jamal_wia.kmptoolkit.permission

/**
 * What the operating system currently thinks about one [Permission].
 *
 * Four cases, because four is what it takes to decide the next move without asking the OS a second
 * question: whether you may proceed ([Granted]), whether asking again would show a dialog
 * ([NotDetermined], [Denied]), and whether asking again would do nothing at all
 * ([PermanentlyDenied]). That last distinction is the one most hand-rolled permission code gets
 * wrong, and it is the difference between a prompt the user sees and a button that silently does
 * nothing.
 *
 * Nothing here is a message. Which words to put in front of a user for [Denied] versus
 * [PermanentlyDenied] is the consuming app's decision — see `docs/01-architecture.md`.
 */
public sealed interface PermissionStatus {

    /** The app may use the capability now. */
    public data object Granted : PermissionStatus

    /**
     * The user said no, and asking again would still show the system dialog.
     *
     * @param shouldShowRationale whether the OS is asking you to explain yourself first. Android
     *   sets this after the first refusal; iOS never does, because iOS shows its system dialog at
     *   most once and a refusal there is already final ([PermanentlyDenied]). Treat it as advice
     *   about *this* platform's dialog policy, not as a general "the user needs convincing".
     */
    public data class Denied(public val shouldShowRationale: Boolean = false) : PermissionStatus

    /**
     * Asking again would not show anything. Only a trip to system settings can change this.
     *
     * On Android this is a second refusal, or "Don't allow" on Android 11+; on iOS it is any
     * refusal, and also a restriction imposed by parental controls or an MDM profile, which the
     * user themselves cannot lift. That last case is why nothing in this module promises that a
     * settings trip *can* succeed — only that it is the only remaining path.
     */
    public data object PermanentlyDenied : PermissionStatus

    /** Never asked. The next request will show the system dialog. */
    public data object NotDetermined : PermissionStatus
}

/** Whether this is [PermissionStatus.Granted]. The one check most call sites actually want. */
public val PermissionStatus.isGranted: Boolean
    get() = this is PermissionStatus.Granted

/**
 * Whether requesting this permission would put a system dialog on screen.
 *
 * True for [PermissionStatus.NotDetermined] and [PermissionStatus.Denied]; false for
 * [PermissionStatus.Granted] (nothing to ask) and [PermissionStatus.PermanentlyDenied] (asking is
 * a no-op). Use it to decide whether a "Enable X" button should prompt or open settings.
 */
public val PermissionStatus.canPrompt: Boolean
    get() = this is PermissionStatus.NotDetermined || this is PermissionStatus.Denied
