package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 5 (lifecycle chaos + concurrency storm): the guarantees of `Uploader.cancel`, `cancelByTag` and REPLACE
 * (D-48) against stores and transaction runners that behave like the real SQL ones at the moment a coroutine is
 * cancelled. A real store call made through `withContext` writes, and then reports the caller's cancellation
 * when it returns: the write stands, the call throws.
 */
class UploaderEngineWithdrawalWindowRound5AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    /** A store whose point read notices a cancelled caller, as one that hops dispatchers does. */
    private class CancelAwareStore(private val inner: UploaderStore) : UploaderStore by inner {
        override suspend fun getById(id: String): UploaderItem? {
            currentCoroutineContext().ensureActive()
            return inner.getById(id)
        }
    }

    /** A store whose deletes commit and then report the cancellation of a caller the test cancelled meanwhile. */
    private class CancelAfterCommitStore(private val inner: UploaderStore) : UploaderStore by inner {
        var caller: Job? = null

        override suspend fun deleteById(id: String) {
            inner.deleteById(id)
            caller?.cancel()
            currentCoroutineContext().ensureActive()
        }

        override suspend fun deleteByTag(tag: String): List<UploaderItem> {
            val removed: List<UploaderItem> = inner.deleteByTag(tag)
            caller?.cancel()
            currentCoroutineContext().ensureActive()
            return removed
        }
    }

    private class RecordingTransport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = error("re-hand-aware overload expected")
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += "launch:$itemId"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }

        fun of(id: String): List<String> = events.filter { it.endsWith(":$id") }
    }

    private class UploadingHandler(
        transport: UploadTransport,
        private val prepare: suspend () -> UploadPreparation = { UploadPreparation.Proceed(REQUEST) },
    ) : UploadHandler<String>(transport) {
        override val type: String = "r5.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation = prepare()
    }

    private companion object {
        val REQUEST: UploadRequest = UploadRequest(
            url = "https://example.com/upload",
            headers = emptyMap(),
            fields = listOf(UploadField.Text("a", "b")),
        )
    }

    // --- the hand-off's own check after its launch ---------------------------------------------------------

    @Test
    fun `an upload withdrawn between its claim and its launch is cancelled after its launch under a store that sees cancellation`() =
        runTest(timeout = 30.seconds) {
            val inner = TestUploaderStore()
            val transport = RecordingTransport()
            val handler = UploadingHandler(transport)
            val engine: UploaderEngine = testEngine(CancelAwareStore(inner), listOf(handler), backgroundScope)
            val id: String = engine.enqueue(handler, "x")!!
            inner.afterNextMarkInFlight = { engine.cancel(id) }

            engine.drain()

            assertNull(inner.find(id))
            assertEquals(
                listOf("cancel:$id", "launch:$id", "cancel:$id"),
                transport.of(id),
                "the upload launched after the withdrawal returned must be cancelled by the hand-off that launched it",
            )
        }

    @Test
    fun `an upload wiped by tag between its claim and its launch is cancelled after its launch under a store that sees cancellation`() =
        runTest(timeout = 30.seconds) {
            val inner = TestUploaderStore()
            val transport = RecordingTransport()
            val handler = UploadingHandler(transport)
            val engine: UploaderEngine = testEngine(CancelAwareStore(inner), listOf(handler), backgroundScope)
            val id: String = engine.enqueue(handler, "x", tag = "account-1")!!
            inner.afterNextMarkInFlight = { engine.cancelByTag("account-1") }

            engine.drain()

            assertNull(inner.find(id))
            assertEquals(listOf("cancel:$id", "launch:$id", "cancel:$id"), transport.of(id))
        }

    @Test
    fun `an upload replaced between its claim and its launch is cancelled after its launch under a store that sees cancellation`() =
        runTest(timeout = 30.seconds) {
            val inner = TestUploaderStore()
            val transport = RecordingTransport()
            val handler = UploadingHandler(transport)
            val engine: UploaderEngine = testEngine(CancelAwareStore(inner), listOf(handler), backgroundScope)
            val old: String = engine.enqueue(handler, "old", uniqueKey = "k")!!
            inner.afterNextMarkInFlight = {
                engine.enqueue(handler, "new", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE)
            }

            engine.drain()

            assertNull(inner.find(old))
            assertEquals(listOf("cancel:$old", "launch:$old", "cancel:$old"), transport.of(old))
        }

    // --- a caller cancelled after the withdrawal committed --------------------------------------------------

    @Test
    fun `a cancel whose caller is cancelled after the row was deleted still cancels the running upload`() =
        runTest(timeout = 30.seconds) {
            val inner = TestUploaderStore()
            val store = CancelAfterCommitStore(inner)
            val transport = RecordingTransport()
            val handler = UploadingHandler(transport)
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
            val id: String = engine.enqueue(handler, "x")!!
            engine.drain()
            assertEquals(listOf("launch:$id"), transport.of(id))

            val caller: Job = launch { engine.cancel(id) }
            store.caller = caller
            runCurrent()
            caller.join()

            assertNull(inner.find(id), "the delete committed")
            assertEquals(
                listOf("launch:$id", "cancel:$id"),
                transport.of(id),
                "the row is gone, so nothing else will ever stop this upload",
            )
        }

    @Test
    fun `a cancelByTag whose caller is cancelled after the wipe committed still cancels the running upload`() =
        runTest(timeout = 30.seconds) {
            val inner = TestUploaderStore()
            val store = CancelAfterCommitStore(inner)
            val transport = RecordingTransport()
            val handler = UploadingHandler(transport)
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
            val id: String = engine.enqueue(handler, "x", tag = "account-1")!!
            engine.drain()

            val caller: Job = launch { engine.cancelByTag("account-1") }
            store.caller = caller
            runCurrent()
            caller.join()

            assertNull(inner.find(id))
            assertEquals(listOf("launch:$id", "cancel:$id"), transport.of(id))
        }

    @Test
    fun `a cancel whose caller is cancelled after the row was deleted still stops the running plain attempt`() =
        runTest(timeout = 30.seconds) {
            val inner = TestUploaderStore()
            val store = CancelAfterCommitStore(inner)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var cancelled = false
            val handler = TestHandler(
                onExecute = { _, _ ->
                    started.complete(Unit)
                    try {
                        release.await()
                    } catch (e: CancellationException) {
                        cancelled = true
                        throw e
                    }
                    AttemptResult.Success
                },
            )
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
            val id: String = engine.enqueue(handler, "x")!!
            try {
                val drain: Job = backgroundScope.launch { engine.drain() }
                runCurrent()
                assertTrue(started.isCompleted, "the attempt is running")

                val caller: Job = launch { engine.cancel(id) }
                store.caller = caller
                runCurrent()
                caller.join()
                runCurrent()

                assertNull(inner.find(id))
                assertTrue(cancelled, "a running attempt of a withdrawn item is cancelled")
                drain.cancel()
            } finally {
                release.complete(Unit)
            }
        }

    // --- close while the handler prepares and ignores the cancellation ---------------------------------------

    @Test
    fun `a closed engine launches nothing even when prepareUpload swallowed the cancellation of the close`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val inner = TestUploaderStore()
                val transport = RecordingTransport()
                lateinit var engine: UploaderEngine
                val handler = UploadingHandler(transport, prepare = {
                    engine.close()
                    UploadPreparation.Proceed(REQUEST) // a handler that never looks at cancellation
                })
                engine = testEngine(inner, listOf(handler), CoroutineScope(StandardTestDispatcher(testScheduler) + parent))
                val id: String = engine.enqueue(handler, "x")!!
                engine.start()
                runCurrent()

                assertEquals(emptyList(), transport.events, "close() returned before this launch; the engine is inert")
                assertEquals(UploaderItemState.PENDING, inner.find(id)?.state, "and nothing was claimed either")
            } finally {
                parent.cancel()
            }
        }
}
