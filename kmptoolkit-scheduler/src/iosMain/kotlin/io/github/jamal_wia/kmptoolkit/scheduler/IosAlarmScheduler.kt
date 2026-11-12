package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
): AlarmScheduler = IosAlarmScheduler(soundResolver, config, SystemNotificationCenter)

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
    private val center: NotificationCenterPort,
) : AlarmScheduler {

    override suspend fun schedule(alarm: ScheduledAlarm): AlarmScheduleResult {
        // iOS keeps only the soonest-firing 64 pending requests and silently discards the rest,
        // reporting no error from `add`. Refuse up front instead of claiming `Exact` for a request
        // that may never fire. A request that replaces an already-pending identifier does not grow
        // the pending set, so it is not counted. The whole call - authorization check, cap check
        // and add - is one critical section under a process-wide lock (createAlarmScheduler returns
        // a new instance per call), and so are cancel and cancelAll: concurrent calls for one id
        // apply whole, one after the other, exactly as the contract says. Without it a cancel that
        // ran while schedule was still awaiting the authorization status removed nothing and the
        // alarm was armed right after. Requests added by code outside this library are still not
        // serialised with it.
        //
        // The alarm is copied before the lock: the caller may edit or reuse its payload map while
        // this call waits behind another one, and the request armed is the alarm passed at the call.
        val snapshot: ScheduledAlarm = alarm.copy(payload = alarm.payload.toMap())
        return scheduleLock.withLock {
            if (center.isAuthorizationDenied()) {
                return@withLock AlarmScheduleResult.Failed(AlarmFailure.NotificationPermissionDenied)
            }

            val otherPending: Int = center.pendingIdentifiers().count { it != snapshot.id }
            if (otherPending >= PENDING_REQUEST_CAP) {
                return@withLock AlarmScheduleResult.Failed(
                    AlarmFailure.PlatformError(
                        "iOS keeps at most $PENDING_REQUEST_CAP pending local notifications per " +
                            "app; cancel one before scheduling another",
                    ),
                )
            }

            val request: UNNotificationRequest = UNNotificationRequest.requestWithIdentifier(
                identifier = snapshot.id,
                content = contentFor(snapshot),
                trigger = triggerFor(snapshot.fireAtEpochMillis),
            )
            val error: NSError? = center.add(request)
            if (error == null) {
                AlarmScheduleResult.Exact
            } else {
                AlarmScheduleResult.Failed(AlarmFailure.PlatformError(error.localizedDescription))
            }
        }
    }

    override suspend fun cancel(id: String) {
        scheduleLock.withLock { center.remove(listOf(id)) }
    }

    override suspend fun cancelAll(ids: Collection<String>) {
        // Copied now, not after the lock is won: the caller may edit its collection while this
        // call waits behind a schedule in flight.
        val snapshot: List<String> = ids.toList()
        if (snapshot.isEmpty()) return
        scheduleLock.withLock { center.remove(snapshot) }
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
}

/**
 * Serialises every schedule, cancel and cancelAll - the cap check with the add, and a cancel with a
 * schedule of the same id - across every scheduler instance in the process.
 */
private val scheduleLock: Mutex = Mutex()

/** The most pending local notifications iOS keeps per app; anything beyond is silently discarded. */
internal const val PENDING_REQUEST_CAP: Int = 64

/**
 * The slice of `UNUserNotificationCenter` the scheduler uses, so the cap pre-check can be tested
 * against a fake center without an app bundle or an authorization state.
 */
internal interface NotificationCenterPort {
    /**
     * Denied is the only status worth refusing on. `NotDetermined` and `Provisional` are not: the
     * app may still ask, and provisional authorization does deliver quietly — refusing either would
     * throw away an alarm the OS would have honored.
     */
    suspend fun isAuthorizationDenied(): Boolean

    /** Identifiers of every request currently pending. */
    suspend fun pendingIdentifiers(): List<String>

    /** `addNotificationRequest`, awaited: the returned [NSError] is `null` on success. */
    suspend fun add(request: UNNotificationRequest): NSError?

    /** Removes the pending requests with these identifiers. */
    suspend fun remove(ids: List<String>)
}

/** The real center. */
internal object SystemNotificationCenter : NotificationCenterPort {
    private val center: UNUserNotificationCenter
        get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun isAuthorizationDenied(): Boolean =
        suspendCancellableCoroutine { continuation ->
            center.getNotificationSettingsWithCompletionHandler { settings: UNNotificationSettings? ->
                continuation.resume(settings?.authorizationStatus == UNAuthorizationStatusDenied)
            }
        }

    override suspend fun pendingIdentifiers(): List<String> =
        suspendCancellableCoroutine { continuation ->
            center.getPendingNotificationRequestsWithCompletionHandler { requests: List<*>? ->
                continuation.resume(requests.orEmpty().mapNotNull { (it as? UNNotificationRequest)?.identifier })
            }
        }

    override suspend fun add(request: UNNotificationRequest): NSError? =
        suspendCancellableCoroutine { continuation ->
            center.addNotificationRequest(request) { error -> continuation.resume(error) }
        }

    override suspend fun remove(ids: List<String>) {
        center.removePendingNotificationRequestsWithIdentifiers(ids)
    }
}
