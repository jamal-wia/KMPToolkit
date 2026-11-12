package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R19-S2: 04-api-reference, `schedule`: "On iOS ... An exception that is not a refusal - one thrown by your
 * `AlarmSoundResolver`, or a cancellation - propagates instead; nothing is armed and the scheduler stays usable."
 * A caller cancelled while the notification center is adding the request: the center accepts a request at the moment
 * `addNotificationRequest` is called, the completion handler only reports it later. The cancellation propagates,
 * and "nothing is armed" must hold: the request that was handed to the center must not stay pending behind a
 * caller that was told the call was cancelled.
 */
class IosAlarmSchedulerCancelledDuringAddAdversarialTest {

    /** A center that accepts the request at once and answers only when the gate opens, as the real one does. */
    private class SlowCompletionCenter(private val gate: CompletableDeferred<Unit>) : NotificationCenterPort {
        val pending: LinkedHashSet<String> = linkedSetOf()
        val entered: CompletableDeferred<Unit> = CompletableDeferred()

        override suspend fun isAuthorizationDenied(): Boolean = false
        override suspend fun pendingIdentifiers(): List<String> = pending.toList()

        override suspend fun add(request: UNNotificationRequest): NSError? {
            pending += request.identifier
            entered.complete(Unit)
            gate.await()
            return null
        }

        override suspend fun remove(ids: List<String>) {
            pending.removeAll(ids.toSet())
        }
    }

    private fun alarm(id: String): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "T",
        fireAtEpochMillis = 10_000_000_000_000L,
        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
    )

    @Test
    fun `a schedule cancelled while the center is adding the request leaves nothing armed`() {
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val center = SlowCompletionCenter(gate)
        val scheduler: AlarmScheduler = IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)
        var thrown: Throwable? = null
        runBlocking {
            try {
                withTimeout(20_000L) {
                    val job = launch(start = CoroutineStart.UNDISPATCHED) {
                        thrown = runCatching { scheduler.schedule(alarm("x")) }.exceptionOrNull()
                    }
                    center.entered.await()
                    job.cancelAndJoin()
                }
            } finally {
                gate.complete(Unit)
            }
        }
        assertTrue(thrown is CancellationException, "the cancellation must propagate, got $thrown")
        assertEquals(emptySet(), center.pending, "the call was cancelled, so nothing may stay armed")

        // And the scheduler stays usable.
        val result: AlarmScheduleResult = runBlocking { withTimeout(20_000L) { scheduler.schedule(alarm("y")) } }
        assertEquals(AlarmScheduleResult.Exact, result)
    }

    @Test
    fun `a replacement cancelled while the center is adding it leaves no alarm for the id`() {
        // A healthy first schedule of "x" (the center answers at once), then a replacement whose add never answers.
        val open: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val center = SlowCompletionCenter(open)
        val first: AlarmScheduler = IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)
        assertEquals(AlarmScheduleResult.Exact, runBlocking { withTimeout(20_000L) { first.schedule(alarm("x")) } })
        assertEquals(setOf("x"), center.pending)

        val second: CompletableDeferred<Unit> = CompletableDeferred()
        val slow = SlowCompletionCenter(second).also { it.pending += center.pending }
        val replacing: AlarmScheduler = IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), slow)
        var thrown: Throwable? = null
        runBlocking {
            try {
                withTimeout(20_000L) {
                    val job = launch(start = CoroutineStart.UNDISPATCHED) {
                        thrown = runCatching { replacing.schedule(alarm("x")) }.exceptionOrNull()
                    }
                    slow.entered.await()
                    job.cancelAndJoin()
                }
            } finally {
                second.complete(Unit)
            }
        }
        assertTrue(thrown is CancellationException, "the cancellation must propagate, got $thrown")
        assertEquals(emptySet(), slow.pending, "iOS had already replaced the old request, so the cancelled call leaves the id without an alarm")
    }
}
