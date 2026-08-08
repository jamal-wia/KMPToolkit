package io.github.jamal_wia.kmptoolkit.scheduler

/**
 * Reacts to a fired alarm of one [type].
 *
 * **Android only.** Handlers are passed to `createAlarmScheduler(context, handlers, config)` at
 * startup; when an alarm fires, this module's broadcast receiver resolves the handler whose [type]
 * equals the alarm's [ScheduledAlarm.type] (via [handlerFor]) and calls [onFire]. On iOS the OS
 * shows the pre-scheduled notification by itself and nothing of yours runs, so no handler is
 * involved — see `docs/kmptoolkit-scheduler/05-platform-notes.md`.
 *
 * **Contract for implementors:**
 * - [onFire] runs on a background dispatcher inside a short-lived broadcast window (roughly ten
 *   seconds). Do the minimum — post a notification, enqueue work — and return. There is no
 *   guarantee the process survives past your return.
 * - [onFire] may run after process death, in a freshly created process where nothing but
 *   `Application.onCreate` has run. Do not rely on state some screen was supposed to populate.
 * - A throwable escaping [onFire] propagates into the receiver's coroutine and crashes the
 *   process; catch what you can handle.
 */
public interface AlarmHandler {

    /** Stable key matched against [ScheduledAlarm.type]. Exact, case-sensitive. */
    public val type: String

    /** Called at the alarm's fire time, on a background dispatcher. */
    public suspend fun onFire(alarm: ScheduledAlarm)
}

/**
 * Resolves the handler registered for [type], or `null` when none is.
 *
 * This is the platform-agnostic half of "dispatch a fired alarm to its owner", kept out of the
 * Android receiver so it can be tested without an emulator, and public because it is also the
 * documented way to reuse this dispatch rule in your own receiver if you ever route alarms
 * yourself.
 *
 * A `null` result means the alarm is dropped. That mirrors the OS's own contract: once an alarm
 * has fired, nothing can retroactively un-fire it, so there is nothing to retry and nothing to
 * report to.
 *
 * If two handlers claim the same [type] — a configuration mistake, since [type] is meant to be one
 * stable key per feature — the **first** one in the collection wins. Treat that as "exactly one
 * handler per type", not as a priority mechanism.
 */
public fun Collection<AlarmHandler>.handlerFor(type: String): AlarmHandler? =
    firstOrNull { it.type == type }
