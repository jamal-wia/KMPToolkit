package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * A2 round 11, Q5: normative sentences of the docs that changed after round 10 and that no existing test pinned with an
 * assertion of their own wording (found by mapping every changed docs line to its tests): `settle` called again after a
 * failed delete is harmless, a later Park does not override a held delivery and `settle` returns normally, a delivery
 * whose commit failed and that a drain confirmed later runs no `onSettled`, a `close()` during a manual `drain()` makes
 * that call return (and a cancelled caller still gets its cancellation), and an in-flight item of an unregistered type
 * is parked only after its lease ended, with the queue going on behind it.
 */
class DocsNormativeRound11AdversarialTest {

    private val engines: MutableList<UploaderEngine> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        engines.forEach { UploaderEngineRegistry.unregister(it); it.close() }
    }

    private class FlakyDeleteStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var failures: Int = 0
        override suspend fun deleteById(id: String) {
            if (failures > 0) {
                failures--
                error("the store is down")
            }
            inner.deleteById(id)
        }
    }

    private class DetachingHandler(type: String) : UploaderHandler<String> {
        override val type: String = type
        override val schemaVersion: Int = 1
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        val contexts: MutableList<AttemptContext> = mutableListOf()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            contexts += context
            return AttemptResult.Detached(60_000L)
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.detached(label: String): Triple<UploaderEngine, DetachingHandler, FlakyDeleteStoreRig> {
        val inner = TestUploaderStore()
        val store = FlakyDeleteStore(inner)
        val handler = DetachingHandler("r11.docs.$label")
        val engine: UploaderEngine = testEngine(
            store, listOf(handler), backgroundScope, clock = TestClock(40_000_000L), idGenerator = SequentialIds("d-$label")::next,
        )
        engines += engine
        val id: String = checkNotNull(engine.enqueue(handler, "x"))
        engine.drain()
        assertEquals(UploaderItemState.IN_FLIGHT, inner.find(id)?.state, "setup: handed off")
        return Triple(engine, handler, FlakyDeleteStoreRig(inner, store, id, handler.contexts.single().claim))
    }

    private class FlakyDeleteStoreRig(val inner: TestUploaderStore, val store: FlakyDeleteStore, val id: String, val claim: Long)

    @Test
    fun `settle called again after a delivery whose delete failed is harmless and deletes the row`() = runTest {
        val (engine, handler, rig) = detached("again")
        rig.store.failures = 1
        assertFailsWith<IllegalStateException> { engine.settle(rig.id, rig.claim, SettleResult.Delivered) }
        assertNotNull(rig.inner.find(rig.id), "setup: the delete failed, the row is still there")

        engine.settle(rig.id, rig.claim, SettleResult.Delivered)
        engine.settle(rig.id, rig.claim, SettleResult.Delivered)

        assertNull(rig.inner.find(rig.id), "the repeated call deleted the row")
        assertEquals(1, handler.contexts.size, "the delivered item was never handed out again")
    }

    @Test
    fun `a Park or Failed after a failed delete of a delivery returns normally and does not apply`() = runTest {
        for ((label, verdict) in listOf("park" to SettleResult.Park("late"), "failed" to SettleResult.Failed(IllegalStateException("late")))) {
            val (engine, handler, rig) = detached(label)
            rig.store.failures = 1
            assertFailsWith<IllegalStateException> { engine.settle(rig.id, rig.claim, SettleResult.Delivered) }

            engine.settle(rig.id, rig.claim, verdict)

            val row: UploaderItem? = rig.inner.find(rig.id)
            assertNull(row, "$label: the row is deleted and the verdict is not applied, saw $row")
            engine.drain()
            assertEquals(1, handler.contexts.size, "$label: nothing was handed out again")
        }
    }

    private class HookHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "r11.docs.hook"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        var delivered: Int = 0
        var settled: Int = 0
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = emptyList()))

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered++
        }

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            settled++
        }
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

    @Test
    fun `a delivery whose commit failed and that a drain confirmed later runs no onSettled`() = runTest {
        for (failDelete in listOf(false, true)) {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val transport = CountingTransport()
            val handler = HookHandler(transport)
            val clock = TestClock(50_000_000L + if (failDelete) 1_000L else 0L)
            val engine: UploaderEngine = testEngine(
                store, listOf(handler), backgroundScope, clock = clock,
                idGenerator = SequentialIds(if (failDelete) "h-fail" else "h-ok")::next,
            )
            engines += engine
            UploaderEngineRegistry.register(engine)
            val id: String = checkNotNull(engine.enqueue(handler, "x"))
            engine.drain()
            store.failures = if (failDelete) 1 else 0

            runCatching { UploadGateway.complete(id, UploadResponse(201, emptyMap(), ByteArray(0), false), 1.seconds) }
            // The drain confirms the held delete once the hand-off's lease has ended (see suspect r11-S1 for the
            // unexpired-lease reading of the docs); every existing pin of the held delete advances the clock the same way.
            clock.millis += 61_000L
            engine.drain()

            assertNull(inner.find(id), "failDelete=$failDelete: the row is gone after the confirming drain")
            assertEquals(1, handler.delivered, "failDelete=$failDelete: delivered once")
            assertEquals(1, transport.launches, "failDelete=$failDelete: never uploaded again")
            // The control (no failure) proves the hook is counted at all, so the zero below is not vacuous.
            assertEquals(if (failDelete) 0 else 1, handler.settled, "failDelete=$failDelete: onSettled count")
        }
    }

    @Test
    fun `a close during a manual drain makes that drain return and a cancelled caller still gets its cancellation`() = runTest {
        val inner = TestUploaderStore()
        val running = CompletableDeferred<Unit>()
        val handler = TestHandler(type = "r11.docs.close", onExecute = { _, _ ->
            running.complete(Unit)
            CompletableDeferred<Unit>().await()
            AttemptResult.Success
        })
        val engine: UploaderEngine = testEngine(inner, listOf(handler), backgroundScope, clock = TestClock(60_000_000L), idGenerator = SequentialIds("cl")::next)
        engines += engine
        engine.enqueue(handler, "x")
        val pass = launch { engine.drain() }
        withTimeout(30.seconds) { running.await() }

        engine.close()
        withTimeout(30.seconds) { pass.join() }

        assertFalse(pass.isCancelled, "the drain returned; it was not cancelled or failed")

        // A caller that is cancelled still gets its cancellation, on an engine that is not closed.
        val gate = CompletableDeferred<Unit>()
        val second = TestHandler(type = "r11.docs.cancel", onExecute = { _, _ -> gate.await(); AttemptResult.Success })
        val engine2: UploaderEngine = testEngine(TestUploaderStore(), listOf(second), backgroundScope, clock = TestClock(60_000_000L), idGenerator = SequentialIds("cx")::next)
        engines += engine2
        engine2.enqueue(second, "y")
        val caller = launch { engine2.drain() }
        testScheduler.runCurrent()
        caller.cancel()
        withTimeout(30.seconds) { caller.join() }
        assertTrue(caller.isCancelled, "a cancelled drain caller gets its cancellation")
        gate.complete(Unit)
    }

    @Test
    fun `an in-flight item of an unregistered type is parked only after its lease and the queue goes on behind it`() = runTest {
        val clock = TestClock(70_000_000L)
        val orphan = UploaderItem(
            id = "orphan-1", type = "r11.nobody", payload = "o", schemaVersion = 1, uniqueKey = null, orderingKey = null,
            tag = null, state = UploaderItemState.IN_FLIGHT, attempts = 0, nextRunAtEpochMillis = 0L,
            createdAtEpochMillis = 1L, lastError = null, leaseUntilEpochMillis = clock.millis + 60_000L, claim = 3L,
        )
        val store = TestUploaderStore(listOf(orphan))
        val handler = TestHandler(type = "r11.docs.live")
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock, idGenerator = SequentialIds("or")::next)
        engines += engine
        engine.enqueue(handler, "behind")

        engine.drain()
        assertEquals(UploaderItemState.IN_FLIGHT, store.find("orphan-1")?.state, "an unexpired lease is respected first")
        assertEquals(1, handler.payloads.size, "the queue went on behind the orphan")

        clock.millis += 120_000L
        engine.drain()
        val parked: UploaderItem = assertNotNull(store.find("orphan-1"))
        assertEquals(UploaderItemState.PARKED, parked.state, "parked once the lease ended")
        engine.drain()
        assertEquals(parked.claim, store.find("orphan-1")?.claim, "a later drain does not touch the parked orphan again")
    }
}
