package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.testing.MutableUploaderClock
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Round 9 (iOS parity): the engine over a real SQLite queue, scripted and seeded, as plain suspending checks that the
 * Android host and the iOS simulator both run. Round 7 and 8 drove this over Robolectric only; a difference the two
 * drivers make (pooled native connections, a different clock of file locks) shows here as a failing invariant.
 */
internal class EngineOverSqliteParityRound9Checks {

    private class Recorder(override val type: String = "p9", val behave: (String, AttemptContext) -> AttemptResult) :
        UploaderHandler<String> {
        val runs: MutableList<Pair<String, AttemptContext>> = mutableListOf()
        override val schemaVersion: Int = 1
        override val constraintKeys: Set<String> = emptySet()
        override val retryPolicy: RetryPolicy = object : RetryPolicy {
            override val maxDelayMillis: Long = 60_000L
            override val giveUp: GiveUpPolicy = GiveUpPolicy.ParkAfterAttempts(6)
            override fun backoffMillis(attempts: Int): Long = 5_000L
        }
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            runs += payload to context
            return behave(payload, context)
        }
    }

    private suspend fun <T> real(millis: Long, block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(millis) { block() } }

    private fun newEngine(storage: UploaderStorage, handler: Recorder, clock: MutableUploaderClock, scope: CoroutineScope): UploaderEngine =
        createUploaderEngine(
            store = storage.store,
            handlers = listOf(handler),
            scope = scope,
            transactionRunner = storage.transactionRunner,
            clock = clock,
        )

    suspend fun scriptedLifeOfAnItemThroughRetryRestartDetachAndSettle() {
        val name = "round9-script-${Random.nextLong().toString(16)}"
        val clock = MutableUploaderClock(10_000_000L)
        var storage: UploaderStorage = createFileUploaderStorage(name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // 1. a failing handler: the row stays, attempts and the gate are persisted
            val failing = Recorder { _, _ -> AttemptResult.Retry(IllegalStateException("down")) }
            var engine: UploaderEngine = newEngine(storage, failing, clock, scope)
            val id: String = assertNotNull(real(30_000) { engine.enqueue(failing, "one", uniqueKey = "k") })
            real(30_000) { engine.drain() }
            val afterRetry: UploaderItem = assertNotNull(storage.store.getById(id))
            assertEquals(UploaderItemState.PENDING, afterRetry.state)
            assertEquals(1, afterRetry.attempts)
            assertEquals(clock.nowMillis + 5_000L, afterRetry.nextRunAtEpochMillis)
            assertEquals(1, failing.runs.size)

            // 2. a second drain before the gate does not run it again
            real(30_000) { engine.drain() }
            assertEquals(1, failing.runs.size)
            engine.close()

            // 3. the process "restarts": a new storage over the same file and a new engine, the gate survived
            storage.close()
            storage = createFileUploaderStorage(name)
            val detaching = Recorder { _, _ -> AttemptResult.Detached(60_000L) }
            engine = newEngine(storage, detaching, clock, scope)
            real(30_000) { engine.drain() }
            assertEquals(0, detaching.runs.size, "the persisted gate holds across the restart")
            clock.advanceBy(5_000L)
            real(30_000) { engine.drain() }
            val context: AttemptContext = detaching.runs.single().second
            assertEquals(1, context.attempts)
            assertEquals(false, context.wasDetached)
            val inFlight: UploaderItem = assertNotNull(storage.store.getById(id))
            assertEquals(UploaderItemState.IN_FLIGHT, inFlight.state)
            assertEquals(clock.nowMillis + 60_000L, inFlight.leaseUntilEpochMillis)
            assertEquals(context.claim, inFlight.claim)

            // 4. a stale failed report is a no-op, the current one spends an attempt, the item returns to pending
            engine.settle(id, context.claim + 5, SettleResult.Failed(IllegalStateException("stale")))
            assertEquals(UploaderItemState.IN_FLIGHT, assertNotNull(storage.store.getById(id)).state)
            engine.settle(id, context.claim, SettleResult.Failed(IllegalStateException("current")))
            val back: UploaderItem = assertNotNull(storage.store.getById(id))
            assertEquals(UploaderItemState.PENDING, back.state)
            assertEquals(2, back.attempts)

            // 5. a delivered report from any claim removes the row, a second one finds nothing
            engine.settle(id, 99, SettleResult.Delivered)
            assertNull(storage.store.getById(id))
            engine.settle(id, 99, SettleResult.Delivered)
            engine.close()
        } finally {
            scope.cancel()
            storage.close()
            deleteDatabaseFile(name)
        }
    }

    suspend fun seededWorkloadsNeverLoseDuplicateOrResurrectAnItem() {
        for (seed in 1..4) {
            val random = Random(seed * 7919)
            val clock = MutableUploaderClock(50_000_000L)
            val storage: UploaderStorage = createInMemoryUploaderStorage()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val delivered: MutableMap<String, Int> = mutableMapOf() // payload -> confirmed deliveries
            val withdrawn: MutableSet<String> = mutableSetOf()
            val executedAfterWithdrawal: MutableList<String> = mutableListOf()
            val detached: MutableList<Pair<String, AttemptContext>> = mutableListOf()
            val enqueued: MutableMap<String, String> = mutableMapOf() // payload -> id of the live row
            val behave: (String, AttemptContext) -> AttemptResult = { payload, ctx ->
                if (payload in withdrawn) executedAfterWithdrawal += payload
                val roll: Int = random.nextInt(100)
                when {
                    roll < 45 -> {
                        delivered[payload] = (delivered[payload] ?: 0) + 1
                        AttemptResult.Success
                    }
                    roll < 75 -> AttemptResult.Retry(IllegalStateException("flaky"))
                    roll < 92 -> {
                        detached += payload to ctx
                        AttemptResult.Detached(30_000L)
                    }
                    else -> AttemptResult.Park("handler gave up")
                }
            }
            val handler = Recorder(behave = behave)
            try {
                val engine: UploaderEngine = newEngine(storage, handler, clock, scope)
                var counter = 0
                repeat(70) {
                    when (random.nextInt(10)) {
                        0, 1, 2 -> {
                            val payload = "p${counter++}"
                            val policy: ConflictPolicy = if (random.nextBoolean()) ConflictPolicy.KEEP else ConflictPolicy.REPLACE
                            val key: String? = if (random.nextInt(3) == 0) "key${random.nextInt(3)}" else null
                            val id: String? = real(30_000) { engine.enqueue(handler, payload, uniqueKey = key, tag = "tag${random.nextInt(2)}", conflictPolicy = policy) }
                            if (id != null) enqueued[payload] = id
                            // a REPLACE or KEEP changes what the row for that key is; the check below reads the store, not this map
                        }
                        3 -> storage.store.getAllActive().randomOrNull(random)?.let { row ->
                            real(30_000) { engine.cancel(row.id) }
                            withdrawn += row.payload
                        }
                        4 -> {
                            val tag = "tag${random.nextInt(2)}"
                            val rows: List<UploaderItem> = storage.store.getAllActive().filter { it.tag == tag }
                            real(30_000) { engine.cancelByTag(tag) }
                            withdrawn += rows.map { it.payload }
                        }
                        5 -> detached.removeFirstOrNull()?.let { (payload, ctx) ->
                            val id: String = storage.store.getAllActive().firstOrNull { it.payload == payload }?.id ?: return@let
                            val result: SettleResult = if (random.nextBoolean()) SettleResult.Delivered else SettleResult.Failed(IllegalStateException("late"))
                            if (result is SettleResult.Delivered) delivered[payload] = (delivered[payload] ?: 0) + 1
                            real(30_000) { engine.settle(id, ctx.claim, result) }
                        }
                        6 -> clock.advanceBy(random.nextLong(0L, 7_200_000L))
                        else -> real(60_000) { engine.drain() }
                    }
                    // invariant every step: unique ids, unique live keys, nothing in two states
                    val rows: List<UploaderItem> = storage.store.getAllActive()
                    assertEquals(rows.size, rows.map { it.id }.toSet().size, "seed $seed: duplicate row ids")
                    val keyed: List<Pair<String, String>> = rows.mapNotNull { r -> r.uniqueKey?.let { r.type to it } }
                    assertEquals(keyed.size, keyed.toSet().size, "seed $seed: two live rows share a unique key")
                }
                // quiet period: everything succeeds, the clock moves a day per pass; only parked rows may remain
                val finisher = Recorder { payload, _ ->
                    delivered[payload] = (delivered[payload] ?: 0) + 1
                    AttemptResult.Success
                }
                engine.close()
                val engine2: UploaderEngine = newEngine(storage, finisher, clock, scope)
                repeat(6) {
                    clock.advanceBy(86_400_000L)
                    real(60_000) { engine2.drain() }
                }
                val left: List<UploaderItem> = storage.store.getAllActive()
                assertTrue(left.all { it.state == UploaderItemState.PARKED }, "seed $seed: only parked rows may stay, got ${left.map { it.id to it.state }}")
                assertEquals(emptyList(), executedAfterWithdrawal, "seed $seed: a withdrawn item ran again")
                // a REPLACE or a KEEP can legitimately turn a payload's row into another one's; what may never happen is a row
                // that was confirmed delivered coming back to run again with the same payload without a new enqueue
                val reruns: Map<String, Int> = delivered.filterValues { it > 2 }
                assertEquals(emptyMap(), reruns, "seed $seed: an item confirmed delivered more than twice (success plus one late report)")
            } finally {
                scope.cancel()
                storage.close()
            }
        }
    }
}
