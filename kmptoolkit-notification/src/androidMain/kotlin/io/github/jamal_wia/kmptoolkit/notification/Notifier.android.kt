package io.github.jamal_wia.kmptoolkit.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.isUsable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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
 * controller would say — whose own pre-API-33 answer is the app-wide switch, which this notifier
 * reports separately as [NotificationResult.NotificationsDisabled]. See `docs/kmptoolkit-notification/05-platform-notes.md`.
 *
 * @param context any `Context`; its application context is what gets kept.
 * @param permissions from `kmptoolkit-permission` — used to *check* `POST_NOTIFICATIONS`,
 *   never to request it. Pass the same instance the rest of your app uses so its denial bookkeeping
 *   stays consistent.
 * @param config identifiers and coalescing limits; see [NotificationConfig]. Its
 *   `actionBroadcastAction` default is derived from this app's own application id.
 * @param foregroundIds the ids a foreground service shows its notification under, each bound to the
 *   untagged `Int` it passed to `startForeground`; see [ForegroundNotificationId]. A [Notifier.post]
 *   or [Notifier.cancel] of one of these ids addresses that `Int`, so it updates or removes the
 *   service's own notification instead of posting a second one beside it. Every other id is posted
 *   under its [String] as the platform tag. Empty by default.
 * @throws IllegalArgumentException if two entries of [foregroundIds] share an id or a platform id.
 */
public fun createNotifier(
    context: Context,
    permissions: PermissionController,
    config: NotificationConfig = NotificationConfig(),
    foregroundIds: Set<ForegroundNotificationId> = emptySet(),
): Notifier {
    val appContext: Context = context.applicationContext
    return AndroidNotifier(
        context = appContext,
        permissions = permissions,
        renderer = NotificationRenderer(appContext, config.resolveBroadcastAction(appContext.packageName)),
        coalescer = ProgressCoalescer(config.progressBucketPercent, config.minProgressInterval),
        foregroundIds = foregroundIds,
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
 *
 * **Identity.** A notification is posted as `notify(tag = id, TAGGED_NOTIFICATION_ID)`: the caller's
 * [String] is the platform key itself, so two different ids are two notifications whatever their
 * hash codes. Only an id in [foregroundIds] is posted untagged, under the `Int` its foreground
 * service started with — the one key `startForeground` can share.
 */
internal class AndroidNotifier(
    private val context: Context,
    private val permissions: PermissionController,
    private val renderer: NotificationRenderer,
    private val coalescer: ProgressCoalescer,
    foregroundIds: Set<ForegroundNotificationId> = emptySet(),
) : Notifier {

    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context)

    /**
     * Orders a post's final "still wanted? then notify" step against [cancel] and [cancelAll].
     * Separate from the coalescer's own lock and always taken first, so the two never invert.
     */
    private val postLock: NotifierLock = notifierLock()

    /** Foreground id to the untagged `Int` its service started with. */
    private val foregroundPlatformIds: Map<String, Int> = foregroundIds.associate { foreground: ForegroundNotificationId ->
        foreground.id to foreground.platformId
    }

    init {
        require(foregroundPlatformIds.size == foregroundIds.size) {
            "foregroundIds must not bind one id twice."
        }
        require(foregroundPlatformIds.values.toSet().size == foregroundIds.size) {
            "foregroundIds must not share a platformId: two of them would be one notification."
        }
    }

    /**
     * Ids whose 2.x notification this instance has already swept away; see [sweepLegacy]. Updated
     * with `update`, because [post] runs concurrently from any thread.
     *
     * A cache, not a ledger: it only saves one redundant binder `cancel` per post, so forgetting an
     * entry is harmless. [cancel] and [cancelAll] drop entries, and the set restarts from the
     * newest id once it reaches [MAX_SWEPT_LEGACY_IDS], so an app that posts under a fresh id per
     * message does not grow it, or copy it, without bound.
     */
    private val sweptLegacyIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    /**
     * Channel specs already handed to the platform by this instance.
     *
     * Creating a channel is a binder round-trip that the system persists, and the second one for an
     * unchanged spec achieves nothing. Keying on the whole spec rather than on the id means a
     * changed `name` or `description` — the two fields the platform *does* still update — is
     * re-applied rather than cached away.
     *
     * A [MutableStateFlow] of an immutable set, updated with `update`, because [post] is documented
     * safe to call concurrently from any thread and a plain `mutableSetOf()` — a `LinkedHashSet` —
     * has undefined behavior under concurrent writes. A lost entry here would only cost one more
     * idempotent channel write; a corrupted hash table would not be so harmless.
     */
    private val ensuredChannels: MutableStateFlow<Set<NotificationChannelSpec>> = MutableStateFlow(emptySet())

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
    // analysis that cannot see through the PermissionController indirection.
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
        // Everything but the percentage: a frame that changes any of it is not held back by the bucket.
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
        val ticket: ProgressCoalescer.PostTicket =
            coalescer.prepare(id, notification.progress, content) ?: return NotificationResult.Coalesced
        try {
            // Built outside the lock: real work (PendingIntents, bitmaps).
            val built: Notification = renderer.build(id, notification, options, iconResId)
            // Decide-and-notify is one critical section with cancel()'s forget-and-cancel, so a
            // cancel that ran while this frame was being built is not followed by the frame, and a
            // frame overtaken by a newer one (a terminal "Done") is not shown on top of it. The
            // lock is a blocking one because cancel() is not suspending; notify() is a short binder
            // enqueue.
            val shown: Boolean = postLock.withLock {
                if (!coalescer.isLive(ticket)) return@withLock false
                when (val foregroundId: Int? = foregroundPlatformIds[id]) {
                    null -> notificationManager.notify(id, TAGGED_NOTIFICATION_ID, built)
                    else -> notificationManager.notify(foregroundId, built)
                }
                // notify() returned, so the platform has accepted the frame: nothing can undo it.
                coalescer.complete(ticket, acknowledged = true)
                true
            }
            if (!shown) {
                // Built but never shown: its tap target must not stand in for the frame on the tray.
                restoreTapTarget(id)
                return NotificationResult.Coalesced
            }
        } catch (e: Throwable) {
            // Nothing reached the tray, so the frame must not stand as the baseline: a retry of the
            // same progress has to post. A no-op if cancel/clear or a newer post got there first.
            coalescer.rollback(ticket)
            restoreTapTarget(id)
            throw e
        }
        // The frame is on the tray; a refused sweep must not turn that into a failure. An entry is
        // recorded only for a sweep that went through, so a refused one is retried by the next post.
        if (id !in sweptLegacyIds.value && sweepLegacy(id)) {
            sweptLegacyIds.update { swept: Set<String> ->
                if (swept.size >= MAX_SWEPT_LEGACY_IDS) setOf(id) else swept + id
            }
        }
        return NotificationResult.Posted
    }

    /**
     * Writes the tap target of the frame the tray is known to show back into its `PendingIntent`.
     *
     * A tap target is one `PendingIntent` per id that `FLAG_UPDATE_CURRENT` rewrites when a frame is
     * rendered, which happens before the platform has accepted that frame. A frame that is then
     * refused or dropped would leave the frame still showing opening the app with *its* extras. The
     * coalescer hands over the shown frame's content only while no other frame of the id is in
     * flight (that frame renders its own target, and a restore here would overwrite it), and does so
     * under its lock, so a frame prepared meanwhile renders after this write. Unknown after a process
     * restart, where the tray frame's content is not remembered; nothing is restored then.
     */
    private fun restoreTapTarget(id: String) {
        try {
            coalescer.withShownContent(id) { content: Any? ->
                val extras: Map<String, String>? = (content as? CoalescingKey)?.notification?.contentExtras
                if (extras != null) renderer.restoreContentIntent(id, extras)
            }
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "Could not restore the tap target of notification '$id'.", e)
        }
    }

    /**
     * Whether the runtime notification permission allows posting.
     *
     * Asked only from API 33, where `POST_NOTIFICATIONS` exists. Below it there is nothing to grant,
     * and deciding that here rather than trusting the [PermissionController] to say so keeps a controller
     * that answers differently — an adapter over an app's own permission code, a test double left at
     * "denied" — from silently suppressing every notification on older devices.
     */
    private suspend fun mayPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            permissions.check(CommonPermission.Notifications).isUsable

    override fun cancel(id: String) {
        // One critical section with the decide-and-notify of an in-flight post (see postGated).
        postLock.withLock {
            coalescer.forget(id)
            // Never throws: see Notifier.cancel. Each platform call is guarded on its own, so a
            // refusal of the first does not skip the second.
            platformCall("cancel notification '$id'") {
                when (val foregroundId: Int? = foregroundPlatformIds[id]) {
                    null -> notificationManager.cancel(id, TAGGED_NOTIFICATION_ID)
                    else -> notificationManager.cancel(foregroundId)
                }
            }
            sweepLegacy(id)
            // The sweep above is the sweep the cache stands for, so the entry has nothing left to say.
            sweptLegacyIds.update { swept: Set<String> -> swept - id }
        }
    }

    /**
     * Removes the notification a 2.x version of this library would have shown under [id] — untagged,
     * under [legacyPlatformNotificationId] — so an app updated from 2.x neither keeps a stale copy
     * next to the one it now posts nor finds that copy beyond the reach of [cancel].
     *
     * Skipped when that `Int` is one of [foregroundPlatformIds]: there it is a live notification of
     * this version, not a leftover. A migration aid for the 3.x line only; it goes in 4.0.
     *
     * @return `false` when the platform refused the removal (logged, never thrown: the sweep is a
     *   side job and must not decide the outcome of a post or a cancel); `true` otherwise.
     */
    private fun sweepLegacy(id: String): Boolean {
        val legacyId: Int = legacyPlatformNotificationId(id)
        if (legacyId in foregroundPlatformIds.values) return true
        return platformCall("sweep the legacy notification of '$id'") { notificationManager.cancel(legacyId) }
    }

    override fun cancelAll() {
        postLock.withLock {
            coalescer.clear()
            platformCall("cancel all notifications") { notificationManager.cancelAll() }
            sweptLegacyIds.value = emptySet()
        }
    }

    /**
     * Runs a platform removal call. [cancel] and [cancelAll] are teardown calls, often made on logout
     * or from a `finally`, and promise never to throw for a runtime condition (a dead binder, a
     * revoked right): the refusal is logged and the notification may stay on the tray.
     */
    private inline fun platformCall(what: String, call: () -> Unit): Boolean = try {
        call()
        true
    } catch (e: RuntimeException) {
        Log.w(LOG_TAG, "The platform refused to $what; it may still be showing.", e)
        false
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
        if (channel == null || (!redundant && spec !in ensuredChannels.value)) {
            manager.createNotificationChannel(context.notificationChannelFor(spec))
            ensuredChannels.update { ensured: Set<NotificationChannelSpec> -> ensured + spec }
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

    /** Test seam: how many ids [sweptLegacyIds] currently remembers. */
    internal fun sweptLegacyCountForTest(): Int = sweptLegacyIds.value.size

    private companion object {
        const val HEX: Int = 16

        const val LOG_TAG: String = "KmpToolkitNotification"

        /** Bound of [sweptLegacyIds]; a forgotten entry costs one idempotent binder `cancel`. */
        const val MAX_SWEPT_LEGACY_IDS: Int = 256

        /**
         * The `Int` half of every tagged notification's `(tag, id)` key. The tag — the caller's id —
         * is what tells notifications apart, so this is a constant.
         */
        const val TAGGED_NOTIFICATION_ID: Int = 1
    }
}

/** Clears the sign bit, so a hash becomes a non-negative notification id. */
private const val LEGACY_ID_MASK: Int = 0x7FFFFFFF

/**
 * The untagged `Int` a 2.x version of this library showed [id] under, and that its `notificationIdOf`
 * returned: the masked `String.hashCode()`, never 0. Kept only so [AndroidNotifier] can sweep away
 * what 2.x left in the tray; two ids with equal hashes shared it, which is why 3.0 stopped using it.
 */
internal fun legacyPlatformNotificationId(id: String): Int = (id.hashCode() and LEGACY_ID_MASK).coerceAtLeast(1)
