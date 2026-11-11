package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger

/**
 * A seeded operation-sequence fuzzer for the iOS scheduler against a model of docs 04 and 05: schedule
 * (any instant, replacing an id's pending request), cancel, cancelAll over known, unknown, repeated and
 * empty id lists, authorization denied and granted, the platform refusing an add, and code outside the
 * library filling the 64-request budget. After every step the center must hold exactly the model's
 * pending ids, never more than the cap through this library's own adds, every answer must be the first
 * of denied, over the cap, refused that applies, and every request handed over must carry the alarm id
 * verbatim, a time interval trigger of at least one second and a finite number of seconds, and the id
 * and type under the configured keys. The seed is in every failure message.
 */
class IosAlarmSchedulerModelFuzzAdversarialTest {

    private class Center : NotificationCenterPort {
        val pending: LinkedHashSet<String> = LinkedHashSet()
        val requests: LinkedHashMap<String, UNNotificationRequest> = LinkedHashMap()
        var denied: Boolean = false
        var refuseAdd: Boolean = false

        override suspend fun isAuthorizationDenied(): Boolean = denied
        override suspend fun pendingIdentifiers(): List<String> = pending.toList()
        override suspend fun add(request: UNNotificationRequest): NSError? {
            if (refuseAdd) return NSError.errorWithDomain("test", 7, null)
            pending += request.identifier
            requests[request.identifier] = request
            return null
        }

        override suspend fun remove(ids: List<String>) {
            ids.forEach { pending.remove(it); requests.remove(it) }
        }
    }

    private fun alarm(id: String, at: Long, tag: String): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TYPE-$tag",
        fireAtEpochMillis = at,
        notification = AlarmNotification(title = "t$tag", body = "b", channelId = "c"),
        payload = mapOf("tag" to tag),
    )

    @Test
    fun `no sequence of schedules cancels refusals and foreign requests makes the center or an answer disagree with the contract`() = runBlocking {
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed)
            val center = Center()
            val config = AlarmSchedulerConfig()
            val scheduler: AlarmScheduler = IosAlarmScheduler(AlarmSoundResolver { null }, config, center)
            val model: MutableSet<String> = mutableSetOf()
            val log = StringBuilder()
            var foreign = 0
            repeat(STEPS) { step: Int ->
                when (random.nextInt(11)) {
                    0, 1, 2, 3, 4 -> {
                        val id: String = IDS.random(random)
                        val at: Long = TIMES.random(random)
                        val alarm: ScheduledAlarm = alarm(id, at, "$seed-$step")
                        val result: AlarmScheduleResult = scheduler.schedule(alarm)
                        log.append("schedule($id@$at,denied=${center.denied},refuse=${center.refuseAdd},others=${model.count { it != id }})=${result::class.simpleName}; ")
                        val others: Int = center.pending.count { it != id }
                        when {
                            center.denied -> assertIs<AlarmFailure.NotificationPermissionDenied>(assertIs<AlarmScheduleResult.Failed>(result).reason, "seed $seed step $step: $log")
                            others >= PENDING_REQUEST_CAP -> assertIs<AlarmFailure.PlatformError>(assertIs<AlarmScheduleResult.Failed>(result).reason, "seed $seed step $step: $log")
                            center.refuseAdd -> assertIs<AlarmFailure.PlatformError>(assertIs<AlarmScheduleResult.Failed>(result).reason, "seed $seed step $step: $log")
                            else -> {
                                assertEquals(AlarmScheduleResult.Exact, result, "seed $seed step $step. Steps: $log")
                                model += id
                                val request: UNNotificationRequest = center.requests.getValue(id)
                                assertEquals(id, request.identifier)
                                val trigger = assertIs<UNTimeIntervalNotificationTrigger>(request.trigger, "seed $seed step $step: trigger kind. Steps: $log")
                                assertTrue(!trigger.timeInterval.isNaN() && !trigger.timeInterval.isInfinite() && trigger.timeInterval >= 1.0, "seed $seed step $step: interval ${trigger.timeInterval}. Steps: $log")
                                assertTrue(!trigger.repeats, "seed $seed step $step: the trigger repeats. Steps: $log")
                                val info: Map<Any?, *> = request.content.userInfo
                                assertEquals(id, info[config.alarmIdKey], "seed $seed step $step: id in userInfo. Steps: $log")
                                assertEquals(alarm.type, info[config.alarmTypeKey], "seed $seed step $step: type in userInfo. Steps: $log")
                                assertEquals(alarm.payload.getValue("tag"), info["tag"], "seed $seed step $step: payload in userInfo. Steps: $log")
                            }
                        }
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
                        model.removeAll(some.toSet())
                        log.append("cancelAll($some); ")
                    }

                    8 -> {
                        center.denied = !center.denied
                        log.append("denied=${center.denied}; ")
                    }

                    9 -> {
                        center.refuseAdd = !center.refuseAdd
                        log.append("refuse=${center.refuseAdd}; ")
                    }

                    else -> {
                        // Code outside the library fills the budget (or frees what it filled).
                        if (random.nextBoolean() && foreign > 0) {
                            center.pending.remove("foreign-${foreign--}")
                        } else {
                            val fill: Int = random.nextInt(1, 40)
                            repeat(fill) { center.pending += "foreign-${++foreign}" }
                        }
                        log.append("foreign=$foreign; ")
                    }
                }
                val ours: Set<String> = center.pending.filter { !it.startsWith("foreign-") }.toSet()
                assertEquals(model.toSet(), ours, "seed $seed step $step: the center and the model disagree. Steps: $log")
            }
        }
    }

    private companion object {
        const val SEEDS: Int = 60
        const val STEPS: Int = 60
        val IDS: List<String> = listOf("a", "b", "c", "d/e", "é")
        val TIMES: List<Long> = listOf(
            Long.MIN_VALUE, -1L, 0L, 1L, 1_000L, 1_700_000_000_000L, 4_102_444_800_000L, Long.MAX_VALUE - 1, Long.MAX_VALUE,
        )
    }
}
