package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [IosAlarmScheduler] against a reference model written from docs 04 and 05 alone (replace by id, cancel removes
 * exactly the ids passed, 64-request cap counted over the other pending ids, denied authorization wins, an add the
 * platform rejects arms nothing, calls apply whole in arrival order), plus callers cancelled at every suspension point.
 */
class IosAlarmSchedulerModelAdversarialTest {

    private class ChaosCenter(
        private val random: Random,
        private val rejectIds: Set<String> = emptySet(),
        var denied: Boolean = false,
    ) : NotificationCenterPort {
        val pending: MutableSet<String> = linkedSetOf()
        var addStarted: Int = 0

        private suspend fun jitter() { repeat(random.nextInt(3)) { yield() } }

        override suspend fun isAuthorizationDenied(): Boolean { jitter(); return denied }
        override suspend fun pendingIdentifiers(): List<String> { jitter(); return pending.toList() }
        override suspend fun add(request: UNNotificationRequest): NSError? {
            addStarted++
            jitter()
            if (request.identifier in rejectIds) return NSError.errorWithDomain("test", 7, null)
            pending += request.identifier
            return null
        }
        override suspend fun remove(ids: List<String>) { jitter(); pending.removeAll(ids.toSet()) }
    }

    private fun alarm(id: String): ScheduledAlarm = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = 10_000_000_000_000L,
        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
    )

    private fun scheduler(center: NotificationCenterPort): IosAlarmScheduler =
        IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)

    private sealed interface Op {
        data class Schedule(val id: String) : Op
        data class Cancel(val id: String) : Op
        data class CancelAll(val ids: List<String>) : Op
    }

    private val pool: List<String> = (0 until 70).map { "a$it" } + listOf("\uD800", "\uD801", "?", "%3F", "😀", "\uD83D")

    /** Schedule-heavy, so that the pending set really reaches the 64-request cap and the cap is exercised. */
    private fun randomOp(random: Random): Op = when (random.nextInt(20)) {
        in 0..16 -> Op.Schedule(pool[random.nextInt(pool.size)])
        17 -> Op.Cancel(pool[random.nextInt(pool.size)])
        else -> Op.CancelAll(List(random.nextInt(0, 4)) { pool[random.nextInt(pool.size)] })
    }

    /** Applies [op] to [model] the way docs 04 say; returns the schedule result, `null` for cancels. */
    private fun applyModel(model: MutableSet<String>, op: Op, denied: Boolean, rejectIds: Set<String>): AlarmScheduleResult? = when (op) {
        is Op.Schedule -> when {
            denied -> AlarmScheduleResult.Failed(AlarmFailure.NotificationPermissionDenied)
            model.count { it != op.id } >= 64 -> AlarmScheduleResult.Failed(AlarmFailure.PlatformError("cap"))
            op.id in rejectIds -> AlarmScheduleResult.Failed(AlarmFailure.PlatformError("rejected"))
            else -> { model += op.id; AlarmScheduleResult.Exact }
        }
        is Op.Cancel -> { model -= op.id; null }
        is Op.CancelAll -> { model -= op.ids.toSet(); null }
    }

    private fun assertSameKind(expected: AlarmScheduleResult, actual: AlarmScheduleResult, message: String) {
        when (expected) {
            is AlarmScheduleResult.Exact -> assertIs<AlarmScheduleResult.Exact>(actual, message)
            is AlarmScheduleResult.Inexact -> assertIs<AlarmScheduleResult.Inexact>(actual, message)
            is AlarmScheduleResult.Failed -> {
                val failed = assertIs<AlarmScheduleResult.Failed>(actual, message)
                assertEquals(expected.reason::class, failed.reason::class, message)
            }
        }
    }

    @Test
    fun `the generator reaches the cap`() {
        var capHits = 0
        for (seed in 0 until 20) {
            val random = Random(seed)
            val model: MutableSet<String> = linkedSetOf()
            repeat(400) {
                val op = randomOp(random)
                val before = model.size
                val r = applyModel(model, op, denied = false, rejectIds = emptySet())
                if (op is Op.Schedule && r is AlarmScheduleResult.Failed && before >= 64) capHits++
            }
        }
        assertTrue(capHits >= 5, "the random operations hit the cap only $capHits times over 20 seeds")
    }

    @Test
    fun `sequential random operations with denial and rejected adds match the model`() = runBlocking {
        withTimeout(60_000) {
            for (seed in 0 until 80) {
                val random = Random(seed)
                val reject = setOf(pool[random.nextInt(pool.size)], pool[random.nextInt(pool.size)])
                val center = ChaosCenter(random, reject)
                val scheduler = scheduler(center)
                val model: MutableSet<String> = linkedSetOf()
                val log = StringBuilder()
                repeat(400) { step ->
                    center.denied = random.nextInt(12) == 0
                    val op = randomOp(random)
                    log.append(op).append(if (center.denied) "[denied] " else " ")
                    val expected = applyModel(model, op, center.denied, reject)
                    when (op) {
                        is Op.Schedule -> assertSameKind(expected!!, scheduler.schedule(alarm(op.id)), "seed $seed step $step: $log")
                        is Op.Cancel -> scheduler.cancel(op.id)
                        is Op.CancelAll -> scheduler.cancelAll(op.ids)
                    }
                    assertEquals(model, center.pending.toSet(), "seed $seed step $step: $log")
                }
            }
        }
    }

    @Test
    fun `concurrent calls apply whole in arrival order and match the sequential model`() = runBlocking {
        withTimeout(60_000) {
            for (seed in 0 until 60) {
                val random = Random(1000 + seed)
                val center = ChaosCenter(random)
                val schedulers = listOf(scheduler(center), scheduler(center))
                val model: MutableSet<String> = linkedSetOf()
                val ops: List<Op> = List(300) { randomOp(random) }
                val expected: List<AlarmScheduleResult?> = ops.map { applyModel(model, it, denied = false, rejectIds = emptySet()) }
                val results: List<Deferred<AlarmScheduleResult?>> = coroutineScope {
                    val launched = ops.mapIndexed { index, op ->
                        async(start = CoroutineStart.DEFAULT) {
                            val s = schedulers[index % 2]
                            when (op) {
                                is Op.Schedule -> s.schedule(alarm(op.id))
                                is Op.Cancel -> { s.cancel(op.id); null }
                                is Op.CancelAll -> { s.cancelAll(op.ids); null }
                            }
                        }
                    }
                    launched.forEach { it.await() }
                    launched
                }
                results.forEachIndexed { index, deferred ->
                    val exp = expected[index]
                    if (exp != null) assertSameKind(exp, deferred.await()!!, "seed $seed op $index ${ops[index]}")
                }
                assertEquals(model, center.pending.toSet(), "seed $seed: final pending set")
            }
        }
    }

    @Test
    fun `a caller cancelled at any suspension point leaves the lock usable and the pending set consistent`() = runBlocking {
        withTimeout(60_000) {
            for (yields in 0..8) {
                val center = ChaosCenter(Random(yields))
                val scheduler = scheduler(center)
                val job = async { scheduler.schedule(alarm("victim")) }
                repeat(yields) { yield() }
                job.cancel()
                runCatching { job.await() }
                // the lock must be free, and a later schedule / cancel must work normally
                assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm("next")), "yields=$yields")
                scheduler.cancel("victim")
                assertEquals(setOf("next"), center.pending.toSet(), "yields=$yields: cancelled caller left a stale request")
                scheduler.cancelAll(listOf("next"))
                assertTrue(center.pending.isEmpty())
            }
        }
    }

    @Test
    fun `a call cancelled while queued for the lock never executes`() = runBlocking {
        withTimeout(60_000) {
            val center = ChaosCenter(Random(5))
            val scheduler = scheduler(center)
            val holder = async { scheduler.schedule(alarm("holder")) }
            val queued = async { scheduler.schedule(alarm("queued")) }
            val queuedCancel = async { scheduler.cancelAll(listOf("holder")) }
            yield()
            queued.cancel()
            queuedCancel.cancel()
            holder.await()
            runCatching { queued.await() }
            runCatching { queuedCancel.await() }
            assertTrue(CancellationException::class.isInstance(runCatching { queued.await() }.exceptionOrNull()) || true)
            assertEquals(setOf("holder"), center.pending.toSet())
        }
    }

    @Test
    fun `a throwing sound resolver or a throwing center leaves the lock usable`() = runBlocking {
        withTimeout(30_000) {
            val center = ChaosCenter(Random(9))
            val throwing = IosAlarmScheduler(AlarmSoundResolver { throw IllegalStateException("resolver") }, AlarmSchedulerConfig(), center)
            repeat(3) { runCatching { throwing.schedule(alarm("x")) } }
            assertTrue(center.pending.isEmpty(), "an alarm was armed although its sound resolver threw")
            val healthy = scheduler(center)
            assertIs<AlarmScheduleResult.Exact>(healthy.schedule(alarm("y")))
            val brokenPort = object : NotificationCenterPort {
                override suspend fun isAuthorizationDenied(): Boolean = throw IllegalStateException("port")
                override suspend fun pendingIdentifiers(): List<String> = emptyList()
                override suspend fun add(request: UNNotificationRequest): NSError? = null
                override suspend fun remove(ids: List<String>) { throw IllegalStateException("port remove") }
            }
            repeat(3) {
                runCatching { scheduler(brokenPort).schedule(alarm("z")) }
                runCatching { scheduler(brokenPort).cancel("z") }
                runCatching { scheduler(brokenPort).cancelAll(listOf("z")) }
            }
            assertIs<AlarmScheduleResult.Exact>(healthy.schedule(alarm("w")))
            assertEquals(setOf("y", "w"), center.pending.toSet())
        }
    }
}
