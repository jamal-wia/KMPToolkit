package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Boss round 5 of unit A1, S2: the held sweep must record its re-check only after its reads finished. Stamped before
 * them, a drain cancelled during its re-check sweep consumed the re-check without reading anything, and the next one
 * waited a whole heartbeat from that stamp, so a caller whose drain is cancelled once per heartbeat (a budgeted
 * background drain) kept a parked held row from ever being rescued. Contract: rule P10 of DefaultUploaderEngine, a
 * sweep cancelled during its reads does not count as a re-check; the row is rescued by the first sweep that completes.
 */
class Boss5CancelledRecheckAdversarialTest {

    private val remembered: MutableList<UploaderItem> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        remembered.forEach { DeliveredUnconfirmed.forget(it) }
        remembered.clear()
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    private class ProbeStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var nullOnceFor: String? = null
        var deleteFailures: Int = 0
        var blockReadsOf: String? = null
        val blocked: CompletableDeferred<Unit> = CompletableDeferred()
        var reachedBlock: Int = 0

        override suspend fun getById(id: String): UploaderItem? {
            if (id == nullOnceFor) {
                nullOnceFor = null
                return null
            }
            if (id == blockReadsOf) {
                reachedBlock++
                blocked.await()
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
    fun `a drain cancelled during each re-check sweep does not keep a parked held row from its rescue`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = ProbeStore(inner)
            val clock = TestClock(5_000_000L)
            val handler = TestHandler(type = "b5c.plain")
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("b5c-row")::next)
            val heartbeat: Long = UploaderConfig().heartbeatInterval.inWholeMilliseconds
            val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
            store.deleteFailures = 1
            engine.drain()
            val row: UploaderItem = assertNotNull(inner.find(id), "setup: the delivered row could not be deleted")
            remembered += row
            assertTrue(inner.park(id, "late verdict", row.state, row.claim), "setup: the row is parked")
            clock.millis += 1_000L
            store.nullOnceFor = id
            engine.drain()
            assertEquals(UploaderItemState.PARKED, inner.find(id)?.state, "setup: the sweep that read null leaves the row alone")

            store.blockReadsOf = id
            repeat(4) { round ->
                if (inner.find(id) == null) return@repeat // rescued: nothing is held any more
                clock.millis += heartbeat
                // A budgeted drain that is cancelled while its re-check sweep reads the row.
                val budgeted: Job = launch { engine.drain() }
                testScheduler.runCurrent()
                assertEquals(round + 1, store.reachedBlock, "setup: round $round's drain is inside its re-check sweep")
                budgeted.cancel()
                testScheduler.runCurrent()
                // The next ordinary drain, at the same clock.
                store.blockReadsOf = null
                engine.drain()
                store.blockReadsOf = id
            }
            store.blockReadsOf = null

            assertNull(
                inner.find(id),
                "four heartbeats after the last completed re-check the parked held row is still " + inner.find(id)?.state,
            )
        } finally {
            parent.cancel()
        }
    }
}
