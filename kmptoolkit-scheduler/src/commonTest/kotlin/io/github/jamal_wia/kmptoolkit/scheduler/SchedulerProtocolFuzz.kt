package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.random.Random
import kotlin.test.assertEquals

/** What a platform has to offer the shared scheduler fuzzer: a scheduler over one shared "system" state and a read of it. */
internal interface SchedulerFuzzHost {

    /** Clears the system state: nothing armed. */
    fun reset()

    /** A scheduler over the same system state as every earlier one; a restart is another call of this. */
    fun newScheduler(): AlarmScheduler

    /** What the system holds armed for the given ids, as the notification and tap payload it will deliver. */
    fun armed(ids: List<String>): Map<String, ArmedView>
}

/** What a fired alarm shows and carries; the fields both platforms can read back. */
internal data class ArmedView(
    val type: String,
    val title: String,
    val body: String,
    val payload: Map<String, String>,
)

/**
 * One reference model of the documented scheduling contract (docs 03 to 05), shared by both platforms:
 * schedule replaces an id's alarm, cancel and cancelAll (over known, unknown, repeated and empty id lists)
 * remove it, a restart (another scheduler over the same state) changes nothing, and the system holds
 * exactly the model's alarms with the content of the last schedule of each. The same seeded sequences
 * run on Android and iOS, so the two must also agree with each other. The seed is in every failure message.
 */
internal object SchedulerProtocolFuzz {

    val IDS: List<String> = listOf("a", "b c", "é", "x/y", "💥", "%41", "A", "..", "q?r=1#s")
    private val TIMES: List<Long> = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 1_700_000_000_000L, 4_102_444_800_000L, Long.MAX_VALUE)
    private val TYPES: List<String> = listOf("T", "REMINDER", "t u")
    private val PAYLOADS: List<Map<String, String>> = listOf(
        emptyMap(),
        mapOf("k" to "v"),
        mapOf("k" to "", "é" to "💥"),
        mapOf("a b" to "c=d&e", "k2" to "x".repeat(300)),
    )

    suspend fun run(host: SchedulerFuzzHost, seeds: Int, steps: Int) {
        repeat(seeds) { seed: Int -> runSeed(host, seed, steps) }
    }

    private suspend fun runSeed(host: SchedulerFuzzHost, seed: Int, steps: Int) {
        val random = Random(seed)
        host.reset()
        var scheduler: AlarmScheduler = host.newScheduler()
        val model: MutableMap<String, ArmedView> = mutableMapOf()
        val log = StringBuilder()
        repeat(steps) { step: Int ->
            val where = "seed $seed step $step"
            when (random.nextInt(11)) {
                0, 1, 2, 3, 4 -> {
                    val id: String = IDS.random(random)
                    val alarm = ScheduledAlarm(
                        id = id,
                        type = TYPES.random(random),
                        fireAtEpochMillis = TIMES.random(random),
                        notification = AlarmNotification(title = "t$step", body = "b${random.nextInt(2)}", channelId = "c"),
                        payload = PAYLOADS.random(random),
                    )
                    val result: AlarmScheduleResult = scheduler.schedule(alarm)
                    log.append("schedule($id@${alarm.fireAtEpochMillis},${alarm.type},${alarm.payload.keys})=${result::class.simpleName}; ")
                    assertEquals(AlarmScheduleResult.Exact, result, "$where: answer. Steps: $log")
                    model[id] = ArmedView(alarm.type, alarm.notification.title, alarm.notification.body, alarm.payload)
                }

                5, 6 -> {
                    val id: String = IDS.random(random)
                    scheduler.cancel(id)
                    model.remove(id)
                    log.append("cancel($id); ")
                }

                7 -> {
                    val some: List<String> = List(random.nextInt(0, 5)) { (IDS + "never-scheduled").random(random) }
                    scheduler.cancelAll(some)
                    some.forEach { model.remove(it) }
                    log.append("cancelAll($some); ")
                }

                8 -> {
                    scheduler = host.newScheduler()
                    log.append("restart; ")
                }

                else -> {
                    // A second alarm for an id already armed: the replacement must carry only its own payload.
                    val id: String = IDS.random(random)
                    val alarm = ScheduledAlarm(
                        id = id,
                        type = "T",
                        fireAtEpochMillis = TIMES.random(random),
                        notification = AlarmNotification(title = "r$step", body = "", channelId = "c"),
                    )
                    val result: AlarmScheduleResult = scheduler.schedule(alarm)
                    log.append("replace($id)=${result::class.simpleName}; ")
                    assertEquals(AlarmScheduleResult.Exact, result, "$where: answer. Steps: $log")
                    model[id] = ArmedView("T", alarm.notification.title, "", emptyMap())
                }
            }
            assertEquals(model.toMap(), host.armed(IDS), "$where: what the system holds differs from the model. Steps: $log")
        }
    }
}
