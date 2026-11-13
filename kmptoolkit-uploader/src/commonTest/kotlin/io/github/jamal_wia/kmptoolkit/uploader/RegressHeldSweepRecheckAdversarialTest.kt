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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * N1, the re-check cadence of the held-row sweep: an entry of [DeliveredUnconfirmed] whose row the sweep found absent
 * is not read again before the heartbeat cadence, a row that comes back after it read absent is rescued at that
 * cadence at the latest, and a store outage costs one error line per call, not one per remembered entry.
 *
 * Deterministic by construction: virtual time, a hand-driven clock, no sleep and no randomness. Every id carries the
 * prefix `n1r-` because the registry is process-wide.
 */
class RegressHeldSweepRecheckAdversarialTest {

    private val remembered: MutableList<UploaderItem> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        remembered.forEach { DeliveredUnconfirmed.forget(it) }
        remembered.clear()
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    private class CollectingLogger : Logger {
        val errors: MutableList<String> = mutableListOf()
        override val tag: String = "n1r"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            val line: String = message()
            if (level == LogLevel.ERROR) errors += line
        }
    }

    /** Counts point reads, can fail every point read of an `n1r-held-` id, and can answer `null` once for one id. */
    private class ProbeStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var pointReads: Int = 0
        var outage: Boolean = false
        var nullOnceFor: String? = null

        override suspend fun getById(id: String): UploaderItem? {
            pointReads++
            if (outage && id.startsWith("n1r-held-")) throw IllegalStateException("database is locked")
            if (id == nullOnceFor) {
                nullOnceFor = null
                return null
            }
            return inner.getById(id)
        }

        var deleteFailures: Int = 0

        override suspend fun deleteById(id: String) {
            if (deleteFailures > 0) {
                deleteFailures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    private fun remember(count: Int) {
        repeat(count) { index ->
            val row = UploaderItem(
                id = "n1r-held-$index", type = "n1r.gone", payload = "p$index", schemaVersion = 1, uniqueKey = null, orderingKey = null,
                tag = null, state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = index.toLong(),
                lastError = null,
            )
            DeliveredUnconfirmed.remember(row)
            remembered += row
        }
    }

    @Test
    fun `entries found absent are read again once per heartbeat and not before`() = runTest {
        val parent = Job()
        try {
            remember(10_000)
            val store = ProbeStore(TestUploaderStore())
            val clock = TestClock(5_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(TestHandler()), scopeOn(parent), clock, idGenerator = SequentialIds("n1r")::next)
            val heartbeat: Long = UploaderConfig().heartbeatInterval.inWholeMilliseconds

            engine.drain()
            val first: Int = store.pointReads
            store.pointReads = 0
            engine.drain()
            val second: Int = store.pointReads
            clock.millis += heartbeat - 1
            store.pointReads = 0
            engine.drain()
            val justBefore: Int = store.pointReads
            clock.millis += 1
            store.pointReads = 0
            engine.drain()
            val atHeartbeat: Int = store.pointReads
            store.pointReads = 0
            engine.drain()
            val afterRecheck: Int = store.pointReads

            assertTrue(first >= 10_000, "the first sweep reads every remembered entry, was $first")
            assertTrue(second <= 40, "entries found absent are not re-read before the heartbeat cadence, was $second")
            assertTrue(justBefore <= 40, "one millisecond short of the heartbeat nothing is re-read, was $justBefore")
            assertTrue(atHeartbeat >= 10_000, "a drain one heartbeat after the last re-check reads all entries again, was $atHeartbeat")
            assertTrue(afterRecheck <= 40, "the re-check does not repeat in the next call, was $afterRecheck")
            assertEquals(10_000, DeliveredUnconfirmed.ids().count { it.startsWith("n1r-held-") }, "the entries stay remembered")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a parked held row that read absent once is rescued at the heartbeat and not by the next drain`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = ProbeStore(inner)
            val clock = TestClock(5_000_000L)
            val handler = TestHandler(type = "n1r.plain")
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("n1r-row")::next)
            val heartbeat: Long = UploaderConfig().heartbeatInterval.inWholeMilliseconds
            val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
            // The handler succeeds and its confirming delete fails: the delivery is remembered, the row stays queued.
            store.deleteFailures = 1
            engine.drain()
            val row: UploaderItem = assertNotNull(inner.find(id), "setup: the delivered row could not be deleted")
            remembered += row
            assertEquals(1, handler.payloads.size, "setup: the handler ran once")
            // A verdict parks the row out of the active list, and the next sweep reads it as absent (a withdrawal
            // rolled back in a transaction the sweep could observe).
            assertTrue(inner.park(id, "late verdict", row.state, row.claim), "setup: the row is parked")
            clock.millis += 1_000L
            store.nullOnceFor = id

            engine.drain()
            assertEquals(UploaderItemState.PARKED, inner.find(id)?.state, "the sweep that read null leaves the row alone")
            engine.drain()

            assertEquals(UploaderItemState.PARKED, inner.find(id)?.state, "entries found absent are not re-read by the next drain at the same clock")
            clock.millis += heartbeat - 1_000L
            engine.drain()

            assertNull(inner.find(id), "the row is confirmed at the heartbeat cadence")
            assertEquals(1, handler.payloads.size, "the handler ran once: the rescue only retries the delete")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a store outage with a hundred remembered entries logs at most one error line per drain call`() = runTest {
        val parent = Job()
        try {
            remember(100)
            val inner = TestUploaderStore()
            val store = ProbeStore(inner)
            val logger = CollectingLogger()
            val clock = TestClock(5_000_000L)
            val engine: UploaderEngine = createUploaderEngine(
                store = store, handlers = listOf(TestHandler()), scope = scopeOn(parent), clock = clock, logger = logger,
                idGenerator = SequentialIds("n1r-out")::next,
            )
            store.outage = true

            engine.drain()
            val firstCall: Int = logger.errors.size
            engine.drain()
            val secondCall: Int = logger.errors.size - firstCall
            store.outage = false
            store.pointReads = 0
            engine.drain()

            assertEquals(1, firstCall, "one error line for the whole sweep of the first call, was $firstCall: ${logger.errors.take(3)}")
            assertEquals(1, secondCall, "one error line for the whole sweep of the second call, was $secondCall")
            assertTrue(store.pointReads >= 100, "a read that failed is not remembered as absent: the next call reads it, was ${store.pointReads}")
        } finally {
            parent.cancel()
        }
    }
}
