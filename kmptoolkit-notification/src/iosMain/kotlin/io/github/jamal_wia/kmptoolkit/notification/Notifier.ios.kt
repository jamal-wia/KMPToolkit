@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionHandler
import io.github.jamal_wia.kmptoolkit.permission.isGranted
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSProcessInfo
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
 * with `kmptoolkit-permission` (`Permission.NOTIFICATIONS`) at a moment you choose. Posting while
 * unauthorized returns [NotificationResult.PermissionDenied] rather than handing the OS a request
 * it silently discards.
 *
 * @param permissionHandler from `kmptoolkit-permission`; used only to check authorization.
 * @param config coalescing limits; see [NotificationConfig]. Its `actionBroadcastAction` is
 *   Android-only and ignored here.
 */
public fun createNotifier(
    permissionHandler: PermissionHandler,
    config: NotificationConfig = NotificationConfig(),
): Notifier = IosNotifier(
    permissionHandler = permissionHandler,
    coalescer = ProgressCoalescer(config.progressBucketPercent, config.minProgressInterval),
)

/**
 * `UNUserNotificationCenter` implementation.
 *
 * The caller's id is the request identifier verbatim, so re-posting one id replaces its
 * notification and cancelling removes exactly it — no id mapping is needed as on Android.
 *
 * Three fields of [LocalNotification] have no iOS counterpart at all and are ignored:
 * [LocalNotification.icon] (the OS always shows the app icon), [LocalNotification.ongoing] and
 * [LocalNotification.autoCancel]. [LocalNotification.actions] is ignored too, but for a different
 * reason — see [NotificationAction] and [LocalNotification.iosCategoryId].
 */
internal class IosNotifier(
    private val permissionHandler: PermissionHandler,
    private val coalescer: ProgressCoalescer,
) : Notifier {

    private val center: UNUserNotificationCenter
        get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun post(id: String, notification: LocalNotification): NotificationResult {
        // Validation, not a catch: `requestWithIdentifier` rejects an empty identifier by raising an
        // Objective-C exception, and Kotlin/Native cannot catch one — the process would die. This is
        // the only reason post() throws at all, and it throws for a caller's bug, not for a device
        // state. See Notifier.post's KDoc.
        requireValidNotificationId(id)
        // iOS reports "the user switched notifications off in Settings" and "the user answered no
        // to the prompt" as one and the same authorization status, so there is no honest way to
        // return NotificationsDisabled here — PermissionDenied covers both.
        if (!permissionHandler.check(Permission.NOTIFICATIONS).isGranted) {
            return NotificationResult.PermissionDenied
        }
        if (!coalescer.shouldPost(id, notification.progress)) return NotificationResult.Coalesced

        val request: UNNotificationRequest = UNNotificationRequest.requestWithIdentifier(
            identifier = id,
            content = contentFor(notification),
            // No trigger: deliver it now. A trigger is what turns this into scheduling, which is
            // kmptoolkit-scheduler's job, not this module's.
            trigger = null,
        )
        val error: NSError? = suspendCancellableCoroutine { continuation ->
            center.addNotificationRequest(request) { error: NSError? ->
                continuation.resume(error)
            }
        }
        return if (error == null) {
            NotificationResult.Posted
        } else {
            NotificationResult.Failed(IllegalStateException(error.localizedDescription))
        }
    }

    override fun cancel(id: String) {
        coalescer.forget(id)
        val notificationCenter: UNUserNotificationCenter = center
        notificationCenter.removeDeliveredNotificationsWithIdentifiers(listOf(id))
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(listOf(id))
    }

    override fun cancelAll() {
        coalescer.clear()
        // Delivered only, mirroring Android's cancelAll(): a pending request is something the OS
        // has been asked to show later, which is nobody's idea of "clear the tray".
        center.removeAllDeliveredNotifications()
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
