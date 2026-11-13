package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * A2 round 11, Q4: the resource budget of the engine with the stand-ins. After thousands of enqueue-complete cycles
 * and after hundreds of items queued at once nothing is retained: no row, no running-attempt entry, no entry of the
 * process-wide delivered-but-unconfirmed memory, no journal entry or per-item journal lock, no live child of the
 * engine's scope, and the wake alarm is not left armed. Counts are read from the internal accessors, never from the
 * clock; the engine runs on the test dispatcher, so the run is deterministic and takes a fraction of a second.
 */
class ResourceBudgetRound11AdversarialTest {

    private val engines: MutableList<UploaderEngine> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        engines.forEach { UploaderEngineRegistry.unregister(it); it.close() }
    }

    private fun CoroutineScope.liveChildren(): Int = coroutineContext[Job]!!.children.count { it.isActive }

    @Test
    fun `five thousand enqueue and complete cycles retain nothing`() = runTest {
        val store = TestUploaderStore()
        val wake = TestWakeScheduler()
        val handler = TestHandler(type = "r11.budget")
        val ids = SequentialIds("cyc")
        val engine: UploaderEngine = testEngine(
            store, listOf(handler), backgroundScope, clock = TestClock(1_000L), wakeScheduler = wake, idGenerator = ids::next,
        )
        engines += engine
        val unconfirmedBefore: Set<String> = DeliveredUnconfirmed.ids()
        val childrenBefore: Int = backgroundScope.liveChildren()
        repeat(5_000) { i ->
            engine.enqueue(handler, "p$i")
            engine.drain()
            if (i % 997 == 0) {
                assertTrue(store.items.isEmpty(), "cycle $i left rows: ${store.items.size}")
            }
        }
        assertEquals(5_000, handler.attempts.size, "one attempt per cycle")
        assertTrue(store.items.isEmpty(), "rows retained: ${store.items.size}")
        assertEquals(unconfirmedBefore, DeliveredUnconfirmed.ids(), "the unconfirmed-delivery memory grew")
        assertEquals(childrenBefore, backgroundScope.liveChildren(), "a job of the engine's scope is still alive")
        assertTrue(listOf("cyc-1", "cyc-2500", "cyc-5000").all { (engine as DefaultUploaderEngine).runningAttemptStamp(it) == null }, "a running-attempt entry is retained")
        assertTrue(!wake.armed, "the wake alarm is left armed over an empty queue")
    }

    @Test
    fun `two thousand upload cycles through the journal leave no entry no lock and no row`() = runTest {
        val store = TestUploaderStore()
        val storage = MemStorage()
        val journal = OutcomeJournal(storage, TestClock(5_000_000L))
        val transport = CountingTransport()
        val handler = UploadingHandler(transport)
        val engine: UploaderEngine = testEngine(
            store, listOf(handler), backgroundScope, clock = TestClock(1_000L), idGenerator = SequentialIds("up")::next,
        )
        engines += engine
        UploaderEngineRegistry.register(engine)
        val unconfirmedBefore: Set<String> = DeliveredUnconfirmed.ids()
        repeat(2_000) { i ->
            val id: String = checkNotNull(engine.enqueue(handler, "p$i"))
            engine.drain()
            val prepared: PreparedUpload = UploadGateway.prepareStampedAttempt(id, 1.seconds)
            val response = UploadResponse(if (i % 7 == 0) 500 else 201, emptyMap(), ByteArray(0), false)
            journal.record(id, response, prepared.bodyLimit, prepared.attemptStamp)
            journal.settleRecorded(id, response, prepared.attemptStamp, prepared.bodyLimit, gatewaySettle(1.seconds))
            if (i % 7 == 0) {
                // A 500 is retried: finish that item with a delivery so the cycle ends empty.
                store.items.forEach { engine.settle(it.id, it.claim, SettleResult.Delivered) }
            }
        }
        assertTrue(store.items.isEmpty(), "rows retained: ${store.items.map { it.id }.take(5)}")
        assertTrue(storage.files.isEmpty(), "journal entries retained: ${storage.files.keys.take(5)}")
        assertEquals(0, OutcomeReplayLocks.activeKeys, "per-item journal locks retained")
        assertEquals(2_000, transport.launches, "one launch per cycle")
        assertEquals(unconfirmedBefore, DeliveredUnconfirmed.ids(), "the unconfirmed-delivery memory grew")
    }

    @Test
    fun `five hundred items queued at once drain once each and leave nothing behind`() = runTest {
        val store = TestUploaderStore()
        val wake = TestWakeScheduler()
        val constraint = TestConstraint("net", satisfied = false)
        val handler = TestHandler(type = "r11.many", constraintKeys = setOf("net"))
        val engine: UploaderEngine = testEngine(
            store, listOf(handler), backgroundScope, clock = TestClock(1_000L), wakeScheduler = wake,
            constraintProviders = listOf(constraint), idGenerator = SequentialIds("many")::next,
        )
        engines += engine
        val childrenBefore: Int = backgroundScope.liveChildren()
        // Observers come and go; none may stay behind.
        repeat(100) {
            val watcher: Job = backgroundScope.launch { engine.observe("r11.many").collect { } }
            testScheduler.runCurrent()
            watcher.cancel()
        }
        repeat(500) { i -> engine.enqueue(handler, "p$i", tag = if (i % 2 == 0) "even" else "odd") }
        assertEquals(500, store.items.size)
        engine.drain()
        assertEquals(0, handler.attempts.size, "an unsatisfied constraint holds every item")
        constraint.set(true)
        engine.drain()
        assertEquals(500, handler.attempts.size, "each item ran")
        assertEquals(500, handler.attempts.map { it.id }.toSet().size, "each item ran exactly once")
        assertTrue(store.items.isEmpty(), "rows retained: ${store.items.size}")
        engine.cancelByTag("even")
        testScheduler.runCurrent()
        assertEquals(childrenBefore, backgroundScope.liveChildren(), "an observer or a job of the engine is still alive")
        assertTrue(!wake.armed, "the wake alarm is left armed over an empty queue")
    }

    private class CountingTransport : UploadTransport {
        var launches: Int = 0
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = error("re-hand-aware overload expected")
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            launches++
        }

        override fun cancel(itemId: String) {}
    }

    private class UploadingHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "r11.budget.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = emptyList()))
    }

    private class MemStorage : OutcomeStorage {
        val files: MutableMap<String, ByteArray> = LinkedHashMap()
        override fun write(itemId: String, bytes: ByteArray): Boolean {
            files[itemId] = bytes.copyOf()
            return true
        }

        override fun read(itemId: String): ByteArray? = files[itemId]?.copyOf()
        override fun delete(itemId: String) {
            files.remove(itemId)
        }

        override fun itemIds(): List<String> = files.keys.toList()
        override fun purgeLeftovers() {}
    }
}
