package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest

/**
 * Round 20 (consumer setups): two iOS schedulers over one notification center, which is what two
 * instances in a process are (the center is the process's). The platform notes say the authorization
 * check, the cap check and the add run under one process-wide lock, so the 64-request cap holds across
 * instances even when the center takes its time to accept a request: 100 schedules from both instances
 * at once leave exactly 64 pending, the first 64 to get the lock answered Exact and the rest Failed.
 */
class IosAlarmSchedulerTwoInstancesAdversarialTest {

    private class SlowCenter : NotificationCenterPort {
        val pending: LinkedHashSet<String> = linkedSetOf()
        override suspend fun isAuthorizationDenied(): Boolean = false
        override suspend fun pendingIdentifiers(): List<String> = pending.toList()
        override suspend fun add(request: UNNotificationRequest): NSError? {
            delay(2)
            pending += request.identifier
            return null
        }
        override suspend fun remove(ids: List<String>) {
            pending.removeAll(ids.toSet())
        }
    }

    private fun alarm(id: String): ScheduledAlarm =
        ScheduledAlarm(id, "T", 10_000_000_000_000L, AlarmNotification("t", "b", "c"))

    private fun scheduler(center: NotificationCenterPort): AlarmScheduler =
        IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)

    @Test
    fun `the pending cap holds across two instances scheduling concurrently through a slow center`() {
        val center = SlowCenter()
        val a: AlarmScheduler = scheduler(center)
        val b: AlarmScheduler = scheduler(center)

        val results: List<AlarmScheduleResult> = runBlocking {
            withTimeout(60_000L) {
                (0 until 100).map { index: Int ->
                    async(Dispatchers.Default) { (if (index % 2 == 0) a else b).schedule(alarm("id-$index")) }
                }.awaitAll()
            }
        }

        assertEquals(64, center.pending.size, "no more than the platform cap may be handed to the center")
        assertEquals(64, results.count { it == AlarmScheduleResult.Exact })
        assertEquals(36, results.count { it is AlarmScheduleResult.Failed })
    }

    @Test
    fun `one instance cancels and replaces what the other armed`() {
        val center = SlowCenter()
        val a: AlarmScheduler = scheduler(center)
        val b: AlarmScheduler = scheduler(center)
        runBlocking {
            withTimeout(30_000L) {
                a.schedule(alarm("x"))
                b.schedule(alarm("x"))
                assertEquals(setOf("x"), center.pending, "one id is one request, whoever armed it")
                b.cancel("x")
                assertTrue(center.pending.isEmpty())
                a.schedule(alarm("y"))
                b.cancelAll(listOf("y", "never"))
                assertTrue(center.pending.isEmpty())
            }
        }
    }
}
