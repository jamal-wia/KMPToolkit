package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * Round 9 (documentation as a test, mutation survivors): the exact edges of the promises in the engine and
 * UploaderEngine KDoc. Every case is a boundary one step either side, over the in-memory store and a manual clock,
 * so a `>` that became a `>=`, a `+ 1` that vanished or a flag that is always false cannot hide.
 */
class EngineKdocPromisesRound9AdversarialTest {

    private class Handler(
        override val type: String = "r9",
        override val schemaVersion: Int = 1,
        override val retryPolicy: RetryPolicy = Policy(),
        private val ordering: (String) -> String? = { null },
    ) : UploaderHandler<String> {
        val contexts: MutableList<AttemptContext> = mutableListOf()
        val seen: MutableList<String> = mutableListOf()
        var behave: (String) -> AttemptResult = { AttemptResult.Success }
        override val constraintKeys: Set<String> = emptySet()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override fun orderingKey(payload: String): String? = ordering(payload)
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            contexts += context
            seen += payload
            return behave(payload)
        }
    }

    private class Policy(
        override val maxDelayMillis: Long = 60_000L,
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never,
        val delay: Long = 1_000L,
    ) : RetryPolicy {
        override fun backoffMillis(attempts: Int): Long = delay
    }

    private fun row(
        id: String,
        payload: String = id,
        type: String = "r9",
        state: UploaderItemState = UploaderItemState.PENDING,
        nextRunAt: Long = 0L,
        lease: Long = 0L,
        claim: Long = 0L,
        attempts: Int = 0,
        schemaVersion: Int = 1,
        orderingKey: String? = null,
        uniqueKey: String? = null,
    ): UploaderItem = UploaderItem(
        id = id, type = type, payload = payload, schemaVersion = schemaVersion, uniqueKey = uniqueKey,
        orderingKey = orderingKey, tag = null, state = state, attempts = attempts, nextRunAtEpochMillis = nextRunAt,
        createdAtEpochMillis = 1L, lastError = null, leaseUntilEpochMillis = lease, claim = claim,
    )

    private fun TestScope.engineOf(
        store: InMemoryUploaderStore,
        vararg handlers: UploaderHandler<*>,
        clock: MutableUploaderClock,
    ): UploaderEngine = createUploaderEngine(store, handlers.toList(), backgroundScope, clock = clock)

    // ---- a persisted gate and a lease are honoured up to, and not past, the instant they name -------------

    @Test
    fun `a backoff gate equal to now is due and one millisecond ahead is not`() = runTest {
        val now = 5_000_000L
        val store = InMemoryUploaderStore(
            listOf(row("due", nextRunAt = now), row("early", nextRunAt = now + 1), row("past", nextRunAt = now - 1)),
        )
        val handler = Handler()
        val engine = engineOf(store, handler, clock = MutableUploaderClock(now))

        withTimeout(5.seconds) { engine.drain() }

        assertEquals(setOf("due", "past"), handler.seen.toSet(), "ran exactly the rows whose gate is not in the future")
        assertEquals(listOf("early"), store.snapshot().map { it.id })
    }

    @Test
    fun `a lease that ends exactly now is expired and re-handed and one that ends a millisecond later is held`() = runTest {
        val now = 5_000_000L
        val store = InMemoryUploaderStore(
            listOf(
                row("ends-now", state = UploaderItemState.IN_FLIGHT, lease = now, claim = 3),
                row("ends-later", state = UploaderItemState.IN_FLIGHT, lease = now + 1, claim = 3),
            ),
        )
        val handler = Handler()
        val engine = engineOf(store, handler, clock = MutableUploaderClock(now))

        withTimeout(5.seconds) { engine.drain() }

        assertEquals(listOf("ends-now"), handler.seen)
        assertEquals(listOf("ends-later"), store.snapshot().map { it.id })
    }

    // ---- the attempt context -----------------------------------------------------------------------------

    @Test
    fun `the attempt context carries the attempt count the next claim the keys and whether the item was detached`() = runTest {
        val now = 9_000_000L
        val store = InMemoryUploaderStore(
            listOf(
                row("fresh", attempts = 0, claim = 0, uniqueKey = "uk", orderingKey = "ok"),
                row("retried", attempts = 4, claim = 6, orderingKey = "other"),
                row("expired", state = UploaderItemState.IN_FLIGHT, lease = now - 1, attempts = 2, claim = 9),
            ),
        )
        val handler = Handler()
        val engine = engineOf(store, handler, clock = MutableUploaderClock(now))

        withTimeout(5.seconds) { engine.drain() }

        val byId: Map<String, AttemptContext> = handler.contexts.associateBy { it.id }
        assertEquals(AttemptContext("fresh", 0, "uk", "ok", claim = 1, wasDetached = false), byId["fresh"])
        assertEquals(AttemptContext("retried", 4, null, "other", claim = 7, wasDetached = false), byId["retried"])
        assertEquals(AttemptContext("expired", 2, null, null, claim = 10, wasDetached = true), byId["expired"])
    }

    // ---- a payload from a newer schema parks; the same version and older ones run ----------------------------

    @Test
    fun `a payload of the handler's own schema version or older runs and only a newer one parks`() = runTest {
        val store = InMemoryUploaderStore(
            listOf(
                row("older", schemaVersion = 1),
                row("same", schemaVersion = 2),
                row("newer", schemaVersion = 3),
            ),
        )
        val handler = Handler(schemaVersion = 2)
        val engine = engineOf(store, handler, clock = MutableUploaderClock(1_000L))

        withTimeout(5.seconds) { engine.drain() }

        assertEquals(setOf("older", "same"), handler.seen.toSet())
        val left: UploaderItem = store.snapshot().single()
        assertEquals("newer", left.id)
        assertEquals(UploaderItemState.PARKED, left.state)
    }

    // ---- the give-up policies fire exactly at their limit ---------------------------------------------------

    @Test
    fun `ParkAfterAttempts and DropAfterAttempts act on the attempt that reaches the limit and not before`() = runTest {
        for (parking in listOf(true, false)) {
            val limit = 3
            val policy = Policy(
                giveUp = if (parking) GiveUpPolicy.ParkAfterAttempts(limit) else GiveUpPolicy.DropAfterAttempts(limit),
                delay = 0L,
            )
            val store = InMemoryUploaderStore(listOf(row("a")))
            val clock = MutableUploaderClock(1_000_000L)
            val handler = Handler(retryPolicy = policy)
            handler.behave = { AttemptResult.Retry(IllegalStateException("no")) }
            val engine = engineOf(store, handler, clock = clock)

            val states: MutableList<String> = mutableListOf()
            repeat(limit) {
                clock.advanceBy(120_000L)
                withTimeout(5.seconds) { engine.drain() }
                val s: UploaderItem? = store.snapshot().singleOrNull()
                states += if (s == null) "gone" else "${s.state}/${s.attempts}"
            }

            val expectedLast: String = if (parking) "PARKED/$limit" else "gone"
            assertEquals(listOf("PENDING/1", "PENDING/2", expectedLast), states, "parking=$parking")
            assertEquals(limit, handler.seen.size)
        }
    }

    // ---- settle: which claims are current, exactly -------------------------------------------------------------

    @Test
    fun `a failed or parked report from an in-flight item is current for its own claim and the next and no other`() = runTest {
        val now = 2_000_000L
        for (result in listOf<SettleResult>(SettleResult.Failed(IllegalStateException("x")), SettleResult.Park("stop"))) {
            for ((offset, applies) in listOf(-1L to false, 0L to true, 1L to true, 2L to false)) {
                val store = InMemoryUploaderStore(
                    listOf(row("a", state = UploaderItemState.IN_FLIGHT, lease = now + 60_000, claim = 5)),
                )
                val engine = engineOf(store, Handler(), clock = MutableUploaderClock(now))

                engine.settle("a", 5 + offset, result)

                val after: UploaderItem = store.snapshot().single()
                val changed: Boolean = after.claim != 5L || after.state != UploaderItemState.IN_FLIGHT
                assertEquals(applies, changed, "$result with claim offset $offset on an in-flight row")
            }
        }
    }

    @Test
    fun `a failed or parked report on a pending item is current only for the claim the drain is about to write`() = runTest {
        val now = 2_000_000L
        for (result in listOf<SettleResult>(SettleResult.Failed(IllegalStateException("x")), SettleResult.Park("stop"))) {
            for ((offset, applies) in listOf(-1L to false, 0L to false, 1L to true, 2L to false)) {
                val store = InMemoryUploaderStore(listOf(row("a", state = UploaderItemState.PENDING, claim = 5)))
                val engine = engineOf(store, Handler(), clock = MutableUploaderClock(now))

                engine.settle("a", 5 + offset, result)

                val after: UploaderItem = store.snapshot().single()
                val changed: Boolean = after.claim != 5L
                assertEquals(applies, changed, "$result with claim offset $offset on a pending row")
            }
        }
    }

    @Test
    fun `a delivered or dropped report stands from any claim and a report on a parked row or an unknown id changes nothing`() =
        runTest {
            val now = 2_000_000L
            for (result in listOf<SettleResult>(SettleResult.Delivered, SettleResult.Drop("gone"))) {
                for (claim in listOf(-7L, 0L, 5L, 6L, 99L)) {
                    val store = InMemoryUploaderStore(
                        listOf(row("a", state = UploaderItemState.IN_FLIGHT, lease = now + 1_000, claim = 5)),
                    )
                    engineOf(store, Handler(), clock = MutableUploaderClock(now)).settle("a", claim, result)
                    assertEquals(emptyList(), store.snapshot(), "$result with claim $claim")
                }
            }
            val parked = row("p", state = UploaderItemState.PARKED, claim = 5)
            val store = InMemoryUploaderStore(listOf(parked))
            val engine = engineOf(store, Handler(), clock = MutableUploaderClock(now))
            for (claim in listOf(5L, 6L)) {
                engine.settle("p", claim, SettleResult.Failed(IllegalStateException("x")))
                engine.settle("p", claim, SettleResult.Park("again"))
            }
            engine.settle("nobody", 1, SettleResult.Failed(IllegalStateException("x")))
            assertEquals(listOf(parked), store.snapshot())
        }

    // ---- ordering channels are per type and key --------------------------------------------------------------

    @Test
    fun `an ordering key is a channel per handler type so equal keys of two types do not block each other`() = runTest {
        val store = InMemoryUploaderStore(
            listOf(
                row("a1", type = "ta", orderingKey = "k"),
                row("b1", type = "tb", orderingKey = "k"),
                row("a2", type = "ta", orderingKey = "k"),
            ),
        )
        val a = Handler(type = "ta", ordering = { "k" })
        val b = Handler(type = "tb", ordering = { "k" })
        a.behave = { if (it == "a1") AttemptResult.Retry(IllegalStateException("hold")) else AttemptResult.Success }
        val engine = engineOf(store, a, b, clock = MutableUploaderClock(1_000_000L))

        withTimeout(5.seconds) { engine.drain() }

        assertEquals(listOf("a1"), a.seen, "a2 waits behind its failing head of the same type and key")
        assertEquals(listOf("b1"), b.seen, "the other type's channel is independent")
        assertEquals(listOf("a1", "a2"), store.snapshot().map { it.id })
    }

    // ---- awaitDrained ----------------------------------------------------------------------------------------

    @Test
    fun `awaitDrained itself triggers a pass and an item that is due only now is delivered without any other wake`() = runTest {
        val clock = MutableUploaderClock(1_000_000L)
        val store = InMemoryUploaderStore(listOf(row("a", nextRunAt = 1_000_000L + 100_000L)))
        val handler = Handler()
        val engine = engineOf(store, handler, clock = clock)
        engine.start()
        runCurrent() // the first pass sees a gate 100 s away (inside the policy's reachable 120 s) and arms an alarm that far out
        assertEquals(emptyList(), handler.seen)

        clock.advanceBy(100_001L) // the wall clock moves, virtual time does not: only an explicit trigger can notice
        val drained: Boolean = withTimeout(10.seconds) { engine.awaitDrained(5.seconds) }

        assertTrue(drained, "awaitDrained is documented to trigger a drain")
        assertEquals(listOf("a"), handler.seen)
    }

    @Test
    fun `awaitDrained gives up at its timeout and not much later and an in-flight item is never drained`() = runTest {
        val clock = MutableUploaderClock(1_000_000L)
        val store = InMemoryUploaderStore(
            listOf(row("a", state = UploaderItemState.IN_FLIGHT, lease = 1_000_000L + 86_400_000L, claim = 1)),
        )
        val engine = engineOf(store, Handler(), clock = clock)
        engine.start()

        val before: Long = testScheduler.currentTime
        val drained: Boolean = withTimeout(60.seconds) { engine.awaitDrained(10.seconds) }
        val waited: Long = testScheduler.currentTime - before

        assertFalse(drained)
        assertTrue(waited in 10_000L..10_500L, "waited $waited ms of virtual time for a 10 s timeout at a 500 ms poll")
        assertTrue(withTimeout(5.seconds) { InMemoryUploaderStore().let { createUploaderEngine(it, emptyList(), backgroundScope).awaitDrained(0.seconds) } })
    }
}
