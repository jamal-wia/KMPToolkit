package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Refactor-regression round, second set of tests written for mutants that survived the existing suites. Each one is
 * derived from the stated contract: a delivery whose confirming delete failed backs off before its delete is retried
 * (03 guide), a cancelled attempt's answers record nothing (P2), a settle under the claim the drain is about to write
 * stands (`UploaderEngine.settle`), and a worker-side preparation is owed only for an item that is in flight (04,
 * `UploadAttempt.NothingOwed`).
 *
 * Deterministic by construction: virtual time, a hand-driven clock, counters instead of randomness, no sleep. Every id
 * carries the prefix `k2` because the registry of delivered-but-unconfirmed rows is process-wide.
 */
class RegressSurvivorKills2AdversarialTest {

    private val remembered: MutableList<UploaderItem> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        remembered.forEach { DeliveredUnconfirmed.forget(it) }
        remembered.clear()
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Counts every `deleteById` call (a failing one included) and fails the first [failDeletes] of them. */
    private class DeleteCountingStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var failDeletes: Int = 0
        var deleteCalls: Int = 0

        override suspend fun deleteById(id: String) {
            deleteCalls++
            if (failDeletes > 0) {
                failDeletes--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    // ---- A5: a failed confirming delete is backed off, not retried at once ---------------------------------------

    @Test
    fun `a delivery whose delete fails backs off before its delete is retried`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = DeleteCountingStore(inner)
            val clock = TestClock(5_000_000L)
            val handler = TestHandler()
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("k2a5")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
            store.failDeletes = 1

            engine.drain()
            remembered += assertNotNull(inner.find(id), "setup: the delivered row could not be deleted and is held")
            assertEquals(1, store.deleteCalls, "setup: one delete after the delivery")

            engine.drain()
            assertEquals(1, store.deleteCalls, "a second drain at the same clock does not retry the delete before its backoff")

            clock.millis += UploaderConfig().minAlarmDelay.inWholeMilliseconds
            engine.drain()

            assertEquals(2, store.deleteCalls, "the delete is retried once its backoff has passed")
            assertNull(inner.find(id), "the row is gone")
            assertEquals(1, handler.attempts.size, "the effect ran exactly once")
        } finally {
            parent.cancel()
        }
    }

    // ---- A15: a decoder that throws as its attempt is really cancelled --------------------------------------------

    @Test
    fun `a decoder that throws as its attempt is really cancelled parks nothing`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            var drainJob: Job? = null
            var decoderRan = false
            val handler = TestHandler(decode = {
                decoderRan = true
                drainJob!!.cancel()
                throw CancellationException("the attempt was cancelled while decoding")
            })
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("k2a15")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "payload"))

            drainJob = launch { engine.drain() }
            runCurrent()
            drainJob.join()

            assertTrue(decoderRan, "setup: the decoder ran")
            val row: UploaderItem = assertNotNull(store.find(id), "the row is still there")
            assertEquals(UploaderItemState.PENDING, row.state, "a cancelled attempt parks nothing")
            assertEquals(0, row.attempts, "and spends no attempt")
            assertNull(row.lastError, "and records no error")
            assertEquals(0, handler.attempts.size, "execute never ran")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a decoder that throws a cancellation nobody asked for still parks the payload as undecodable`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val handler = TestHandler(decode = { throw CancellationException("an escaped timeout inside the decoder") })
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("k2a15c")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "payload"))

            engine.drain()

            val row: UploaderItem = assertNotNull(store.find(id))
            assertEquals(UploaderItemState.PARKED, row.state, "a decode failure parks, however it is thrown")
            assertTrue("undecodable payload" in row.lastError.orEmpty(), "the reason says so: ${row.lastError}")
            assertEquals(0, handler.attempts.size, "execute never ran")
        } finally {
            parent.cancel()
        }
    }

    // ---- S3: a settle under the claim the drain is about to write -------------------------------------------------

    @Test
    fun `a fast executor's park under the claim a re-hand is about to write stands`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val clock = TestClock(5_000_000L)
            val store = TestUploaderStore()
            lateinit var engine: UploaderEngine
            val handler = TestHandler(onExecute = { context, _ ->
                if (context.wasDetached) engine.settle(context.id, context.claim, SettleResult.Park("fast"))
                AttemptResult.Detached(1_000L)
            })
            engine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("k2s3p")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "p"))
            engine.drain()
            assertEquals(UploaderItemState.IN_FLIGHT, store.find(id)?.state, "setup: the first attempt detached")
            clock.millis += 2_000L

            engine.drain()

            val row: UploaderItem = assertNotNull(store.find(id))
            assertEquals(UploaderItemState.PARKED, row.state, "the park of the re-handed attempt stands, the hand-off does not claim over it")
            assertTrue("fast" in row.lastError.orEmpty(), "the park reason is kept: ${row.lastError}")
            assertEquals(2, handler.attempts.size, "setup: the item was re-handed once")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a fast executor's failure under the claim a re-hand is about to write is charged and not re-claimed`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val clock = TestClock(5_000_000L)
            val store = TestUploaderStore()
            lateinit var engine: UploaderEngine
            val handler = TestHandler(onExecute = { context, _ ->
                if (context.wasDetached) engine.settle(context.id, context.claim, SettleResult.Failed(IllegalStateException("fast failure")))
                AttemptResult.Detached(1_000L)
            })
            engine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("k2s3f")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "p"))
            engine.drain()
            assertEquals(UploaderItemState.IN_FLIGHT, store.find(id)?.state, "setup: the first attempt detached")
            clock.millis += 2_000L

            engine.drain()

            val row: UploaderItem = assertNotNull(store.find(id))
            assertEquals(UploaderItemState.PENDING, row.state, "the failure is applied, the row waits for its retry instead of being claimed again")
            assertEquals(1, row.attempts, "the failed attempt is charged")
            assertTrue("fast failure" in row.lastError.orEmpty(), "the failure is recorded: ${row.lastError}")
            assertEquals(2, handler.attempts.size, "setup: the item was re-handed once")
        } finally {
            parent.cancel()
        }
    }

    // ---- S6: a worker preparation is owed only for an item that is in flight ---------------------------------------

    private class QuietTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    private class AnsweringUploadHandler : UploadHandler<String>(QuietTransport()) {
        override val type: String = "regress.k2.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        var prepares: Int = 0
        var answer: UploadPreparation = UploadPreparation.Drop("nothing owed")
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            prepares++
            return answer
        }
    }

    @Test
    fun `a worker preparation for an item that is not in flight runs no prepareUpload and changes nothing`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val handler = AnsweringUploadHandler()
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("k2s6")::next)
            // The first item is parked by its own first preparation, through a drain; the second is never drained.
            val parkedId: String = assertNotNull(engine.enqueue(handler, "parked"))
            handler.answer = UploadPreparation.Park("first answer")
            engine.drain()
            assertEquals(UploaderItemState.PARKED, store.find(parkedId)?.state, "setup: the drain parked the first item")
            assertEquals(1, handler.prepares, "setup: one preparation so far")
            val pendingId: String = assertNotNull(engine.enqueue(handler, "pending"))
            handler.answer = UploadPreparation.Drop("nothing owed")
            val defaultEngine: DefaultUploaderEngine = engine as DefaultUploaderEngine

            val pendingAttempt: UploadAttempt = defaultEngine.prepareUploadAttempt(pendingId)
            val parkedAttempt: UploadAttempt = defaultEngine.prepareUploadAttempt(parkedId)

            assertSame(UploadAttempt.NothingOwed, pendingAttempt, "a pending item is owed no upload yet")
            assertSame(UploadAttempt.NothingOwed, parkedAttempt, "a parked item owes no upload")
            assertEquals(1, handler.prepares, "prepareUpload ran for neither of them")
            assertEquals(UploaderItemState.PENDING, store.find(pendingId)?.state, "the pending row is untouched")
            assertEquals(UploaderItemState.PARKED, store.find(parkedId)?.state, "the parked row is untouched")
        } finally {
            parent.cancel()
        }
    }
}
