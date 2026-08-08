package io.github.jamal_wia.kmptoolkit.scheduler

/**
 * Outcome of one [AlarmScheduler.schedule] call.
 *
 * Scheduling an exact alarm is a request the OS is free to downgrade or refuse, and the caller is
 * the only one who knows whether a downgrade is acceptable — a medication reminder is not a
 * newsletter. So this type never hides a degradation behind a `Boolean`: the three cases are
 * "armed exactly", "armed, but the OS may deliver it late", and "not armed at all", and the two
 * non-exact ones carry a typed reason.
 *
 * Nothing here is meant to be shown to a user. Map it to your own copy if you surface it.
 */
public sealed interface AlarmScheduleResult {

    /**
     * The alarm is armed for the exact instant requested.
     *
     * On Android that means `AlarmManager.setExactAndAllowWhileIdle` accepted it — it fires at the
     * requested time even in Doze. On iOS it means `UNUserNotificationCenter` accepted the pending
     * request; see `05-platform-notes.md` for what "exact" does and does not guarantee there.
     */
    public data object Exact : AlarmScheduleResult

    /**
     * The alarm **is** armed, but only inexactly — the OS may deliver it late, typically by
     * minutes rather than hours.
     *
     * This is the graceful degradation path: the alarm was still scheduled, so a caller that can
     * live with drift needs to do nothing. A caller that cannot should treat this as a failure,
     * cancel the alarm, and ask the user to grant exact-alarm permission.
     */
    public data class Inexact(val reason: InexactReason) : AlarmScheduleResult

    /** The alarm is **not** armed. Nothing will fire. */
    public data class Failed(val reason: AlarmFailure) : AlarmScheduleResult
}

/** Why an alarm could only be armed inexactly. Android-only today; iOS never reports it. */
public enum class InexactReason {

    /**
     * The app does not hold `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` (Android 12+), so exact
     * scheduling was never attempted. Either the permission is not declared in the consuming app's
     * manifest at all, or the user has not granted it in "Alarms & reminders".
     */
    EXACT_ALARM_PERMISSION_MISSING,

    /**
     * The permission was held when the scheduler checked and gone by the time it armed the alarm —
     * the OS rejected the exact call. Android 12+ lets the user revoke this permission at any
     * moment, so the check-then-act gap is real and cannot be closed by the caller.
     */
    EXACT_ALARM_PERMISSION_REVOKED,
}

/** Why an alarm could not be armed at all. */
public sealed interface AlarmFailure {

    /**
     * The OS refuses to show notifications for this app, so a pre-scheduled local notification
     * would be silently discarded. iOS only: the user denied (or has not been asked for)
     * notification authorization.
     */
    public data object NotificationPermissionDenied : AlarmFailure

    /**
     * The platform scheduling service could not be obtained at all — `ALARM_SERVICE` returned
     * nothing on Android. Not expected on a healthy device; treat it as unrecoverable.
     */
    public data object SchedulerUnavailable : AlarmFailure

    /**
     * The platform rejected the request for some other reason — for example iOS's cap of roughly
     * 64 pending local notifications per app.
     *
     * @param message the platform's own diagnostic text, if it supplied one. For logs and bug
     *   reports; it is not localized and must never be shown to a user.
     */
    public data class PlatformError(val message: String?) : AlarmFailure
}
