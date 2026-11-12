package io.github.jamal_wia.kmptoolkit.scheduler

import io.github.jamal_wia.kmptoolkit.scheduler.testing.RecordingAlarmScheduler
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.UserNotifications.UNNotificationRequest

/**
 * Round 18 (fake fidelity), iOS: the three ways the real iOS scheduler refuses (authorization denied, the 64 pending
 * requests cap, an error from `add`), scripted into RecordingAlarmScheduler as a consumer would script them, over seeded
 * sequences: a refused schedule leaves the alarm already pending under the id exactly as it was, in the real scheduler
 * and in the double.
 */
class IosAlarmSchedulerFidelityGatesAdversarialTest {

    private class Center : NotificationCenterPort {
        val requests: LinkedHashMap<String, UNNotificationRequest> = LinkedHashMap()
        var denied: Boolean = false
        var addError: Boolean = false
        var foreignPending: Int = 0
        override suspend fun isAuthorizationDenied(): Boolean = denied
        override suspend fun pendingIdentifiers(): List<String> = requests.keys.toList() + List(foreignPending) { "foreign-$it" }
        override suspend fun add(request: UNNotificationRequest): NSError? {
            if (addError) return NSError.errorWithDomain("test.center", 3, mapOf<Any?, Any?>(NSLocalizedDescriptionKey to "add refused"))
            requests[request.identifier] = request
            return null
        }

        override suspend fun remove(ids: List<String>) {
            ids.forEach { requests.remove(it) }
        }
    }

    private val ids: List<String> = listOf("a", "b c", "é", "x/y")

    @Test
    fun `refusals of every kind leave the pending alarms as they were in the real scheduler and in the double`() {
        runBlocking {
            withTimeout(120_000) {
                repeat(60) { seed: Int ->
                    val random = Random(seed * 7 + 5)
                    val center = Center()
                    val scheduler: AlarmScheduler = IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)
                    val fake = RecordingAlarmScheduler()
                    val log = StringBuilder()
                    repeat(40) { step: Int ->
                        val where = "seed $seed step $step"
                        if (random.nextInt(6) == 0) {
                            val id: String = ids.random(random)
                            scheduler.cancel(id)
                            fake.cancel(id)
                            log.append("cancel($id); ")
                        } else {
                            val mode: Int = random.nextInt(4)
                            center.denied = mode == 1
                            center.addError = mode == 2
                            center.foreignPending = if (mode == 3) 64 else 0
                            val id: String = ids.random(random)
                            val alarm = ScheduledAlarm(id, "T", 1_900_000_000_000L + step, AlarmNotification("t$step", "b$mode", "c"), mapOf("m" to "$mode"))
                            val answered: AlarmScheduleResult = scheduler.schedule(alarm)
                            log.append("schedule($id,mode=$mode)=${answered::class.simpleName}; ")
                            when (mode) {
                                1 -> assertEquals(AlarmScheduleResult.Failed(AlarmFailure.NotificationPermissionDenied), answered, "$where. Steps: $log")
                                2 -> {
                                    val failed = answered as AlarmScheduleResult.Failed
                                    assertTrue((failed.reason as AlarmFailure.PlatformError).message == "add refused", "$where: the platform text is passed on. Steps: $log")
                                }
                                3 -> assertTrue((answered as AlarmScheduleResult.Failed).reason is AlarmFailure.PlatformError, "$where: the cap refuses. Steps: $log")
                                else -> assertEquals(AlarmScheduleResult.Exact, answered, "$where. Steps: $log")
                            }
                            fake.resultFor = { answered }
                            fake.schedule(alarm)
                        }
                        val pending: Map<String, Pair<String, String>> = center.requests.mapValues { (_, r: UNNotificationRequest) -> r.content.title to r.content.body }
                        val double: Map<String, Pair<String, String>> = fake.armed.associate { it.id to (it.notification.title to it.notification.body) }
                        assertEquals(pending, double, "$where: the pending requests and the double disagree. Steps: $log")
                    }
                }
            }
        }
    }
}
