package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest

/**
 * The iOS half of the cross-platform differential for the scheduler: the seeded sequences and the
 * model of [SchedulerProtocolFuzz] that `AndroidAlarmSchedulerProtocolFuzzDifferentialAdversarialTest`
 * runs through the Android scheduler, here over a fake notification center.
 */
class IosAlarmSchedulerProtocolFuzzDifferentialAdversarialTest {

    private class Center : NotificationCenterPort {
        val requests: LinkedHashMap<String, UNNotificationRequest> = LinkedHashMap()
        override suspend fun isAuthorizationDenied(): Boolean = false
        override suspend fun pendingIdentifiers(): List<String> = requests.keys.toList()
        override suspend fun add(request: UNNotificationRequest): NSError? {
            requests[request.identifier] = request
            return null
        }

        override suspend fun remove(ids: List<String>) {
            ids.forEach { requests.remove(it) }
        }
    }

    private val center = Center()
    private val config = AlarmSchedulerConfig()

    private val host = object : SchedulerFuzzHost {
        override fun reset() {
            center.requests.clear()
        }

        override fun newScheduler(): AlarmScheduler = IosAlarmScheduler(AlarmSoundResolver { null }, config, center)

        override fun armed(ids: List<String>): Map<String, ArmedView> =
            center.requests.mapValues { (_, request: UNNotificationRequest) ->
                val info: Map<Any?, *> = request.content.userInfo
                ArmedView(
                    type = info[config.alarmTypeKey] as String,
                    title = request.content.title,
                    body = request.content.body,
                    payload = info.entries
                        .filter { it.key != config.alarmIdKey && it.key != config.alarmTypeKey }
                        .associate { it.key as String to it.value as String },
                )
            }
    }

    @Test
    fun `seeded sequences give the answers and the pending requests of the shared model`() {
        runBlocking { withTimeout(120_000) { SchedulerProtocolFuzz.run(host, seeds = 200, steps = 40) } }
    }
}
