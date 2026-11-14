package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * N1, the per-call store read cost of remembered deliveries (the held-row sweep of the drain): measured at 10 000
 * remembered entries and bounded from above. The sweep reads one row per remembered entry and does it once per call of
 * `drain()`, not once per pass over the queue and not once per queued item.
 */
class RegressHeldSweepCostAdversarialTest {

    private val remembered: MutableList<UploaderItem> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        remembered.forEach { DeliveredUnconfirmed.forget(it) }
        remembered.clear()
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    private class CountingStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var pointReads: Int = 0
        var listReads: Int = 0

        override suspend fun getById(id: String): UploaderItem? {
            pointReads++
            return inner.getById(id)
        }

        override suspend fun getAllActive(): List<UploaderItem> {
            listReads++
            return inner.getAllActive()
        }
    }

    private fun remember(count: Int) {
        repeat(count) { index ->
            val row = UploaderItem(
                id = "n1-held-$index", type = "n1.gone", payload = "p$index", schemaVersion = 1, uniqueKey = null, orderingKey = null,
                tag = null, state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = index.toLong(),
                lastError = null,
            )
            DeliveredUnconfirmed.remember(row)
            remembered += row
        }
    }

    @Test
    fun `a drain call with ten thousand remembered deliveries reads each of them once and nothing more`() = runTest {
        val parent = Job()
        try {
            remember(10_000)
            val inner = TestUploaderStore()
            val store = CountingStore(inner)
            val handler = TestHandler()
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("n1")::next)
            engine.enqueue(handler, "one")

            engine.drain()
            val first: Int = store.pointReads
            store.pointReads = 0
            engine.drain()
            val second: Int = store.pointReads

            assertEquals(1, handler.attempts.size, "the queued item ran exactly once")
            assertTrue(first in 10_000..10_040, "first call: one point read per remembered entry plus a handful for the item, was $first")
            assertTrue(second <= 40, "entries found absent are not re-read before the heartbeat cadence, was $second")
            assertEquals(10_000, DeliveredUnconfirmed.ids().count { it.startsWith("n1-held-") }, "entries whose rows are gone stay remembered")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `the sweep of remembered deliveries happens once per call and not once per queued item`() = runTest {
        val parent = Job()
        try {
            remember(1_000)
            val inner = TestUploaderStore()
            val store = CountingStore(inner)
            val handler = TestHandler()
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("n1b")::next)
            repeat(200) { engine.enqueue(handler, "item-$it") }

            engine.drain()

            assertEquals(200, handler.attempts.size)
            assertTrue(store.pointReads <= 1_000 + 6 * 200 + 20, "one sweep plus a few reads per queued item, was ${store.pointReads}")
            assertTrue(store.pointReads >= 1_000, "the sweep did read the remembered entries, was ${store.pointReads}")
        } finally {
            parent.cancel()
        }
    }
}
