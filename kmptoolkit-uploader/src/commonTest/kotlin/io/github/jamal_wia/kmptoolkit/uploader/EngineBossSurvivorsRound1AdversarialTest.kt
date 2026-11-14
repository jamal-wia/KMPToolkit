package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A1 final boss round 1, hand-made mutants of the engine that the whole uploader suite let through, each pinned here by
 * the contract sentence it breaks.
 */
class EngineBossSurvivorsRound1AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
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
        override val type: String = "boss1.survivor"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    private fun inFlight(id: String): UploaderItem = UploaderItem(
        id = id, type = "boss1.survivor", payload = "p", schemaVersion = 1, uniqueKey = null, orderingKey = null,
        tag = null, state = UploaderItemState.IN_FLIGHT, attempts = 0, nextRunAtEpochMillis = 0L,
        createdAtEpochMillis = 0L, lastError = null, leaseUntilEpochMillis = 100_000_000L, claim = 1L,
    )

    /** The delete fails and so does the read that would tell whether it changed anything. */
    private class BlindStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var reads = 0
        override suspend fun deleteById(id: String) {
            inner.deleteById(id) // committed, then the driver reports a failure
            throw IllegalStateException("disk I/O error")
        }

        override suspend fun getById(id: String): UploaderItem? {
            reads++
            if (reads >= 2) throw IllegalStateException("database is locked")
            return inner.getById(id)
        }
    }

    /**
     * P1: "A read that itself fails counts as not provably there, the conservative answer": a cancel whose delete reported
     * a failure and whose check read failed too still cancels the upload of the row it may well have removed. (Mutant B4:
     * a failed read counted as "still queued".)
     */
    @Test
    fun `a cancel whose delete and check read both fail still cancels the upload`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore(listOf(inFlight("u1")))
            val transport = Transport()
            val engine: UploaderEngine = testEngine(BlindStore(inner), listOf(UploadingHandler(transport)), scopeOn(parent), TestClock(1_000L))
            assertFailsWith<IllegalStateException> { engine.cancel("u1") }
            assertEquals(listOf("cancel:u1"), transport.events, "the row is gone but its upload was left running")
        } finally {
            parent.cancel()
        }
    }

    /**
     * P4 and the post-launch check: a withdrawal that lands between the claim and the launch has its own cancel before the
     * launch; the hand-off's cancel after the launch must come even when the store cannot be read at that moment. (Mutant
     * B6: the attempt's own withdrawal flag not consulted, only the re-read.)
     */
    @Test
    fun `a withdrawal between claim and launch is followed by a cancel even when the re-read fails`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            val id: String = engine.enqueue(handler, "x")!!
            store.afterNextMarkInFlight = {
                engine.cancel(id)
                store.beforeNextGetById = { throw IllegalStateException("database is locked") }
            }
            runCatching { engine.drain() }
            assertEquals(listOf("cancel:$id", "launch:$id", "cancel:$id"), transport.events, "no cancel after the last launch")
        } finally {
            parent.cancel()
        }
    }

    /**
     * The attempt re-reads its row and runs only when the row is still exactly what the pass read: a detached executor that
     * parked its expired-lease item between the pass's read and the attempt must not see the effect run again, nor the
     * parked row deleted by that run's success. (Mutant B14: only the row's presence checked.)
     */
    @Test
    fun `a row parked by its executor after the pass read it is not run again`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore(
                listOf(inFlight("p1").copy(type = TestHandler.DEFAULT_TYPE, leaseUntilEpochMillis = 500L)),
            )
            val handler = TestHandler()
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            store.afterNextGetAllActive = { engine.settle("p1", 1L, SettleResult.Park("rejected by the server")) }
            engine.drain()
            assertEquals(emptyList(), handler.payloads, "the parked item's effect ran again")
            assertEquals(UploaderItemState.PARKED, store.find("p1")?.state, "the parked row was lost")
        } finally {
            parent.cancel()
        }
    }

    /** A handler whose preparation answers [answer] after running [during] (a close, a charge), without suspending. */
    private class PreparingHandler(transport: UploadTransport, private val answer: () -> UploadPreparation) :
        UploadHandler<String>(transport) {
        var during: (suspend () -> Unit)? = null
        override val type: String = "boss1.survivor"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            during?.let { hook ->
                during = null
                hook()
            }
            return answer()
        }
    }

    /**
     * UploadGateway.prepareAttempt: "a charge is MovedOn" (the job retries and prepares the new attempt). A worker whose
     * preparation saw its item charged and re-handed meanwhile must not upload the old attempt's request. (Mutant B19:
     * the attempts comparison after the preparation removed.)
     */
    @Test
    fun `a worker preparation that saw its item charged and re-handed does not upload the old attempt`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore(listOf(inFlight("w1").copy(leaseUntilEpochMillis = 900_000_000L)))
            val transport = Transport()
            val handler = PreparingHandler(transport) {
                UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", "b"))))
            }
            val clock = TestClock(1_000L)
            val engine = testEngine(store, listOf(handler), scopeOn(parent), clock) as DefaultUploaderEngine
            handler.during = {
                engine.settle("w1", 1L, SettleResult.Failed(IllegalStateException("503"))) // charged: attempts 1, pending
                clock.millis += 60_000L
                engine.drain() // re-handed: in flight again under attempt 1
            }
            val attempt: UploadAttempt = engine.prepareUploadAttempt("w1")
            assertEquals(1, store.find("w1")?.attempts, "setup: the item was charged")
            assertEquals(UploaderItemState.IN_FLIGHT, store.find("w1")?.state, "setup: and re-handed")
            assertEquals(UploadAttempt.EngineUnavailable, attempt, "the worker was handed the old attempt's request")
        } finally {
            parent.cancel()
        }
    }

    /**
     * P2: a Success is a fact and removes its row whatever happens to the attempt: a handler whose effect succeeded while
     * close() landed (and which ignored the cancellation) must not leave its row to be run again by the next engine.
     * (Mutant B26: a cancelled attempt's Success not recorded.)
     */
    @Test
    fun `a success answered after close still removes its row`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            lateinit var engine: UploaderEngine
            val handler = TestHandler(onExecute = { _, _ ->
                engine.close() // the effect already went out; the stop lands as execute returns
                AttemptResult.Success
            })
            engine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            val id: String = engine.enqueue(handler, "x")!!
            runCatching { engine.drain() }
            assertEquals(1, handler.payloads.size)
            assertEquals(null, store.find(id), "a delivered effect's row was left for the next engine to run again")
        } finally {
            parent.cancel()
        }
    }

    /** "A closed engine is permanently inert: a manual drain() on one runs nothing." (Mutant B28: that check removed.) */
    @Test
    fun `a manual drain of a closed engine runs nothing`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val handler = TestHandler()
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            val id: String = engine.enqueue(handler, "x")!!
            engine.close()
            engine.drain()
            assertEquals(emptyList(), handler.payloads, "a closed engine ran an effect")
            assertEquals(UploaderItemState.PENDING, store.find(id)?.state)
        } finally {
            parent.cancel()
        }
    }

    /**
     * P2 and P5: every UploadPreparation of a cancelled attempt records nothing. A handler that ignores the close landing
     * during its preparation and answers Drop must not delete the row of the closed engine. (A1 boss round 1, S4: close() must stop a drain() the owner called itself.)
     */
    @Test
    fun `a drop answered after close by a preparation that ignored it deletes nothing`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = Transport()
            val handler = PreparingHandler(transport) { UploadPreparation.Drop("not needed any more") }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            val id: String = engine.enqueue(handler, "x")!!
            handler.during = { engine.close() }
            runCatching { engine.drain() }
            assertEquals(UploaderItemState.PENDING, store.find(id)?.state, "the closed engine's attempt dropped the row")
            assertEquals(emptyList(), transport.events)
        } finally {
            parent.cancel()
        }
    }

    /**
     * P5: a throw from prepareUpload that arrives after the attempt's cancellation spends no attempt. (Mutant B25: the
     * drain-side catch charges it.)
     */
    @Test
    fun `a preparation that throws after close spends no attempt`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = Transport()
            val handler = PreparingHandler(transport) { throw IllegalStateException("socket closed by the stop") }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            val id: String = engine.enqueue(handler, "x")!!
            handler.during = { engine.close() }
            runCatching { engine.drain() }
            val row: UploaderItem? = store.find(id)
            assertEquals(UploaderItemState.PENDING, row?.state)
            assertEquals(0, row?.attempts, "the stop's own failure was charged to the item")
        } finally {
            parent.cancel()
        }
    }

    /**
     * Decision D-29 (S4): a close() during a drain() the caller made stops it like the engine's own pass, and drain()
     * returns normally instead of throwing the stop at its caller.
     */
    @Test
    fun `a drain whose engine is closed meanwhile returns normally and spends no attempt`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val handler = PreparingHandler(Transport()) { throw IllegalStateException("socket closed by the stop") }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            val id: String = engine.enqueue(handler, "x")!!
            handler.during = { engine.close() }
            engine.drain() // must not throw: the stop is the owner's own doing
            val row: UploaderItem? = store.find(id)
            assertEquals(UploaderItemState.PENDING, row?.state)
            assertEquals(0, row?.attempts, "the stop's own failure was charged to the item")
        } finally {
            parent.cancel()
        }
    }

    /** A success that a manual drain answered while close() landed still removes its row, and drain() returns. */
    @Test
    fun `a manual drain closed during a success still deletes its row and returns`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            lateinit var engine: UploaderEngine
            val handler = TestHandler(onExecute = { _, _ ->
                engine.close()
                AttemptResult.Success
            })
            engine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            val id: String = engine.enqueue(handler, "x")!!
            engine.drain()
            assertEquals(1, handler.payloads.size)
            assertEquals(null, store.find(id), "a delivered effect's row was left for the next engine to run again")
        } finally {
            parent.cancel()
        }
    }

    /** A caller cancelled during its own drain() still gets the cancellation: only a close() is swallowed. */
    @Test
    fun `a drain whose caller is cancelled still throws`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val handler = PreparingHandler(Transport()) { UploadPreparation.Drop("not needed any more") }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            val id: String = engine.enqueue(handler, "x")!!
            val inPreparation = CompletableDeferred<Unit>()
            handler.during = {
                inPreparation.complete(Unit)
                awaitCancellation()
            }
            var returned = false
            val caller: Deferred<Unit> = async {
                engine.drain()
                returned = true
            }
            runCurrent()
            assertTrue(inPreparation.isCompleted, "setup: the drain is inside the preparation")
            caller.cancel()
            runCurrent()
            assertTrue(caller.isCancelled, "the caller's cancellation did not end the call")
            assertFalse(returned, "drain() returned normally to a cancelled caller")
            assertEquals(UploaderItemState.PENDING, store.find(id)?.state, "the cancelled drain recorded something")
        } finally {
            parent.cancel()
        }
    }

    /** No close: the drain finishes by itself, and nothing it set up is left running (runTest would not end). */
    @Test
    fun `a drain without a close ends and leaves no watcher behind`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val handler = TestHandler()
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            engine.enqueue(handler, "x")!!
            engine.drain()
            engine.drain()
            runCurrent()
            assertEquals(1, handler.payloads.size)
            assertTrue(coroutineContext[Job]!!.children.none { it.isActive }, "a drain left a coroutine running in its caller's scope")
        } finally {
            parent.cancel()
        }
    }

    /** The owner's scope, cancelled during a manual drain(), stops it like a close() does. */
    @Test
    fun `a drain stops when the owner's scope is cancelled meanwhile`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val handler = PreparingHandler(Transport()) { UploadPreparation.Drop("not needed any more") }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            val id: String = engine.enqueue(handler, "x")!!
            handler.during = { parent.cancel() }
            engine.drain()
            assertEquals(UploaderItemState.PENDING, store.find(id)?.state, "an attempt of a cancelled engine dropped the row")
        } finally {
            parent.cancel()
        }
    }
}
