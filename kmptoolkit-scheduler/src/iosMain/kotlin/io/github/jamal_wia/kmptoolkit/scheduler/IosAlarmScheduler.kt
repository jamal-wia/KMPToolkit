package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSettings
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * Creates the iOS [AlarmScheduler], backed by `UNUserNotificationCenter`.
 *
 * Unlike Android there is no handler list: iOS runs none of your code at fire time, the OS simply
 * presents the notification you pre-scheduled. Everything the user will see must therefore be in
 * the [ScheduledAlarm] at schedule time.
 *
 * Requesting notification authorization is the app's job and is not done here — this module never
 * shows a system prompt on your behalf. Scheduling while authorization is denied returns
 * [AlarmScheduleResult.Failed] with [AlarmFailure.NotificationPermissionDenied] instead of arming
 * a notification the OS would discard.
 *
 * @param soundResolver maps a channel id to a bundled sound filename; defaults to "always the
 *   platform default sound".
 * @param config identifiers written into the notification's `userInfo`; see [AlarmSchedulerConfig].
 *   Its `alarmIntentScheme` is Android-only and ignored here.
 */
public fun createAlarmScheduler(
    soundResolver: AlarmSoundResolver = AlarmSoundResolver { null },
    config: AlarmSchedulerConfig = AlarmSchedulerConfig(),
): AlarmScheduler = IosAlarmScheduler(soundResolver, config)

/**
 * `UNUserNotificationCenter` implementation.
 *
 * [ScheduledAlarm.id] is the notification-request identifier verbatim, so re-adding one id replaces
 * its pending request and cancelling removes exactly it — no request-code mapping is needed as on
 * Android.
 */
internal class IosAlarmScheduler(
    private val soundResolver: AlarmSoundResolver,
    private val config: AlarmSchedulerConfig,
) : AlarmScheduler {

    private val center: UNUserNotificationCenter
        get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun schedule(alarm: ScheduledAlarm): AlarmScheduleResult {
        val notificationCenter: UNUserNotificationCenter = center
        if (notificationCenter.isAuthorizationDenied()) {
            return AlarmScheduleResult.Failed(AlarmFailure.NotificationPermissionDenied)
        }

        val request: UNNotificationRequest = UNNotificationRequest.requestWithIdentifier(
            identifier = alarm.id,
            content = contentFor(alarm),
            trigger = triggerFor(alarm.fireAtEpochMillis),
        )
        val error: NSError? = notificationCenter.add(request)
        return if (error == null) {
            AlarmScheduleResult.Exact
        } else {
            AlarmScheduleResult.Failed(AlarmFailure.PlatformError(error.localizedDescription))
        }
    }

    override suspend fun cancel(id: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
    }

    override suspend fun cancelAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        center.removePendingNotificationRequestsWithIdentifiers(ids.toList())
    }

    private fun contentFor(alarm: ScheduledAlarm): UNMutableNotificationContent =
        UNMutableNotificationContent().apply {
            setTitle(alarm.notification.title)
            setBody(alarm.notification.body)
            // iOS has no channels, so the sound is resolved per notification from the channel id
            // the alarm carries, falling back to the platform default.
            setSound(toSound(soundResolver.soundFor(alarm.notification.channelId)))
            // The payload goes in as-is, at the top level, because this dictionary is all a tap
            // handler ever receives. Populated with the same keys a remote push carries, a tapped
            // local notification is indistinguishable from a tapped push and one handler serves
            // both. The alarm's own id and type ride alongside under the configured keys.
            setUserInfo(
                alarm.payload + mapOf(
                    config.alarmIdKey to alarm.id,
                    config.alarmTypeKey to alarm.type,
                ),
            )
        }

    private fun triggerFor(fireAtEpochMillis: Long): UNTimeIntervalNotificationTrigger {
        val nowEpochMillis: Long = (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()
        return UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            triggerIntervalSeconds(fireAtEpochMillis, nowEpochMillis),
            false,
        )
    }

    /** A bundled custom sound by name, or the platform default when the channel resolves to none. */
    private fun toSound(soundFileName: String?): UNNotificationSound =
        soundFileName?.let { UNNotificationSound.soundNamed(it) } ?: UNNotificationSound.defaultSound

    /** `addNotificationRequest`, awaited: the returned [NSError] is `null` on success. */
    private suspend fun UNUserNotificationCenter.add(request: UNNotificationRequest): NSError? =
        suspendCancellableCoroutine { continuation ->
            addNotificationRequest(request) { error -> continuation.resume(error) }
        }

    /**
     * Denied is the only status worth refusing on. `NotDetermined` and `Provisional` are not: the
     * app may still ask, and provisional authorization does deliver quietly — refusing either would
     * throw away an alarm the OS would have honored.
     */
    private suspend fun UNUserNotificationCenter.isAuthorizationDenied(): Boolean =
        suspendCancellableCoroutine { continuation ->
            getNotificationSettingsWithCompletionHandler { settings: UNNotificationSettings? ->
                continuation.resume(settings?.authorizationStatus == UNAuthorizationStatusDenied)
            }
        }
}
