package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/** [InMemoryUploaderStore] against the published contract, plus the real engine running on it. */
class InMemoryUploaderStoreTest {

    @Test
    fun `it satisfies every invariant of the UploaderStore contract`() = runTest {
        val checksRun: Int = UploaderStoreContract { InMemoryUploaderStore() }.verifyAll()

        assertTrue(checksRun >= EXPECTED_CONTRACT_CHECKS, "only $checksRun checks ran")
    }

    @Test
    fun `it starts with the items it was seeded with`() = runTest {
        val store = InMemoryUploaderStore(listOf(item("a"), item("b")))

        assertEquals(listOf("a", "b"), store.getAllActive().map { it.id })
        assertEquals(listOf("a", "b"), store.snapshot().map { it.id })
    }

    @Test
    fun `the items flow exposes every state for assertions`() = runTest {
        val store = InMemoryUploaderStore()
        store.insertKeep(item("a"))
        store.park("a", "parked")

        assertEquals(listOf(UploaderItemState.PARKED), store.items.value.map { it.state })
        assertEquals(emptyList(), store.getAllActive())
    }

    @Test
    fun `concurrent inserts of the same unique key produce exactly one item`() = runTest {
        val store = InMemoryUploaderStore()

        val results: List<Boolean> = List(CONCURRENT_WRITERS) { index ->
            async { store.insertKeep(item("id-$index", uniqueKey = "shared")) }
        }.awaitAll()

        assertEquals(1, results.count { it }, "exactly one insert may win the key")
        assertEquals(1, store.snapshot().size)
    }

    @Test
    fun `concurrent recordFailure calls under one lease let exactly one through`() = runTest {
        val store = InMemoryUploaderStore()
        store.insertKeep(item("a"))
        store.markInFlight("a", leaseUntilEpochMillis = 900L)

        val applied: List<Boolean> = List(CONCURRENT_WRITERS) {
            async {
                store.recordFailure("a", 1, 10L, null, expectedLeaseUntilEpochMillis = 900L)
            }
        }.awaitAll()

        assertEquals(
            1,
            applied.count { it },
            "the guard must serialize: the first write clears the lease the rest expect",
        )
    }

    @Test
    fun `the real engine delivers an item enqueued into this store`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = RecordingHandler()
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = MutableUploaderClock(),
        )

        val id: String? = engine.enqueue(handler, "hello")
        engine.drain()

        assertNotNull(id)
        assertEquals(listOf("hello"), handler.payloads)
        assertEquals(emptyList(), store.snapshot())
    }

    @Test
    fun `the real engine retries a failing item against this store`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = RecordingHandler(result = AttemptResult.Retry())
        val clock = MutableUploaderClock()
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = clock,
        )
        engine.enqueue(handler, "p")

        engine.drain()

        val stored: UploaderItem = assertNotNull(store.snapshot().singleOrNull())
        assertEquals(1, stored.attempts)
        assertEquals(UploaderItemState.PENDING, stored.state)
        assertTrue(stored.nextRunAtEpochMillis > 0L, "a backoff gate must have been written")
    }

    @Test
    fun `the real engine's REPLACE against this store keeps one item`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = RecordingHandler(result = AttemptResult.Retry())
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = MutableUploaderClock(),
        )

        engine.enqueue(handler, "v1", uniqueKey = "u")
        engine.enqueue(handler, "v2", uniqueKey = "u", conflictPolicy = ConflictPolicy.REPLACE)

        assertEquals(listOf("v2"), store.snapshot().map { it.payload })
    }

    @Test
    fun `a tag wipe removes what the engine enqueued under it`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = RecordingHandler(result = AttemptResult.Retry())
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = MutableUploaderClock(),
        )
        engine.enqueue(handler, "mine", tag = "session-1")
        engine.enqueue(handler, "theirs", tag = "session-2")

        store.deleteByTag("session-1")

        assertEquals(listOf("theirs"), store.snapshot().map { it.payload })
    }

    private fun item(
        id: String,
        uniqueKey: String? = null,
    ): UploaderItem = UploaderItem(
        id = id,
        type = "test",
        payload = "p",
        schemaVersion = 1,
        uniqueKey = uniqueKey,
        orderingKey = null,
        tag = null,
        state = UploaderItemState.PENDING,
        attempts = 0,
        nextRunAtEpochMillis = 0L,
        createdAtEpochMillis = 0L,
        lastError = null,
    )

    private class RecordingHandler(
        private val result: AttemptResult = AttemptResult.Success,
    ) : UploaderHandler<String> {
        val payloads: MutableList<String> = mutableListOf()
        override val type: String = "test"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            payloads += payload
            return result
        }
    }

    private companion object {
        /** Guards against a silently shrinking contract: verifyAll must keep running them all. */
        const val EXPECTED_CONTRACT_CHECKS: Int = 30
        const val CONCURRENT_WRITERS: Int = 8
    }
}
