package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 19 (process death), the iOS counterpart of the Android kill matrix: the process dies at every
 * single call into the notification center during an edit session (schedule three alarms, edit one,
 * retire one, add one, cancel one), once before the call takes effect and once after (the answer is
 * lost), and a new process, with a new scheduler over the same center, whose pending requests survive,
 * runs the guide's reconcile pass over the desired list. Afterwards the center holds exactly the desired
 * identifiers, the dead process's scheduler lock did not outlive it in a way that blocks the new one
 * (the lock is process-wide here, so the test also proves a killed call releases it), and a second pass
 * changes nothing.
 */
class IosAlarmSchedulerKillAtEveryStepAdversarialTest {

    private class ProcessKilled(call: Int) : Error("process killed at center call $call")

    /** A center whose pending requests outlive the scheduler, counting calls and dying at the configured one. */
    private class PersistentCenter : NotificationCenterPort {
        val pending: LinkedHashMap<String, String> = linkedMapOf()
        var calls: Int = 0
        var killAt: Int = 0
        var killAfterEffect: Boolean = false

        private fun step(effect: () -> Unit) {
            calls += 1
            val dying: Boolean = calls == killAt
            if (dying && !killAfterEffect) throw ProcessKilled(calls)
            effect()
            if (dying) throw ProcessKilled(calls)
        }

        override suspend fun isAuthorizationDenied(): Boolean {
            step { }
            return false
        }

        override suspend fun pendingIdentifiers(): List<String> {
            step { }
            return pending.keys.toList()
        }

        override suspend fun add(request: UNNotificationRequest): NSError? {
            step { pending[request.identifier] = request.content.title }
            return null
        }

        override suspend fun remove(ids: List<String>) {
            step { ids.forEach { pending.remove(it) } }
        }
    }

    private fun alarm(id: String, version: Int): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "T",
        fireAtEpochMillis = 10_000_000_000_000L + version,
        notification = AlarmNotification(title = "t$version", body = "b", channelId = "c"),
    )

    private fun scheduler(center: NotificationCenterPort): AlarmScheduler =
        IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)

    private fun session(scheduler: AlarmScheduler) = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            scheduler.schedule(alarm("a", 1))
            scheduler.schedule(alarm("b", 1))
            scheduler.schedule(alarm("c", 1))
            scheduler.schedule(alarm("a", 2))
            scheduler.cancel("b")
            scheduler.schedule(alarm("d", 1))
            scheduler.cancelAll(listOf("c", "never-armed"))
        }
    }

    private val desired: Map<String, ScheduledAlarm> = mapOf("a" to alarm("a", 2), "d" to alarm("d", 1))
    private val retired: List<String> = listOf("b", "c")

    private fun reconcile(scheduler: AlarmScheduler): List<AlarmScheduleResult> = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            retired.forEach { scheduler.cancel(it) }
            desired.values.map { scheduler.schedule(it) }
        }
    }

    @Test
    fun `a death at any single center call of an edit session is repaired by one reconcile pass`() {
        val clean = PersistentCenter()
        session(scheduler(clean))
        val total: Int = clean.calls
        assertTrue(total >= 10, "the session is expected to call the center many times, made $total")

        for (afterEffect: Boolean in listOf(false, true)) {
            for (kill: Int in 1..total) {
                val label = "kill position $kill (${if (afterEffect) "after" else "before"} the effect)"
                val center = PersistentCenter()
                center.killAt = kill
                center.killAfterEffect = afterEffect
                val death: Throwable? = runCatching { session(scheduler(center)) }.exceptionOrNull()
                assertTrue(death is ProcessKilled, "$label: the session was expected to die, got $death")

                // The new process: same center and pending requests, a new scheduler, no kill any more.
                center.killAt = 0
                val results: List<AlarmScheduleResult> = reconcile(scheduler(center))
                assertTrue(results.all { it is AlarmScheduleResult.Exact }, "$label: the reconcile pass answered $results")
                assertEquals(desired.keys.toSet(), center.pending.keys.toSet(), "$label: pending requests after one pass")
                assertEquals(
                    desired.mapValues { it.value.notification.title },
                    center.pending.toMap(),
                    "$label: content of the pending requests",
                )

                reconcile(scheduler(center))
                assertEquals(desired.keys.toSet(), center.pending.keys.toSet(), "$label: after a second pass")

                runBlocking { withTimeout(TIMEOUT_MILLIS) { scheduler(center).cancelAll(desired.keys + retired) } }
                assertEquals(emptyMap(), center.pending.toMap(), "$label: teardown")
            }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 20_000L
    }
}
