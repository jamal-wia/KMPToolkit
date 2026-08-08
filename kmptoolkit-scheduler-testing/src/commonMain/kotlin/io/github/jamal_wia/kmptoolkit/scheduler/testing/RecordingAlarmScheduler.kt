package io.github.jamal_wia.kmptoolkit.scheduler.testing

import io.github.jamal_wia.kmptoolkit.scheduler.AlarmScheduleResult
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmScheduler
import io.github.jamal_wia.kmptoolkit.scheduler.ScheduledAlarm

/**
 * An in-memory [AlarmScheduler] for tests: it arms nothing, records everything, and returns
 * whatever result the test asks for.
 *
 * It exists because the interesting logic in an app that uses this library is *which* alarms it
 * decides to arm and cancel — and asserting that should not need an emulator, a device clock, or a
 * granted permission. It also lets a test drive the paths that are otherwise hard to reach on real
 * hardware: a missing exact-alarm permission, a denied notification authorization, a platform
 * refusal.
 *
 * ```kotlin
 * val scheduler = RecordingAlarmScheduler()
 * ReminderSync(scheduler).apply(reminders)
 *
 * assertEquals(listOf("reminder-1", "reminder-2"), scheduler.armed.map { it.id })
 *
 * scheduler.resultFor = { AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING) }
 * // ...assert your code surfaces the downgrade
 * ```
 *
 * Replacement semantics match the real schedulers: scheduling an id that is already armed replaces
 * it in [armed] rather than adding a second entry, and an alarm whose result is
 * [AlarmScheduleResult.Failed] is not armed at all — it appears in [scheduleCalls] only.
 *
 * **Not thread-safe.** It is a test double meant for one test's coroutine at a time; sharing one
 * instance across concurrently running coroutines on different threads will lose recordings.
 */
public class RecordingAlarmScheduler(

    /**
     * Decides what [schedule] returns for a given alarm. Assignable mid-test, so one instance can
     * report success for some alarms and a downgrade or failure for others.
     */
    public var resultFor: (ScheduledAlarm) -> AlarmScheduleResult = { AlarmScheduleResult.Exact },
) : AlarmScheduler {

    private val armedById: MutableMap<String, ScheduledAlarm> = mutableMapOf()
    private val recordedScheduleCalls: MutableList<ScheduledAlarm> = mutableListOf()
    private val recordedCancelledIds: MutableList<String> = mutableListOf()

    /** Alarms currently armed, in the order their ids were first scheduled. */
    public val armed: List<ScheduledAlarm>
        get() = armedById.values.toList()

    /** Every [schedule] call in order, including ones that replaced or failed. */
    public val scheduleCalls: List<ScheduledAlarm>
        get() = recordedScheduleCalls.toList()

    /**
     * Every id passed to [cancel] or [cancelAll], in order, including ids that were not armed —
     * the real schedulers cannot tell those apart either, and a test asserting "we asked for this
     * to be cancelled" should not have to.
     */
    public val cancelledIds: List<String>
        get() = recordedCancelledIds.toList()

    override suspend fun schedule(alarm: ScheduledAlarm): AlarmScheduleResult {
        recordedScheduleCalls += alarm
        val result: AlarmScheduleResult = resultFor(alarm)
        if (result !is AlarmScheduleResult.Failed) {
            armedById[alarm.id] = alarm
        }
        return result
    }

    override suspend fun cancel(id: String) {
        recordedCancelledIds += id
        armedById.remove(id)
    }

    override suspend fun cancelAll(ids: Collection<String>) {
        ids.forEach { cancel(it) }
    }

    /** Drops every recording and every armed alarm. [resultFor] is left alone. */
    public fun clear() {
        armedById.clear()
        recordedScheduleCalls.clear()
        recordedCancelledIds.clear()
    }
}
