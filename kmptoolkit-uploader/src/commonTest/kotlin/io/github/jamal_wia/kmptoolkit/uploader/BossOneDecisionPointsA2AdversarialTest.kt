package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A2 boss round 1: the decision points round 12's design mutants left alive (M3, M4, M7, M9), pinned against the
 * documented contract rather than the implementation.
 *
 * - `settle` guards (docs 04): a report is current only for the claim the item is held under or the one the drain is
 *   about to write; a `Failed` or `Park` from any other claim is a no-op. A PENDING row is held under no claim.
 * - `UploaderItem.nextRunAtEpochMillis`: "epoch millis before which the item must not be attempted again", so at that
 *   millisecond it is due.
 * - `UploaderItem.leaseUntilEpochMillis`: "epoch millis at which a detached executor's claim expires", so at that
 *   millisecond the item is attemptable again.
 * - The drain's empty-queue exit: an item that is owed keeps the platform wake armed, also when its enqueue lands between
 *   the drain's read of an empty queue and its cancel of the wake.
 *
 * Virtual time only (an injected clock, `runTest`), no sleep, no randomness, no files.
 */
class BossOneDecisionPointsA2AdversarialTest {

    @Test
    fun `a failure reported twice by the executor whose failure was already recorded spends one attempt`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()
        val first: AttemptContext = handler.attempts.single()
        engine.settle("item-1", first.claim, SettleResult.Failed(IllegalStateException("503")))
        val afterFirst: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.PENDING, afterFirst.state, "precondition: the recorded failure returned the item to pending")
        assertEquals(1, afterFirst.attempts, "precondition: one attempt spent")

        // The same report arrives again (an executor retrying its own callback): its attempt is no longer run.
        engine.settle("item-1", first.claim, SettleResult.Failed(IllegalStateException("503")))

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.PENDING, item.state)
        assertEquals(1, item.attempts, "a failure from a claim whose verdict was recorded is a superseded report (settle guards)")
    }

    @Test
    fun `a park from the executor whose failure was already recorded leaves the pending item in rotation`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()
        val first: AttemptContext = handler.attempts.single()
        engine.settle("item-1", first.claim, SettleResult.Failed(IllegalStateException("503")))

        engine.settle("item-1", first.claim, SettleResult.Park("late verdict"))

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.PENDING, item.state, "a park from a claim the engine no longer runs is a no-op (settle guards)")
        clock.millis = 60_000L
        engine.drain()
        assertEquals(2, handler.attempts.size, "the item must still be retried")
    }

    @Test
    fun `at the very millisecond the lease ends the item is handed out again`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()
        assertEquals(10_000L, store.find("item-1")?.leaseUntilEpochMillis, "precondition: the lease")

        clock.millis = 10_000L
        engine.drain()

        assertEquals(2, handler.attempts.size, "leaseUntilEpochMillis is the millisecond the claim expires")
        assertTrue(handler.attempts[1].wasDetached, "the re-hand is flagged")
    }

    @Test
    fun `at the very millisecond the backoff gate opens the item is attempted`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 1_000L)
        var failures = 1
        val handler = TestHandler(
            retryPolicy = FixedRetryPolicy(delayMillis = 1_000L),
            onExecute = { _, _ -> if (failures-- > 0) AttemptResult.Retry(IllegalStateException("503")) else AttemptResult.Success },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()
        val gate: Long = assertNotNull(store.find("item-1")).nextRunAtEpochMillis
        assertEquals(2_000L, gate, "precondition: the backoff gate")

        clock.millis = gate
        engine.drain()

        assertEquals(2, handler.attempts.size, "nextRunAtEpochMillis is the first millisecond the item may run again")
        assertEquals(null, store.find("item-1"), "the retry delivered")
    }

    @Test
    fun `an enqueue that lands between the drain's empty read and its wake cancel keeps the item owed and woken`() = runTest {
        val store = TestUploaderStore()
        val wake = TestWakeScheduler()
        val handler = TestHandler()
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, wakeScheduler = wake)
        var enqueued: String? = null
        // The drain reads an empty queue; before it acts on that read, the app enqueues.
        store.afterNextGetAllActive = { enqueued = engine.enqueue(handler, "late") }

        engine.drain()

        assertNotNull(enqueued, "precondition: the enqueue ran inside the window")
        val stillOwed: Boolean = store.items.isNotEmpty()
        assertTrue(
            !stillOwed || wake.armed,
            "an owed item lost its platform wake: items=${store.items} armed=${wake.armed} " +
                "schedules=${wake.scheduleCount} cancels=${wake.cancelCount}",
        )
    }
    /** A store whose next [deleteById] fails, once: the confirming delete of a delivery that landed. */
    private class FailNextDelete(private val inner: TestUploaderStore) : UploaderStore by inner {
        var failNext: Boolean = false
        override suspend fun deleteById(id: String) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("disk busy")
            }
            inner.deleteById(id)
        }
    }

    private fun detachOldSucceedNew(): TestHandler = TestHandler(
        onExecute = { _, payload -> if (payload == "old") AttemptResult.Detached(leaseMillis = 10_000) else AttemptResult.Success },
    )

    @Test
    fun `a KEEP over a parked row held as delivered withdraws it and runs the new intent once`() = runTest {
        val backing = TestUploaderStore()
        val store = FailNextDelete(backing)
        val clock = TestClock(millis = 0L)
        val handler: TestHandler = detachOldSucceedNew()
        val engine: UploaderEngine =
            testEngine(store, listOf(handler), backgroundScope, clock = clock, idGenerator = SequentialIds("bk1")::next)
        val oldId: String = assertNotNull(engine.enqueue(handler, "old", uniqueKey = "k"))
        engine.drain()
        val claim: Long = handler.attempts.single().claim
        engine.settle(oldId, claim, SettleResult.Park("verdict first"))
        assertEquals(UploaderItemState.PARKED, backing.find(oldId)?.state, "precondition: parked")
        store.failNext = true
        assertTrue(runCatching { engine.settle(oldId, claim, SettleResult.Delivered) }.isFailure, "precondition: the delete failed")

        val newId: String? = engine.enqueue(handler, "new", uniqueKey = "k")
        engine.drain()

        assertNotNull(newId, "a KEEP over a parked holder inserts (03-guide: it withdraws the parked row and runs the new intent)")
        assertEquals(listOf("old", "new"), handler.payloads, "the old effect is not run again; the new one runs once")
        assertTrue(backing.items.isEmpty(), "nothing is left: ${backing.items}")
    }

    @Test
    fun `a REPLACE over an in-flight row held as delivered withdraws it and runs the new intent once`() = runTest {
        val backing = TestUploaderStore()
        val store = FailNextDelete(backing)
        val clock = TestClock(millis = 0L)
        val handler: TestHandler = detachOldSucceedNew()
        val engine: UploaderEngine =
            testEngine(store, listOf(handler), backgroundScope, clock = clock, idGenerator = SequentialIds("bk2")::next)
        val oldId: String = assertNotNull(engine.enqueue(handler, "old", uniqueKey = "k"))
        engine.drain()
        val claim: Long = handler.attempts.single().claim
        store.failNext = true
        assertTrue(runCatching { engine.settle(oldId, claim, SettleResult.Delivered) }.isFailure, "precondition: the delete failed")
        assertEquals(UploaderItemState.IN_FLIGHT, backing.find(oldId)?.state, "precondition: held in flight")

        val newId: String? = engine.enqueue(handler, "new", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE)
        engine.drain()
        clock.millis = 20_000L
        engine.drain()

        assertNotNull(newId)
        assertEquals(listOf("old", "new"), handler.payloads, "the old effect is not run again; the new one runs once")
        assertTrue(backing.items.isEmpty(), "nothing is left: ${backing.items}")
    }

    @Test
    fun `a KEEP over an in-flight row held as delivered is kept and the held row is confirmed without a second run`() = runTest {
        val backing = TestUploaderStore()
        val store = FailNextDelete(backing)
        val clock = TestClock(millis = 0L)
        val handler: TestHandler = detachOldSucceedNew()
        val engine: UploaderEngine =
            testEngine(store, listOf(handler), backgroundScope, clock = clock, idGenerator = SequentialIds("bk3")::next)
        val oldId: String = assertNotNull(engine.enqueue(handler, "old", uniqueKey = "k"))
        engine.drain()
        val claim: Long = handler.attempts.single().claim
        store.failNext = true
        assertTrue(runCatching { engine.settle(oldId, claim, SettleResult.Delivered) }.isFailure, "precondition: the delete failed")

        val newId: String? = engine.enqueue(handler, "new", uniqueKey = "k")
        clock.millis = 20_000L
        engine.drain()

        assertEquals(null, newId, "an in-flight holder wins a KEEP (04: KeepResult.Kept)")
        assertEquals(listOf("old"), handler.payloads, "the delivered effect is never handed out again")
        assertTrue(backing.items.isEmpty(), "the held row is deleted at a later pass: ${backing.items}")
    }
    @Test
    fun `with the clock held at the top of its range a failing item waits for the alarm and is retried by it`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = Long.MAX_VALUE)
        val handler: TestHandler = failingHandler(retryPolicy = FixedRetryPolicy(delayMillis = 0L))
        val config = UploaderConfig(heartbeatInterval = 1.hours, minAlarmDelay = 1.seconds)
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock, config = config)
        engine.enqueue(handler, "p")

        engine.start()
        runCurrent()
        val afterStart: Int = handler.attempts.size
        advanceTimeBy(10.minutes)
        runCurrent()
        val afterAlarms: Int = handler.attempts.size
        engine.close()

        // 04 drain: every gate reads as due, so each pass gives the item one turn and the next waits for an alarm.
        assertEquals(1, afterStart, "one turn per pass, no busy loop at the top of the clock")
        assertTrue(afterAlarms >= 2, "the alarm retried the item without any trigger: $afterAlarms attempts")
        assertTrue(afterAlarms <= 1 + 600, "at most one retry per alarm floor (1 s): $afterAlarms attempts in 10 min")
    }
    @Test
    fun `a held in-flight delivery stays owed until its lease ends and is then deleted without a second run`() = runTest {
        val backing = TestUploaderStore()
        val store = FailNextDelete(backing)
        val clock = TestClock(millis = 0L)
        val handler: TestHandler = detachOldSucceedNew()
        val engine: UploaderEngine =
            testEngine(store, listOf(handler), backgroundScope, clock = clock, idGenerator = SequentialIds("bk4")::next)
        val oldId: String = assertNotNull(engine.enqueue(handler, "old"))
        engine.drain()
        val claim: Long = handler.attempts.single().claim
        store.failNext = true
        assertTrue(runCatching { engine.settle(oldId, claim, SettleResult.Delivered) }.isFailure, "precondition: the delete failed")

        // D-118 (lease first) and 04 settle: while the row is in flight the confirming pass comes once its lease has ended;
        // until then the item counts as owed.
        clock.millis = 9_999L
        engine.drain()
        assertEquals(UploaderItemState.IN_FLIGHT, backing.find(oldId)?.state, "the lease is honoured before the confirming delete")

        clock.millis = 10_000L
        engine.drain()
        assertEquals(null, backing.find(oldId), "deleted at the first pass after the lease")
        assertEquals(listOf("old"), handler.payloads, "never handed out again")
    }
}
