package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A1 final boss round 1: two documented promises read together, and the rulings that followed.
 *
 * The engine KDoc and guide 03 promise that "within one process an effect whose handler succeeded but whose row could
 * not be deleted is never run again". The close KDoc and P6 describe a replacement engine started in the same process
 * after close() (P3 even keeps its claim book, [ClaimsInDoubt], per process for exactly that case). These tests run the
 * delivered-but-unconfirmed effect through such a replacement (decision D-29: the memory is per process).
 */
class EngineBossRound1AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    /** Fails the next [failures] confirming deletes as a locked database would: nothing written. */
    private class FlakyDeleteStore(private val inner: TestUploaderStore, var failures: Int) : UploaderStore by inner {
        override suspend fun deleteById(id: String) {
            if (failures > 0) {
                failures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    @Test
    fun `control - the engine that saw the delivery retries only the delete`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 1)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock)
            val id: String = engine.enqueue(handler, "send-message")!!
            engine.drain()
            assertEquals(1, handler.payloads.size)
            clock.millis += 60_000L
            engine.drain()
            assertEquals(1, handler.payloads.size, "the same engine ran the delivered effect again")
            assertNull(inner.find(id), "the confirming delete was never retried")
        } finally {
            parent.cancel()
        }
    }

    private fun TestScope.engineOn(
        store: UploaderStore,
        handler: TestHandler,
        parent: Job,
        clock: TestClock,
        ids: SequentialIds? = null,
    ): UploaderEngine =
        if (ids == null) testEngine(store, listOf(handler), scopeOn(parent), clock)
        else testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = ids::next)

    @Test
    fun `a replacement engine in the same process does not run an effect whose delete failed again`() = runTest {
        val parent1 = Job()
        val parent2 = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 1)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent1), clock)
            val id: String = first.enqueue(handler, "send-message")!!
            first.drain()
            assertEquals(1, handler.payloads.size, "setup: the effect ran once and its delete failed")
            first.close() // the owner rebuilds its engine, say after a logout; the process lives on

            clock.millis += 60_000L
            val second: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent2), clock)
            second.drain()
            assertEquals(
                1,
                handler.payloads.size,
                "within one process the effect whose confirming delete failed ran again (in the replacement engine)",
            )
            assertNull(inner.find(id), "the replacement never confirmed the delivery")
        } finally {
            parent1.cancel()
            parent2.cancel()
        }
    }

    @Test
    fun `a delete that fails in two engines in a row is confirmed by the third without a second run`() = runTest {
        val parents: List<Job> = List(3) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 2)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val ids = SequentialIds("s1a")
            val first: UploaderEngine = engineOn(store, handler, parents[0], clock, ids)
            val id: String = first.enqueue(handler, "send-message")!!
            first.drain()
            first.close()
            val second: UploaderEngine = engineOn(store, handler, parents[1], clock, ids)
            second.drain()
            assertEquals(1, handler.payloads.size, "the second engine ran the delivered effect again")
            assertEquals(UploaderItemState.PENDING, inner.find(id)?.state, "setup: the second delete failed too")
            second.close()
            val third: UploaderEngine = engineOn(store, handler, parents[2], clock, ids)
            third.drain()
            assertEquals(1, handler.payloads.size, "the third engine ran the delivered effect again")
            assertNull(inner.find(id), "the third engine did not confirm the delivery")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    @Test
    fun `an item of another store with the same id runs and does not erase the entry of the first`() = runTest {
        val parents: List<Job> = List(3) { Job() }
        try {
            val innerOne = TestUploaderStore()
            val storeOne = FlakyDeleteStore(innerOne, failures = 1)
            val innerTwo = TestUploaderStore()
            val handlerOne = TestHandler()
            val handlerTwo = TestHandler()
            val clock = TestClock(1_000_000L)
            val sameId: () -> String = { "s1b-shared-id" }
            val one: UploaderEngine = testEngine(storeOne, listOf(handlerOne), scopeOn(parents[0]), clock, idGenerator = sameId)
            one.enqueue(handlerOne, "first-store")!!
            one.drain()
            assertEquals(1, handlerOne.payloads.size, "setup: the effect ran and its delete failed")
            one.close()

            clock.millis += 5_000L
            val two: UploaderEngine = testEngine(innerTwo, listOf(handlerTwo), scopeOn(parents[1]), clock, idGenerator = sameId)
            two.enqueue(handlerTwo, "second-store")!!
            two.drain()
            assertEquals(listOf("second-store"), handlerTwo.payloads, "an item that only shares the id must run once")
            assertNull(innerTwo.find("s1b-shared-id"), "its delivery was not confirmed")

            clock.millis += 60_000L
            val replacement: UploaderEngine = testEngine(storeOne, listOf(handlerOne), scopeOn(parents[2]), clock)
            replacement.drain()
            assertEquals(1, handlerOne.payloads.size, "the other store's delivery erased the first store's entry")
            assertNull(innerOne.find("s1b-shared-id"), "the replacement did not confirm the first store's delivery")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    @Test
    fun `an item withdrawn by a replacement engine is withdrawn without a run and a new item under a reused id runs`() = runTest {
        val parents: List<Job> = List(2) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 1)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val sameId: () -> String = { "s1c-reused-id" }
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[0]), clock, idGenerator = sameId)
            first.enqueue(handler, "payload")!!
            first.drain()
            first.close()
            assertEquals(1, handler.payloads.size, "setup: the effect ran and its delete failed")

            val second: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[1]), clock, idGenerator = sameId)
            second.cancel("s1c-reused-id")
            assertNull(inner.find("s1c-reused-id"), "the replacement's cancel left the row")
            assertEquals(1, handler.payloads.size, "withdrawing ran the effect")
            // A withdrawal keeps the entry because it may be rolled back; a reused id with a later creation time is
            // another item.
            clock.millis += 1
            second.enqueue(handler, "payload")!!
            second.drain()
            assertEquals(2, handler.payloads.size, "a new item under a withdrawn id was taken for the delivered one")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    @Test
    fun `an item withdrawn by tag by a replacement engine is withdrawn without a run and a new item under a reused id runs`() = runTest {
        val parents: List<Job> = List(2) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 1)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val sameId: () -> String = { "s1d-reused-id" }
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[0]), clock, idGenerator = sameId)
            first.enqueue(handler, "payload", tag = "account")!!
            first.drain()
            first.close()
            assertEquals(1, handler.payloads.size, "setup: the effect ran and its delete failed")

            val second: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[1]), clock, idGenerator = sameId)
            second.cancelByTag("account")
            assertNull(inner.find("s1d-reused-id"), "the wipe left the row")
            // A withdrawal keeps the entry because it may be rolled back; a reused id with a later creation time is
            // another item.
            clock.millis += 1
            second.enqueue(handler, "payload")!!
            second.drain()
            assertEquals(2, handler.payloads.size, "a new item under a wiped id was taken for the delivered one")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    private class Transport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 15 * 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events += "launch:$itemId"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }
    }

    private class UploadingHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "boss1.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    /**
     * A store that hops to its own thread, as the SQLDelight one does: the first claim commits, and its caller, closed
     * meanwhile, is told about the cancellation on the way back (the SPI's "after its commit" case).
     */
    private class CommitThenNoticeStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        val entered: CompletableDeferred<Unit> = CompletableDeferred()
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        private var first = true
        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            val written: Boolean = inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
            if (first) {
                first = false
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                currentCoroutineContext().ensureActive()
            }
            return written
        }
    }

    /**
     * 04-api-reference, close: "a claim that was being written, or that finished after close, is released if it is still
     * this engine's, so the next engine starts the item at once". The replacement's first pass sees the claim the closed
     * engine is still unwinding (in flight, live lease) and arms its alarm for the lease's end; the release then puts the
     * row back to pending, and nothing tells the replacement.
     *
     * The setup used to assert that the row was PENDING right after the release. That assumed a delayed wake, the
     * opposite of "at once": the woken replacement starts the item in the same step. The launch itself is the proof now
     * (decision D-31); with a live lease, a 30 s heartbeat and virtual time standing still only the release plus the
     * wake can produce it.
     */
    @Test
    fun `a claim released after close is started by the replacement engine at once`() = runTest {
        val parent1 = Job()
        val parent2 = Job()
        try {
            val inner = TestUploaderStore()
            val store = CommitThenNoticeStore(inner)
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val clock = TestClock(1_000_000L)
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent1), clock)
            val id: String = first.enqueue(handler, "avatar")!!
            first.start()
            runCurrent()
            assertTrue(store.entered.isCompleted, "setup: the first claim is written and its caller has not heard back")
            first.close()

            val second: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parent2), clock)
            second.start()
            runCurrent()
            assertEquals(emptyList(), transport.events, "setup: the replacement sees a live claim and waits")

            store.gate.complete(Unit) // the closed engine hears of its cancellation and releases its claim
            runCurrent()
            assertEquals(listOf("launch:$id"), transport.events, "the release wakes the replacement, which starts the item at once")

            repeat(10) {
                advanceTimeBy(1_000L)
                clock.millis += 1_000L
                runCurrent()
            }
            assertEquals(
                listOf("launch:$id"),
                transport.events,
                "the item was not launched exactly once (a second launch from the old claim, or a spurious pass)",
            )
        } finally {
            parent1.cancel()
            parent2.cancel()
        }
    }

    @Test
    fun `an engine started after a release launches the released item on its start pass`() = runTest {
        val parent1 = Job()
        val parent2 = Job()
        try {
            val inner = TestUploaderStore()
            val store = CommitThenNoticeStore(inner)
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val clock = TestClock(1_000_000L)
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent1), clock)
            val id: String = first.enqueue(handler, "avatar")!!
            first.start()
            runCurrent()
            assertTrue(store.entered.isCompleted, "setup: the first claim is written and its caller has not heard back")
            first.close()
            store.gate.complete(Unit)
            runCurrent()
            assertEquals(UploaderItemState.PENDING, inner.find(id)?.state, "setup: the claim was released before the replacement exists")

            val second: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parent2), clock)
            second.start()
            runCurrent()
            assertEquals(listOf("launch:$id"), transport.events, "the replacement started after the release did not start the item")
        } finally {
            parent1.cancel()
            parent2.cancel()
        }
    }

    /**
     * Two replacements over two stores: the release of one store's claim wakes both engines (a wake only causes a pass),
     * and only the owner of the released row launches anything.
     */
    @Test
    fun `a release wakes the engines of other stores but only the owner launches`() = runTest {
        val parents: List<Job> = List(3) { Job() }
        try {
            val innerOne = TestUploaderStore()
            val storeOne = CommitThenNoticeStore(innerOne)
            val innerTwo = TestUploaderStore()
            val transportOne = Transport()
            val transportTwo = Transport()
            val handlerOne = UploadingHandler(transportOne)
            val handlerTwo = UploadingHandler(transportTwo)
            val clock = TestClock(1_000_000L)
            val first: UploaderEngine = testEngine(storeOne, listOf(handlerOne), scopeOn(parents[0]), clock, idGenerator = SequentialIds("one")::next)
            val idOne: String = first.enqueue(handlerOne, "avatar")!!
            first.start()
            runCurrent()
            assertTrue(storeOne.entered.isCompleted, "setup: the first claim is written and its caller has not heard back")
            first.close()

            val replacementOne: UploaderEngine = testEngine(innerOne, listOf(handlerOne), scopeOn(parents[1]), clock)
            replacementOne.start()
            val engineTwo: UploaderEngine = testEngine(innerTwo, listOf(handlerTwo), scopeOn(parents[2]), clock, idGenerator = SequentialIds("two")::next)
            engineTwo.start()
            runCurrent()
            assertEquals(emptyList(), transportOne.events, "setup: the replacement sees a live claim and waits")

            storeOne.gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("launch:$idOne"), transportOne.events, "the owner's replacement did not start the released item")
            assertEquals(emptyList(), transportTwo.events, "an engine over another store launched something")
            assertEquals(UploaderItemState.IN_FLIGHT, innerOne.find(idOne)?.state)
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    @Test
    fun `engines started and closed or cancelled leave no release wake registered`() = runTest {
        val before: Int = ClaimsInDoubt.wakeCount
        val store = TestUploaderStore()
        val handler = TestHandler()
        repeat(50) {
            val parent = Job()
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            engine.start()
            runCurrent()
            assertEquals(before + 1, ClaimsInDoubt.wakeCount, "a started engine is registered once")
            engine.close()
            runCurrent()
            parent.cancel()
            runCurrent()
            assertEquals(before, ClaimsInDoubt.wakeCount, "a closed engine stayed registered")
        }
        repeat(50) {
            val parent = Job()
            testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L)).start()
            runCurrent()
            parent.cancel() // the owner's scope ends without close()
            runCurrent()
            assertEquals(before, ClaimsInDoubt.wakeCount, "an engine whose scope was cancelled stayed registered")
        }
    }

    @Test
    fun `a release announced while no engine is registered changes nothing`() {
        val before: Int = ClaimsInDoubt.wakeCount
        ClaimsInDoubt.announceRelease()
        assertEquals(before, ClaimsInDoubt.wakeCount)
    }
}
