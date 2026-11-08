package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * Round 5, D-61 and D-48 together: an upload outcome whose item is withdrawn or replaced between the settlement's
 * read of the row and its commit, for every kind of verdict, and the duplicate reports that follow.
 */
class UploaderEngineSettleWithdrawalRound5AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class Transport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = error("re-hand-aware overload expected")
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += "launch:$itemId"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }
    }

    private class Handler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "r5.settle"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        val delivered: MutableList<String> = mutableListOf()
        val settled: MutableList<SettleResult> = mutableListOf()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))

        override suspend fun classify(payload: String, result: UploadResult): SettleResult =
            if (result is UploadResult.Completed && result.statusCode == 418) SettleResult.Park("teapot") else defaultUploadClassification(result)

        override suspend fun onDelivered(payload: String) {
            delivered += payload
        }

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            settled += result
        }
    }

    private class Rig(scope: kotlinx.coroutines.CoroutineScope) {
        val store = TestUploaderStore()
        val transport = Transport()
        val handler = Handler(transport)
        val engine: UploaderEngine = testEngine(store, listOf(handler), scope).also { UploaderEngineRegistry.register(it) }

        suspend fun launched(payload: String = "p", uniqueKey: String? = null): String {
            val id: String = engine.enqueue(handler, payload, uniqueKey = uniqueKey)!!
            engine.drain()
            assertEquals(UploaderItemState.IN_FLIGHT, store.find(id)?.state)
            return id
        }

        suspend fun report(id: String, status: Int): Boolean =
            UploadGateway.complete(id, UploadResult.Completed(status), 10.milliseconds)
    }

    @Test
    fun `a delivery withdrawn between the settlement's read and its commit runs onDelivered and onSettled once`() =
        runTest(timeout = 30.seconds) {
            val rig = Rig(backgroundScope)
            val id: String = rig.launched()
            rig.store.afterNextGetById = { rig.engine.cancel(id) }

            assertTrue(rig.report(id, 201))
            assertTrue(rig.report(id, 201), "a duplicate report is a landed no-op")

            assertNull(rig.store.find(id))
            assertEquals(listOf("p"), rig.handler.delivered)
            assertEquals(listOf<SettleResult>(SettleResult.Delivered), rig.handler.settled)
        }

    @Test
    fun `a drop withdrawn between the settlement's read and its commit runs onSettled once`() = runTest(timeout = 30.seconds) {
        val rig = Rig(backgroundScope)
        val id: String = rig.launched()
        rig.store.afterNextGetById = { rig.engine.cancel(id) }

        assertTrue(rig.report(id, 404))

        assertNull(rig.store.find(id))
        assertEquals(1, rig.handler.settled.size)
        assertTrue(rig.handler.settled.single() is SettleResult.Drop)
        assertEquals(emptyList(), rig.handler.delivered)
    }

    @Test
    fun `a failure withdrawn between the settlement's read and its commit records nothing and runs no hook`() =
        runTest(timeout = 30.seconds) {
            val rig = Rig(backgroundScope)
            val id: String = rig.launched()
            rig.store.afterNextGetById = { rig.engine.cancel(id) }

            assertTrue(rig.report(id, 503))

            assertNull(rig.store.find(id), "the withdrawn row is not resurrected by the retry")
            assertEquals(emptyList(), rig.handler.settled)
        }

    @Test
    fun `a park verdict withdrawn between the settlement's read and its commit records nothing and runs no hook`() =
        runTest(timeout = 30.seconds) {
            val rig = Rig(backgroundScope)
            val id: String = rig.launched()
            rig.store.afterNextGetById = { rig.engine.cancel(id) }

            assertTrue(rig.report(id, 418))

            assertNull(rig.store.find(id))
            assertEquals(emptyList(), rig.handler.settled)
        }

    @Test
    fun `a delivery replaced between the settlement's read and its commit leaves the replacement alone`() =
        runTest(timeout = 30.seconds) {
            val rig = Rig(backgroundScope)
            val old: String = rig.launched("old", uniqueKey = "k")
            var replacement: String? = null
            rig.store.afterNextGetById = {
                replacement = rig.engine.enqueue(rig.handler, "new", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE)
            }

            assertTrue(rig.report(old, 201))

            val survivor: UploaderItem? = rig.store.find(assertNotNull(replacement))
            assertNotNull(survivor, "the replacement row survives the delivery of the item it superseded")
            assertNull(rig.store.find(old))
            assertEquals(listOf("old"), rig.handler.delivered)
            assertEquals(listOf<SettleResult>(SettleResult.Delivered), rig.handler.settled)
        }

    @Test
    fun `a report for an item withdrawn before the settlement ran runs no hook at all`() = runTest(timeout = 30.seconds) {
        val rig = Rig(backgroundScope)
        val id: String = rig.launched()
        rig.engine.cancel(id)

        assertTrue(rig.report(id, 201))

        assertEquals(emptyList(), rig.handler.delivered)
        assertEquals(emptyList(), rig.handler.settled)
    }

    @Test
    fun `a failure report of a withdrawn upload never makes the drain hand the item off again`() = runTest(timeout = 30.seconds) {
        val rig = Rig(backgroundScope)
        val id: String = rig.launched()
        rig.engine.cancel(id)
        rig.report(id, 503)

        rig.engine.drain()

        assertEquals(listOf("launch:$id", "cancel:$id"), rig.transport.events)
    }
}
