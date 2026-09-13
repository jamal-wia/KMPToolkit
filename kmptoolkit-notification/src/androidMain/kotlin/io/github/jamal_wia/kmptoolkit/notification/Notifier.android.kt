package io.github.jamal_wia.kmptoolkit.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
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
 * platform would do. Below API 33 there is no such permission and the check is skipped, whatever the
 * handler would say. See `docs/kmptoolkit-notification/05-platform-notes.md`.
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
        renderer = NotificationRenderer(appContext, config.resolveBroadcastAction(appContext.packageName)),
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
    private val renderer: NotificationRenderer,
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

    override suspend fun post(id: String, notification: LocalNotification): NotificationResult =
        post(id, notification, NotificationOptions.DEFAULT)

    override suspend fun post(
        id: String,
        notification: LocalNotification,
        options: NotificationOptions,
    ): NotificationResult {
        // Outside the guard below, and before anything else: a blank id is a bug in the caller, and
        // require() throws IllegalArgumentException, which the guard would otherwise swallow.
        requireValidNotificationId(id)
        return try {
            postGated(id, notification, options)
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
        options: NotificationOptions,
    ): NotificationResult {
        if (!mayPostNotifications()) return NotificationResult.PermissionDenied
        if (!notificationManager.areNotificationsEnabled()) {
            return NotificationResult.NotificationsDisabled
        }
        // Everything but the percentage: a frame that changes any of it is never redundant.
        val content = CoalescingKey(notification.copy(progress = null), options)
        // Asked here, acted on at the end: knowing the frame is redundant lets the channel *write*
        // be skipped, and a write cannot change any result. Every gate still runs, so a suppressed
        // frame never hides a real failure.
        val redundant: Boolean = coalescer.wouldSuppress(id, notification.progress, content)
        channelGate(notification.channel, redundant)?.let { return it }
        val iconResId: Int = renderer.smallIconRes(notification.icon)
        if (!renderer.isResolvable(iconResId)) {
            return NotificationResult.Failed(
                Resources.NotFoundException("Notification icon resource 0x${iconResId.toString(HEX)} does not resolve."),
            )
        }
        if (!coalescer.shouldPost(id, notification.progress, content)) return NotificationResult.Coalesced
        notificationManager.notify(
            platformNotificationId(id),
            renderer.build(id, notification, options, iconResId),
        )
        return NotificationResult.Posted
    }

    /**
     * Whether the runtime notification permission allows posting.
     *
     * Asked only from API 33, where `POST_NOTIFICATIONS` exists. Below it there is nothing to grant,
     * and deciding that here rather than trusting the [PermissionHandler] to say so keeps a handler
     * that answers differently — an adapter over an app's own permission code, a test double left at
     * "denied" — from silently suppressing every notification on older devices.
     */
    private suspend fun mayPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            permissionHandler.check(Permission.NOTIFICATIONS).isGranted

    override fun cancel(id: String) {
        coalescer.forget(id)
        notificationManager.cancel(platformNotificationId(id))
    }

    override fun cancelAll() {
        coalescer.clear()
        notificationManager.cancelAll()
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
     * A re-created deleted channel comes back with the user's settings, not the app's, which is why
     * a blocked channel is reported rather than repaired.
     *
     * @return [NotificationResult.ChannelBlocked] when the channel or its group is muted, `null`
     *   when the notification may proceed.
     */
    private fun channelGate(
        spec: NotificationChannelSpec,
        redundant: Boolean,
    ): NotificationResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val manager: NotificationManager = context.frameworkNotificationManager() ?: return null
        var channel: NotificationChannel? = manager.getNotificationChannel(spec.id)
        if (channel == null || (!redundant && spec !in ensuredChannels)) {
            manager.createNotificationChannel(context.notificationChannelFor(spec))
            ensuredChannels += spec
            channel = manager.getNotificationChannel(spec.id)
        }
        val existing: NotificationChannel = channel ?: return null
        return if (isBlocked(manager, existing)) NotificationResult.ChannelBlocked(spec.id) else null
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

    /** What a post shows apart from its progress, compared by the coalescer. */
    private data class CoalescingKey(val notification: LocalNotification, val options: NotificationOptions)

    private companion object {
        const val HEX: Int = 16
    }
}
