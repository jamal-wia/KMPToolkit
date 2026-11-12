@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.isUsable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSProcessInfo
import platform.UserNotifications.UNErrorCodeNotificationsNotAllowed
import platform.UserNotifications.UNErrorDomain
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelPassive
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Creates the iOS [Notifier], backed by `UNUserNotificationCenter`.
 *
 * Build it once and pass the resulting [Notifier] into shared code. It holds no state beyond its
 * progress-coalescing bookkeeping and nothing needs releasing.
 *
 * Asking the user for notification authorization is your app's job, not this module's — request it
 * with `kmptoolkit-permission` (`CommonPermission.Notifications`) at a moment you choose. Posting while
 * unauthorized returns [NotificationResult.PermissionDenied] rather than handing the OS a request
 * it silently discards.
 *
 * @param permissions from `kmptoolkit-permission`; used only to check authorization.
 * @param config coalescing limits; see [NotificationConfig]. Its `actionBroadcastAction` is
 *   Android-only and ignored here.
 */
public fun createNotifier(
    permissions: PermissionController,
    config: NotificationConfig = NotificationConfig(),
): Notifier = IosNotifier(
    permissions = permissions,
    coalescer = ProgressCoalescer(config.progressBucketPercent, config.minProgressInterval),
)

/**
 * The slice of `UNUserNotificationCenter` that [IosNotifier] uses, as a seam: a test binary cannot
 * call `currentNotificationCenter()` (it is not an app bundle), so tests drive a fake instead.
 * Every method is non-suspending on purpose: [IosNotifier] issues [add] under its post lock and
 * awaits the completion outside it.
 */
internal interface NotificationCenterPort {

    /** `addNotificationRequest`; [completion] receives the system's error, or `null` on success. */
    fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit)

    /** `removeDeliveredNotificationsWithIdentifiers`. */
    fun removeDelivered(ids: List<String>)

    /** `removePendingNotificationRequestsWithIdentifiers`. */
    fun removePending(ids: List<String>)

    /** `removeAllDeliveredNotifications`. */
    fun removeAllDelivered()
}

/**
 * The real center. It fetches `currentNotificationCenter()` on every call and never caches it, so
 * building a notifier before the app is up (or in a test binary) does not touch the center.
 */
internal object SystemNotificationCenter : NotificationCenterPort {

    private val center: UNUserNotificationCenter
        get() = UNUserNotificationCenter.currentNotificationCenter()

    override fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit) {
        center.addNotificationRequest(request, completion)
    }

    override fun removeDelivered(ids: List<String>) {
        center.removeDeliveredNotificationsWithIdentifiers(ids)
    }

    override fun removePending(ids: List<String>) {
        center.removePendingNotificationRequestsWithIdentifiers(ids)
    }

    override fun removeAllDelivered() {
        center.removeAllDeliveredNotifications()
    }
}

/**
 * `UNUserNotificationCenter` implementation.
 *
 * The caller's id is the request identifier verbatim, so re-posting one id replaces its request
 * and cancelling removes the delivered notification and any pending request under it. The
 * identifier space is the app's, shared with `kmptoolkit-scheduler`'s alarm ids — see
 * docs/kmptoolkit-notification/05-platform-notes.md.
 *
 * Three fields of [LocalNotification] have no iOS counterpart at all and are ignored:
 * [LocalNotification.icon] (the OS always shows the app icon), [LocalNotification.ongoing] and
 * [LocalNotification.autoCancel]. [LocalNotification.actions] is ignored too, but for a different
 * reason — see [NotificationAction] and [LocalNotification.iosCategoryId].
 */
internal class IosNotifier(
    private val permissions: PermissionController,
    private val coalescer: ProgressCoalescer,
    private val center: NotificationCenterPort = SystemNotificationCenter,
    /**
     * Orders a post's "still wanted? then add" step against [cancel] and [cancelAll]. Separate from
     * the coalescer's own lock and always taken first, so the two never invert. An `NSLock` is not
     * reentrant: nothing under it calls back into this class. A parameter only so a test can hook
     * the moment between a post's liveness check and its hand-over.
     */
    private val postLock: NotifierLock = notifierLock(),
) : Notifier {

    override suspend fun post(id: String, notification: LocalNotification): NotificationResult {
        // Validation, not a catch: `requestWithIdentifier` rejects an empty identifier by raising an
        // Objective-C exception, and Kotlin/Native cannot catch one — the process would die. This is
        // the only reason post() throws at all, and it throws for a caller's bug, not for a device
        // state. See Notifier.post's KDoc.
        requireValidNotificationId(id)
        // The post is in flight from here on: a cancel that returns while it is still in the
        // permission check, before the coalescer would hear of it, is ordered against it all the same.
        val claim: ProgressCoalescer.PostClaim = coalescer.claim(id)
        try {
            // One read of the caller's mutable lists and maps, before anything suspends: the baseline
            // and the request are both made from this copy, so they describe one frame even if the
            // caller edits (and edits back) its own collections while this post is in flight.
            return postClaimed(
                id,
                notification.copy(
                    actions = notification.actions.toList(),
                    contentExtras = notification.contentExtras?.toMap(),
                ),
                claim,
            )
        } catch (e: CancellationException) {
            // Cancellation is not a notification failure; it is a RuntimeException in Kotlin, so it
            // is re-thrown before the clause below sees it.
            throw e
        } catch (e: RuntimeException) {
            // A permission controller (or anything else on this path) that throws is a runtime
            // failure, and every runtime failure is a result, as on Android.
            return NotificationResult.Failed(e)
        } finally {
            coalescer.release(claim)
        }
    }

    private suspend fun postClaimed(
        id: String,
        notification: LocalNotification,
        claim: ProgressCoalescer.PostClaim,
    ): NotificationResult {
        // iOS reports "the user switched notifications off in Settings" and "the user answered no
        // to the prompt" as one and the same authorization status, so there is no honest way to
        // return NotificationsDisabled here — PermissionDenied covers both. Provisional authorization
        // (PermissionStatus.Limited) does deliver, quietly, so it passes this gate as it did in 2.x.
        if (!permissions.check(CommonPermission.Notifications).isUsable) {
            return NotificationResult.PermissionDenied
        }
        // `notification` is the snapshot taken in post(), so the baseline and the request agree.
        val baseline: LocalNotification = notification.copy(progress = null)
        val ticket: ProgressCoalescer.PostTicket =
            coalescer.prepare(id, notification.progress, baseline, claim)
                ?: return NotificationResult.Coalesced

        val request: UNNotificationRequest = UNNotificationRequest.requestWithIdentifier(
            identifier = id,
            content = contentFor(notification),
            // No trigger: deliver it now. A trigger is what turns this into scheduling, which is
            // kmptoolkit-scheduler's job, not this module's.
            trigger = null,
        )
        var overtaken = false
        var handedOver = false
        val error: NSError? = try {
            suspendCancellableCoroutine { continuation ->
                // Issued under the lock cancel() also takes, after asking whether the frame is still
                // wanted: the center applies requests in call order, so a cancel that ran first is
                // not followed by this frame, and a frame overtaken by a newer one is dropped. The
                // completion is awaited outside the lock.
                val issued: Boolean = postLock.withLock {
                    if (!coalescer.isLive(ticket)) return@withLock false
                    center.add(request) { error: NSError? ->
                        continuation.resume(error)
                    }
                    handedOver = true
                    coalescer.complete(ticket)
                    true
                }
                if (!issued) {
                    overtaken = true
                    continuation.resume(null)
                }
            }
        } catch (e: Throwable) {
            // Cancelled while waiting for the system. Once the request was handed over the outcome is
            // unknown (the center may have accepted it), and neither the frame nor the baseline behind
            // it may be trusted: the id is left with no baseline and its next frame posts. Before the
            // hand-over nothing reached the center and the frame is simply rolled back. A no-op if
            // cancel/clear got there first.
            if (handedOver) coalescer.abandon(ticket) else coalescer.rollback(ticket)
            throw e
        }
        if (overtaken) return NotificationResult.Coalesced
        if (error == null) {
            // Confirmed: only now may the frame stop being undoable (see ProgressCoalescer.complete).
            coalescer.acknowledge(ticket)
            return NotificationResult.Posted
        }
        // The system rejected it: nothing is showing, so a retry of the same progress must post.
        coalescer.rollback(ticket)
        return if (error.isNotificationsNotAllowed()) {
            // The authorization check above passed and the user revoked it before `add` ran: the
            // same answer the check would have given a moment later.
            NotificationResult.PermissionDenied
        } else {
            NotificationResult.Failed(IllegalStateException(error.localizedDescription))
        }
    }

    private fun NSError.isNotificationsNotAllowed(): Boolean =
        domain == UNErrorDomain && code == UNErrorCodeNotificationsNotAllowed

    override fun cancel(id: String) {
        // One critical section with the decide-and-add of an in-flight post (see post).
        postLock.withLock {
            coalescer.forget(id)
            // Pending too: a post handed over a moment ago may not be presented yet. This also
            // removes a scheduler alarm armed under the same id (documented in 05-platform-notes).
            center.removeDelivered(listOf(id))
            center.removePending(listOf(id))
        }
    }

    override fun cancelAll() {
        postLock.withLock {
            coalescer.clear()
            // Delivered only, mirroring Android's cancelAll(): a pending request is something the OS
            // has been asked to show later, which is nobody's idea of "clear the tray".
            center.removeAllDelivered()
        }
    }

    private fun contentFor(notification: LocalNotification): UNMutableNotificationContent =
        UNMutableNotificationContent().apply {
            setTitle(notification.title)
            setBody(notification.body)
            // The channel id is the closest thing iOS has to a channel: a thread identifier groups
            // an app's notifications together in Notification Centre.
            setThreadIdentifier(notification.channel.id)
            notification.iosCategoryId?.let { setCategoryIdentifier(it) }
            soundFor(notification.channel.sound)?.let { setSound(it) }
            if (SUPPORTS_INTERRUPTION_LEVEL) {
                setInterruptionLevel(interruptionLevelFor(notification.channel.importance))
            }
        }

    private fun soundFor(sound: NotificationSound): UNNotificationSound? = when (sound) {
        NotificationSound.Silent -> null
        NotificationSound.Default -> UNNotificationSound.defaultSound
        is NotificationSound.Custom -> UNNotificationSound.soundNamed(sound.resourceName)
    }

    /**
     * [NotificationImportance.High] deliberately maps to `active`, not `timeSensitive`: the latter
     * needs an Apple-granted entitlement, and a library that quietly required one would break the
     * consumer's app review rather than their build.
     */
    private fun interruptionLevelFor(
        importance: NotificationImportance,
    ): UNNotificationInterruptionLevel = when (importance) {
        NotificationImportance.Low -> UNNotificationInterruptionLevelPassive
        NotificationImportance.Default, NotificationImportance.High ->
            UNNotificationInterruptionLevelActive
    }

    private companion object {

        private const val INTERRUPTION_LEVEL_MIN_IOS: Long = 15

        /**
         * `interruptionLevel` arrived in iOS 15. Sending the setter to an older system is an
         * unrecognized-selector crash, and this module's deployment target is older than that.
         */
        val SUPPORTS_INTERRUPTION_LEVEL: Boolean =
            NSProcessInfo.processInfo.operatingSystemVersion.useContents {
                majorVersion >= INTERRUPTION_LEVEL_MIN_IOS
            }
    }
}
