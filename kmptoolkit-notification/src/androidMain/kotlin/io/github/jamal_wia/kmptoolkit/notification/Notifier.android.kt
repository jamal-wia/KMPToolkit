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
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionHandler
import io.github.jamal_wia.kmptoolkit.permission.isGranted
import kotlin.coroutines.cancellation.CancellationException

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
 * this notification, which is the one worth acting on. Coalescing is decided last, because a
 * suppressed post must not consume a decision that a real gate would have rejected anyway; what a
 * suppressed frame does skip is the channel *write*, which cannot change any result.
 *
 * Every one of those calls sits inside one guard, so a system service that is momentarily
 * unreachable comes back as [NotificationResult.Failed] rather than as an exception from a method
 * whose contract says it does not throw.
 */
internal class AndroidNotifier(
    private val context: Context,
    private val permissionHandler: PermissionHandler,
    private val broadcastAction: String,
    private val coalescer: ProgressCoalescer,
) : Notifier {

    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context)

    /**
     * Channel specs already handed to the platform by this instance.
     *
     * Creating a channel is a binder round-trip that the system persists, and the second one for an
     * unchanged spec achieves nothing. Keying on the whole spec rather than on the id means a
     * changed `name` or `description` — the two fields the platform *does* still update — is
     * re-applied rather than cached away.
     */
    private val ensuredChannels: MutableSet<NotificationChannelSpec> = mutableSetOf()

    override suspend fun post(id: String, notification: LocalNotification): NotificationResult {
        // Outside the guard below, and before anything else: a blank id is a bug in the caller, and
        // require() throws IllegalArgumentException, which the guard would otherwise swallow.
        requireValidNotificationId(id)
        return try {
            postGated(id, notification)
        } catch (e: CancellationException) {
            // Cancellation is not a notification failure: kotlinx's CancellationException is a
            // RuntimeException, so it has to be re-thrown before the catch below sees it.
            throw e
        } catch (e: RuntimeException) {
            // Anything the framework throws — a SecurityException if the permission is revoked
            // mid-call, a dead binder when a system service restarts, a payload notify() refuses.
            NotificationResult.Failed(e)
        }
    }

    // The permission is checked before notify() is reached; the annotation only silences the static
    // analysis that cannot see through the PermissionHandler indirection.
    @SuppressLint("MissingPermission")
    private suspend fun postGated(
        id: String,
        notification: LocalNotification,
    ): NotificationResult {
        if (!permissionHandler.check(Permission.NOTIFICATIONS).isGranted) {
            return NotificationResult.PermissionDenied
        }
        if (!notificationManager.areNotificationsEnabled()) {
            return NotificationResult.NotificationsDisabled
        }
        // Asked here, acted on at the end: knowing the frame is redundant lets the channel *write*
        // be skipped, and a write cannot change any result. Every gate still runs, so a suppressed
        // frame never hides a real failure.
        val redundant: Boolean = coalescer.wouldSuppress(id, notification.progress)
        channelGate(notification.channel, redundant)?.let { return it }
        val iconResId: Int = smallIconRes(notification.icon)
        if (!isResolvable(iconResId)) {
            return NotificationResult.Failed(
                Resources.NotFoundException("Notification icon resource 0x${iconResId.toString(HEX)} does not resolve."),
            )
        }
        if (!coalescer.shouldPost(id, notification.progress)) return NotificationResult.Coalesced
        notificationManager.notify(notificationId(id), build(id, notification, iconResId))
        return NotificationResult.Posted
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
            // Pre-26 the sound belongs to the notification, not to a channel that does not exist —
            // and a NotificationCompat notification is silent unless a sound is set explicitly, so
            // NotificationSound.Default has to be spelled out here or it would mean "Silent" on
            // exactly the API levels nobody tests on.
            builder.setSound(legacySoundUri(notification.channel))
        }
        return builder.build()
    }

    /**
     * Makes sure the channel exists, then reports whether posting on it would go nowhere.
     *
     * One binder read either way — the read the block check needs anyway. The persisted *write* is
     * what [redundant] saves, and only when the channel is already there with the spec this
     * instance last sent: a missing channel is always created, even for a frame about to be
     * coalesced, because a notification on a channel that does not exist is dropped by the platform
     * without a word.
     *
     * `createNotificationChannel` is idempotent *and* deliberately powerless on an existing
     * channel's importance and sound — those belong to the user once the channel exists, and a
     * re-created deleted channel comes back with the user's settings, not the app's. That is why a
     * blocked channel is reported rather than repaired.
     *
     * @return [NotificationResult.ChannelBlocked] when the channel or its group is muted, `null`
     *   when the notification may proceed.
     */
    private fun channelGate(
        spec: NotificationChannelSpec,
        redundant: Boolean,
    ): NotificationResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val manager: NotificationManager = systemNotificationManager() ?: return null
        var channel: NotificationChannel? = manager.getNotificationChannel(spec.id)
        if (channel == null || (!redundant && spec !in ensuredChannels)) {
            manager.createNotificationChannel(androidChannel(spec))
            ensuredChannels += spec
            channel = manager.getNotificationChannel(spec.id)
        }
        val existing: NotificationChannel = channel ?: return null
        return if (isBlocked(manager, existing)) NotificationResult.ChannelBlocked(spec.id) else null
    }

    // Reached only through channelGate, which returns early below API 26; the annotation is what
    // carries that guarantee across the function boundary for lint.
    @RequiresApi(Build.VERSION_CODES.O)
    private fun androidChannel(spec: NotificationChannelSpec): NotificationChannel =
        NotificationChannel(spec.id, spec.name, androidImportance(spec.importance)).apply {
            description = spec.description
            when (val sound: NotificationSound = spec.sound) {
                NotificationSound.Silent -> setSound(null, null)
                NotificationSound.Default -> Unit
                is NotificationSound.Custom ->
                    setSound(customSoundUri(sound.resourceName), CUSTOM_SOUND_ATTRS)
            }
        }

    /**
     * Whether notifications on [channel] would go nowhere.
     *
     * Two ways for that to be true, and checking only the first was a hole: the user can mute the
     * channel itself, and from API 28 they can mute the whole **group** it belongs to, which
     * silences every channel in it while each one still reports its original importance. This
     * module creates no groups, but a consumer's channel can have been put in one elsewhere in
     * their app.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun isBlocked(manager: NotificationManager, channel: NotificationChannel): Boolean {
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val groupId: String = channel.group ?: return false
        return manager.getNotificationChannelGroup(groupId)?.isBlocked == true
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

    /**
     * The framework manager, or `null` on the rare occasion the service is unavailable.
     *
     * Declared nullable because `getSystemService` returns a platform type: assigning it to a
     * non-null declaration turns "no service" into a `NullPointerException` thrown from inside a
     * method whose contract says it does not throw. A `null` here simply means the channel work is
     * skipped; the `notify` call that follows reports the real failure.
     */
    private fun systemNotificationManager(): NotificationManager? =
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
