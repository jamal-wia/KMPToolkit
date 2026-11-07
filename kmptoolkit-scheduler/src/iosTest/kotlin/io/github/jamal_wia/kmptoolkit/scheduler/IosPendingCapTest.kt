package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the 64-request pre-check of [IosAlarmScheduler] against a fake notification center.
 * iOS keeps only the soonest 64 pending requests and silently discards the rest, so a request
 * past the cap must come back as a failure rather than `Exact`.
 */
class IosPendingCapTest {

    private class FakeCenter(
        initialPending: List<String>,
        private val denied: Boolean = false,
    ) : NotificationCenterPort {
        val pending: MutableList<String> = initialPending.toMutableList()
        var addCalls: Int = 0

        override suspend fun isAuthorizationDenied(): Boolean = denied
        override suspend fun pendingIdentifiers(): List<String> = pending.toList()
        override suspend fun add(request: UNNotificationRequest): NSError? {
            addCalls++
            // Adding an identifier already pending replaces it, like the real center.
            if (request.identifier !in pending) pending += request.identifier
            return null
        }

        override suspend fun remove(ids: List<String>) {
            pending.removeAll(ids)
        }
    }

    private fun ids(count: Int): List<String> = (1..count).map { "alarm-$it" }

    private fun alarm(id: String): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = 10_000_000_000_000L,
        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
    )

    private fun scheduler(center: NotificationCenterPort): IosAlarmScheduler =
        IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)

    @Test
    fun `below the cap the request is added`() = runBlocking {
        val center = FakeCenter(ids(63))
        val result: AlarmScheduleResult = scheduler(center).schedule(alarm("new"))
        assertEquals(AlarmScheduleResult.Exact, result)
        assertEquals(64, center.pending.size)
    }

    @Test
    fun `an empty center accepts a request`() = runBlocking {
        val center = FakeCenter(emptyList())
        assertEquals(AlarmScheduleResult.Exact, scheduler(center).schedule(alarm("a")))
        assertEquals(1, center.addCalls)
    }

    @Test
    fun `a new request at the cap fails with a platform error and is not added`() = runBlocking {
        val center = FakeCenter(ids(64))
        val result: AlarmScheduleResult = scheduler(center).schedule(alarm("new"))
        val failed = assertIs<AlarmScheduleResult.Failed>(result)
        assertIs<AlarmFailure.PlatformError>(failed.reason)
        assertEquals(0, center.addCalls)
        assertEquals(64, center.pending.size)
    }

    @Test
    fun `rescheduling an already pending id at the cap succeeds`() = runBlocking {
        val center = FakeCenter(ids(64))
        val result: AlarmScheduleResult = scheduler(center).schedule(alarm("alarm-10"))
        assertEquals(AlarmScheduleResult.Exact, result)
        assertEquals(1, center.addCalls)
        assertEquals(64, center.pending.size)
    }

    @Test
    fun `cancelling one frees room for a new request at the cap`() = runBlocking {
        val center = FakeCenter(ids(64))
        val scheduler = scheduler(center)
        scheduler.cancel("alarm-1")
        assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm("new")))
    }

    @Test
    fun `denied authorization wins over the cap check`() = runBlocking {
        val center = FakeCenter(ids(64), denied = true)
        val result: AlarmScheduleResult = scheduler(center).schedule(alarm("new"))
        val failed = assertIs<AlarmScheduleResult.Failed>(result)
        assertTrue(failed.reason is AlarmFailure.NotificationPermissionDenied)
    }
}
