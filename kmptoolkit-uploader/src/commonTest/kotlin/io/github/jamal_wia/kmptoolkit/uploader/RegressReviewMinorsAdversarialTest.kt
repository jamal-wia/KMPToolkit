package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Refactor-regression round, pins for the three weak spots the Opus review of rule P11 (one attempt per item) and of the
 * held-sweep re-check cadence named: a pass that finds an item's attempt running skips it without spending the item's
 * turn, arming a gate or counting it as work (W1); a held row found again at a re-check leaves the absent set, so its
 * delete is retried at its own backoff (W2); a clock that steps backwards re-checks at once (W3).
 *
 * Deterministic by construction: a test dispatcher on the test scheduler, gates in place of time, a hand-driven clock,
 * no sleep and no randomness. Every id of the registry tests carries the prefix `rm-` because the registry of
 * delivered-but-unconfirmed rows is process-wide.
 */
class RegressReviewMinorsAdversarialTest {

    private val remembered: MutableList<UploaderItem> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        remembered.forEach { DeliveredUnconfirmed.forget(it) }
        remembered.clear()
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    private class CollectingLogger : Logger {
        val lines: MutableList<String> = mutableListOf()
        override val tag: String = "rm"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            lines += message()
        }

        fun count(fragment: String): Int = lines.count { fragment in it }
    }

    /** Counts the reads of the active list: one per sweep of a drain pass. */
    private class ActiveReadsStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var activeReads: Int = 0
        override suspend fun getAllActive(): List<UploaderItem> {
            activeReads++
            return inner.getAllActive()
        }
    }

    private companion object {
        const val BUSY_LINE: String = "already running one for it"
        const val DISAGREE_LINE: String = "differs from its getAllActive"
    }

    // ---- M2 (W1): the BUSY skip is not a turn, not a gate and not work ----------------------------------------------

    @Test
    fun `a drain nested in the only item's own attempt skips it in one sweep and logs no disagreement`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = ActiveReadsStore(inner)
            val logger = CollectingLogger()
            lateinit var engine: UploaderEngine
            var nestedReads = -1
            val handler = TestHandler(onExecute = { _, _ ->
                val before: Int = store.activeReads
                engine.drain()
                nestedReads = store.activeReads - before
                AttemptResult.Success
            })
            engine = createUploaderEngine(
                store = store, handlers = listOf(handler), scope = scopeOn(parent), clock = TestClock(5_000_000L), logger = logger,
                idGenerator = SequentialIds("rm-a")::next,
            )
            engine.enqueue(handler, "only")

            engine.drain()

            assertEquals(1, nestedReads, "the nested pass skips the running item and ends after one sweep: a skip is not work")
            assertEquals(1, logger.count(BUSY_LINE), "the skip is logged once, for the one sweep: ${logger.lines}")
            assertEquals(0, logger.count(DISAGREE_LINE), "a skip is not read as a store that disagrees with itself: ${logger.lines}")
            assertEquals(1, handler.attempts.size, "the item ran once")
            assertEquals(emptyList(), inner.items, "and is delivered")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a drain nested in an attempt runs the other item and skips the running one with no disagreement`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = ActiveReadsStore(inner)
            val logger = CollectingLogger()
            lateinit var engine: UploaderEngine
            var nested = 0
            var nestedReads = -1
            val handler = TestHandler(onExecute = { _, payload ->
                if (payload == "outer" && nested++ == 0) {
                    val before: Int = store.activeReads
                    engine.drain()
                    nestedReads = store.activeReads - before
                }
                AttemptResult.Success
            })
            engine = createUploaderEngine(
                store = store, handlers = listOf(handler), scope = scopeOn(parent), clock = TestClock(5_000_000L), logger = logger,
                idGenerator = SequentialIds("rm-b")::next,
            )
            engine.enqueue(handler, "outer")
            engine.enqueue(handler, "inner")

            engine.drain()

            assertEquals(listOf("outer", "inner"), handler.payloads, "each item ran once, the nested pass ran the other one")
            assertEquals(2, nestedReads, "one sweep that runs the other item, one that finds only the running one")
            assertEquals(2, logger.count(BUSY_LINE), "one skip line per sweep of the nested pass: ${logger.lines}")
            assertEquals(0, logger.count(DISAGREE_LINE), "a skip is not read as a store that disagrees with itself: ${logger.lines}")
            assertEquals(emptyList(), inner.items, "both are delivered")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a manual drain next to the engine's running attempt skips it in one sweep`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        val gate = CompletableDeferred<Unit>()
        var drainJob: Job? = null
        try {
            val inner = TestUploaderStore()
            val store = ActiveReadsStore(inner)
            val logger = CollectingLogger()
            lateinit var handler: TestHandler
            handler = TestHandler(onExecute = { _, _ ->
                if (handler.attempts.size == 1) gate.await()
                AttemptResult.Success
            })
            val engine: UploaderEngine = createUploaderEngine(
                store = store, handlers = listOf(handler), scope = scopeOn(parent), clock = TestClock(5_000_000L), logger = logger,
                idGenerator = SequentialIds("rm-c")::next,
            )
            engine.start()
            engine.enqueue(handler, "x")
            runCurrent()
            assertEquals(1, handler.attempts.size, "setup: the loop's attempt is running and waits on the gate")
            val readsBefore: Int = store.activeReads
            val busyBefore: Int = logger.count(BUSY_LINE)

            drainJob = launch { engine.drain() }
            runCurrent()

            assertTrue(drainJob.isCompleted, "the manual drain returned without waiting for the running attempt")
            assertEquals(1, store.activeReads - readsBefore, "the manual pass read the queue once and ended: a skip is not work")
            assertEquals(1, logger.count(BUSY_LINE) - busyBefore, "and logged the skip once: ${logger.lines}")
            assertEquals(0, logger.count(DISAGREE_LINE), "a skip is not read as a store that disagrees with itself: ${logger.lines}")
        } finally {
            gate.complete(Unit)
            drainJob?.cancel()
            parent.cancel()
        }
    }

    // ---- M3 and M4 (W2, W3): the re-check cadence of the held sweep ----------------------------------------------------

    /** Counts the point reads of `rm-held-` ids, can answer `null` once for one id and fails the first deletes. */
    private class ProbeStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var heldReads: Int = 0
        var nullOnceFor: String? = null
        var deleteFailures: Int = 0

        override suspend fun getById(id: String): UploaderItem? {
            if (id.startsWith("rm-held-")) heldReads++
            if (id == nullOnceFor) {
                nullOnceFor = null
                return null
            }
            return inner.getById(id)
        }

        override suspend fun deleteById(id: String) {
            if (deleteFailures > 0) {
                deleteFailures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    @Test
    fun `a held row found again at the re-check has its failed delete retried at its backoff and not a heartbeat later`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = ProbeStore(inner)
            val clock = TestClock(5_000_000L)
            val handler = TestHandler(type = "rm.plain")
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("rm-held")::next)
            val config = UploaderConfig()
            val heartbeat: Long = config.heartbeatInterval.inWholeMilliseconds
            val backoff: Long = config.minAlarmDelay.inWholeMilliseconds
            val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
            // The handler succeeds and its confirming delete fails: the delivery is remembered, the row stays queued.
            store.deleteFailures = 1
            engine.drain()
            val row: UploaderItem = assertNotNull(inner.find(id), "setup: the delivered row could not be deleted")
            remembered += row
            // A verdict parks the row out of the active list; the next sweep reads it as absent once.
            assertTrue(inner.park(id, "late verdict", row.state, row.claim), "setup: the row is parked")
            clock.millis += 1_000L
            store.nullOnceFor = id
            engine.drain()
            assertEquals(UploaderItemState.PARKED, inner.find(id)?.state, "setup: the sweep that read null left the row alone")
            // At the heartbeat the re-check finds the row, and its confirming delete fails once more.
            clock.millis += heartbeat - 1_000L
            store.deleteFailures = 1

            engine.drain()
            assertEquals(UploaderItemState.PARKED, inner.find(id)?.state, "the confirming delete failed at the re-check, the row is still held")

            clock.millis += backoff
            engine.drain()

            assertNull(inner.find(id), "the delete is retried when its backoff has passed, not at the next heartbeat")
            assertEquals(1, handler.payloads.size, "the handler ran once: only the delete is retried")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a clock that steps backwards re-reads the entries found absent at once`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val count = 200
            repeat(count) { index ->
                val row = UploaderItem(
                    id = "rm-held-$index", type = "rm.gone", payload = "p$index", schemaVersion = 1, uniqueKey = null, orderingKey = null,
                    tag = null, state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = index.toLong(),
                    lastError = null,
                )
                DeliveredUnconfirmed.remember(row)
                remembered += row
            }
            val store = ProbeStore(TestUploaderStore())
            val clock = TestClock(5_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(TestHandler()), scopeOn(parent), clock, idGenerator = SequentialIds("rm-clk")::next)

            engine.drain()
            val first: Int = store.heldReads
            store.heldReads = 0
            engine.drain()
            val sameClock: Int = store.heldReads
            clock.millis -= 1L
            store.heldReads = 0
            engine.drain()
            val stepped: Int = store.heldReads
            store.heldReads = 0
            engine.drain()
            val afterStep: Int = store.heldReads

            assertTrue(first >= count, "the first sweep reads every remembered entry, was $first")
            assertEquals(0, sameClock, "at the same clock nothing found absent is read again")
            assertTrue(stepped >= count, "a clock one millisecond behind the last re-check re-reads every entry at once, was $stepped")
            assertEquals(0, afterStep, "the re-check does not repeat in the next call, was $afterStep")
        } finally {
            parent.cancel()
        }
    }
}
