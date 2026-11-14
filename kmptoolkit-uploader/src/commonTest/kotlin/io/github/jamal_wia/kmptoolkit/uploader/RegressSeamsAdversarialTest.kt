package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.KeepResult
import io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Refactor-regression round, seams between the engine components: what the engine does with each [KeepResult] a custom
 * store may return, a transaction whose commit fails after the supersede, and the withdrawal and launch paths after
 * the engine was closed (the lifecycle component is gone, the ledger and the withdrawals are not).
 */
class RegressSeamsAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    private class RecordingTransport : UploadTransport {
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

    private class ProceedHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "regress.seams.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    /** A store that inserts like the reference store but may answer a KEEP with another [KeepResult]. */
    private class ScriptedKeepStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var forced: ((UploaderItem) -> KeepResult)? = null
        var insertFirst: Boolean = true

        override suspend fun insertKeep(record: UploaderItem): KeepResult {
            val scripted: KeepResult? = forced?.invoke(record)
            if (scripted == null) return inner.insertKeep(record)
            if (insertFirst) inner.insertKeep(record)
            return scripted
        }
    }

    private fun parkedUpload(id: String, key: String, type: String): UploaderItem = UploaderItem(
        id = id, type = type, payload = "old", schemaVersion = 1, uniqueKey = key, orderingKey = null, tag = null,
        state = UploaderItemState.PARKED, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = "parked",
    )

    @Test
    fun `a transaction whose commit fails after a KEEP superseded a parked row still cancels the upload of that row`() = runTest {
        val parent = Job()
        try {
            val transport = RecordingTransport()
            val handler = ProceedHandler(transport)
            val store = TestUploaderStore(listOf(parkedUpload("old", "avatar", handler.type)))
            val failingCommit = object : TransactionRunner {
                override suspend fun <R> inTransaction(block: suspend () -> R): R {
                    block()
                    throw IllegalStateException("commit failed")
                }
            }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), transactionRunner = failingCommit)

            assertFailsWith<IllegalStateException> { engine.enqueue(handler, "new", uniqueKey = "avatar") }

            assertTrue("cancel:old" in transport.events, "the superseded row was removed by the write, so its upload is cancelled even though the commit failed: ${transport.events}")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a closed engine still cancels the upload of a row that a cancel removes`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = RecordingTransport()
            val handler = ProceedHandler(transport)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("seam")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "a", uniqueKey = "k"))
            engine.drain()
            assertEquals(listOf("launch:$id"), transport.events, "setup: the upload is running")

            engine.close()
            engine.cancel(id)

            assertEquals(listOf("launch:$id", "cancel:$id"), transport.events)
            assertNull(store.find(id), "the row is gone")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a closed engine cancels the upload of a replaced row and launches nothing for the replacement`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = RecordingTransport()
            val handler = ProceedHandler(transport)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("seam")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "a", uniqueKey = "k"))
            engine.drain()

            engine.close()
            val second: String? = engine.enqueue(handler, "b", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE)
            engine.drain()

            assertEquals(listOf("launch:$id", "cancel:$id"), transport.events, "no launch of the replacement by a closed engine (P4)")
            assertEquals(UploaderItemState.PENDING, store.find(assertNotNull(second))?.state)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a closed engine cancels the upload of a parked row that a KEEP supersedes`() = runTest {
        val parent = Job()
        try {
            val transport = RecordingTransport()
            val handler = ProceedHandler(transport)
            val store = TestUploaderStore(listOf(parkedUpload("old", "avatar", handler.type)))
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("seam")::next)

            engine.close()
            val id: String? = engine.enqueue(handler, "new", uniqueKey = "avatar")

            assertNotNull(id)
            assertEquals(listOf("cancel:old"), transport.events)
            assertNull(store.find("old"))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a store that answers a KEEP with a superseded row of the item just inserted does not stop that item from running`() = runTest {
        val parent = Job()
        try {
            val transport = RecordingTransport()
            val handler = ProceedHandler(transport)
            val inner = TestUploaderStore()
            val store = ScriptedKeepStore(inner)
            store.forced = { record -> KeepResult.Inserted(record) }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("seam")::next)

            val id: String = assertNotNull(engine.enqueue(handler, "a", uniqueKey = "k"))
            engine.drain()

            assertTrue("launch:$id" in transport.events, "the row was inserted and must still be handed off: ${transport.events}")
            assertEquals(UploaderItemState.IN_FLIGHT, inner.find(id)?.state)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a store that answers a KEEP with a superseded row of an unregistered type is tolerated`() = runTest {
        val parent = Job()
        try {
            val transport = RecordingTransport()
            val handler = ProceedHandler(transport)
            val inner = TestUploaderStore()
            val store = ScriptedKeepStore(inner)
            store.forced = { _ -> KeepResult.Inserted(parkedUpload("ghost", "k", "no.such.type")) }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("seam")::next)

            val id: String? = engine.enqueue(handler, "a", uniqueKey = "k")
            engine.drain()

            assertNotNull(id)
            assertEquals(listOf("launch:$id"), transport.events, "no cancel for a type nobody registered, the real row still runs")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a store that answers Kept without inserting makes enqueue return null and run nothing`() = runTest {
        val parent = Job()
        try {
            val transport = RecordingTransport()
            val handler = ProceedHandler(transport)
            val inner = TestUploaderStore()
            val store = ScriptedKeepStore(inner)
            store.insertFirst = false
            store.forced = { _ -> KeepResult.Kept }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("seam")::next)

            val id: String? = engine.enqueue(handler, "a", uniqueKey = "k")
            engine.drain()

            assertNull(id)
            assertEquals(emptyList(), transport.events)
            assertEquals(emptyList(), inner.items)
        } finally {
            parent.cancel()
        }
    }
}
