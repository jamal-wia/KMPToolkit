package io.github.jamal_wia.kmptoolkit.scheduler

/**
 * Arms and cancels exact-time, one-shot local alarms.
 *
 * Obtain one from the platform factory — `createAlarmScheduler(context, handlers, config)` in
 * `androidMain`, `createAlarmScheduler(soundResolver, config)` in `iosMain` — and hold it wherever
 * you hold your other singletons. The factories differ because the two platforms genuinely need
 * different inputs; everything after construction is this one common interface.
 *
 * The scheduler keeps **no state of its own**: it does not remember which alarms it armed, does
 * not persist anything, and cannot enumerate or re-arm them. The list of alarms that ought to
 * exist belongs to the caller — see `docs/kmptoolkit-scheduler/01-overview.md`.
 *
 * Implementations are safe to call from any thread.
 */
public interface AlarmScheduler {

    /**
     * Arms [alarm], replacing any pending alarm with the same [ScheduledAlarm.id].
     *
     * Replacement is by id on both platforms, so calling this twice with one id leaves exactly one
     * alarm armed, at the later call's time — there is no way to accidentally stack duplicates.
     *
     * A [ScheduledAlarm.fireAtEpochMillis] already in the past is legal and never throws: Android
     * fires such an alarm as soon as it is armed, iOS clamps the trigger to one second from now.
     *
     * @return what the OS actually granted — see [AlarmScheduleResult]. Never throws for a
     *   permission or platform refusal; those come back as [AlarmScheduleResult.Inexact] or
     *   [AlarmScheduleResult.Failed].
     */
    public suspend fun schedule(alarm: ScheduledAlarm): AlarmScheduleResult

    /**
     * Cancels the alarm with [id]. Cancelling an id that is not armed is a no-op, not an error —
     * neither platform can tell the two cases apart, so there is nothing truthful to report.
     */
    public suspend fun cancel(id: String)

    /**
     * Cancels every id in [ids]. An empty collection is a no-op.
     *
     * This cancels **exactly the ids passed**, not "everything this module ever scheduled":
     * Android's `AlarmManager` cannot enumerate its own alarms, and this module stores nothing. If
     * you want a clean slate, pass every id you know about.
     */
    public suspend fun cancelAll(ids: Collection<String>)
}
