package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mutation survivor hunt (round 13). Contract (scheduler docs 04 and 05, iOS): an alarm becomes ONE local
 * notification request whose identifier is the alarm id, whose title and body are the alarm's, whose sound is the
 * resolver's answer for the alarm's channel (the platform default when the resolver has none), which fires once
 * (no repeat) after the time left until `fireAtEpochMillis`.
 */
class IosAlarmSchedulerRequestContentAdversarialTest {

    private class RecordingCenter : NotificationCenterPort {
        val added: MutableList<UNNotificationRequest> = mutableListOf()

        override suspend fun isAuthorizationDenied(): Boolean = false
        override suspend fun pendingIdentifiers(): List<String> = emptyList()
        override suspend fun add(request: UNNotificationRequest): NSError? {
            added += request
            return null
        }

        override suspend fun remove(ids: List<String>) = Unit
    }

    private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    @Test
    fun `the request carries the alarm id title body and a one shot trigger at the time left`(): Unit = runBlocking<Unit> {
        val center = RecordingCenter()
        val asked: MutableList<String> = mutableListOf()
        val scheduler = IosAlarmScheduler(
            AlarmSoundResolver { channelId: String -> asked += channelId; "ding.caf" },
            AlarmSchedulerConfig(),
            center,
        )
        val fireAt: Long = nowMillis() + 3_600_000L
        val result: AlarmScheduleResult = scheduler.schedule(
            ScheduledAlarm(
                id = "pill-7",
                type = "PILL",
                fireAtEpochMillis = fireAt,
                notification = AlarmNotification(title = "Take it", body = "Two tablets", channelId = "meds"),
            ),
        )
        assertEquals(AlarmScheduleResult.Exact, result)
        val request: UNNotificationRequest = center.added.single()
        assertEquals("pill-7", request.identifier)
        assertEquals("Take it", request.content.title)
        assertEquals("Two tablets", request.content.body)
        assertEquals(listOf("meds"), asked, "the resolver must be asked once, with the alarm's channel id")
        assertNotNull(request.content.sound)
        val trigger = request.trigger
        assertIs<UNTimeIntervalNotificationTrigger>(trigger)
        assertFalse(trigger.repeats, "an alarm fires once")
        assertTrue(trigger.timeInterval in 3_590.0..3_601.0, "interval was ${trigger.timeInterval} s for a fire time an hour ahead")
    }

    @Test
    fun `a resolver without a sound still gives the request a sound`(): Unit = runBlocking<Unit> {
        val center = RecordingCenter()
        val scheduler = IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)
        scheduler.schedule(
            ScheduledAlarm(
                id = "a",
                type = "T",
                fireAtEpochMillis = nowMillis() + 60_000L,
                notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
            ),
        )
        assertNotNull(center.added.single().content.sound, "no resolved sound means the platform default, not silence")
        Unit
    }
}
