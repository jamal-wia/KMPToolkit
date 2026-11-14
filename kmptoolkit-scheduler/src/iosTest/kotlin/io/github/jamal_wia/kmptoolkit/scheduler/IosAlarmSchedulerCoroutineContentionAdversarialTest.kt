package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.UserNotifications.UNNotificationRequest

/**
 * High contention on the iOS scheduler from coroutines on a multi-threaded dispatcher, through the internal center
 * seam: three scheduler instances share one center that answers from other threads after a random delay. Many
 * coroutines schedule, cancel and cancelAll across more ids than the 64-request cap while their jobs are cancelled
 * at random moments (a hard cancel, a short timeout). Invariants: the center never sees two calls at once (the
 * process-wide lock serialises every call, the cancellation compensation included), the pending set never exceeds 64,
 * only cancellation escapes, nothing is stuck, and a final cancelAll leaves nothing pending.
 */
class IosAlarmSchedulerCoroutineContentionAdversarialTest {

    private class AsyncCenter(private val scope: CoroutineScope) : NotificationCenterPort {
        private val lock = NSLock()
        val pending: MutableSet<String> = linkedSetOf()
        var inFlight: Int = 0
        var maxInFlight: Int = 0
        var maxPending: Int = 0
        var adds: Int = 0

        private fun enter() { lock.lock(); inFlight++; if (inFlight > maxInFlight) maxInFlight = inFlight; lock.unlock() }
        private fun leave() { lock.lock(); inFlight--; lock.unlock() }

        private suspend fun answerLater(n: Int) {
            // Hop to another thread and back, with a random short pause, so the lock really is held across a suspension.
            val job: Job = scope.launch { if (n % 3 != 0) delay((n % 3).toLong()) }
            job.join()
        }

        override suspend fun isAuthorizationDenied(): Boolean {
            enter(); try { answerLater(1) } finally { leave() }
            return false
        }

        override suspend fun pendingIdentifiers(): List<String> {
            enter()
            try {
                answerLater(2)
                lock.lock(); val copy: List<String> = pending.toList(); lock.unlock()
                return copy
            } finally { leave() }
        }

        override suspend fun add(request: UNNotificationRequest): NSError? {
            enter()
            try {
                lock.lock(); val n: Int = ++adds; lock.unlock()
                answerLater(n)
                lock.lock(); pending += request.identifier; if (pending.size > maxPending) maxPending = pending.size; lock.unlock()
                return null
            } finally { leave() }
        }

        override suspend fun remove(ids: List<String>) {
            enter()
            try {
                answerLater(3)
                lock.lock(); pending.removeAll(ids.toSet()); lock.unlock()
            } finally { leave() }
        }

        fun snapshot(): Set<String> { lock.lock(); val copy: Set<String> = pending.toSet(); lock.unlock(); return copy }
    }

    private fun alarm(id: String, op: Int): ScheduledAlarm = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = 10_000_000_000_000L + op,
        notification = AlarmNotification(title = "t$op", body = "b", channelId = "c"),
    )

    @Test
    fun `cancelled scheduling coroutines never overlap center calls or break the cap`() {
        val ids: List<String> = (0 until 90).map { "id$it" }
        repeat(ROUNDS) { round: Int ->
            val centerScope = CoroutineScope(Dispatchers.Default)
            val center = AsyncCenter(centerScope)
            val schedulers: List<AlarmScheduler> = List(3) { IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center) }
            val escaped: MutableList<String> = mutableListOf()
            val escapedLock = NSLock()
            fun record(e: Throwable) { escapedLock.lock(); escaped += "${e::class.simpleName}: ${e.message}"; escapedLock.unlock() }
            runBlocking {
                withTimeout(JOIN_MILLIS) {
                    val scope = CoroutineScope(Dispatchers.Default)
                    val jobs: List<Job> = List(COROUTINES) { index: Int ->
                        scope.launch {
                            val random = Random(round * 1000 + index)
                            val scheduler: AlarmScheduler = schedulers[index % schedulers.size]
                            val children: MutableList<Job> = mutableListOf()
                            repeat(OPS) { op: Int ->
                                val id: String = ids[random.nextInt(ids.size)]
                                try {
                                    when (random.nextInt(100)) {
                                        in 0..9 -> scheduler.cancel(id)
                                        in 10..11 -> scheduler.cancelAll(List(random.nextInt(0, 8)) { ids[random.nextInt(ids.size)] })
                                        in 12..54 -> {
                                            val job: Job = launch {
                                                try {
                                                    scheduler.schedule(alarm(id, op))
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Throwable) {
                                                    record(e)
                                                }
                                            }
                                            children += job
                                            if (random.nextInt(3) == 0) job.cancel()
                                        }
                                        in 55..69 -> withTimeoutOrNull(random.nextLong(1, 3)) { scheduler.schedule(alarm(id, op)) }
                                        else -> if (children.isNotEmpty()) children[random.nextInt(children.size)].cancel()
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    record(e)
                                }
                                if (random.nextInt(8) == 0) delay(1)
                            }
                            children.joinAll()
                        }
                    }
                    jobs.joinAll()
                }
            }
            assertEquals(emptyList(), escaped.toList(), "round $round: an exception other than cancellation escaped")
            assertEquals(1, center.maxInFlight, "round $round: the center saw ${center.maxInFlight} calls at once (the lock is not serialising)")
            assertTrue(center.maxPending <= PENDING_REQUEST_CAP, "round $round: pending reached ${center.maxPending}, over the cap")
            assertTrue(center.snapshot().all { it in ids }, "round $round: a request under a foreign id")
            runBlocking { withTimeout(JOIN_MILLIS) { schedulers[0].cancelAll(ids) } }
            assertEquals(emptySet(), center.snapshot(), "round $round: cancelAll left requests")
            // nothing stuck: the lock accepts a normal call again
            assertEquals(AlarmScheduleResult.Exact, runBlocking { withTimeout(JOIN_MILLIS) { schedulers[1].schedule(alarm("after", 0)) } })
            runBlocking { schedulers[2].cancel("after") }
            centerScope.coroutineContext[Job]?.cancel()
        }
    }

    private companion object {
        const val ROUNDS: Int = 4
        const val COROUTINES: Int = 24
        const val OPS: Int = 120
        const val JOIN_MILLIS: Long = 90_000L
    }
}
