package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A1 final boss round 3: the delivered-but-unconfirmed memory against ordering channels, several engines over several
 * stores, both conflict policies across a restart and a crash, the transaction boundary, handlers that withdraw or
 * replace their own item, a clock near the top of its range, and the stop job of a manual drain. Every test runs on
 * virtual time and ends by itself.
 */
class EngineBossRound3AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Fails the next [deleteFailures] deletes: a locked database. */
    private class FlakyStore(val inner: TestUploaderStore) : UploaderStore by inner {
        var deleteFailures: Int = 0
        override suspend fun deleteById(id: String) {
            if (deleteFailures > 0) {
                deleteFailures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    private class Rollback : RuntimeException("the consumer's transaction rolled back")

    /** A consumer transaction over the in-memory store: restores every row when its block throws. */
    private class RollingBackRunner(private val inner: TestUploaderStore) : TransactionRunner {
        override suspend fun <R> inTransaction(block: suspend () -> R): R {
            val before: List<UploaderItem> = inner.items
            try {
                return block()
            } catch (e: Throwable) {
                inner.items.forEach { inner.deleteById(it.id) }
                before.forEach { inner.insertKeep(it) }
                throw e
            }
        }
    }

    private class Transport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events += "launch:$itemId"
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += if (isRehandOff) "relaunch:$itemId" else "launch:$itemId"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }
    }

    private class UploadingHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "boss3.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    @Test
    fun `a confirmed delivery at the head of a channel lets its tail run in the same drain`() = runTest {
        val parent = Job()
        try {
            val store = FlakyStore(TestUploaderStore())
            val handler = TestHandler(ordering = { "chat-7" })
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3p1")::next)
            val head: String = assertNotNull(engine.enqueue(handler, "first"))
            val tail: String = assertNotNull(engine.enqueue(handler, "second"))
            store.deleteFailures = 1
            engine.drain()
            assertEquals(listOf("first"), handler.payloads, "setup: the head ran, its delete failed, the tail waits behind it")
            assertNotNull(store.inner.find(head))

            clock.millis += 60_000L
            engine.drain()

            assertEquals(listOf("first", "second"), handler.payloads, "the head is confirmed without a run and the tail runs in the same drain")
            assertNull(store.inner.find(head))
            assertNull(store.inner.find(tail))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `three engines over three stores keep each other's delivered memory through closes and replacements`() = runTest {
        val parent = Job()
        try {
            val clock = TestClock(2_000_000L) // one frozen clock: every row has the same type, payload and creation time
            val stores: List<FlakyStore> = List(3) { FlakyStore(TestUploaderStore()) }
            val handlers: List<TestHandler> = List(3) { TestHandler() }
            val ids: List<SequentialIds> = List(3) { SequentialIds("bn3p2-$it") }
            val first: List<UploaderEngine> = List(3) { testEngine(stores[it], listOf(handlers[it]), scopeOn(parent), clock, idGenerator = ids[it]::next) }
            val rows: List<String> = List(3) { assertNotNull(first[it].enqueue(handlers[it], "sync-profile")) }
            stores.forEach { it.deleteFailures = 1 }
            first.forEach { it.drain() }
            assertTrue(handlers.all { it.payloads.size == 1 }, "setup: every effect ran once")
            assertTrue(stores.indices.all { stores[it].inner.find(rows[it]) != null }, "setup: every delete failed")

            // The middle engine is replaced first and confirms its own row; the other two must keep their memory.
            first[1].close()
            val second1: UploaderEngine = testEngine(stores[1], listOf(handlers[1]), scopeOn(parent), clock, idGenerator = ids[1]::next)
            second1.drain()
            assertNull(stores[1].inner.find(rows[1]))
            first[0].close()
            first[2].close()
            val second0: UploaderEngine = testEngine(stores[0], listOf(handlers[0]), scopeOn(parent), clock, idGenerator = ids[0]::next)
            val second2: UploaderEngine = testEngine(stores[2], listOf(handlers[2]), scopeOn(parent), clock, idGenerator = ids[2]::next)
            clock.millis += 60_000L
            second2.drain()
            second0.drain()

            assertEquals(listOf(1, 1, 1), handlers.map { it.payloads.size }, "no engine ran a delivered effect again")
            assertTrue(stores.indices.all { stores[it].inner.find(rows[it]) == null }, "every delivery was confirmed by a delete")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `KEEP and REPLACE against a delivered row through a replacement engine run nothing twice`() = runTest {
        val parent = Job()
        try {
            for (policy in ConflictPolicy.entries) {
                val store = FlakyStore(TestUploaderStore())
                val handler = TestHandler()
                val clock = TestClock(3_000_000L)
                val ids = SequentialIds("bn3p3-${policy.name}")
                val old: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = ids::next)
                val delivered: String = assertNotNull(old.enqueue(handler, "v1", uniqueKey = "draft"))
                store.deleteFailures = 1
                old.drain()
                old.close()
                val fresh: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = ids::next)

                val second: String? = fresh.enqueue(handler, "v2", uniqueKey = "draft", conflictPolicy = policy)
                clock.millis += 60_000L
                fresh.drain()

                when (policy) {
                    ConflictPolicy.KEEP -> {
                        assertNull(second, "KEEP kept the queued (delivered) item")
                        assertEquals(listOf("v1"), handler.payloads, "KEEP: nothing ran twice and nothing new ran")
                    }
                    ConflictPolicy.REPLACE -> {
                        assertNotNull(second)
                        assertEquals(listOf("v1", "v2"), handler.payloads, "REPLACE: the new effect ran once, the delivered one never again")
                    }
                }
                assertNull(store.inner.find(delivered))
                assertTrue(store.inner.items.isEmpty(), "$policy: the queue is empty")
            }
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a REPLACE by the engine built after a crash cancels the upload the dead engine handed off`() = runTest {
        val parent = Job()
        val crashed = Job(parent)
        try {
            val store = TestUploaderStore()
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val clock = TestClock(4_000_000L)
            val ids = SequentialIds("bn3p4")
            val dead: UploaderEngine = testEngine(store, listOf(handler), scopeOn(crashed), clock, idGenerator = ids::next)
            val old: String = assertNotNull(dead.enqueue(handler, "v1", uniqueKey = "avatar"))
            dead.drain()
            val claim: Long = assertNotNull(store.find(old)).claim
            crashed.cancel() // the engine's scope dies without close(): as inert as a closed engine

            val next: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = ids::next)
            val fresh: String = assertNotNull(next.enqueue(handler, "v2", uniqueKey = "avatar", conflictPolicy = ConflictPolicy.REPLACE))
            next.drain()
            next.settle(old, claim, SettleResult.Delivered) // the dead engine's transfer reports late

            assertEquals(listOf("launch:$old", "cancel:$old", "launch:$fresh"), transport.events)
            assertEquals(UploaderItemState.IN_FLIGHT, store.find(fresh)?.state, "the late report of the superseded item touched nothing")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a KEEP that revives a parked key inside a rolled back transaction restores the parked row and runs nothing`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = Transport()
            val parking = TestHandler(onExecute = { _, _ -> AttemptResult.Park("rejected") })
            val clock = TestClock(5_000_000L)
            val engine: UploaderEngine = testEngine(
                store, listOf(parking), scopeOn(parent), clock,
                transactionRunner = RollingBackRunner(store), idGenerator = SequentialIds("bn3p5")::next,
            )
            val parkedId: String = assertNotNull(engine.enqueue(parking, "v1", uniqueKey = "profile"))
            engine.drain()
            val parked: UploaderItem = assertNotNull(store.find(parkedId))
            assertEquals(UploaderItemState.PARKED, parked.state)

            assertFailsWith<Rollback> {
                RollingBackRunner(store).inTransaction {
                    assertNotNull(engine.enqueue(parking, "v2", uniqueKey = "profile"), "the KEEP revives the parked key inside the transaction")
                    throw Rollback()
                }
            }
            assertEquals(listOf(parked), store.items, "the rollback restored exactly the parked row")
            clock.millis += 60_000L
            engine.drain()
            assertEquals(listOf("v1"), parking.payloads, "a parked row restored by a rollback is not run")
            assertEquals(emptyList(), transport.events)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a handler that replaces its own item while it runs has the replacement delivered once in the same drain`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val clock = TestClock(6_000_000L)
            lateinit var engine: UploaderEngine
            lateinit var handler: TestHandler
            handler = TestHandler(onExecute = { _, payload ->
                if (payload == "v1") engine.enqueue(handler, "v2", uniqueKey = "presence", conflictPolicy = ConflictPolicy.REPLACE)
                AttemptResult.Success
            })
            engine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3p6")::next)
            assertNotNull(engine.enqueue(handler, "v1", uniqueKey = "presence"))

            engine.drain()

            assertEquals(listOf("v1", "v2"), handler.payloads, "the replacement written by the running attempt runs once")
            assertTrue(store.items.isEmpty(), "the stopped attempt's Success did not delete the replacement")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a handler that withdraws its own item and answers Detached claims nothing`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val clock = TestClock(7_000_000L)
            lateinit var engine: UploaderEngine
            val handler = TestHandler(onExecute = { context, _ ->
                engine.cancel(context.id)
                AttemptResult.Detached(60_000L)
            })
            engine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3p7")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "v1"))

            engine.drain()
            clock.millis += 120_000L
            engine.drain()

            assertNull(store.find(id), "the withdrawn item stays gone")
            assertEquals(1, handler.payloads.size)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a delivered row is confirmed without a run when the clock then jumps near the top of its range or back to zero`() = runTest {
        val parent = Job()
        try {
            for (jumpTo in listOf(Long.MAX_VALUE - 1, 0L, Long.MIN_VALUE + 1)) {
                val store = FlakyStore(TestUploaderStore())
                val handler = TestHandler()
                val clock = TestClock(Long.MAX_VALUE - 10_000L)
                val ids = SequentialIds("bn3p8-$jumpTo")
                val old: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = ids::next)
                val id: String = assertNotNull(old.enqueue(handler, "near-max"))
                store.deleteFailures = 1
                old.drain()
                old.close()
                clock.millis = jumpTo

                val fresh: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = ids::next)
                fresh.drain()

                assertEquals(1, handler.payloads.size, "clock at $jumpTo: the delivered effect ran again")
                assertNull(store.inner.find(id), "clock at $jumpTo: the delivery was confirmed")
            }
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `many manual drains leave no child behind and close ends the engine's job`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val handler = failingHandler() // every drain leaves a backoff alarm armed
            val clock = TestClock(8_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3p9")::next)
            engine.enqueue(handler, "x")
            repeat(50) {
                engine.drain()
                clock.millis += 2_000L
            }
            runCurrent()
            val engineJob: Job = parent.children.single()
            assertEquals(1, engineJob.children.count { it.isActive }, "only the alarm is left: every stop job completed")

            engine.close()
            runCurrent()

            assertTrue(engineJob.isCompleted, "close ended the engine's job (registry unregister and wake dispose depend on it)")
            assertTrue(handler.payloads.size in 1..50)
        } finally {
            parent.cancel()
        }
    }
    /** Holds the first claim write, committed, until [gate] opens: a store that finishes a write it started. */
    private class GatedClaimStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        val entered: CompletableDeferred<Unit> = CompletableDeferred()
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        var failNextClaim: Boolean = false
        private var first = true
        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            if (failNextClaim) {
                failNextClaim = false
                throw IllegalStateException("database is locked")
            }
            val written: Boolean = inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
            if (first) {
                first = false
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
            }
            return written
        }
    }

    @Test
    fun `a claim that finishes after the engine's scope was cancelled is released and never launched`() = runTest {
        val parent = Job()
        val owner = Job(parent)
        try {
            val inner = TestUploaderStore()
            val store = GatedClaimStore(inner)
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val clock = TestClock(9_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(owner), clock, idGenerator = SequentialIds("bn3k1")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "v1"))
            val drained = launch { engine.drain() }
            runCurrent()
            assertTrue(store.entered.isCompleted, "setup: the claim write is running")

            owner.cancel() // the scope the engine was created in ends: as surely as close()
            store.gate.complete(Unit)
            runCurrent()

            assertTrue(drained.isCompleted, "the drain ended with its engine")
            assertEquals(emptyList(), transport.events, "a hand-off of an engine whose scope ended launched")
            assertEquals(UploaderItemState.PENDING, inner.find(id)?.state, "the claim was released, so the next engine starts the item at once")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a claim write that fails leaves no lease reserved for the item`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = GatedClaimStore(inner)
            store.gate.complete(Unit)
            store.failNextClaim = true
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val clock = TestClock(10_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3k2")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "v1"))

            engine.drain()

            assertEquals(emptyList(), transport.events, "setup: the failed claim launched nothing")
            val lease: Long = clock.millis + transport.leaseMillis
            val granted: Long = ClaimsInDoubt.reserve(id, lease)
            ClaimsInDoubt.drop(id, granted)
            assertEquals(lease, granted, "the failed claim kept its lease reserved (ClaimsInDoubt entries live for one claim write)")
        } finally {
            parent.cancel()
        }
    }
}
