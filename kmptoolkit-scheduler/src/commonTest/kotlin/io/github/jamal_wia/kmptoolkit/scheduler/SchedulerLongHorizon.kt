package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What a platform offers the 30-day model: a scheduler over one shared system state, and the system's own behaviour. */
internal interface SchedulerHorizonHost {

    /** The epoch millisecond the virtual clock starts at (a constant on Android, the real now on iOS). */
    fun baseEpochMillis(): Long

    /** The real clock, for the platform that converts an absolute instant into an interval at schedule time. */
    fun realNowMillis(): Long

    /** Clears the system state: nothing armed, permission granted. */
    fun reset()

    /** A scheduler over the same system state as every earlier one; a restart is another call of this. */
    fun newScheduler(): AlarmScheduler

    /** The exact-alarm right (Android 12+) or the notification authorization (iOS) the next schedule sees. */
    fun setPermission(granted: Boolean)

    /** What the documents say `schedule` answers with the permission in this state. */
    fun expectedAnswer(granted: Boolean): AlarmScheduleResult

    /** True where the platform loses every alarm at a reboot (Android); iOS keeps pending requests. */
    val wipesOnReboot: Boolean

    /** The reboot: the platform forgets every alarm and pending intent. A no-op where it does not. */
    fun wipe()

    /** What the system holds, id to content; fails when an id holds more than one alarm. */
    fun armed(): Map<String, ArmedView>

    /** The system delivers (and thereby forgets) these alarms, as it does when their time has come. */
    fun fire(ids: Collection<String>)

    /** Null when the armed alarm of [id] is set for [fireAt], else what is wrong; [beforeReal] and [afterReal] bracket the schedule call. */
    fun timeProblem(id: String, fireAt: Long, beforeReal: Long, afterReal: Long): String?
}

/** The ids the model draws from, for a host that has to clear the system by name. */
internal object SchedulerLongHorizonIds {
    val ALL: List<String> = listOf("reminder-1", "reminder-2", "study-é", "x y", "💥", "Aa", "BB", "r/9", "never")
}

/**
 * Round 24 (docs-only newcomer): a 30-day virtual-clock model of the scheduling contract, written from
 * docs/kmptoolkit-scheduler/02 to 04 only. The virtual clock jumps by seconds up to three days; alarms come
 * due and the system fires (forgets) them, the consumer's desired-state table re-arms after a reboot as the
 * guide shows, permission flips between grants and refusals, the scheduler instance is replaced (process
 * restart) and `cancel` / `cancelAll` hit fired, armed and unknown ids. The model: an id owns the alarm of its
 * last accepted schedule until it fires or is cancelled; a refused schedule (iOS authorization denied) leaves
 * what was armed as it was; the answer is the one the permission state allows. After every step the system must
 * hold exactly the model's alarms, with the content and instant of the last accepted schedule of each. Typed
 * outcomes only. The seed is in every failure message.
 */
internal object SchedulerLongHorizon {

    private val IDS: List<String> = SchedulerLongHorizonIds.ALL - "never"
    private val TYPES: List<String> = listOf("STUDY_REMINDER", "SNOOZE")
    private val DELTAS: List<Duration> = listOf(
        (-1).days, (-1).milliseconds, 0.milliseconds, 1.milliseconds, 999.milliseconds, 1.seconds, 1.minutes,
        1.hours, 1.days, 9.days, 29.days,
    )
    private val TICKS: List<Duration> = listOf(
        1.seconds, 59.seconds, 1.minutes, 1.hours, 6.hours, 1.days - 1.milliseconds, 1.days, 3.days,
    )
    private val TOTAL: Duration = 30.days

    class Tally {
        var scheduled: Int = 0
        var refused: Int = 0
        var inexact: Int = 0
        var fired: Int = 0
        var reboots: Int = 0
        var restarts: Int = 0
        var replacedAfterFire: Int = 0
        var finalVirtualTime: Duration = Duration.ZERO
    }

    fun assertExercised(tally: Tally, host: SchedulerHorizonHost) {
        assertTrue(tally.scheduled > 200, "scheduled ${tally.scheduled}")
        assertTrue(tally.fired > 50, "fired ${tally.fired}")
        assertTrue(tally.reboots > 3, "reboots ${tally.reboots}")
        assertTrue(tally.restarts > 3, "restarts ${tally.restarts}")
        assertTrue(tally.replacedAfterFire > 10, "an id fired and was scheduled again only ${tally.replacedAfterFire} times")
        assertTrue(tally.finalVirtualTime > 10.days, "the virtual clock only reached ${tally.finalVirtualTime}")
        if (host.expectedAnswer(granted = false) != AlarmScheduleResult.Exact) {
            assertTrue(
                tally.refused + tally.inexact > 5,
                "permission flips never changed an answer (refused ${tally.refused}, inexact ${tally.inexact})",
            )
        }
    }

    suspend fun run(host: SchedulerHorizonHost, seeds: Int, steps: Int): Tally {
        val tally = Tally()
        repeat(seeds) { seed: Int -> runSeed(host, seed, steps, tally) }
        return tally
    }

    private class Entry(val alarm: ScheduledAlarm)

    private suspend fun runSeed(host: SchedulerHorizonHost, seed: Int, steps: Int, tally: Tally) {
        val random = Random(2_000 + seed)
        host.reset()
        val base: Long = host.baseEpochMillis()
        var now: Duration = Duration.ZERO
        var granted = true
        var scheduler: AlarmScheduler = host.newScheduler()
        val model: MutableMap<String, Entry> = linkedMapOf()
        val firedIds: MutableSet<String> = mutableSetOf()
        val log: ArrayDeque<String> = ArrayDeque()
        fun note(entry: String) {
            log.addLast(entry)
            if (log.size > 40) log.removeFirst()
        }

        fun virtualNowMillis(): Long = base + now.inWholeMilliseconds

        suspend fun scheduleAndJudge(alarm: ScheduledAlarm, where: String) {
            val before: Long = host.realNowMillis()
            val result: AlarmScheduleResult = scheduler.schedule(alarm)
            val after: Long = host.realNowMillis()
            val context = "$where. Last steps: ${log.joinToString("; ")}"
            assertEquals(host.expectedAnswer(granted), result, "$context: the answer for permission granted=$granted")
            when (result) {
                is AlarmScheduleResult.Failed -> tally.refused++
                is AlarmScheduleResult.Inexact -> {
                    tally.inexact++
                    tally.scheduled++
                }

                AlarmScheduleResult.Exact -> tally.scheduled++
            }
            if (result !is AlarmScheduleResult.Failed) {
                if (alarm.id in firedIds) tally.replacedAfterFire++
                firedIds.remove(alarm.id)
                model[alarm.id] = Entry(alarm)
                val problem: String? = host.timeProblem(alarm.id, alarm.fireAtEpochMillis, before, after)
                assertNull(problem, "$context: ${alarm.id} was armed for the wrong instant: $problem")
            }
        }

        repeat(steps) { step: Int ->
            val where = "seed $seed step $step virtual ${now.inWholeHours} h"
            when (random.nextInt(14)) {
                0, 1, 2, 3, 4, 5 -> {
                    val id: String = IDS.random(random)
                    val alarm = ScheduledAlarm(
                        id = id,
                        type = TYPES.random(random),
                        fireAtEpochMillis = virtualNowMillis() + DELTAS.random(random).inWholeMilliseconds,
                        notification = AlarmNotification(title = "t$step", body = "b${random.nextInt(2)}", channelId = "reminders"),
                        payload = if (random.nextBoolean()) mapOf("lessonId" to "${random.nextInt(50)}") else emptyMap(),
                    )
                    note("schedule($id@+${alarm.fireAtEpochMillis - virtualNowMillis()}ms,${alarm.type})")
                    scheduleAndJudge(alarm, where)
                }

                6, 7 -> {
                    val id: String = IDS.random(random)
                    scheduler.cancel(id)
                    model.remove(id)
                    firedIds.remove(id)
                    note("cancel($id)")
                }

                8 -> {
                    val some: List<String> = List(random.nextInt(0, 5)) { (IDS + "never").random(random) }
                    scheduler.cancelAll(some)
                    some.forEach { model.remove(it); firedIds.remove(it) }
                    note("cancelAll($some)")
                }

                9 -> {
                    scheduler = host.newScheduler()
                    tally.restarts++
                    note("restart")
                }

                10 -> {
                    granted = !granted
                    host.setPermission(granted)
                    note("permission granted=$granted")
                }

                11 -> {
                    // Reboot, then the guide's reconcile: the consumer re-arms every alarm of its own table.
                    tally.reboots++
                    host.wipe()
                    if (host.wipesOnReboot) scheduler = host.newScheduler()
                    note("reboot")
                    for (entry: Entry in model.values.toList()) {
                        scheduleAndJudge(entry.alarm, "$where (reconcile)")
                    }
                }

                else -> {
                    var by: Duration = TICKS.random(random)
                    if (now + by > TOTAL) by = TOTAL - now
                    now += by
                    note("+${by.inWholeMilliseconds}ms")
                }
            }
            // The system fires what has come due; a fired alarm is gone from it and from the model (nothing to cancel).
            val due: List<String> = model.filterValues { it.alarm.fireAtEpochMillis <= virtualNowMillis() }.keys.toList()
            if (due.isNotEmpty()) {
                host.fire(due)
                due.forEach { model.remove(it) }
                firedIds += due
                tally.fired += due.size
            }
            val expected: Map<String, ArmedView> = model.mapValues { (_, entry: Entry) ->
                ArmedView(entry.alarm.type, entry.alarm.notification.title, entry.alarm.notification.body, entry.alarm.payload)
            }
            assertEquals(expected, host.armed(), "$where: what the system holds differs from the model. Last steps: ${log.joinToString("; ")}")
        }
        tally.finalVirtualTime = maxOf(tally.finalVirtualTime, now)
    }
}
