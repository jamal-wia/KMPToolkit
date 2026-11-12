package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A1 final boss round 3, candidate defects.
 *
 * Group bn3-S1: a row the engine parks (or drops) at a re-hand of an expired upload lease can still have the earlier
 * claim's transfer running: the re-hand prepares before it launches, so nothing replaced that transfer. The engine
 * assumes a parked row has no upload ("A parked item has no upload to cancel"), so a logout wipe by tag and a REPLACE
 * leave that transfer uploading, while a cancel(id) of the very same row does cancel it.
 *
 * Group bn3-S2: a detached executor reports Delivered through settle() and the delete fails. Unlike a handler's own
 * Success, the engine does not remember the delivery, so the lease's end re-hands the delivered item in the same process.
 *
 * Group bn3-S3: with the wall clock at exactly Long.MAX_VALUE (where MutableUploaderClock.advanceBy saturates) the
 * in-memory backoff of a failure outside the handler is always due: one drain() call retries a failing confirming
 * delete, or re-runs a handler whose failure could not be written, in a loop until the store recovers.
 */
class EngineBossRound3SuspectsAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Tracks which transfers are live: launched and not cancelled since. */
    private class LiveTransport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        val live: MutableSet<String> = mutableSetOf()
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events += "launch:$itemId"
            live += itemId
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += if (isRehandOff) "relaunch:$itemId" else "launch:$itemId"
            live += itemId
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
            live -= itemId
        }
    }

    /** Proceeds on a first hand-off; at a re-hand of an expired lease answers [onRehand]. */
    private class RehandHandler(transport: UploadTransport, private val onRehand: UploadPreparation) : UploadHandler<String>(transport) {
        override val type: String = "boss3.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            if (context.wasDetached) onRehand else UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    /** Hands an upload off, lets its lease expire with the transfer still running, and lets the re-hand answer. */
    private suspend fun rehanded(engine: UploaderEngine, handler: RehandHandler, transport: LiveTransport, clock: TestClock, store: TestUploaderStore): String {
        val id: String = assertNotNull(engine.enqueue(handler, "avatar", uniqueKey = "avatar", tag = "account-1"))
        engine.drain()
        assertEquals(listOf("launch:$id"), transport.events, "setup: the first hand-off launched the transfer")
        clock.millis += 61_000L // the lease runs out while the transfer is still uploading
        engine.drain()
        assertTrue(id in transport.live, "setup: nothing touched the earlier claim's transfer at the re-hand")
        return id
    }

    @Test
    fun `bn3-S1a a logout wipe by tag cancels the transfer of an item parked at its re-hand`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = RehandHandler(transport, UploadPreparation.Park("signed out"))
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3s1a")::next)
            val id: String = rehanded(engine, handler, transport, clock, store)
            assertEquals(UploaderItemState.PARKED, store.find(id)?.state, "setup: the re-hand parked the item")

            engine.cancelByTag("account-1")

            assertEquals(null, store.find(id), "setup: the wipe removed the row")
            assertTrue(
                id !in transport.live,
                "the logout wipe left the parked item's transfer uploading under the signed-out account: ${transport.events}",
            )
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bn3-S1 control a cancel of the same parked item cancels its transfer`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = RehandHandler(transport, UploadPreparation.Park("signed out"))
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3s1k")::next)
            val id: String = rehanded(engine, handler, transport, clock, store)

            engine.cancel(id)

            assertTrue(id !in transport.live, "cancel(id) cancels the parked item's transfer: ${transport.events}")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bn3-S1b a REPLACE cancels the transfer of the item parked at its re-hand`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = RehandHandler(transport, UploadPreparation.Park("signed out"))
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3s1b")::next)
            val id: String = rehanded(engine, handler, transport, clock, store)

            val fresh: String = assertNotNull(engine.enqueue(handler, "avatar-v2", uniqueKey = "avatar", conflictPolicy = ConflictPolicy.REPLACE))

            assertEquals(null, store.find(id), "setup: the REPLACE superseded the parked row")
            assertTrue(fresh != id)
            assertTrue(id !in transport.live, "the superseded version keeps uploading next to the new one: ${transport.events}")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bn3-S1c a drop at the re-hand cancels the earlier claim's transfer of the removed item`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = RehandHandler(transport, UploadPreparation.Drop("the server already has it"))
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3s1c")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "avatar", uniqueKey = "avatar"))
            engine.drain()
            clock.millis += 61_000L
            engine.drain()

            assertEquals(null, store.find(id), "setup: the drop removed the row")
            assertTrue(id !in transport.live, "the dropped item's earlier transfer keeps uploading for a row that is gone: ${transport.events}")
        } finally {
            parent.cancel()
        }
    }

    /** Fails the next [deleteFailures] deletes and the next [failureWrites] failure writes: a locked database. */
    private class FlakyStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var deleteFailures: Int = 0
        var deleteCalls: Int = 0
        var failureWrites: Int = 0
        override suspend fun deleteById(id: String) {
            deleteCalls++
            if (deleteFailures > 0) {
                deleteFailures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }

        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean {
            if (failureWrites > 0) {
                failureWrites--
                throw IllegalStateException("database is locked")
            }
            return inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }
    }

    @Test
    fun `bn3-S2a a delivery a detached executor reported is not re-handed when its delete failed`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyStore(inner)
            var handed: AttemptContext? = null
            val handler = TestHandler(onExecute = { context, _ ->
                handed = context
                AttemptResult.Detached(60_000L)
            })
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3s2a")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "send-video"))
            engine.drain()
            val context: AttemptContext = assertNotNull(handed, "setup: handed off")

            store.deleteFailures = 1
            assertFailsWith<IllegalStateException> { engine.settle(id, context.claim, SettleResult.Delivered) }
            assertNotNull(inner.find(id), "setup: the delivered row could not be deleted")

            clock.millis += 61_000L // the executor is done and gone; its lease runs out
            engine.drain()

            assertEquals(
                1, handler.payloads.size,
                "the engine was told the effect was delivered, yet re-handed it in the same process (settle KDoc: re-handing would send it twice)",
            )
        } finally {
            parent.cancel()
        }
    }

    /** A handler whose preparation is a script; counts what the upload's delivery reported. */
    private class ScriptedUploadHandler(
        transport: UploadTransport,
        override val retryPolicy: RetryPolicy = FixedRetryPolicy(),
        private val prepare: (AttemptContext) -> UploadPreparation,
    ) : UploadHandler<String>(transport) {
        override val type: String = "boss3.scripted"
        override val schemaVersion: Int = 1
        var delivered: Int = 0
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation = prepare(context)
        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered++
        }
    }

    private fun proceed(): UploadPreparation =
        UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", "b"))))

    @Test
    fun `bn3-S1 fix a REPLACE over a parked item that was never launched cancels its id`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = ScriptedUploadHandler(transport) { UploadPreparation.Park("signed out") }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000_000L), idGenerator = SequentialIds("bn3f1")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "avatar", uniqueKey = "avatar"))
            engine.drain()
            assertEquals(UploaderItemState.PARKED, store.find(id)?.state, "setup: parked at its first hand-off")
            assertEquals(emptyList(), transport.events, "setup: nothing was ever launched")

            engine.enqueue(handler, "avatar-v2", uniqueKey = "avatar", conflictPolicy = ConflictPolicy.REPLACE)

            assertEquals(listOf("cancel:$id"), transport.events, "a withdrawal cancels the superseded id whatever its state (P8)")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bn3-S1 fix a re-hand whose failure is given up with a drop cancels the earlier claim's transfer`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = ScriptedUploadHandler(transport, FixedRetryPolicy(giveUp = GiveUpPolicy.DropAfterAttempts(1))) { context ->
                if (context.wasDetached) throw IllegalStateException("preparation keeps failing") else proceed()
            }
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3f2")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "avatar", uniqueKey = "avatar"))
            engine.drain()
            assertTrue(id in transport.live, "setup: the first hand-off launched the transfer")
            clock.millis += 61_000L

            engine.drain()

            assertEquals(null, store.find(id), "setup: the failed re-hand preparation gave up with a drop")
            assertTrue(id !in transport.live, "the dropped item's earlier transfer must be cancelled: ${transport.events}")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bn3-S1 fix a re-hand that parks leaves the earlier transfer running and its delivery settles the item once`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = ScriptedUploadHandler(transport) { context ->
                if (context.wasDetached) UploadPreparation.Park("signed out") else proceed()
            }
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3f3")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "avatar", uniqueKey = "avatar"))
            engine.drain()
            clock.millis += 61_000L
            engine.drain()
            assertEquals(UploaderItemState.PARKED, store.find(id)?.state, "setup: the re-hand parked the item")

            // A park at a re-hand does not cancel: the transports erase their outcome journal on cancel, and a
            // delivery that already happened would be lost and the revived item sent twice.
            assertTrue(id in transport.live, "a park at a re-hand leaves the earlier transfer alone: ${transport.events}")
            assertTrue(
                (engine as DefaultUploaderEngine).settleUpload(id, UploadResult.Completed(200)),
                "the earlier transfer's delivery settles the parked item",
            )

            assertEquals(null, store.find(id), "the delivery deleted the parked row")
            assertEquals(1, handler.delivered, "onDelivered ran exactly once")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bn3-S2 fix a dropped item reported through settle is not re-handed when its delete failed`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyStore(inner)
            var handed: AttemptContext? = null
            val handler = TestHandler(onExecute = { context, _ ->
                handed = context
                AttemptResult.Detached(60_000L)
            })
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3s2d")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "send-video"))
            engine.drain()
            val context: AttemptContext = assertNotNull(handed, "setup: handed off")

            store.deleteFailures = 1
            assertFailsWith<IllegalStateException> { engine.settle(id, context.claim, SettleResult.Drop("not owed any more")) }
            assertNotNull(inner.find(id), "setup: the dropped row could not be deleted")
            clock.millis += 61_000L
            engine.drain()

            assertEquals(1, handler.payloads.size, "a drop reported through settle must not be re-handed (P7)")
            assertEquals(null, inner.find(id), "the next pass only retried the delete")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bn3-S2 fix an engine built to replace the first one confirms a settled delivery without a run`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyStore(inner)
            var handed: AttemptContext? = null
            val handler = TestHandler(onExecute = { context, _ ->
                handed = context
                AttemptResult.Detached(60_000L)
            })
            val clock = TestClock(1_000_000L)
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3s2r")::next)
            val id: String = assertNotNull(first.enqueue(handler, "send-video"))
            first.drain()
            val context: AttemptContext = assertNotNull(handed, "setup: handed off")
            store.deleteFailures = 1
            assertFailsWith<IllegalStateException> { first.settle(id, context.claim, SettleResult.Delivered) }
            first.close()

            val replacement: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3s2r2")::next)
            clock.millis += 61_000L
            replacement.drain()

            assertEquals(1, handler.payloads.size, "the replacement engine re-handed a delivery the first one was told about")
            assertEquals(null, inner.find(id), "the replacement engine confirmed the delivery by deleting the row")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bn3-S2 fix an upload delivery whose commit failed is confirmed by the drain once its lease is out`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyStore(inner)
            val transport = LiveTransport()
            val handler = ScriptedUploadHandler(transport) { proceed() }
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bn3s2u")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "avatar", uniqueKey = "avatar"))
            engine.drain()
            assertEquals(listOf("launch:$id"), transport.events, "setup: the transfer was launched")

            store.deleteFailures = 1
            assertEquals(false, (engine as DefaultUploaderEngine).settleUpload(id, UploadResult.Completed(200)), "setup: the commit did not land")
            assertNotNull(inner.find(id), "setup: the delivered row is still there")
            assertEquals(1, handler.delivered, "setup: onDelivered ran before the commit")
            clock.millis += 61_000L
            engine.drain()

            assertEquals(null, inner.find(id), "the drain confirmed the delivery")
            assertEquals(listOf("launch:$id"), transport.events, "the delivered upload was not relaunched")
            assertEquals(1, handler.delivered, "onDelivered ran exactly once")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bn3-S2 fix a later successful settle forgets the remembered delivery`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyStore(inner)
            val transport = LiveTransport()
            val handler = ScriptedUploadHandler(transport) { proceed() }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000_000L), idGenerator = SequentialIds("bn3s2f")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "avatar", uniqueKey = "avatar"))
            engine.drain()
            val row: UploaderItem = assertNotNull(inner.find(id), "setup: the item is in flight")

            store.deleteFailures = 1
            assertEquals(false, (engine as DefaultUploaderEngine).settleUpload(id, UploadResult.Completed(200)), "setup: the commit did not land")
            assertTrue(DeliveredUnconfirmed.matches(row), "setup: the failed delete is remembered")

            assertTrue(engine.settleUpload(id, UploadResult.Completed(200)), "the repeated settle lands")

            assertEquals(null, inner.find(id), "the repeated settle deleted the row")
            assertTrue(!DeliveredUnconfirmed.matches(row), "a successful delete forgets the entry")
        } finally {
            parent.cancel()
        }
    }
}
