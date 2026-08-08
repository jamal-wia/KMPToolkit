package io.github.jamal_wia.kmptoolkit.notification

/**
 * What became of a [Notifier.post] call.
 *
 * A notification can fail to appear for reasons that have nothing to do with your code being
 * wrong, and the platform APIs are famously quiet about it: `NotificationManagerCompat.notify`
 * returns `void` and drops the post when the user blocked the channel, and
 * `UNUserNotificationCenter.add` succeeds while the OS discards the request because the app is not
 * authorized. Silence is the trap this type exists to close — every branch below is a distinct
 * thing you can do something about, and none of them throws.
 *
 * Nothing here is a message. Turning [PermissionDenied] into copy in front of a user is the
 * consuming app's job — see `docs/01-architecture.md`.
 */
public sealed interface NotificationResult {

    /**
     * The platform accepted the notification.
     *
     * A statement about the *request*, not about a pixel: the OS may still not draw anything —
     * Do Not Disturb, a focus mode, a paired watch taking the alert instead. Neither platform
     * reports that back, so neither does this module.
     */
    public data object Posted : NotificationResult

    /**
     * Suppressed on purpose: this was a determinate-progress update too close to the previous one
     * to be worth pushing at the platform. See [NotificationConfig.progressBucketPercent] and
     * [NotificationConfig.minProgressInterval].
     *
     * The notification already showing under that id is left exactly as it was. This is a success,
     * not a failure — treat it as "nothing to do".
     */
    public data object Coalesced : NotificationResult

    /**
     * The app may not post notifications because the runtime permission is not granted:
     * `POST_NOTIFICATIONS` on Android 13+, notification authorization on iOS.
     *
     * Ask for it with `kmptoolkit-permission` (`Permission.NOTIFICATIONS`) — this module never
     * shows a system prompt on your behalf. On Android 12 and below there is no runtime permission
     * to be missing, so this value cannot occur there.
     */
    public data object PermissionDenied : NotificationResult

    /**
     * Notifications are switched off for the whole app, in system settings.
     *
     * Android only, and distinct from [PermissionDenied]: the permission can be granted while the
     * app-level toggle is off, and on Android 12 and below the toggle is the *only* thing there is.
     * No prompt can fix it — only a trip to system settings, which is the consuming app's call to
     * offer. On iOS the OS collapses both cases into "not authorized", which this module reports as
     * [PermissionDenied]; see `docs/kmptoolkit-notification/05-platform-notes.md`.
     */
    public data object NotificationsDisabled : NotificationResult

    /**
     * The channel exists but the user has muted it, so this notification would go nowhere.
     *
     * Android 8+ only. Channel settings belong to the user once the channel has been created: an
     * app cannot raise the importance of a channel it already created, and re-creating a deleted
     * channel restores the user's old settings rather than yours. So the only honest recovery is a
     * different channel or a trip to system settings — see
     * `docs/kmptoolkit-notification/03-guide.md`.
     *
     * @property channelId the [NotificationChannelSpec.id] that is blocked.
     */
    public data class ChannelBlocked(public val channelId: String) : NotificationResult

    /**
     * The platform rejected the notification for a reason that is none of the above — in practice
     * a small icon that does not resolve to a drawable, a payload the framework refuses, or a
     * system service that is momentarily unreachable.
     *
     * @property cause the platform's own error where there is one: the caught exception on
     *   Android, an exception carrying the `NSError`'s localized description on iOS. The concrete
     *   throwable type is **not** part of this module's contract — log it, do not branch on it.
     */
    public data class Failed(public val cause: Throwable?) : NotificationResult
}

/**
 * Whether the platform accepted the notification — [NotificationResult.Posted] only.
 *
 * Note that [NotificationResult.Coalesced] is deliberately **not** included: it means nothing was
 * handed to the platform this time. If what you want is "no problem to report", check
 * `result !is NotificationResult.Failed` together with the branches you care about instead.
 */
public val NotificationResult.isPosted: Boolean
    get() = this is NotificationResult.Posted
