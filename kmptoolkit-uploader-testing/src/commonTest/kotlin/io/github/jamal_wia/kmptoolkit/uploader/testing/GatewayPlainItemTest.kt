package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadAttempt
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadResponse
import io.github.jamal_wia.kmptoolkit.uploader.UploadResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/** The gateway is the channel of UploadHandler items only: a plain handler's item is never changed by it (R8-S3, D-23). */
class GatewayPlainItemTest {

    private class Plain : UploaderHandler<String> {
        var lastContext: AttemptContext? = null
        override val type: String = "plain"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            lastContext = context
            return AttemptResult.Detached(600_000)
        }
    }

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    @Test
    fun `gateway calls with a plain handler's in-flight id change nothing and its own settle still lands`() = runTest {
        val store = InMemoryUploaderStore()
        val plain = Plain()
        val engine = createUploaderEngine(store, listOf(plain), backgroundScope)
        UploaderEngineRegistry.register(engine)
        val id: String = engine.enqueue(plain, "x")!!
        engine.drain()
        val before: UploaderItem = store.snapshot().single()
        assertEquals(UploaderItemState.IN_FLIGHT, before.state)

        assertEquals(UploadAttempt.NothingOwed, UploadGateway.prepareAttempt(id, 0.seconds))
        assertTrue(UploadGateway.complete(id, UploadResult.Completed(200), 0.seconds))
        assertTrue(UploadGateway.complete(id, UploadResult.TransportFailure("x"), 0.seconds))
        assertTrue(UploadGateway.complete(id, UploadResponse(500, emptyMap(), ByteArray(0), false), 0.seconds))

        assertEquals(before, store.snapshot().single()) // state, claim, attempts, lease, lastError all as they were

        engine.settle(id, plain.lastContext!!.claim, SettleResult.Delivered)
        assertTrue(store.snapshot().isEmpty())
        engine.close()
    }

    @Test
    fun `complete with a plain handler's pending id leaves it pending`() = runTest {
        val store = InMemoryUploaderStore()
        val plain = Plain()
        val engine = createUploaderEngine(store, listOf(plain), backgroundScope) // not started, not drained
        UploaderEngineRegistry.register(engine)
        val id: String = engine.enqueue(plain, "x")!!

        assertTrue(UploadGateway.complete(id, UploadResult.Completed(200), 0.seconds))

        val row: UploaderItem = store.snapshot().single()
        assertEquals(UploaderItemState.PENDING, row.state)
        assertNull(row.lastError)
        engine.close()
    }

    @Test
    fun `a row of a type with no handler at all is still parked by the gateway`() = runTest {
        val store = InMemoryUploaderStore()
        store.insertKeep(
            UploaderItem(
                id = "ghost-1", type = "ghost", payload = "p", schemaVersion = 1, uniqueKey = null,
                orderingKey = null, tag = null, state = UploaderItemState.IN_FLIGHT, attempts = 0,
                nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
                leaseUntilEpochMillis = Long.MAX_VALUE / 2, claim = 1L,
            ),
        )
        val engine = createUploaderEngine(store, listOf(Plain()), backgroundScope)
        UploaderEngineRegistry.register(engine)

        assertEquals(UploadAttempt.NothingOwed, UploadGateway.prepareAttempt("ghost-1", 0.seconds))

        assertEquals(UploaderItemState.PARKED, store.snapshot().single().state)
        engine.close()
    }
}
