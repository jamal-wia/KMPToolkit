package io.github.jamal_wia.kmptoolkit.scheduler

import io.github.jamal_wia.kmptoolkit.scheduler.testing.RecordingAlarmScheduler
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * Runs one seeded script through a real scheduler (over a [SchedulerFuzzHost]) and through
 * [RecordingAlarmScheduler] side by side. The double is told what the real scheduler answered; the two
 * must agree on which alarms are armed, with what content, after every step, and the double's logs must
 * hold exactly what was asked, in order, with the ids that were not armed included.
 */
internal object SchedulerFidelityDriver {

    private val TIMES: List<Long> = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 1_700_000_000_000L, Long.MAX_VALUE)
    private val TYPES: List<String> = listOf("T", "REMINDER", "t u")
    private val PAYLOADS: List<Map<String, String>> = listOf(
        emptyMap(),
        mapOf("k" to "v"),
        mapOf("k" to "", "é" to "💥"),
        mapOf("a b" to "c=d&e", "k2" to "x".repeat(300)),
        // Keys and values a consumer's own data can contain: an empty key, line breaks, direction marks, a very long key.
        mapOf("" to "empty key", "line\nbreak" to "tab\there", "\u202E" to "\uFEFF"),
        mapOf("k".repeat(5_000) to "v".repeat(5_000)),
    )
    private val TEXTS: List<String> = listOf("", " ", "plain", "line\nbreak", "\u202Erlo", "é💥", "\uFEFF", "t".repeat(2_000))
    private val CHANNEL_IDS: List<String> = listOf("c", " c ", "é/💥", "a b?c#d", "x".repeat(300))

    suspend fun run(host: SchedulerFuzzHost, seeds: Int, steps: Int) {
        repeat(seeds) { seed: Int -> runSeed(host, seed, steps) }
    }

    private suspend fun runSeed(host: SchedulerFuzzHost, seed: Int, steps: Int) {
        val random = Random(seed * 104729 + 7)
        host.reset()
        var real: AlarmScheduler = host.newScheduler()
        val fake = RecordingAlarmScheduler()
        val askedSchedules: MutableList<ScheduledAlarm> = mutableListOf()
        val askedCancels: MutableList<String> = mutableListOf()
        val order: MutableList<String> = mutableListOf()
        val log = StringBuilder()
        // SchedulerProtocolFuzz.IDS plus the unpaired surrogates (what a truncated emoji leaves) and the "?" a lossy URI encoding
        // turns them into: each must stay its own alarm on both platforms and in the double.
        val ids: List<String> = SchedulerProtocolFuzz.IDS + listOf("\uD800", "\uDC00", "x\uD83D", "?")

        repeat(steps) { step: Int ->
            val where = "seed $seed step $step"
            when (random.nextInt(9)) {
                0, 1, 2, 3, 4 -> {
                    val id: String = ids.random(random)
                    val alarm = ScheduledAlarm(
                        id = id,
                        type = TYPES.random(random),
                        fireAtEpochMillis = TIMES.random(random),
                        notification = AlarmNotification(title = TEXTS.random(random), body = TEXTS.random(random), channelId = CHANNEL_IDS.random(random)),
                        payload = PAYLOADS.random(random),
                    )
                    val answered: AlarmScheduleResult = real.schedule(alarm)
                    fake.resultFor = { answered }
                    val recorded: AlarmScheduleResult = fake.schedule(alarm)
                    askedSchedules += alarm
                    if (answered !is AlarmScheduleResult.Failed && id !in order) order += id
                    log.append("schedule($id@${alarm.fireAtEpochMillis},${alarm.type})=${answered::class.simpleName}; ")
                    assertEquals(answered, recorded, "$where: the double answers what it was told. Steps: $log")
                }

                5, 6 -> {
                    val id: String = ids.random(random)
                    real.cancel(id)
                    fake.cancel(id)
                    askedCancels += id
                    order.remove(id)
                    log.append("cancel($id); ")
                }

                7 -> {
                    val some: List<String> = List(random.nextInt(0, 5)) { (ids + "never-scheduled").random(random) }
                    real.cancelAll(some)
                    fake.cancelAll(some)
                    askedCancels += some
                    order.removeAll(some.toSet())
                    log.append("cancelAll($some); ")
                }

                else -> {
                    real = host.newScheduler()
                    log.append("restart; ")
                }
            }
            val system: Map<String, ArmedView> = host.armed(ids)
            val double: Map<String, ArmedView> = fake.armed.associate { alarm: ScheduledAlarm ->
                alarm.id to ArmedView(alarm.type, alarm.notification.title, alarm.notification.body, alarm.payload)
            }
            assertEquals(system, double, "$where: the double and the real system disagree on what is armed. Steps: $log")
            assertEquals(order, fake.armed.map { it.id }, "$where: the double lists armed alarms in first-scheduled order. Steps: $log")
            assertEquals(askedSchedules, fake.scheduleCalls, "$where: the schedule log. Steps: $log")
            assertEquals(askedCancels, fake.cancelledIds, "$where: the cancel log. Steps: $log")
        }
    }
}
