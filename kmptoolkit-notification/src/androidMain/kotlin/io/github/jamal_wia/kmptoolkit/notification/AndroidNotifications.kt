package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.content.Context
import android.os.Build

/**
 * Builds — without posting — the notification a foreground service passes to `startForeground`.
 *
 * Starting a foreground service is not this module's job, but the notification it must show from
 * the first moment is: built here, it has the same channel mapping, the same button and dismissal
 * broadcasts and the same tap target as everything [Notifier] posts, so a later [Notifier.post] under
 * [foreground]'s id updates it in place without changing what its buttons do — provided the notifier
 * was created with [foreground] among its `foregroundIds`:
 *
 * ```kotlin
 * val PLAYBACK = ForegroundNotificationId(id = "playback", platformId = 1001)
 *
 * startForeground(
 *     PLAYBACK.platformId,
 *     buildForegroundNotification(this, PLAYBACK, notification, config, options),
 * )
 * // elsewhere: createNotifier(context, permissionHandler, config, foregroundIds = setOf(PLAYBACK))
 * ```
 *
 * The result is always `ongoing`, whatever [notification] says: the platform's own requirement for a
 * foreground service's notification. The channel is created first, as [Notifier.post] would. No
 * permission, toggle or channel-block check runs — a foreground service must call `startForeground`
 * in time either way, and the platform shows a foreground service's notification regardless. It
 * needs nothing but a [Context], so it works from a service the system restarted before the rest of
 * the app was set up.
 *
 * @param context any context; its application context is used.
 * @param foreground the id this notification is, and will be updated, under, with the `Int` to pass
 *   to `startForeground`.
 * @param notification what to show.
 * @param config the same [NotificationConfig] your [Notifier] was created with, so the button
 *   broadcasts match.
 * @param options presentation; see [NotificationOptions].
 * @throws IllegalArgumentException if [notification]'s small icon does not resolve to a resource —
 *   `startForeground` would otherwise crash the service with a far less telling error.
 * @since 3.0.0 — replaces the 1.5.0 overload that took a `String` id and paired with the removed
 *   `notificationIdOf`.
 */
public fun buildForegroundNotification(
    context: Context,
    foreground: ForegroundNotificationId,
    notification: LocalNotification,
    config: NotificationConfig = NotificationConfig(),
    options: NotificationOptions = NotificationOptions.DEFAULT,
): Notification {
    val appContext: Context = context.applicationContext
    val renderer = NotificationRenderer(appContext, config.resolveBroadcastAction(appContext.packageName))
    val iconResId: Int = renderer.smallIconRes(notification.icon)
    require(renderer.isResolvable(iconResId)) {
        "Notification icon resource 0x${iconResId.toString(HEX_RADIX)} does not resolve; startForeground would crash on it."
    }
    NotificationChannels.ensure(appContext, notification.channel)
    return renderer.build(
        id = foreground.id,
        notification = notification.copy(ongoing = true),
        options = options,
        iconResId = iconResId,
    )
}

private const val HEX_RADIX: Int = 16

/**
 * Creates and retires Android notification channels ahead of any post.
 *
 * [Notifier.post] creates the channel it needs by itself, so most apps never call this. It is for
 * two things a lazy creation cannot do:
 *
 * - **Having channels in system settings before the first notification.** A user who opens the
 *   app's notification settings on day one sees every category and can tune it, in the language the
 *   app is running in.
 * - **Retiring a channel.** A channel's sound and importance are fixed once it exists, so changing
 *   either means publishing a new channel id — and deleting the old one, which would otherwise linger
 *   in settings next to its replacement.
 *
 * Both are no-ops below API 26, where channels do not exist.
 *
 * @since 1.5.0
 */
public object NotificationChannels {

    /**
     * Creates the channel described by [spec], or updates the name and description of the one that
     * already exists under its id. Importance and sound of an existing channel are left as they are:
     * they belong to the user once the channel exists.
     *
     * @param context any context; its application context is used.
     */
    public fun ensure(context: Context, spec: NotificationChannelSpec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appContext: Context = context.applicationContext
        appContext.frameworkNotificationManager()?.createNotificationChannel(appContext.notificationChannelFor(spec))
    }

    /**
     * Deletes the channel [id] from the app's notification settings, along with the notifications
     * showing on it. A no-op for an id that does not exist.
     *
     * For retiring a channel an app has replaced, not for resetting a live one: re-creating a
     * deleted id brings back the user's old settings for it, and Android shows the deletion count in
     * the app's settings.
     *
     * @param context any context; its application context is used.
     */
    public fun delete(context: Context, id: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.applicationContext.frameworkNotificationManager()?.deleteNotificationChannel(id)
    }
}
