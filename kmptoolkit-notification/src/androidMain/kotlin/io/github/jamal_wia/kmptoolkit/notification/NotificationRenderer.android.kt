package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
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
     * Keyed by the notification id *and* the action id: two notifications offering the same button
     * would otherwise share one `PendingIntent`, and `FLAG_UPDATE_CURRENT` would quietly rewrite the
     * first one's extras to the second one's. See [distinguishBy] for how the key is applied.
     */
    private fun actionPendingIntent(id: String, action: NotificationAction): PendingIntent =
        broadcastPendingIntent(pendingIntentKey(KIND_ACTION, id, action.id), actionBroadcast(id, action))

    /**
     * The broadcast sent when the notification is swiped away: the same broadcast a button for
     * [action] sends, so one receiver answers both, under a key of its own so it never collapses
     * onto that button's `PendingIntent`.
     */
    private fun dismissPendingIntent(id: String, action: NotificationAction): PendingIntent =
        broadcastPendingIntent(pendingIntentKey(KIND_DISMISS, id, action.id), actionBroadcast(id, action))

    private fun broadcastPendingIntent(key: String, broadcast: Intent): PendingIntent {
        // Below API 29 the key can only be carried by an explicit intent (see distinguishBy), so the
        // broadcast is addressed to the app's receiver directly — when there is exactly one to
        // address. With none declared, or several, it stays the implicit broadcast it always was.
        // From 29 the identifier carries the key and the broadcast is left implicit.
        val explicit: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && broadcast.addressToSoleReceiver()
        val requestCode: Int = broadcast.distinguishBy(key, explicit)
        return PendingIntent.getBroadcast(context, requestCode, broadcast, PENDING_INTENT_FLAGS)
    }

    /**
     * Sets [component][Intent.setComponent] to the one manifest receiver of this broadcast's action in
     * this app, and reports whether there was exactly one. An explicit broadcast is delivered without
     * consulting the receiver's intent filter, which is what lets a category carry the key below
     * API 29 even though the filter the docs tell an app to declare names only the action.
     */
    private fun Intent.addressToSoleReceiver(): Boolean {
        val receivers: List<ResolveInfo> = context.packageManager.queryBroadcastReceivers(this, 0)
        val receiver: ActivityInfo = receivers.singleOrNull()?.activityInfo ?: return false
        component = ComponentName(receiver.packageName, receiver.name)
        return true
    }

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
     *
     * Keyed by the notification id, so two notifications' taps never share a `PendingIntent` — and
     * with it, under `FLAG_UPDATE_CURRENT`, each other's extras. The launch intent names its
     * component, so the key can ride on it at every API level; see [distinguishBy].
     */
    private fun contentPendingIntent(id: String, extras: Map<String, String>): PendingIntent? {
        val launch: Intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null
        extras.forEach { (key: String, value: String) -> launch.putExtra(key, value) }
        val requestCode: Int = launch.distinguishBy(pendingIntentKey(KIND_CONTENT, id, null), explicit = true)
        return PendingIntent.getActivity(context, requestCode, launch, PENDING_INTENT_FLAGS)
    }

    /**
     * Makes this intent's `PendingIntent` identity depend on [key], and returns the request code to
     * create it with.
     *
     * This is how a notification's `PendingIntent`s — the tap target, each button, the dismissal —
     * are told apart. A `PendingIntent` is identified by its request code plus its intent as
     * `Intent.filterEquals` sees it: action, data, type, identifier, package, component and
     * categories, but **not** extras. Two notifications whose intents differ only in extras, created
     * under one request code, share one `PendingIntent`, and `FLAG_UPDATE_CURRENT` rewrites the first
     * one's extras to the second's. Up to 2.x the request code was a hash of the id, so two ids with
     * equal hashes did exactly that.
     *
     * Now a collision-free string key ([pendingIntentKey]) is put into the intent itself:
     *
     * - **API 29+:** `Intent.setIdentifier(key)`, which exists for precisely this — it takes part in
     *   `filterEquals` and in nothing else: not in which component receives the intent, and not in
     *   anything an app reads from it.
     * - **Below API 29:** as a category, but only on an **explicit** intent. An explicit intent is
     *   delivered without consulting intent filters, so the extra category changes nothing about
     *   who receives it — whereas on an implicit one a filter that does not list it would stop
     *   matching. The tap target is always explicit; a broadcast is made explicit when the app
     *   declares exactly one receiver for it.
     * - **Data was not used**: it would show up as `intent.data` in the app's launcher activity,
     *   where deep-link handling reads it, and an action-only receiver filter would stop matching a
     *   broadcast carrying it.
     *
     * With the key in the intent, the request code is a constant.
     */
    private fun Intent.distinguishBy(key: String, explicit: Boolean): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            setIdentifier(key)
            DISTINCT_REQUEST_CODE
        }
        explicit -> {
            addCategory(key)
            DISTINCT_REQUEST_CODE
        }
        // An implicit broadcast below API 29 with no single receiver to address: nothing in the
        // intent can carry the key without changing who receives it, so the request code does,
        // hashed — the one residual case where two keys can still meet. Documented in
        // 05-platform-notes.md.
        else -> key.hashCode()
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

/** Request code of every `PendingIntent` whose intent carries its own key. */
private const val DISTINCT_REQUEST_CODE: Int = 0

private const val PENDING_INTENT_FLAGS: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

private const val KIND_CONTENT: String = "content"
private const val KIND_ACTION: String = "action"
private const val KIND_DISMISS: String = "dismiss"

/**
 * An unambiguous key for one `PendingIntent` of one notification: kind, notification id and action
 * id as separately percent-encoded URI segments, so no choice of ids — an action called
 * `dismiss/stop`, an id containing `:` — can produce another notification's or another kind's key.
 */
private fun pendingIntentKey(kind: String, id: String, actionId: String?): String =
    Uri.Builder()
        .scheme(PENDING_INTENT_KEY_SCHEME)
        .authority(kind)
        .appendPath(id)
        .apply { if (actionId != null) appendPath(actionId) }
        .build()
        .toString()

private const val PENDING_INTENT_KEY_SCHEME: String = "kmptoolkit-notification"
