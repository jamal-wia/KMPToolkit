package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Boss round 5 of unit A1, design mutant B8 (the held sweep runs on every loop of one drain call instead of once): P10
 * says the parked held rows are named "once per call" and the sweep "reads one row per remembered entry", and
 * DrainCall says a store that keeps failing costs one delete per row per call. A call that loops three times (an
 * ordering channel of two items) must read and try the parked held row once.
 *
 * Deterministic by construction: manual drains, a hand-driven clock, no sleep, no randomness. Ids carry `b5o-`.
 */
class Boss5HeldSweepOncePerCallAdversarialTest {

    private val remembered: MutableList<UploaderItem> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        remembered.forEach { DeliveredUnconfirmed.forget(it) }
        remembered.clear()
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Counts point reads and delete tries per id, and fails the deletes of one id. */
    private class CountingStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        val reads: MutableMap<String, Int> = mutableMapOf()
        val deletes: MutableMap<String, Int> = mutableMapOf()
        var failDeletesOf: String? = null
        var failNextDelete: Boolean = false

        override suspend fun getById(id: String): UploaderItem? {
            reads[id] = (reads[id] ?: 0) + 1
            return inner.getById(id)
        }

        override suspend fun deleteById(id: String) {
            deletes[id] = (deletes[id] ?: 0) + 1
            if (failNextDelete) {
                failNextDelete = false
                throw IllegalStateException("database is locked")
            }
            if (id == failDeletesOf) throw IllegalStateException("database is locked")
            inner.deleteById(id)
        }
    }

    @Test
    fun `a drain call that loops three times reads and tries a parked held row once`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = CountingStore(inner)
            val clock = TestClock(5_000_000L)
            val plain = TestHandler(type = "b5o.plain")
            val chain = TestHandler(type = "b5o.chain", ordering = { "one-channel" })
            val engine: UploaderEngine = testEngine(store, listOf(plain, chain), scopeOn(parent), clock, idGenerator = SequentialIds("b5o-row")::next)
            val heldId: String = assertNotNull(engine.enqueue(plain, "delivered"))
            store.failNextDelete = true
            engine.drain()
            val row: UploaderItem = assertNotNull(inner.find(heldId), "setup: the delivered row could not be deleted")
            remembered += row
            assertTrue(inner.park(heldId, "late verdict", row.state, row.claim), "setup: the row is parked")
            store.failDeletesOf = heldId
            assertNotNull(engine.enqueue(chain, "first"))
            assertNotNull(engine.enqueue(chain, "second"))
            // Past the in-memory backoff of the failed delete (at most one heartbeat), so the held row is due.
            clock.millis += UploaderConfig().heartbeatInterval.inWholeMilliseconds
            store.reads.clear()
            store.deletes.clear()

            engine.drain()

            assertEquals(listOf("first", "second"), chain.payloads, "setup: the channel's two items ran in one call, one per loop")
            assertEquals(1, store.reads[heldId], "the parked held row is read once per call, was ${store.reads[heldId]}")
            assertEquals(1, store.deletes[heldId], "its delete is tried once per call, was ${store.deletes[heldId]}")
            assertEquals(UploaderItemState.PARKED, inner.find(heldId)?.state, "the row whose delete keeps failing stays held")
        } finally {
            parent.cancel()
        }
    }
}
