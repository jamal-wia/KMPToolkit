package io.github.jamal_wia.kmptoolkit.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionHandler
import io.github.jamal_wia.kmptoolkit.permission.isGranted

/**
 * Creates the Android [Notifier], backed by `NotificationManagerCompat`.
 *
 * Build it once — in your `Application`, or wherever you assemble dependencies — and pass the
 * resulting [Notifier] into shared code. Only the application context is retained, so handing an
 * `Activity` to [context] leaks nothing.
 *
 * The app must declare `android.permission.POST_NOTIFICATIONS` itself, and request it: this library
 * declares no permission and shows no prompt, on purpose. Without the grant every [Notifier.post]
 * returns [NotificationResult.PermissionDenied] instead of quietly doing nothing, which is what the
 * platform would do. See `docs/kmptoolkit-notification/05-platform-notes.md`.
 *
 * @param context any `Context`; its application context is what gets kept.
 * @param permissionHandler from `kmptoolkit-permission` — used to *check* `POST_NOTIFICATIONS`,
 *   never to request it. Pass the same instance the rest of your app uses so its denial bookkeeping
 *   stays consistent.
 * @param config identifiers and coalescing limits; see [NotificationConfig]. Its
 *   `actionBroadcastAction` default is derived from this app's own application id.
 */
public fun createNotifier(
    context: Context,
    permissionHandler: PermissionHandler,
    config: NotificationConfig = NotificationConfig(),
): Notifier {
    val appContext: Context = context.applicationContext
    return AndroidNotifier(
        context = appContext,
        permissionHandler = permissionHandler,
        broadcastAction = config.resolveBroadcastAction(appContext.packageName),
        coalescer = ProgressCoalescer(config.progressBucketPercent, config.minProgressInterval),
    )
}

/**
 * Maps a [LocalNotification] onto `NotificationCompat` and posts it, reporting each way that can
 * fail as a [NotificationResult] rather than as silence.
 *
 * The gates run in the order the platform itself would apply them — permission, then the app-wide
 * toggle, then the channel — so the result names the *first* reason the user would not have seen
 * this notification, which is the one worth acting on. Coalescing is checked last, because a
 * suppressed post must not consume a decision that a real gate would have rejected anyway.
 */
internal class AndroidNotifier(
    private val context: Context,
    private val permissionHandler: PermissionHandler,
    private val broadcastAction: String,
    private val coalescer: ProgressCoalescer,
) : Notifier {

    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context)

    // The permission is checked one line above the notify() call; the annotation only silences the
    // static analysis that cannot see through the PermissionHandler indirection.
    @SuppressLint("MissingPermission")
    override suspend fun post(id: String, notification: LocalNotification): NotificationResult {
        if (!permissionHandler.check(Permission.NOTIFICATIONS).isGranted) {
            return NotificationResult.PermissionDenied
        }
        if (!notificationManager.areNotificationsEnabled()) {
            return NotificationResult.NotificationsDisabled
        }
        ensureChannel(notification.channel)
        if (isChannelBlocked(notification.channel.id)) {
            return NotificationResult.ChannelBlocked(notification.channel.id)
        }
        val iconResId: Int = smallIconRes(notification.icon)
        if (!isResolvable(iconResId)) {
            return NotificationResult.Failed(
                Resources.NotFoundException("Notification icon resource 0x${iconResId.toString(HEX)} does not resolve."),
            )
        }
        if (!coalescer.shouldPost(id, notification.progress)) return NotificationResult.Coalesced
        return try {
            notificationManager.notify(notificationId(id), build(id, notification, iconResId))
            NotificationResult.Posted
        } catch (e: RuntimeException) {
            // notify() throws a SecurityException if the permission is revoked between the check
            // above and here, and an IllegalArgumentException for a payload the framework refuses.
            // A notification is never worth crashing the caller over.
            NotificationResult.Failed(e)
        }
    }

    override fun cancel(id: String) {
        coalescer.forget(id)
        notificationManager.cancel(notificationId(id))
    }

    override fun cancelAll() {
        coalescer.clear()
        notificationManager.cancelAll()
    }

    private fun build(id: String, notification: LocalNotification, iconResId: Int): Notification {
        val builder: NotificationCompat.Builder =
            NotificationCompat.Builder(context, notification.channel.id)
                .setSmallIcon(iconResId)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setOngoing(notification.ongoing)
                .setAutoCancel(notification.autoCancel)
                // Re-posting an id is an update, not a new event: alerting once keeps a progress
                // notification from buzzing on every step.
                .setOnlyAlertOnce(true)
                // Below API 26 there are no channels, so heads-up behaviour rides on the priority.
                // From 26 the channel decides and NotificationCompat ignores this.
                .setPriority(notificationPriority(notification.channel.importance))

        when (val progress: NotificationProgress? = notification.progress) {
            is NotificationProgress.Determinate -> builder.setProgress(
                MAX_PERCENT,
                progress.percent.coerceIn(0, MAX_PERCENT),
                false,
            )

            NotificationProgress.Indeterminate -> builder.setProgress(MAX_PERCENT, 0, true)
            null -> Unit
        }
        notification.actions.forEach { action: NotificationAction ->
            // Icon 0: Android 7+ does not render action icons on phones, and inventing one here
            // would mean shipping artwork this library has no business choosing.
            builder.addAction(0, action.label, actionPendingIntent(id, action))
        }
        notification.contentExtras?.let { extras: Map<String, String> ->
            contentPendingIntent(id, extras)?.let(builder::setContentIntent)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Pre-26 the sound belongs to the notification, not to a channel that does not exist.
            (notification.channel.sound as? NotificationSound.Custom)?.let { custom ->
                builder.setSound(customSoundUri(custom.resourceName))
            }
        }
        return builder.build()
    }

    /**
     * Creates the channel if it is not there yet.
     *
     * `createNotificationChannel` is idempotent *and* deliberately powerless on an existing
     * channel: importance and sound are the user's once the channel exists, and re-issuing them
     * changes nothing. That is why a blocked channel is reported rather than repaired.
     */
    private fun ensureChannel(spec: NotificationChannelSpec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(spec.id, spec.name, androidImportance(spec.importance))
            .apply {
                description = spec.description
                when (val sound: NotificationSound = spec.sound) {
                    NotificationSound.Silent -> setSound(null, null)
                    NotificationSound.Default -> Unit
                    is NotificationSound.Custom ->
                        setSound(customSoundUri(sound.resourceName), CUSTOM_SOUND_ATTRS)
                }
            }
        systemNotificationManager().createNotificationChannel(channel)
    }

    private fun isChannelBlocked(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val channel: NotificationChannel = systemNotificationManager()
            .getNotificationChannel(channelId) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    private fun isResolvable(resourceId: Int): Boolean = try {
        context.resources.getResourceName(resourceId) != null
    } catch (_: Resources.NotFoundException) {
        false
    }

    /**
     * A broadcast [PendingIntent] carrying the tapped button's id back to the app's receiver.
     *
     * The request code folds in the notification id as well as the action id: two notifications
     * offering the same button would otherwise share one `PendingIntent`, and
     * `FLAG_UPDATE_CURRENT` would quietly rewrite the first one's extras to the second one's.
     */
    private fun actionPendingIntent(id: String, action: NotificationAction): PendingIntent {
        val intent = Intent(broadcastAction).apply {
            setPackage(context.packageName)
            putExtra(NotificationActionIntent.EXTRA_ACTION_ID, action.id)
            putExtra(NotificationActionIntent.EXTRA_NOTIFICATION_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            "$id:${action.id}".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * A tap target: the app's own launcher activity, carrying [extras].
     *
     * Launching by package rather than by an `Activity` class is what keeps this module free of any
     * knowledge about the consuming app. `SINGLE_TOP` alongside `CLEAR_TOP` is what makes a tap
     * arrive in `onNewIntent` instead of occasionally recreating the activity. `null` when the app
     * has no launcher activity at all, which no normal app is.
     */
    private fun contentPendingIntent(id: String, extras: Map<String, String>): PendingIntent? {
        val launch: Intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null
        extras.forEach { (key: String, value: String) -> launch.putExtra(key, value) }
        return PendingIntent.getActivity(
            context,
            notificationId(id),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun customSoundUri(resourceName: String): Uri =
        "android.resource://${context.packageName}/raw/$resourceName".toUri()

    private fun smallIconRes(icon: NotificationIcon): Int = when (icon) {
        NotificationIcon.Default -> android.R.drawable.ic_dialog_info
        is NotificationIcon.AndroidDrawable -> icon.resourceId
    }

    private fun androidImportance(importance: NotificationImportance): Int = when (importance) {
        NotificationImportance.Low -> NotificationManager.IMPORTANCE_LOW
        NotificationImportance.Default -> NotificationManager.IMPORTANCE_DEFAULT
        NotificationImportance.High -> NotificationManager.IMPORTANCE_HIGH
    }

    private fun notificationPriority(importance: NotificationImportance): Int = when (importance) {
        NotificationImportance.Low -> NotificationCompat.PRIORITY_LOW
        NotificationImportance.Default -> NotificationCompat.PRIORITY_DEFAULT
        NotificationImportance.High -> NotificationCompat.PRIORITY_HIGH
    }

    private fun systemNotificationManager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    internal companion object {

        private const val MAX_PERCENT: Int = 100
        private const val HEX: Int = 16

        /** Clears the sign bit, so a hash becomes a non-negative notification id. */
        private const val ID_MASK: Int = 0x7FFFFFFF

        private val CUSTOM_SOUND_ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()

        /**
         * The platform's `Int` id for a caller's `String` id: stable across processes (String
         * hashing is specified), never negative, and never 0 — `startForeground` rejects 0, and the
         * empty string hashes to exactly that.
         */
        fun notificationId(id: String): Int = (id.hashCode() and ID_MASK).coerceAtLeast(1)
    }
}
