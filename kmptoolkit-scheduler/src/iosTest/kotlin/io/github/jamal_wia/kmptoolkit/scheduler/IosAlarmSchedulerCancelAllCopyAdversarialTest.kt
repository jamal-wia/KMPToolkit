package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `docs/kmptoolkit-scheduler/04-api-reference.md`, `cancelAll`: "The ids are copied first, so a
 * collection you change while it runs does not break it."
 *
 * The caller's collection is read the moment `cancelAll` is called, whatever the lock is doing. The
 * iOS scheduler serialises every call behind one process-wide lock; a `cancelAll` that queues behind
 * a slow `schedule` must still cancel the ids that were in the collection when it was called, not
 * the ones left in it when the lock finally frees up.
 */
class IosAlarmSchedulerCancelAllCopyAdversarialTest {

    private class GatedCenter(
        initialPending: List<String>,
        private val authGate: CompletableDeferred<Unit>,
    ) : NotificationCenterPort {
        val pending: MutableList<String> = initialPending.toMutableList()
        val removeCalls: MutableList<List<String>> = mutableListOf()

        override suspend fun isAuthorizationDenied(): Boolean {
            authGate.await()
            return false
        }

        override suspend fun pendingIdentifiers(): List<String> = pending.toList()

        override suspend fun add(request: UNNotificationRequest): NSError? {
            if (request.identifier !in pending) pending += request.identifier
            return null
        }

        override suspend fun remove(ids: List<String>) {
            removeCalls += ids.toList()
            pending.removeAll(ids)
        }
    }

    private fun alarm(id: String): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = 10_000_000_000_000L,
        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
    )

    private fun scheduler(center: NotificationCenterPort): IosAlarmScheduler =
        IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)

    private fun queuedBehindSlowSchedule(mutate: (MutableList<String>) -> Unit): GatedCenter {
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val center = GatedCenter(listOf("a", "b"), gate)
        runBlocking {
            try {
                withTimeout(TIMEOUT_MILLIS) {
                    val subject: IosAlarmScheduler = scheduler(center)
                    val holder = launch { subject.schedule(alarm("x")) }
                    yield()
                    val ids: MutableList<String> = mutableListOf("a", "b")
                    val canceller = launch { subject.cancelAll(ids) }
                    yield()
                    mutate(ids)
                    gate.complete(Unit)
                    holder.join()
                    canceller.join()
                }
            } finally {
                gate.complete(Unit)
            }
        }
        return center
    }

    @Test
    fun `a collection cleared while cancelAll waits for the lock still cancels the ids it held when called`() {
        val center: GatedCenter = queuedBehindSlowSchedule { it.clear() }

        assertEquals(listOf(listOf("a", "b")), center.removeCalls)
        assertEquals(listOf("x"), center.pending)
    }

    @Test
    fun `a collection that gains an id while cancelAll waits for the lock does not cancel the new id`() {
        val center: GatedCenter = queuedBehindSlowSchedule { it.add("x") }

        assertEquals(listOf(listOf("a", "b")), center.removeCalls)
        assertEquals(listOf("x"), center.pending)
    }

    @Test
    fun `control - an untouched collection queued behind a slow schedule is cancelled whole`() {
        val center: GatedCenter = queuedBehindSlowSchedule { }

        assertEquals(listOf(listOf("a", "b")), center.removeCalls)
        assertEquals(listOf("x"), center.pending)
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 10_000L
    }
}
