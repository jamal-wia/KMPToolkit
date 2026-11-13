package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest

/**
 * Round 23: the hand-written scenario table ([SchedulerScenarioTable]) through the real iOS scheduler over a fake
 * notification center and `RecordingAlarmScheduler`.
 */
class IosAlarmSchedulerScenarioTableAdversarialTest {

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
    fun `every row of the scenario table gives the documented answer and the same armed set in the real scheduler and in the double`() {
        runBlocking { withTimeout(120_000) { SchedulerScenarioTable.run(host) } }
    }
}
