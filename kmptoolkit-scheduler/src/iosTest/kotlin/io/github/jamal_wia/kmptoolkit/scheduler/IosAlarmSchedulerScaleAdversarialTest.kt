package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 12 (scale) for the iOS scheduler against a fake notification center: a rolling window of 64
 * over thousands of alarms, a flood past the documented cap with and without foreign pending requests,
 * cancelAll of a very large collection, id churn, a huge id and payload, and the extremes of the fire time.
 * What the guide documents for the cap: the 65th new identifier is refused with Failed(PlatformError) and
 * nothing is added; re-scheduling a pending id and cancelling both stay possible at the cap.
 */
class IosAlarmSchedulerScaleAdversarialTest {

    private class Center(foreign: List<String> = emptyList()) : NotificationCenterPort {
        val pending: LinkedHashMap<String, UNNotificationRequest> = LinkedHashMap()
        val foreignIds: List<String> = foreign
        var addCalls: Int = 0
        var removeCalls: Int = 0
        var removedTotal: Int = 0
        var maxPending: Int = 0

        init {
            foreign.forEach { pending[it] = UNNotificationRequest.requestWithIdentifier(it, platform.UserNotifications.UNMutableNotificationContent(), null) }
        }

        override suspend fun isAuthorizationDenied(): Boolean = false
        override suspend fun pendingIdentifiers(): List<String> = pending.keys.toList()
        override suspend fun add(request: UNNotificationRequest): NSError? {
            addCalls++
            pending[request.identifier] = request
            if (pending.size > maxPending) maxPending = pending.size
            return null
        }

        override suspend fun remove(ids: List<String>) {
            removeCalls++
            removedTotal += ids.size
            ids.forEach { pending.remove(it) }
        }
    }

    private fun alarm(id: String, at: Long = 10_000_000_000_000L, payload: Map<String, String> = emptyMap()): ScheduledAlarm =
        ScheduledAlarm(id, "T", at, AlarmNotification("t", "b", "c"), payload)

    private fun scheduler(center: NotificationCenterPort): IosAlarmScheduler =
        IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)

    @Test
    fun rollingWindowOfSixtyFourOverThreeThousandAlarmsNeverRefusesAndNeverExceedsTheCap() = runBlocking<Unit> {
        withTimeout(60_000) {
            val center = Center()
            val scheduler: IosAlarmScheduler = scheduler(center)
            val order: ArrayDeque<String> = ArrayDeque()
            repeat(3_000) { n ->
                if (center.pending.size == PENDING_REQUEST_CAP) scheduler.cancel(order.removeFirst())
                val id = "window-$n"
                assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm(id)), "alarm $n")
                order.addLast(id)
                assertTrue(center.pending.size <= PENDING_REQUEST_CAP)
            }
            assertEquals(PENDING_REQUEST_CAP, center.pending.size)
            assertEquals(PENDING_REQUEST_CAP, center.maxPending)
            scheduler.cancelAll(order.toList())
            assertEquals(0, center.pending.size)
        }
    }

    @Test
    fun aFloodOfFiveHundredNewIdsAdmitsExactlySixtyFourAndTheRestAreRefusedWithoutBeingAdded() = runBlocking<Unit> {
        withTimeout(60_000) {
            val center = Center()
            val scheduler: IosAlarmScheduler = scheduler(center)
            val results: List<AlarmScheduleResult> = (0 until 500).map { scheduler.schedule(alarm("flood-$it")) }
            assertEquals(PENDING_REQUEST_CAP, results.count { it == AlarmScheduleResult.Exact })
            results.drop(PENDING_REQUEST_CAP).forEach {
                val failed = assertIs<AlarmScheduleResult.Failed>(it)
                assertIs<AlarmFailure.PlatformError>(failed.reason)
            }
            assertEquals(PENDING_REQUEST_CAP, center.addCalls, "a refused request must never reach the center")
            // Replacing a pending id is still possible at the cap, many times over.
            repeat(200) { n ->
                assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm("flood-${n % PENDING_REQUEST_CAP}", at = 20_000_000_000_000L + n)))
            }
            assertEquals(PENDING_REQUEST_CAP, center.pending.size)
            // One cancel makes room for exactly one new id.
            scheduler.cancel("flood-0")
            assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm("late-1")))
            assertIs<AlarmScheduleResult.Failed>(scheduler.schedule(alarm("late-2")))
        }
    }

    @Test
    fun foreignPendingRequestsCountTowardTheCapAsDocumented() = runBlocking<Unit> {
        val center = Center(foreign = (0 until 20).map { "foreign-$it" })
        val scheduler: IosAlarmScheduler = scheduler(center)
        val results: List<AlarmScheduleResult> = (0 until 100).map { scheduler.schedule(alarm("ours-$it")) }
        assertEquals(PENDING_REQUEST_CAP - 20, results.count { it == AlarmScheduleResult.Exact })
        assertEquals(PENDING_REQUEST_CAP, center.pending.size)
        // An id of ours that equals a foreign id replaces it and is not counted twice: documented as the same identifier.
        assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm("foreign-3")))
        assertEquals(PENDING_REQUEST_CAP, center.pending.size)
    }

    @Test
    fun cancelAllOfHundredThousandIdsWithDuplicatesIsOneCenterCallAndAnEmptyCollectionNone() = runBlocking<Unit> {
        withTimeout(60_000) {
            val center = Center()
            val scheduler: IosAlarmScheduler = scheduler(center)
            (0 until 40).forEach { scheduler.schedule(alarm("keep-$it")) }
            val ids: List<String> = (0 until 100_000).map { "gone-${it % 5_000}" } + "keep-0" + "keep-0"
            val before: Int = center.removeCalls
            scheduler.cancelAll(ids)
            assertEquals(before + 1, center.removeCalls, "one call to the center for the whole collection")
            assertEquals(39, center.pending.size)
            val calls: Int = center.removeCalls
            scheduler.cancelAll(emptyList())
            assertEquals(calls, center.removeCalls, "an empty collection must not reach the center")
        }
    }

    @Test
    fun twentyThousandScheduleCancelPairsLeaveNothingPending() = runBlocking<Unit> {
        withTimeout(60_000) {
            val center = Center()
            val scheduler: IosAlarmScheduler = scheduler(center)
            repeat(20_000) { n ->
                assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm("pair-$n")))
                scheduler.cancel("pair-$n")
            }
            assertEquals(0, center.pending.size)
            assertEquals(20_000, center.addCalls)
            assertEquals(1, center.maxPending)
        }
    }

    @Test
    fun aHugeIdAndAHugePayloadReachTheRequestIntact() = runBlocking<Unit> {
        val center = Center()
        val scheduler: IosAlarmScheduler = scheduler(center)
        val id: String = "x".repeat(100_000)
        val payload: Map<String, String> = (0 until 20_000).associate { "k$it" to "v$it" }
        assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm(id, payload = payload)))
        val request: UNNotificationRequest = center.pending.getValue(id)
        assertEquals(id, request.identifier)
        assertEquals(20_000 + 2, request.content.userInfo.size, "the payload plus the alarm's own id and type")
        assertEquals("v19999", request.content.userInfo["k19999"])
        assertEquals(id, request.content.userInfo[AlarmSchedulerConfig.DEFAULT_ALARM_ID_KEY])
        scheduler.cancel(id)
        assertEquals(0, center.pending.size)
    }

    @Test
    fun fireTimesAtTheExtremesOfLongGiveAValidTriggerAndNeverThrow() = runBlocking<Unit> {
        val center = Center()
        val scheduler: IosAlarmScheduler = scheduler(center)
        val times: List<Long> = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)
        times.forEachIndexed { index, at ->
            assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm("extreme-$index", at = at)), "fire time $at")
            val trigger = center.pending.getValue("extreme-$index").trigger as UNTimeIntervalNotificationTrigger
            assertTrue(trigger.timeInterval >= 1.0 && trigger.timeInterval.isFinite(), "fire time $at gave ${trigger.timeInterval}")
            assertEquals(false, trigger.repeats)
        }
    }
}
