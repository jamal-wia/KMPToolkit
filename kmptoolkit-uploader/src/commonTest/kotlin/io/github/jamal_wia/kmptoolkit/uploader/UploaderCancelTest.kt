package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/**
 * [Uploader.cancel], [Uploader.cancelByTag] and the cancellation a [ConflictPolicy.REPLACE] implies,
 * from their KDoc: the row goes, that one item's upload is cancelled on its transport, and nothing
 * else — no other item, no other handler sharing the transport — is touched.
 */
class UploaderCancelTest {

    private val store = TestUploaderStore()
    private val transport = SharedTransport()
    private val audio = TestUploadHandler(transport, type = "task.audio")
    private val voice = TestUploadHandler(transport, type = "chat.voice")
    private var engine: UploaderEngine? = null

    @AfterTest
    fun closeEngine() {
        engine?.close()
    }

    private fun startEngine(scope: CoroutineScope, vararg handlers: UploaderHandler<*>): UploaderEngine =
        testEngine(store, handlers.toList().ifEmpty { listOf(audio, voice) }, scope).also {
            engine = it
            UploaderEngineRegistry.register(it)
        }

    // --- cancel ----------------------------------------------------------------------------------

    @Test
    fun `cancelling one in-flight upload leaves another handler's upload on the same transport alone`() = runTest {
        val engine: UploaderEngine = startEngine(backgroundScope)
        val audioId: String = engine.enqueue(audio, "homework")!!
        val voiceId: String = engine.enqueue(voice, "message")!!
        engine.drain()

        engine.cancel(audioId)

        assertEquals(listOf("cancel:$audioId"), transport.cancelEvents())
        assertNull(store.find(audioId))
        assertEquals(UploaderItemState.IN_FLIGHT, store.find(voiceId)?.state)
        UploadGateway.complete(voiceId, UploadResult.Completed(200), WAIT)
        assertNull(store.find(voiceId), "the other upload still delivers")
        assertEquals(listOf<UploadResult>(UploadResult.Completed(200)), voice.classified)
    }

    @Test
    fun `the late outcome of a cancelled upload settles nothing and runs no hook`() = runTest {
        val engine: UploaderEngine = startEngine(backgroundScope)
        val id: String = engine.enqueue(audio, "homework")!!
        engine.drain()
        engine.cancel(id)

        UploadGateway.complete(id, UploadResult.Completed(200), WAIT)

        assertTrue(audio.classified.isEmpty())
        assertTrue(store.items.isEmpty())
    }

    @Test
    fun `cancelling an unknown id is a no-op`() = runTest {
        val engine: UploaderEngine = startEngine(backgroundScope)
        val kept: String = engine.enqueue(audio, "homework")!!

        engine.cancel("never-queued")

        assertTrue(transport.events.isEmpty())
        assertEquals(listOf(kept), store.items.map { it.id })
    }

    @Test
    fun `a cancelled pending item is never handed off`() = runTest {
        val engine: UploaderEngine = startEngine(backgroundScope)
        val id: String = engine.enqueue(audio, "homework")!!

        engine.cancel(id)
        engine.drain()

        assertTrue(transport.launchEvents().isEmpty())
        assertNull(store.find(id))
    }

    @Test
    fun `a parked item can be withdrawn`() = runTest {
        val parking = TestUploadHandler(transport, prepare = { UploadPreparation.Park("source unreadable") })
        val engine: UploaderEngine = startEngine(backgroundScope, parking)
        val id: String = engine.enqueue(parking, "homework")!!
        engine.drain()
        assertEquals(UploaderItemState.PARKED, store.find(id)?.state)

        engine.cancel(id)

        assertNull(store.find(id))
    }

    @Test
    fun `a plain handler's item loses its row and the engine calls no transport`() = runTest {
        val plain = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(60_000L) })
        val engine: UploaderEngine = startEngine(backgroundScope, plain, audio)
        val id: String = engine.enqueue(plain, "effect")!!
        engine.drain()

        engine.cancel(id)
        engine.settle(id, SettleResult.Failed())

        assertNull(store.find(id), "a later settle does not revive it")
        assertTrue(transport.events.isEmpty())
    }

    @Test
    fun `a transport that throws while cancelling does not fail the withdrawal`() = runTest {
        val engine: UploaderEngine = startEngine(backgroundScope)
        val id: String = engine.enqueue(audio, "homework")!!
        engine.drain()
        transport.failCancelWith = IllegalStateException("scheduler unavailable")

        engine.cancel(id)

        assertNull(store.find(id))
    }

    @Test
    fun `an item withdrawn while its hand-off is being prepared has its upload cancelled after launch`() = runTest {
        // The withdrawal lands while prepareUpload runs: there is no upload to cancel yet, and the
        // hand-off goes on to launch one. The hand-off must notice the row is gone and cancel it.
        lateinit var withdraw: suspend () -> Unit
        val racing = TestUploadHandler(transport, prepare = {
            withdraw()
            UploadPreparation.Proceed(request("t"))
        })
        val engine: UploaderEngine = startEngine(backgroundScope, racing)
        val id: String = engine.enqueue(racing, "homework")!!
        withdraw = { engine.cancel(id) }

        engine.drain()

        assertEquals(listOf("cancel:$id", "launch:$id", "cancel:$id"), transport.events)
        assertNull(store.find(id))
    }

    @Test
    fun `an upload that settled before the post-launch check is not disturbed`() = runTest {
        // The row is gone after launch because the upload already delivered — the cancel that follows
        // reaches a finished transfer, which the transport contract makes a no-op.
        transport.onLaunch = { itemId ->
            store.beforeNextGetById = { UploadGateway.complete(itemId, UploadResult.Completed(201), WAIT) }
        }
        val engine: UploaderEngine = startEngine(backgroundScope)
        val id: String = engine.enqueue(audio, "homework")!!

        engine.drain()

        assertEquals(listOf<UploadResult>(UploadResult.Completed(201)), audio.classified, "delivered exactly once")
        assertEquals(listOf("launch:$id", "cancel:$id"), transport.events)
        assertNull(store.find(id))
    }

    // --- REPLACE ---------------------------------------------------------------------------------

    @Test
    fun `replacing an in-flight upload cancels the superseded one`() = runTest {
        val engine: UploaderEngine = startEngine(backgroundScope)
        val old: String = engine.enqueue(audio, "take 1", uniqueKey = "task-7")!!
        engine.drain()

        val new: String = engine.enqueue(audio, "take 2", uniqueKey = "task-7", conflictPolicy = ConflictPolicy.REPLACE)!!
        engine.drain()

        assertEquals(listOf("launch:$old", "cancel:$old", "launch:$new"), transport.events)
        assertNull(store.find(old))
        assertEquals(UploaderItemState.IN_FLIGHT, store.find(new)?.state)
    }

    @Test
    fun `replacing cancels only the same key of the same handler`() = runTest {
        val engine: UploaderEngine = startEngine(backgroundScope)
        val otherKey: String = engine.enqueue(audio, "task 8", uniqueKey = "task-8")!!
        val otherHandler: String = engine.enqueue(voice, "message", uniqueKey = "task-7")!!
        engine.drain()

        engine.enqueue(audio, "take 2", uniqueKey = "task-7", conflictPolicy = ConflictPolicy.REPLACE)

        assertTrue(transport.cancelEvents().isEmpty())
        assertEquals(UploaderItemState.IN_FLIGHT, store.find(otherKey)?.state)
        assertEquals(UploaderItemState.IN_FLIGHT, store.find(otherHandler)?.state)
    }

    @Test
    fun `a KEEP conflict cancels nothing`() = runTest {
        val engine: UploaderEngine = startEngine(backgroundScope)
        val first: String = engine.enqueue(audio, "take 1", uniqueKey = "task-7")!!
        engine.drain()

        engine.enqueue(audio, "take 2", uniqueKey = "task-7", conflictPolicy = ConflictPolicy.KEEP)

        assertTrue(transport.cancelEvents().isEmpty())
        assertEquals(UploaderItemState.IN_FLIGHT, store.find(first)?.state)
    }

    // --- cancelByTag -----------------------------------------------------------------------------

    @Test
    fun `cancelByTag withdraws every item of that tag in any state and nothing else`() = runTest {
        val parking = TestUploadHandler(transport, type = "parking", prepare = { UploadPreparation.Park("bad") })
        val engine: UploaderEngine = startEngine(backgroundScope, audio, voice, parking)
        val inFlight: String = engine.enqueue(audio, "homework", tag = "session-1")!!
        val parked: String = engine.enqueue(parking, "broken", tag = "session-1")!!
        val otherSession: String = engine.enqueue(audio, "homework", tag = "session-2")!!
        engine.drain()
        val pending: String = engine.enqueue(voice, "message", tag = "session-1")!!
        assertEquals(UploaderItemState.IN_FLIGHT, store.find(inFlight)?.state)
        assertEquals(UploaderItemState.PARKED, store.find(parked)?.state)
        assertEquals(UploaderItemState.PENDING, store.find(pending)?.state)
        transport.events.clear()

        engine.cancelByTag("session-1")

        assertEquals(setOf("cancel:$inFlight", "cancel:$pending"), transport.events.toSet())
        assertEquals(listOf(otherSession), store.items.map { it.id })
    }

    private companion object {
        val WAIT = 10.milliseconds

        fun request(token: String): UploadRequest = UploadRequest(
            url = "https://example.com/upload",
            headers = mapOf("Authorization" to "Bearer $token"),
            fields = listOf(UploadField.Text("a", "b")),
        )
    }

    /** One transport shared by every handler, as an app registers it; records launches and cancels in order. */
    private class SharedTransport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        var failCancelWith: Throwable? = null
        var onLaunch: (String) -> Unit = {}

        override val leaseMillis: Long = 60_000L

        override fun launch(itemId: String, request: UploadRequest) {
            error("UploadHandler must call the re-hand-aware overload")
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += "launch:$itemId"
            onLaunch(itemId)
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
            failCancelWith?.let { throw it }
        }

        fun cancelEvents(): List<String> = events.filter { it.startsWith("cancel:") }

        fun launchEvents(): List<String> = events.filter { it.startsWith("launch:") }
    }

    private class TestUploadHandler(
        transport: UploadTransport,
        override val type: String = "upload",
        private val prepare: suspend (AttemptContext) -> UploadPreparation = { UploadPreparation.Proceed(request("t")) },
    ) : UploadHandler<String>(transport) {

        val classified: MutableList<UploadResult> = mutableListOf()

        override val retryPolicy: RetryPolicy = FixedRetryPolicy()

        override fun encodePayload(payload: String): String = payload

        override fun decodePayload(raw: String): String = raw

        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            prepare(context)

        override suspend fun classify(payload: String, result: UploadResult): SettleResult {
            classified += result
            return defaultUploadClassification(result)
        }
    }
}
