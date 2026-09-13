package io.github.jamal_wia.kmptoolkit.notification

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
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Turns a [LocalNotification] into an Android [Notification] — everything about *what* is shown, and
 * nothing about *whether* it may be.
 *
 * One place for both callers that need it: [AndroidNotifier], which gates and posts, and
 * [buildForegroundNotification], which builds a notification a foreground service posts itself. A
 * notification built for `startForeground` and the one later posted under the same id to update it
 * must agree on every intent — the button broadcasts, the delete broadcast, the tap target — or
 * the update would quietly change what a button does.
 *
 * @param context the application context.
 * @param broadcastAction the action every button and dismissal broadcast carries.
 */
internal class NotificationRenderer(
    private val context: Context,
    private val broadcastAction: String,
) {

    fun build(
        id: String,
        notification: LocalNotification,
        options: NotificationOptions,
        iconResId: Int,
    ): Notification {
        val builder: NotificationCompat.Builder =
            NotificationCompat.Builder(context, notification.channel.id)
                .setSmallIcon(iconResId)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setOngoing(notification.ongoing)
                .setAutoCancel(notification.autoCancel)
                // Re-posting an id is an update, not a new event: alerting once keeps a progress
                // notification from buzzing on every step. A repeating reminder turns it off.
                .setOnlyAlertOnce(options.alertOnce)
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
            // No icon unless the caller supplied one: Android 7+ does not render action icons on a
            // phone's expanded notification, and inventing one would mean shipping artwork this
            // library has no business choosing.
            builder.addAction(
                actionIconRes(options.actionIcons[action.id]),
                action.label,
                actionPendingIntent(id, action),
            )
        }
        options.dismissAction?.let { action: NotificationAction ->
            builder.setDeleteIntent(dismissPendingIntent(id, action))
        }
        if (options.mediaStyle && notification.actions.isNotEmpty()) {
            // The player layout, used for its collapsed row: the first action is drawn without
            // expanding the notification.
            builder.setStyle(
                MediaNotificationCompat.MediaStyle().setShowActionsInCompactView(FIRST_ACTION),
            )
        }
        notification.contentExtras?.let { extras: Map<String, String> ->
            contentPendingIntent(id, extras)?.let(builder::setContentIntent)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Pre-26 the sound belongs to the notification, not to a channel that does not exist —
            // and a NotificationCompat notification is silent unless a sound is set explicitly, so
            // NotificationSound.Default has to be spelled out here or it would mean "Silent" on
            // exactly the API levels nobody tests on.
            builder.setSound(legacySoundUri(notification.channel))
        }
        return builder.build()
    }

    fun smallIconRes(icon: NotificationIcon): Int = when (icon) {
        NotificationIcon.Default -> android.R.drawable.ic_dialog_info
        is NotificationIcon.AndroidDrawable -> icon.resourceId
    }

    fun isResolvable(resourceId: Int): Boolean = try {
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
    private fun actionPendingIntent(id: String, action: NotificationAction): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            "$id:${action.id}".hashCode(),
            actionBroadcast(id, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The broadcast sent when the notification is swiped away: the same broadcast a button for
     * [action] sends, so one receiver answers both, under a request code of its own so it never
     * collapses onto that button's `PendingIntent`. The key is joined with a NUL rather than the
     * button key's `:`, so no pair of ids a caller could choose — an action called `dismiss:stop`,
     * say — yields the button key of another action.
     */
    private fun dismissPendingIntent(id: String, action: NotificationAction): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            "$id\u0000dismiss\u0000${action.id}".hashCode(),
            actionBroadcast(id, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun actionBroadcast(id: String, action: NotificationAction): Intent =
        Intent(broadcastAction).apply {
            setPackage(context.packageName)
            putExtra(NotificationActionIntent.EXTRA_ACTION_ID, action.id)
            putExtra(NotificationActionIntent.EXTRA_NOTIFICATION_ID, id)
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
            platformNotificationId(id),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun customSoundUri(resourceName: String): Uri = context.rawSoundUri(resourceName)

    /**
     * The sound for an API 24–25 notification, where there is no channel to carry one.
     *
     * A [NotificationImportance.Low] channel is silent from API 26 onwards, so it is kept silent
     * here too rather than sounding on the older levels only — the point of mapping importance is
     * that a progress notification behaves the same way everywhere.
     */
    private fun legacySoundUri(channel: NotificationChannelSpec): Uri? {
        if (channel.importance == NotificationImportance.Low) return null
        return when (val sound: NotificationSound = channel.sound) {
            NotificationSound.Silent -> null
            NotificationSound.Default -> Settings.System.DEFAULT_NOTIFICATION_URI
            is NotificationSound.Custom -> customSoundUri(sound.resourceName)
        }
    }

    /** `0` — no icon — for a button the caller gave none, or [NotificationIcon.Default]. */
    private fun actionIconRes(icon: NotificationIcon?): Int = when (icon) {
        is NotificationIcon.AndroidDrawable -> icon.resourceId
        NotificationIcon.Default, null -> 0
    }

    private fun notificationPriority(importance: NotificationImportance): Int = when (importance) {
        NotificationImportance.Low -> NotificationCompat.PRIORITY_LOW
        NotificationImportance.Default -> NotificationCompat.PRIORITY_DEFAULT
        NotificationImportance.High -> NotificationCompat.PRIORITY_HIGH
    }

    private companion object {
        const val MAX_PERCENT: Int = 100

        /** The action drawn in the collapsed row of the media layout. */
        const val FIRST_ACTION: Int = 0
    }
}

/**
 * The platform channel for [spec].
 *
 * `createNotificationChannel` with it is idempotent *and* deliberately powerless on an existing
 * channel's importance and sound — those belong to the user once the channel exists.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal fun Context.notificationChannelFor(spec: NotificationChannelSpec): NotificationChannel =
    NotificationChannel(spec.id, spec.name, androidImportance(spec.importance)).apply {
        description = spec.description
        when (val sound: NotificationSound = spec.sound) {
            NotificationSound.Silent -> setSound(null, null)
            NotificationSound.Default -> Unit
            is NotificationSound.Custom -> setSound(rawSoundUri(sound.resourceName), CUSTOM_SOUND_ATTRS)
        }
    }

/**
 * The framework manager, or `null` on the rare occasion the service is unavailable.
 *
 * Declared nullable because `getSystemService` returns a platform type: assigning it to a non-null
 * declaration turns "no service" into a `NullPointerException` thrown from inside a method whose
 * contract says it does not throw.
 */
internal fun Context.frameworkNotificationManager(): NotificationManager? =
    getSystemService(NotificationManager::class.java)

private fun Context.rawSoundUri(resourceName: String): Uri =
    "android.resource://$packageName/raw/$resourceName".toUri()

private fun androidImportance(importance: NotificationImportance): Int = when (importance) {
    NotificationImportance.Low -> NotificationManager.IMPORTANCE_LOW
    NotificationImportance.Default -> NotificationManager.IMPORTANCE_DEFAULT
    NotificationImportance.High -> NotificationManager.IMPORTANCE_HIGH
}

private val CUSTOM_SOUND_ATTRS: AudioAttributes = AudioAttributes.Builder()
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
    .build()

/** Clears the sign bit, so a hash becomes a non-negative notification id. */
private const val ID_MASK: Int = 0x7FFFFFFF

/**
 * The platform's `Int` id for a caller's `String` id: stable across processes (String hashing is
 * specified), never negative, and never 0 — `startForeground` rejects 0, and the empty string hashes
 * to exactly that.
 */
internal fun platformNotificationId(id: String): Int = (id.hashCode() and ID_MASK).coerceAtLeast(1)
