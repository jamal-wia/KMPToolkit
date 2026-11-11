package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iOS column of the queued-cancellation matrix (the Android column is
 * `AndroidAlarmSchedulerQueuedCancellationMatrixAdversarialTest`): a `schedule`, `cancel` or
 * `cancelAll` cancelled while it waits for the process-wide lock ends cancelled, changes nothing once
 * the lock frees and does not poison the lock. Only `cancel` had such a test on iOS before.
 */
class IosAlarmSchedulerQueuedCancellationMatrixAdversarialTest {

    private var returnedNormally: Boolean = false

    private class GatedCenter(private val gate: CompletableDeferred<Unit>) : NotificationCenterPort {
        val pending: MutableList<String> = mutableListOf()
        val removeCalls: MutableList<List<String>> = mutableListOf()
        private var first: Boolean = true

        override suspend fun isAuthorizationDenied(): Boolean {
            if (first) {
                first = false
                gate.await()
            }
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

    private fun cancelledWhileQueued(
        center: GatedCenter,
        gate: CompletableDeferred<Unit>,
        queued: suspend (AlarmScheduler) -> Unit,
    ): Job {
        lateinit var job: Job
        runBlocking {
            try {
                withTimeout(TIMEOUT_MILLIS) {
                    val subject: IosAlarmScheduler = scheduler(center)
                    val holder = launch(start = CoroutineStart.UNDISPATCHED) { subject.schedule(alarm("holder")) }
                    job = launch(start = CoroutineStart.UNDISPATCHED) { queued(subject); returnedNormally = true }
                    job.cancel()
                    job.join()
                    gate.complete(Unit)
                    holder.join()
                }
            } finally {
                gate.complete(Unit)
            }
        }
        return job
    }

    @Test
    fun `a schedule cancelled while queued ends cancelled and arms nothing`() {
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val center = GatedCenter(gate)

        val job: Job = cancelledWhileQueued(center, gate) { it.schedule(alarm("queued")) }

        assertTrue(job.isCancelled)
        assertFalse(returnedNormally, "the cancelled call returned normally instead of ending cancelled")
        assertEquals(listOf("holder"), center.pending)
    }

    @Test
    fun `a cancel cancelled while queued ends cancelled and removes nothing`() {
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val center = GatedCenter(gate)

        val job: Job = cancelledWhileQueued(center, gate) { it.cancel("holder") }

        assertTrue(job.isCancelled)
        assertFalse(returnedNormally, "the cancelled call returned normally instead of ending cancelled")
        assertTrue(center.removeCalls.isEmpty())
        assertEquals(listOf("holder"), center.pending)
    }

    @Test
    fun `a cancelAll cancelled while queued ends cancelled and removes nothing`() {
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val center = GatedCenter(gate)

        val job: Job = cancelledWhileQueued(center, gate) { it.cancelAll(listOf("holder")) }

        assertTrue(job.isCancelled)
        assertFalse(returnedNormally, "the cancelled call returned normally instead of ending cancelled")
        assertTrue(center.removeCalls.isEmpty())
        assertEquals(listOf("holder"), center.pending)
    }

    @Test
    fun `the lock stays usable after a cancelled schedule and a cancelled cancelAll`() {
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val center = GatedCenter(gate)
        cancelledWhileQueued(center, gate) { it.schedule(alarm("q1")) }

        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                val subject: IosAlarmScheduler = scheduler(center)
                assertEquals(AlarmScheduleResult.Exact, subject.schedule(alarm("after")))
                subject.cancelAll(listOf("after"))
            }
        }

        assertEquals(listOf("holder"), center.pending)
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 10_000L
    }
}
