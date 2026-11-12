package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The process-wide lock of the iOS scheduler across the lives a call can have: a caller that gives
 * up while queued must neither perform its removal later nor leave the lock held, a port that
 * blows up inside the critical section must not poison the lock for the next caller, and the sound
 * resolver (`AlarmSoundResolver`: "once per `schedule` call") is asked once per armed alarm.
 */
class IosAlarmSchedulerLockLifecycleAdversarialTest {

    private class ScriptedCenter(
        private val authGate: CompletableDeferred<Unit>? = null,
        private val explodeOnAdd: Boolean = false,
    ) : NotificationCenterPort {
        val pending: MutableList<String> = mutableListOf()
        val removeCalls: MutableList<List<String>> = mutableListOf()

        override suspend fun isAuthorizationDenied(): Boolean {
            authGate?.await()
            return false
        }

        override suspend fun pendingIdentifiers(): List<String> = pending.toList()

        override suspend fun add(request: UNNotificationRequest): NSError? {
            if (explodeOnAdd) throw IllegalStateException("center exploded")
            if (request.identifier !in pending) pending += request.identifier
            return null
        }

        override suspend fun remove(ids: List<String>) {
            removeCalls += ids.toList()
            pending.removeAll(ids)
        }
    }

    private fun alarm(id: String, channel: String = "c"): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = 10_000_000_000_000L,
        notification = AlarmNotification(title = "t", body = "b", channelId = channel),
    )

    private fun scheduler(center: NotificationCenterPort, resolver: AlarmSoundResolver = AlarmSoundResolver { null }) =
        IosAlarmScheduler(resolver, AlarmSchedulerConfig(), center)

    @Test
    fun `a cancel that gives up while queued removes nothing later and does not hold the lock`() {
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val center = ScriptedCenter(authGate = gate)
        runBlocking {
            try {
                withTimeout(TIMEOUT_MILLIS) {
                    val subject = scheduler(center)
                    val holder = launch { subject.schedule(alarm("x")) }
                    yield()
                    val queued = launch(start = CoroutineStart.UNDISPATCHED) { subject.cancel("x") }
                    yield()
                    queued.cancelAndJoin()
                    gate.complete(Unit)
                    holder.join()
                    // The lock is free again: another call goes straight through.
                    subject.cancel("y")
                }
            } finally {
                gate.complete(Unit)
            }
        }
        assertEquals(listOf(listOf("y")), center.removeCalls)
        assertEquals(listOf("x"), center.pending)
    }

    @Test
    fun `a port that throws inside the critical section does not poison the lock`() {
        val exploding = ScriptedCenter(explodeOnAdd = true)
        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                assertFailsWith<IllegalStateException> { scheduler(exploding).schedule(alarm("a")) }
                val healthy = ScriptedCenter()
                assertEquals(AlarmScheduleResult.Exact, scheduler(healthy).schedule(alarm("b")))
                assertEquals(listOf("b"), healthy.pending)
            }
        }
    }

    @Test
    fun `a sound resolver that throws propagates out of schedule arms nothing and leaves the lock free`() {
        val boom = IllegalStateException("resolver exploded")
        val center = ScriptedCenter()
        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                val thrown = assertFailsWith<IllegalStateException> {
                    scheduler(center, AlarmSoundResolver { throw boom }).schedule(alarm("a"))
                }
                assertSame(boom, thrown)
                assertTrue(center.pending.isEmpty())
                assertEquals(AlarmScheduleResult.Exact, scheduler(center).schedule(alarm("b")))
                assertEquals(listOf("b"), center.pending)
            }
        }
    }

    @Test
    fun `the sound resolver is asked once per armed alarm with its channel and not at all when nothing is armed`() {
        val asked: MutableList<String> = mutableListOf()
        val resolver = AlarmSoundResolver { channel: String -> asked += channel; null }
        val center = ScriptedCenter()
        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                val subject = scheduler(center, resolver)
                subject.schedule(alarm("a", channel = "alpha"))
                subject.schedule(alarm("a", channel = "beta"))
                assertEquals(listOf("alpha", "beta"), asked)
                subject.cancelAll(emptyList())
                subject.cancel("a")
                assertEquals(listOf("alpha", "beta"), asked)
            }
        }
        assertTrue(center.pending.isEmpty())
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 10_000L
    }
}
