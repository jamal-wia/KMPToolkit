package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger

/**
 * Round 24 (iOS seam audit): a strict stand-in for the scheduler's [NotificationCenterPort] that records every
 * call and refuses what no documented behaviour needs. The call sequence of each operation is part of what the
 * docs promise: a denied authorization arms nothing and asks nothing more, the cap check reads the pending list
 * and stops before `add`, a refused `add` is not compensated by a removal, `cancel` and `cancelAll` touch only
 * the removal call (a refusal channel does not exist there), an empty `cancelAll` makes no call at all, and a
 * request handed to `add` is a one-shot interval trigger of at least one second carrying the id verbatim.
 */
class IosAlarmSchedulerStrictCenterCallDisciplineAdversarialTest {

    private class StrictCenter : NotificationCenterPort {
        val calls: MutableList<String> = mutableListOf()
        val pending: LinkedHashMap<String, UNNotificationRequest> = LinkedHashMap()
        var denied: Boolean = false
        var addError: NSError? = null
        private var inside: Boolean = false

        private fun <T> enter(call: String, body: () -> T): T {
            check(!inside) { "overlapping center calls: $call entered while another call was running" }
            inside = true
            try {
                calls += call
                return body()
            } finally {
                inside = false
            }
        }

        override suspend fun isAuthorizationDenied(): Boolean = enter("auth") { denied }
        override suspend fun pendingIdentifiers(): List<String> = enter("pending") { pending.keys.toList() }
        override suspend fun add(request: UNNotificationRequest): NSError? = enter("add:${request.identifier}") {
            val error: NSError? = addError
            if (error == null) pending[request.identifier] = request
            error
        }

        override suspend fun remove(ids: List<String>) {
            enter("remove:${ids.joinToString("+")}") { ids.forEach { pending.remove(it) } }
        }
    }

    private fun alarm(id: String): ScheduledAlarm =
        ScheduledAlarm(id, "T", 4_102_444_800_000L, AlarmNotification("title", "body", "reminders"), mapOf("k" to "v"))

    private fun scheduler(center: StrictCenter): AlarmScheduler =
        IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)

    private fun run(block: suspend () -> Unit) {
        runBlocking { withTimeout(60_000) { block() } }
    }

    @Test
    fun `a schedule asks authorization then the pending list then adds and nothing else`() = run {
        val center = StrictCenter()
        assertEquals(AlarmScheduleResult.Exact, scheduler(center).schedule(alarm("a")))
        assertEquals(listOf("auth", "pending", "add:a"), center.calls)
        center.calls.clear()
        assertEquals(AlarmScheduleResult.Exact, scheduler(center).schedule(alarm("a")))
        assertEquals(listOf("auth", "pending", "add:a"), center.calls, "a replacement is the same add, not a remove then an add")
    }

    @Test
    fun `a denied authorization stops after the one question and arms nothing`() = run {
        val center = StrictCenter().apply { denied = true }
        val result: AlarmScheduleResult = scheduler(center).schedule(alarm("a"))
        assertEquals(AlarmScheduleResult.Failed(AlarmFailure.NotificationPermissionDenied), result)
        assertEquals(listOf("auth"), center.calls)
        assertTrue(center.pending.isEmpty())
    }

    @Test
    fun `at the cap the pending list is read and add is never reached`() = run {
        val center = StrictCenter()
        repeat(PENDING_REQUEST_CAP) { index: Int -> scheduler(center).schedule(alarm("fill-$index")) }
        center.calls.clear()
        val result: AlarmScheduleResult = scheduler(center).schedule(alarm("one-too-many"))
        assertIs<AlarmScheduleResult.Failed>(result)
        assertIs<AlarmFailure.PlatformError>(result.reason)
        assertEquals(listOf("auth", "pending"), center.calls)
        assertEquals(PENDING_REQUEST_CAP, center.pending.size)
    }

    @Test
    fun `a refused add is reported and not compensated by a removal and the earlier alarm stays`() = run {
        val center = StrictCenter()
        scheduler(center).schedule(alarm("a"))
        center.calls.clear()
        center.addError = NSError.errorWithDomain("test.domain", 7, null)
        val result: AlarmScheduleResult = scheduler(center).schedule(alarm("a"))
        assertIs<AlarmScheduleResult.Failed>(result)
        assertEquals(listOf("auth", "pending", "add:a"), center.calls)
        assertEquals(setOf("a"), center.pending.keys)
    }

    @Test
    fun `cancel and cancelAll make only removal calls and an empty cancelAll makes none`() = run {
        val center = StrictCenter()
        val scheduler: AlarmScheduler = scheduler(center)
        listOf("a", "b", "c").forEach { scheduler.schedule(alarm(it)) }
        center.calls.clear()

        scheduler.cancelAll(emptyList())
        assertEquals(emptyList(), center.calls, "an empty collection is a no-op without touching the center")

        scheduler.cancel("a")
        assertTrue(center.calls.isNotEmpty() && center.calls.all { it.startsWith("remove:") }, "cancel calls: ${center.calls}")
        assertEquals(setOf("b", "c"), center.pending.keys)

        center.calls.clear()
        scheduler.cancelAll(listOf("b", "never", "b"))
        assertTrue(center.calls.all { it.startsWith("remove:") }, "cancelAll calls: ${center.calls}")
        val removed: Set<String> = center.calls.flatMap { it.removePrefix("remove:").split("+") }.toSet()
        assertEquals(setOf("b", "never"), removed, "exactly the ids passed")
        assertEquals(setOf("c"), center.pending.keys)

        center.calls.clear()
        scheduler.cancel("unknown")
        assertTrue(center.calls.all { it.startsWith("remove:") })
    }

    @Test
    fun `the request handed to add is a one shot interval trigger of at least a second with the id and the type verbatim`() = run {
        val center = StrictCenter()
        val config = AlarmSchedulerConfig()
        val ids: List<String> = listOf("plain", "with space", "é💥", "x/y?z#1")
        for (id: String in ids) {
            scheduler(center).schedule(alarm(id).copy(fireAtEpochMillis = Long.MIN_VALUE))
            val request: UNNotificationRequest = center.pending.getValue(id)
            assertEquals(id, request.identifier)
            val trigger = request.trigger
            assertIs<UNTimeIntervalNotificationTrigger>(trigger)
            assertTrue(!trigger.repeats, "an alarm is one-shot")
            assertTrue(trigger.timeInterval >= 1.0 && abs(trigger.timeInterval) < Double.MAX_VALUE, "interval ${trigger.timeInterval}")
            assertEquals("title", request.content.title)
            assertEquals("body", request.content.body)
            assertEquals(id, request.content.userInfo[config.alarmIdKey])
            assertEquals("T", request.content.userInfo[config.alarmTypeKey])
            assertEquals("v", request.content.userInfo["k"])
        }
    }
}
