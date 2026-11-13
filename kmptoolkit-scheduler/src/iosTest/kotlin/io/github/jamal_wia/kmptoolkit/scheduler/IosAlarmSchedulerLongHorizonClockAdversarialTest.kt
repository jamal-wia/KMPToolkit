package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger

/**
 * Round 24: the 30-day virtual-clock model of [SchedulerLongHorizon] through the real iOS scheduler over a fake
 * center. The virtual clock starts at the real now; the trigger interval the scheduler hands over is checked
 * against the real clock read before and after the call (the scheduler converts an absolute instant into an
 * interval at schedule time), so the check is exact up to the bracket, not a wall-time tolerance.
 */
class IosAlarmSchedulerLongHorizonClockAdversarialTest {

    private class Center : NotificationCenterPort {
        val requests: LinkedHashMap<String, UNNotificationRequest> = LinkedHashMap()
        var denied: Boolean = false
        override suspend fun isAuthorizationDenied(): Boolean = denied
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

    // timing-hygiene-ignore: TH2 producer brackets the real clock read the iOS scheduler makes itself at schedule time (no clock seam exists), never an elapsed-time assertion
    private fun realNow(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    private val host = object : SchedulerHorizonHost {
        override fun baseEpochMillis(): Long = realNow()

        override fun realNowMillis(): Long = realNow()

        override fun reset() {
            center.requests.clear()
            center.denied = false
        }

        override fun newScheduler(): AlarmScheduler = IosAlarmScheduler(AlarmSoundResolver { null }, config, center)

        override fun setPermission(granted: Boolean) {
            center.denied = !granted
        }

        override fun expectedAnswer(granted: Boolean): AlarmScheduleResult =
            if (granted) AlarmScheduleResult.Exact else AlarmScheduleResult.Failed(AlarmFailure.NotificationPermissionDenied)

        override val wipesOnReboot: Boolean = false

        override fun wipe() = Unit

        override fun armed(): Map<String, ArmedView> =
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

        override fun fire(ids: Collection<String>) {
            ids.forEach { center.requests.remove(it) }
        }

        override fun timeProblem(id: String, fireAt: Long, beforeReal: Long, afterReal: Long): String? {
            val trigger = center.requests.getValue(id).trigger
            assertIs<UNTimeIntervalNotificationTrigger>(trigger)
            // The scheduler truncates the real now to whole milliseconds; two milliseconds of slack cover that and the clamp.
            val low: Double = max(1.0, (fireAt - afterReal) / 1000.0 - 0.002)
            val high: Double = max(1.0, (fireAt - beforeReal) / 1000.0 + 0.002)
            return if (trigger.timeInterval in low..high) null else "interval ${trigger.timeInterval} s outside $low..$high s"
        }
    }

    @Test
    fun `seeded 30 day sequences give the answers and the pending requests of the documented model`() {
        runBlocking {
            withTimeout(120_000) {
                val tally: SchedulerLongHorizon.Tally = SchedulerLongHorizon.run(host, seeds = 10, steps = 400)
                SchedulerLongHorizon.assertExercised(tally, host)
            }
        }
    }
}
