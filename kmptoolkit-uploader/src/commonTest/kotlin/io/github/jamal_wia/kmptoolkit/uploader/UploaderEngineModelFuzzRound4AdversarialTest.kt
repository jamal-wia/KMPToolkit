package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 4: a seeded random walk over the public operations (enqueue with KEEP and REPLACE, cancel, cancelByTag,
 * settle, clock jumps, time passing) against a model of what the documentation promises:
 *
 * - single flight: never two attempts at once;
 * - no attempt of an item starts after the call that withdrew it returned;
 * - an item that was delivered or dropped is never attempted again;
 * - an item is attempted only when no older item of its ordering channel is still owed;
 * - nothing vanishes without having been delivered, dropped, withdrawn or replaced;
 * - once the world is quiet (clock moving, executors settling), the queue empties.
 *
 * Virtual time only, no advanceUntilIdle, every loop capped, every engine closed in a finally.
 */
class UploaderEngineModelFuzzRound4AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    /** The reference store behind a layer that fails calls at random, before or after the write has committed. */
    private class FlakyStore(
        private val inner: TestUploaderStore,
        private val random: Random,
    ) : io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore by inner {
        var failing: Boolean = false
        var failures: Int = 0

        private fun roll(): Int = if (failing && random.nextInt(100) < 12) (if (random.nextBoolean()) 1 else 2) else 0

        private suspend fun <T> guarded(call: suspend () -> T): T {
            val verdict: Int = roll()
            if (verdict == 1) {
                failures++
                throw IllegalStateException("database is locked")
            }
            val result: T = call()
            if (verdict == 2) {
                failures++
                throw IllegalStateException("committed, then the connection dropped")
            }
            return result
        }

        override suspend fun insertKeep(record: UploaderItem): Boolean = guarded { inner.insertKeep(record) }
        override suspend fun insertReplace(record: UploaderItem): UploaderItem? = guarded { inner.insertReplace(record) }
        override suspend fun getAllActive(): List<UploaderItem> = guarded { inner.getAllActive() }
        override suspend fun getById(id: String): UploaderItem? = guarded { inner.getById(id) }
        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition,
        ): Boolean = guarded {
            inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }

        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean =
            guarded { inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim) }

        override suspend fun park(id: String, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long): Boolean =
            guarded { inner.park(id, lastError, expectedState, expectedClaim) }

        override suspend fun deleteById(id: String) = guarded { inner.deleteById(id) }
        override suspend fun deleteByTag(tag: String): List<UploaderItem> = guarded { inner.deleteByTag(tag) }
    }

    private class World(val seed: Int, val store: TestUploaderStore, val clock: TestClock) {
        val random = Random(seed)
        val events: MutableList<String> = mutableListOf()
        val withdrawn: MutableSet<String> = mutableSetOf()
        val finished: MutableSet<String> = mutableSetOf()
        val enqueued: MutableList<String> = mutableListOf()
        val currentByKey: MutableMap<String, String> = mutableMapOf()
        val net = TestConstraint("net", satisfied = true)
        var running = 0
        var maxRunning = 0
        val violations: MutableList<String> = mutableListOf()
        fun violate(message: String) {
            if (violations.size < 5) violations += "seed $seed: $message"
        }
    }

    private fun handlerOf(world: World): TestHandler {
        lateinit var handler: TestHandler
        handler = TestHandler(
            retryPolicy = FixedRetryPolicy(delayMillis = 300L),
            constraintKeys = setOf("net"),
            ordering = { payload -> payload.substringBefore(':').takeIf { it != "free" } },
            onExecute = { context, payload ->
                val id: String = context.id
                world.running++
                world.maxRunning = maxOf(world.maxRunning, world.running)
                try {
                    if (world.running > 1) world.violate("two attempts at once ($id)")
                    if (!world.net.satisfied.value) world.violate("attempt of $id started while its constraint was unsatisfied")
                    if (id in world.withdrawn) world.violate("attempt of $id started after its withdrawal returned")
                    if (id in world.finished) world.violate("attempt of $id started after it was delivered or dropped")
                    val key: String? = payload.substringBefore(':').takeIf { it != "free" }
                    if (key != null) {
                        val older: List<UploaderItem> = world.store.items.takeWhile { it.id != id }
                        val blocking: UploaderItem? = older.firstOrNull {
                            it.orderingKey == key && it.type == handler.type && it.state != UploaderItemState.PARKED
                        }
                        if (blocking != null) world.violate("$id ran while older ${blocking.id} of channel $key is still owed")
                    }
                    val row: UploaderItem? = world.store.find(id)
                    if (row == null) {
                        world.violate("attempt of $id started on a row that does not exist")
                    } else {
                        if (context.attempts != row.attempts) world.violate("$id: context.attempts ${context.attempts} != row ${row.attempts}")
                        if (context.claim != row.claim + 1) world.violate("$id: context.claim ${context.claim} != row claim + 1 (${row.claim + 1})")
                        if (context.wasDetached != (row.state == UploaderItemState.IN_FLIGHT)) world.violate("$id: wasDetached ${context.wasDetached} for a ${row.state} row")
                        if (row.state == UploaderItemState.PARKED) world.violate("attempt of $id started on a parked row")
                    }
                    world.events += "start:$id"
                    delay(world.random.nextLong(0L, 40L).milliseconds)
                    when (world.random.nextInt(100)) {
                        in 0..54 -> AttemptResult.Success.also { world.finished += id }
                        in 55..79 -> AttemptResult.Retry(IllegalStateException("flaky"))
                        in 80..86 -> AttemptResult.Park("no").also { world.events += "parked:$id" }
                        in 87..91 -> AttemptResult.Drop("no").also { world.finished += id }
                        else -> AttemptResult.Detached(world.random.nextLong(200L, 4_000L))
                    }
                } finally {
                    world.running--
                }
            },
        )
        return handler
    }

    private var totalStarts = 0
    private var totalWithdrawals = 0
    private var totalStoreFailures = 0

    private suspend fun TestScope.walk(seed: Int, chaos: Boolean = false, flaky: Boolean = false) {
        val store = TestUploaderStore()
        val clock = TestClock(1_000L)
        val world = World(seed, store, clock)
        val handler: TestHandler = handlerOf(world)
        val ids = SequentialIds("s$seed")
        val flakyStore = FlakyStore(store, Random(seed * 31 + 7)).also { it.failing = flaky }
        val uncertain: MutableSet<String> = mutableSetOf()
        val wake = TestWakeScheduler()
        fun newEngine(): UploaderEngine = createUploaderEngine(
            store = flakyStore,
            handlers = listOf(handler),
            scope = backgroundScope,
            constraintProviders = listOf(world.net),
            clock = clock,
            wakeScheduler = wake,
            idGenerator = ids::next,
        )
        var engine: UploaderEngine = newEngine()
        var skew = 0L
        try {
            engine.start()
            repeat(70) {
                val random: Random = world.random
                when (random.nextInt(100)) {
                    in 0..44 -> {
                        val key: String? = listOf("k1", "k2", "k3", null).random(random)
                        val chan: String = listOf("A", "B", "free").random(random)
                        val replace: Boolean = random.nextInt(3) == 0
                        val tag: String? = listOf("t1", "t2", null).random(random)
                        val before: String? = key?.let { world.currentByKey[it] }
                        val id: String? = try {
                            engine.enqueue(
                                handler, "$chan:${world.enqueued.size}", uniqueKey = key, tag = tag,
                                conflictPolicy = if (replace) ConflictPolicy.REPLACE else ConflictPolicy.KEEP,
                            )
                        } catch (e: IllegalStateException) {
                            if (!flaky) throw e
                            before?.let { uncertain += it } // the outcome is unknown: the old row may or may not be gone
                            null
                        }
                        if (id != null) {
                            world.enqueued += id
                            if (key != null) {
                                if (before != null && before != id) {
                                    world.withdrawn += before // superseded: gone, or parked and revived
                                }
                                world.currentByKey[key] = id
                            }
                        }
                    }

                    in 45..54 -> world.enqueued.randomOrNull(random)?.let { id ->
                        try {
                            engine.cancel(id)
                            world.withdrawn += id
                        } catch (e: IllegalStateException) {
                            if (!flaky) throw e
                            uncertain += id
                        }
                    }

                    in 55..60 -> {
                        val tag: String = listOf("t1", "t2").random(random)
                        val owned: List<String> = store.items.filter { it.tag == tag }.map { it.id }
                        try {
                            engine.cancelByTag(tag)
                            world.withdrawn += owned
                        } catch (e: IllegalStateException) {
                            if (!flaky) throw e
                            uncertain += owned
                        }
                    }

                    in 61..70 -> store.items.filter { it.state == UploaderItemState.IN_FLIGHT }.randomOrNull(random)?.let { row ->
                        val result: SettleResult = when (random.nextInt(4)) {
                            0 -> SettleResult.Delivered
                            1 -> SettleResult.Drop("x")
                            2 -> SettleResult.Park("x")
                            else -> SettleResult.Failed(null)
                        }
                        try {
                            engine.settle(row.id, row.claim, result)
                            if (result is SettleResult.Delivered || result is SettleResult.Drop) world.finished += row.id
                        } catch (e: IllegalStateException) {
                            if (!flaky) throw e
                            uncertain += row.id
                        }
                    }

                    in 71..76 -> skew += random.nextLong(0L, 6_000L)
                    in 87..92 -> world.net.set(random.nextBoolean())
                    in 77..82 -> if (chaos) skew = maxOf(-40_000L, skew - random.nextLong(0L, 30_000L))
                    in 83..86 -> if (chaos) {
                        // the process dies and a fresh engine takes over the same queue
                        engine.close()
                        engine = newEngine()
                        engine.start()
                    }

                    else -> Unit
                }
                advanceTimeBy(random.nextLong(1L, 700L).milliseconds)
                clock.millis = 1_000L + testScheduler.currentTime + skew
                if (!flaky && store.items.any { it.state != UploaderItemState.PARKED }) {
                    // the platform wake must never be disarmed while something is owed
                    if (!wake.armed) world.violate("work is owed but the platform wake is disarmed")
                }
            }

            // Quiet world: the store recovers, time passes, executors settle what they were handed. Capped.
            flakyStore.failing = false
            world.net.set(true)
            var rounds = 0
            while (store.items.any { it.state != UploaderItemState.PARKED } && rounds++ < 400) {
                skew += 5_000L
                clock.millis = 1_000L + testScheduler.currentTime + skew
                advanceTimeBy(1.seconds)
                runCurrent()
                store.items.filter { it.state == UploaderItemState.IN_FLIGHT && rounds % 3 == 0 }.forEach { row ->
                    world.finished += row.id
                    engine.settle(row.id, row.claim, SettleResult.Delivered)
                }
            }

            val stuck: List<String> = store.items.filter { it.state != UploaderItemState.PARKED }.map { "${it.id}:${it.state}:a${it.attempts}" }
            assertTrue(stuck.isEmpty(), "seed $seed: the queue did not drain in a quiet world: $stuck")
            val lost: List<String> = world.enqueued.filter { id ->
                store.find(id) == null && id !in world.finished && id !in world.withdrawn && id !in uncertain
            }
            assertTrue(lost.isEmpty(), "seed $seed: items vanished without being delivered, dropped or withdrawn: $lost")
            advanceTimeBy(1.seconds) // the last settle's trigger gets its pass
            runCurrent()
            if (!flaky) assertTrue(!wake.armed, "seed $seed: the queue is empty but the platform wake is still armed")
            assertEquals(emptyList(), world.violations)
            assertEquals(1, world.maxRunning.coerceAtLeast(1))
            totalStarts += world.events.count { it.startsWith("start:") }
            totalWithdrawals += world.withdrawn.size
            totalStoreFailures += flakyStore.failures
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a random walk over the public operations never breaks a documented guarantee seeds 0 to 59`() =
        runTest(timeout = 120.seconds) {
            for (seed in 0 until 60) walk(seed)
            // the walk must actually exercise the engine, or the invariants above prove nothing
            assertTrue(totalStarts > 1_500, "only $totalStarts attempts in 60 walks")
            assertTrue(totalWithdrawals > 100, "only $totalWithdrawals withdrawals in 60 walks")
        }

    @Test
    fun `the same walk with restarts and wall clock steps back keeps every guarantee`() =
        runTest(timeout = 120.seconds) {
            for (seed in 1_000 until 1_150) walk(seed, chaos = true)
        }

    @Test
    fun `the same walk over a store that fails calls before and after committing keeps every guarantee`() =
        runTest(timeout = 120.seconds) {
            for (seed in 2_000 until 2_150) walk(seed, flaky = true)
            assertTrue(totalStoreFailures > 300, "only $totalStoreFailures injected store failures")
        }

    @Test
    fun `a random walk over the public operations never breaks a documented guarantee seeds 60 to 199`() =
        runTest(timeout = 120.seconds) { for (seed in 60 until 200) walk(seed) }
}
