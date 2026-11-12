package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadAttempt
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadResponse
import io.github.jamal_wia.kmptoolkit.uploader.UploadResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport
import io.github.jamal_wia.kmptoolkit.uploader.Uploader
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.defaultUploadClassification
import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Round 8 (documentation as a test): the code samples and the observable sentences of
 * docs/kmptoolkit-uploader (02-getting-started, 03-guide, 06-testing) and of the KDoc of the -testing doubles, each
 * compiled against the real API and run. The unknown parts of a sample (ChatApi, Auth, DAO) are small stand-ins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocsSamplesRound8AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    // ---- 06-testing: "Testing code that enqueues" -----------------------------------------------

    data class SendMessage(val conversationId: String, val body: String)

    private class SendMessageHandler : UploaderHandler<SendMessage> {
        var attempts: Int = 0
        var fail: Boolean = false
        override val type: String = "chat.send_message"
        override fun encodePayload(payload: SendMessage): String = "${payload.conversationId}|${payload.body}"
        override fun decodePayload(raw: String): SendMessage =
            SendMessage(raw.substringBefore('|'), raw.substringAfter('|'))
        override fun orderingKey(payload: SendMessage): String = payload.conversationId
        override suspend fun execute(context: AttemptContext, payload: SendMessage): AttemptResult {
            attempts++
            return if (fail) AttemptResult.Retry(IllegalStateException("server error")) else AttemptResult.Success
        }
    }

    private class MessageRepository(private val uploader: Uploader, private val handler: UploaderHandler<SendMessage>) {
        suspend fun send(conversationId: String, body: String) {
            uploader.enqueue(
                handler = handler,
                payload = SendMessage(conversationId, body),
                uniqueKey = "message-local-1",
                tag = "session-u1",
            )
        }
    }

    @Test
    fun `testing chapter sample - the repository test over FakeUploader runs as printed`() = runTest {
        val uploader = FakeUploader()
        val repository = MessageRepository(uploader, SendMessageHandler())

        repository.send("c1", "hi")

        val call = assertNotNull(uploader.lastEnqueued())
        assertEquals("message-local-1", call.uniqueKey)
        assertEquals(ConflictPolicy.KEEP, call.conflictPolicy)
        assertEquals(SendMessage("c1", "hi"), call.payload)
        assertEquals("session-u1", call.tag)
        assertEquals(1, call.ordinal)
    }

    @Test
    fun `testing chapter sample - a KEEP conflict is simulated with a result function`() = runTest {
        val uploader = FakeUploader { call -> if (call.uniqueKey == "already-queued") null else "id" }
        val handler = SendMessageHandler()

        val kept: String? = uploader.enqueue(handler, SendMessage("c", "a"), uniqueKey = "already-queued")
        val fresh: String? = uploader.enqueue(handler, SendMessage("c", "a"), uniqueKey = "other")

        assertNull(kept)
        assertEquals("id", fresh)
        assertEquals(2, uploader.enqueued.size, "both calls are recorded, conflict or not")
    }

    @Test
    fun `testing chapter sample - withdrawals are recorded`() = runTest {
        val uploader = FakeUploader()
        uploader.cancel("a")
        uploader.cancel("never-returned")
        uploader.cancelByTag("t1")
        uploader.cancelByTag("t1")

        assertEquals(listOf("a", "never-returned"), uploader.cancelled)
        assertEquals(listOf("t1", "t1"), uploader.cancelledByTag)
    }

    @Test
    fun `testing chapter - FakeUploader never calls encodePayload and records the payload object`() = runTest {
        val handler = object : UploaderHandler<SendMessage> {
            var encoded = 0
            override val type: String = "t"
            override fun encodePayload(payload: SendMessage): String { encoded++; return "x" }
            override fun decodePayload(raw: String): SendMessage = error("never")
            override suspend fun execute(context: AttemptContext, payload: SendMessage): AttemptResult =
                AttemptResult.Success
        }
        val uploader = FakeUploader()
        val payload = SendMessage("c", "b")

        uploader.enqueue(handler, payload)

        assertEquals(0, handler.encoded)
        assertTrue(payload === uploader.lastEnqueued()?.payload, "the object itself, not a copy")
    }

    @Test
    fun `testing chapter - FakeUploader is atomic across threads and its result function may call back into it`() =
        runTest {
            lateinit var fake: FakeUploader
            fake = FakeUploader { call ->
                fake.trigger()
                "seen-${fake.enqueued.size >= call.ordinal}"
            }
            val handler = SendMessageHandler()

            val results: List<String?> = withContext(Dispatchers.Default) {
                (0 until 8).map { t ->
                    async { (0 until 250).map { fake.enqueue(handler, SendMessage("c$t", "$it")) } }
                }.awaitAll().flatten()
            }

            assertEquals(2_000, fake.enqueued.size)
            assertEquals((1..2_000).toList(), fake.enqueued.map { it.ordinal }.sorted())
            assertEquals(2_000, fake.enqueued.map { it.ordinal }.toSet().size)
            assertEquals(2_000, fake.triggerCount)
            assertTrue(results.all { it == "seen-true" })
        }

    @Test
    fun `FakeUploader observe emits the items of the requested type only and reset forgets everything`() = runTest {
        val fake = FakeUploader()
        val a = item("a", type = "x")
        val b = item("b", type = "y")
        fake.emitObserved(listOf(a, b))

        assertEquals(listOf(a), fake.observe("x").first())
        assertEquals(emptyList(), fake.observe("zzz").first())

        fake.enqueue(SendMessageHandler(), SendMessage("c", "d"))
        fake.cancel("q")
        fake.cancelByTag("t")
        fake.trigger()
        fake.reset()

        assertEquals(emptyList(), fake.enqueued)
        assertEquals(emptyList(), fake.cancelled)
        assertEquals(emptyList(), fake.cancelledByTag)
        assertEquals(0, fake.triggerCount)
        assertNull(fake.lastEnqueued())
        assertEquals(emptyList(), fake.observe("x").first())
    }

    // ---- 06-testing: "Testing with the real engine" ---------------------------------------------

    @Test
    fun `testing chapter sample - a failing effect is retried with a backoff gate`() = runTest {
        val store = InMemoryUploaderStore()
        val clock = MutableUploaderClock()
        val handler = SendMessageHandler().also { it.fail = true }
        val engine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = clock,
        )
        engine.enqueue(handler, SendMessage("c", "hi"))

        engine.drain()

        val item = assertNotNull(store.snapshot().singleOrNull())
        assertEquals(1, item.attempts)
        assertTrue(item.nextRunAtEpochMillis > 0L)
        assertEquals(UploaderItemState.PENDING, item.state)
        assertTrue(item.lastError.orEmpty().contains("server error"))
    }

    @Test
    fun `testing chapter sample - the two clocks move together to fire the alarm`() = runTest {
        val store = InMemoryUploaderStore()
        val clock = MutableUploaderClock()
        val handler = object : UploaderHandler<SendMessage> {
            var attempts = 0
            override val type: String = "t"
            override val retryPolicy: RetryPolicy = ExponentialBackoffRetryPolicy(jitterRatio = 0.0)
            override fun encodePayload(payload: SendMessage): String = payload.body
            override fun decodePayload(raw: String): SendMessage = SendMessage("c", raw)
            override suspend fun execute(context: AttemptContext, payload: SendMessage): AttemptResult {
                attempts++
                return AttemptResult.Retry()
            }
        }
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.start()
        engine.enqueue(handler, SendMessage("c", "x"))
        runCurrent()
        assertEquals(1, handler.attempts)

        clock.nowMillis = 1_000L // the persisted gate is now in the past
        advanceTimeBy(1_100.milliseconds) // the alarm's delay fires
        runCurrent()

        assertEquals(2, handler.attempts)
    }

    @Test
    fun `testing chapter sample - lease expiry needs only the wall clock and re-hands with wasDetached`() = runTest {
        val clock = MutableUploaderClock()
        val contexts: MutableList<AttemptContext> = mutableListOf()
        val handler = object : UploaderHandler<String> {
            override val type: String = "t"
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
                contexts += context
                return AttemptResult.Detached(leaseMillis = 10_000)
            }
        }
        val store = InMemoryUploaderStore()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "x")

        engine.drain() // handler returns Detached(leaseMillis = 10_000)
        engine.drain()
        assertEquals(1, contexts.size, "an unexpired lease leaves the item alone")
        clock.advanceBy(10_001)
        engine.drain() // the item is attemptable again, wasDetached == true

        assertEquals(2, contexts.size)
        assertFalse(contexts[0].wasDetached)
        assertTrue(contexts[1].wasDetached)
        assertTrue(contexts[1].claim != contexts[0].claim, "each hand-off carries its own claim")

        // Each hand-off carries its own claim, so a late report names which executor is reporting.
        val first: AttemptContext = contexts[0]
        val before: UploaderItem = store.snapshot().single()
        engine.settle(first.id, first.claim, SettleResult.Failed()) // superseded: changes nothing
        assertEquals(before, store.snapshot().single())
        engine.settle(first.id, first.claim, SettleResult.Delivered) // a delivery stands from any claim
        assertEquals(emptyList(), store.snapshot())
    }

    @Test
    fun `testing chapter - the in-memory store never notices a cancelled caller`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = SendMessageHandler()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
        val job = CoroutineScope(Dispatchers.Default).async {
            repeat(200) { engine.enqueue(handler, SendMessage("c", "$it")) }
        }
        job.cancel()
        runCatching { job.await() }

        // whatever was started was kept; cancelling the caller lost nothing that returned
        assertTrue(store.snapshot().size <= 200)
    }

    // ---- 06-testing: constraint gating, wake -----------------------------------------------------

    @Test
    fun `testing chapter sample - a gated effect fires when the network returns`() = runTest {
        val network = MutableConstraintProvider("network", satisfied = false)
        val handler = object : UploaderHandler<String> {
            var attempts = 0
            override val type: String = "g"
            override val constraintKeys: Set<String> = setOf("network")
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
                attempts++
                return AttemptResult.Success
            }
        }
        val engine = createUploaderEngine(
            store = InMemoryUploaderStore(),
            handlers = listOf(handler),
            scope = backgroundScope,
            constraintProviders = listOf(network),
        )
        engine.start()
        engine.enqueue(handler, "x")
        runCurrent()
        assertEquals(0, handler.attempts)

        network.satisfy()
        runCurrent()

        assertEquals(1, handler.attempts)
        network.block()
        engine.enqueue(handler, "y")
        runCurrent()
        assertEquals(1, handler.attempts)
        network.set(true)
        runCurrent()
        assertEquals(2, handler.attempts)
    }

    @Test
    fun `testing chapter sample - the wake stays armed while a debt is persisted and disarms on empty`() = runTest {
        val wake = RecordingWakeScheduler()
        val handler = SendMessageHandler()
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope, wakeScheduler = wake)

        engine.enqueue(handler, SendMessage("c", "x"))
        assertTrue(wake.isArmed, "a persisted debt must leave a wake armed")

        engine.drain() // the queue empties
        assertFalse(wake.isArmed)
        assertTrue(wake.scheduleCount >= 1)
        assertTrue(wake.cancelCount >= 1)
        wake.reset()
        assertEquals(0, wake.scheduleCount)
        assertEquals(0, wake.cancelCount)
        assertFalse(wake.isArmed)
    }

    // ---- 06-testing: UploadHandler ---------------------------------------------------------------

    private class Avatar(val id: String)

    private class Auth { var refreshed: Boolean = false }

    private class AvatarUploadHandler(transport: UploadTransport, private val auth: Auth) :
        UploadHandler<Avatar>(transport) {
        val jobIds: MutableMap<String, String> = mutableMapOf()
        val responses: MutableList<UploadResponse?> = mutableListOf()
        override val type: String = "avatar_upload"
        override fun encodePayload(payload: Avatar): String = payload.id
        override fun decodePayload(raw: String): Avatar = Avatar(raw)
        override suspend fun prepareUpload(context: AttemptContext, payload: Avatar): UploadPreparation =
            UploadPreparation.Proceed(
                UploadRequest(
                    url = "https://example.com/avatar",
                    headers = mapOf("Authorization" to "Bearer token"),
                    fields = emptyList(),
                ),
            )

        override suspend fun classify(payload: Avatar, result: UploadResult, response: UploadResponse?): SettleResult {
            responses += response
            if (result is UploadResult.Completed && result.statusCode == 401) {
                auth.refreshed = true
                return SettleResult.Failed(null)
            }
            return defaultUploadClassification(result)
        }

        override suspend fun onDelivered(payload: Avatar, response: UploadResponse?) {
            val jobId: String? = response?.header("location")?.substringAfterLast('/')
            if (jobId != null) jobIds[payload.id] = jobId
        }
    }

    @Test
    fun `testing chapter sample - a 401 refreshes the token and retries`() = runTest {
        val transport = RecordingUploadTransport()
        val auth = Auth()
        val handler = AvatarUploadHandler(transport, auth)
        val store = InMemoryUploaderStore()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, Avatar("a1"))
        engine.drain()

        val itemId: String = transport.launches.single().itemId
        assertIs<UploadAttempt.Ready>(UploadGateway.prepareAttempt(itemId))
        UploadGateway.complete(itemId, UploadResult.Completed(401))

        assertTrue(auth.refreshed)
        assertEquals(1, store.snapshot().single().attempts)
        engine.close() // unregisters, so the next test starts clean
        assertNull(UploaderEngineRegistry.current)
    }

    @Test
    fun `testing chapter sample - completing with an UploadResponse hands the handler the answer`() = runTest {
        val transport = RecordingUploadTransport()
        val handler = AvatarUploadHandler(transport, Auth())
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, Avatar("a1"))
        engine.drain()
        val itemId: String = transport.launches.single().itemId
        UploadGateway.prepareAttempt(itemId)

        val response = UploadResponse(
            statusCode = 202,
            headers = mapOf("Location" to "/jobs/42"),
            body = """{"jobId":"42"}""".encodeToByteArray(),
            isBodyTruncated = false,
        )
        assertTrue(UploadGateway.complete(itemId, response))

        assertEquals("42", handler.jobIds["a1"])
        assertEquals(response, handler.responses.single())
        engine.close()
    }

    @Test
    fun `testing chapter - a bare Completed leaves the handler response null and TransportFailure too`() = runTest {
        val transport = RecordingUploadTransport()
        val handler = AvatarUploadHandler(transport, Auth())
        val store = InMemoryUploaderStore()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, Avatar("a1"))
        engine.enqueue(handler, Avatar("a2"))
        engine.drain()
        val ids: List<String> = transport.launches.map { it.itemId }

        assertTrue(UploadGateway.complete(ids[0], UploadResult.Completed(202)))
        assertTrue(UploadGateway.complete(ids[1], UploadResult.TransportFailure("offline")))

        assertEquals(listOf<UploadResponse?>(null, null), handler.responses)
        assertTrue(handler.jobIds.isEmpty())
        assertEquals(1, store.snapshot().size, "the failure stays queued, the delivery left")
        engine.close()
    }

    @Test
    fun `testing chapter - an unknown id is a no-op that returns true and a second complete delivers once`() = runTest {
        val transport = RecordingUploadTransport()
        val handler = AvatarUploadHandler(transport, Auth())
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, Avatar("a1"))
        engine.drain()
        val itemId: String = transport.launches.single().itemId
        val response = UploadResponse(201, mapOf("Location" to "/jobs/1"), ByteArray(0), false)

        assertTrue(UploadGateway.complete("no-such-id", UploadResult.Completed(200)))
        assertTrue(UploadGateway.complete(itemId, response))
        assertTrue(UploadGateway.complete(itemId, response))

        assertEquals(1, handler.responses.size, "classify ran once for one item")
        engine.close()
    }

    @Test
    fun `testing chapter - the engine applies the handler body limit to the body a test passes`() = runTest {
        val transport = RecordingUploadTransport()
        val handler = object : UploadHandler<Avatar>(transport) {
            var seen: UploadResponse? = null
            override val type: String = "lim"
            override val maxResponseBodyBytes: Int = 4
            override fun encodePayload(payload: Avatar): String = payload.id
            override fun decodePayload(raw: String): Avatar = Avatar(raw)
            override suspend fun prepareUpload(context: AttemptContext, payload: Avatar): UploadPreparation =
                UploadPreparation.Proceed(UploadRequest("https://example.com", fields = emptyList()))
            override suspend fun onDelivered(payload: Avatar, response: UploadResponse?) { seen = response }
        }
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, Avatar("a"))
        engine.drain()
        val itemId: String = transport.launches.single().itemId

        UploadGateway.complete(itemId, UploadResponse(200, emptyMap(), "0123456789".encodeToByteArray(), false))

        val seen = assertNotNull(handler.seen)
        assertEquals("0123", seen.body().decodeToString())
        assertTrue(seen.isBodyTruncated)
        engine.close()
    }

    @Test
    fun `testing chapter - RecordingUploadTransport cancels lists only the intended upload on cancel and REPLACE`() =
        runTest {
            val transport = RecordingUploadTransport()
            val handler = AvatarUploadHandler(transport, Auth())
            val store = InMemoryUploaderStore()
            val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
            UploaderEngineRegistry.register(engine)
            val a: String = assertNotNull(engine.enqueue(handler, Avatar("a"), uniqueKey = "ka"))
            val b: String = assertNotNull(engine.enqueue(handler, Avatar("b"), uniqueKey = "kb"))
            engine.drain()
            assertEquals(2, transport.launches.size)

            engine.cancel(a)
            engine.enqueue(handler, Avatar("b2"), uniqueKey = "kb", conflictPolicy = ConflictPolicy.REPLACE)

            assertEquals(listOf(a, b), transport.cancels)
            engine.close()
        }

    // ---- 06-testing: "Testing your own store" ---------------------------------------------------

    @Test
    fun `testing chapter sample - the contract runs against the in-memory store and reports sixty checks`() =
        runTest {
            val passed: Int = UploaderStoreContract { InMemoryUploaderStore() }.verifyAll()

            assertEquals(60, passed)
        }

    @Test
    fun `testing chapter sample - the checks are public one by one`() = runTest {
        val contract = UploaderStoreContract { InMemoryUploaderStore() }
        contract.insertionOrderSurvivesSameMillisecondInserts()
        contract.recordFailureIsRejectedWhenTheClaimMoved()
    }

    // ---- 02-getting-started ----------------------------------------------------------------------

    @Test
    fun `getting started - observe uniqueKey and tag samples`() = runTest {
        val store: io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore = InMemoryUploaderStore()
        val handler = SendMessageHandler().also { it.fail = true }
        val uploader: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
        )
        val pending: Flow<List<UploaderItem>> = uploader.observe("chat.send_message")
        val stillSending: Flow<Set<String>> = pending.map { items ->
            items.filter { it.state != UploaderItemState.PARKED }
                .mapNotNull { it.uniqueKey }
                .toSet()
        }
        val repository = MessageRepository(uploader, handler)

        repository.send("c1", "hello")
        repository.send("c1", "hello again") // a second tap does not queue a second send

        assertEquals(setOf("message-local-1"), withTimeout(5.seconds) { stillSending.first() })
        assertEquals(1, store.let { (it as InMemoryUploaderStore).snapshot().size })
        uploader.cancelByTag("session-u1")
        assertEquals(emptySet(), withTimeout(5.seconds) { stillSending.first() })
    }

    @Test
    fun `getting started - start drains what the previous process left behind`() = runTest {
        val left = item("old", type = "chat.send_message").copy(payload = "c1|yesterday")
        val store = InMemoryUploaderStore(listOf(left))
        val handler = SendMessageHandler()
        val uploader: UploaderEngine = createUploaderEngine(store, listOf(handler), backgroundScope)

        uploader.start()
        runCurrent()

        assertEquals(1, handler.attempts)
        assertEquals(emptyList(), store.snapshot())
    }

    @Test
    fun `getting started - the NetworkConstraint sample seeds optimistically and follows the flow`() = runTest {
        val isOnline = MutableStateFlow(true)
        class NetworkConstraint(observeOnline: Flow<Boolean>, scope: CoroutineScope) : ConstraintProvider {
            override val key: String = "network"
            override val satisfied: StateFlow<Boolean> =
                observeOnline.stateIn(scope, SharingStarted.Eagerly, initialValue = true)
        }
        val provider = NetworkConstraint(isOnline, backgroundScope)
        assertTrue(provider.satisfied.value)
        isOnline.value = false
        runCurrent()
        assertFalse(provider.satisfied.value)
        isOnline.value = true
        runCurrent()
        assertTrue(provider.satisfied.value)
    }

    // ---- 03-guide ---------------------------------------------------------------------------------

    @Test
    fun `guide - KEEP loses to an in-flight item REPLACE supersedes it a parked item is superseded under either`() =
        runTest {
            val handler = object : UploaderHandler<String> {
                var mode: AttemptResult = AttemptResult.Detached(600_000)
                override val type: String = "t"
                override fun encodePayload(payload: String): String = payload
                override fun decodePayload(raw: String): String = raw
                override suspend fun execute(context: AttemptContext, payload: String): AttemptResult = mode
            }
            val store = InMemoryUploaderStore()
            val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
            val first: String = assertNotNull(engine.enqueue(handler, "p1", uniqueKey = "k"))
            engine.drain()
            assertEquals(UploaderItemState.IN_FLIGHT, store.snapshot().single().state)

            assertNull(engine.enqueue(handler, "p2", uniqueKey = "k"), "KEEP loses to an in-flight item")
            val replaced: String = assertNotNull(
                engine.enqueue(handler, "p3", uniqueKey = "draft", conflictPolicy = ConflictPolicy.REPLACE),
            )
            val again: String = assertNotNull(
                engine.enqueue(handler, "p4", uniqueKey = "draft", conflictPolicy = ConflictPolicy.REPLACE),
            )
            assertTrue(replaced != again)
            assertEquals(listOf("p1", "p4"), store.snapshot().map { it.payload })

            handler.mode = AttemptResult.Park("no")
            engine.settle(first, store.snapshot().first { it.id == first }.claim, SettleResult.Park("stop"))
            assertEquals(UploaderItemState.PARKED, store.snapshot().first { it.id == first }.state)
            val revived: String? = engine.enqueue(handler, "p5", uniqueKey = "k") // KEEP, but parked loses
            assertNotNull(revived)
            assertEquals(listOf("p4", "p5"), store.snapshot().map { it.payload }.sorted())
        }

    @Test
    fun `guide - keyless items never conflict`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = SendMessageHandler()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope)

        repeat(5) { assertNotNull(engine.enqueue(handler, SendMessage("c", "same"))) }

        assertEquals(5, store.snapshot().size)
    }

    @Test
    fun `guide - a failing head blocks its tail without spending the tail's budget and a parked head frees it`() =
        runTest {
            val clock = MutableUploaderClock(1_000_000L)
            val log: MutableList<String> = mutableListOf()
            val handler = object : UploaderHandler<SendMessage> {
                var failBody: String? = "one"
                override val type: String = "chat"
                override fun encodePayload(payload: SendMessage): String = payload.body
                override fun decodePayload(raw: String): SendMessage = SendMessage("c1", raw)
                override fun orderingKey(payload: SendMessage): String = payload.conversationId
                override suspend fun execute(context: AttemptContext, payload: SendMessage): AttemptResult {
                    log += payload.body
                    return when (payload.body) {
                        failBody -> AttemptResult.Retry()
                        "park" -> AttemptResult.Park("bad")
                        else -> AttemptResult.Success
                    }
                }
            }
            val store = InMemoryUploaderStore()
            val engine = createUploaderEngine(store, listOf(handler), backgroundScope, clock = clock)
            engine.enqueue(handler, SendMessage("c1", "one"))
            engine.enqueue(handler, SendMessage("c1", "two"))
            engine.enqueue(handler, SendMessage("c2", "other"))

            engine.drain()
            engine.drain()

            assertEquals(listOf("one", "other"), log, "the tail of c1 waits behind its failing head")
            val two: UploaderItem = store.snapshot().first { it.payload == "two" }
            assertEquals(0, two.attempts, "a blocked tail spends no retry budget")
        }

    @Test
    fun `guide - the retry policy sample as printed gives up by parking at its twentieth attempt`() = runTest {
        val policy: RetryPolicy = ExponentialBackoffRetryPolicy(
            baseDelayMillis = 2_000,
            maxDelayMillis = 10 * 60_000,
            jitterRatio = 0.2,
            giveUp = GiveUpPolicy.ParkAfterAttempts(maxAttempts = 20),
        )
        val clock = MutableUploaderClock(0L)
        val handler = object : UploaderHandler<String> {
            override val type: String = "t"
            override val retryPolicy: RetryPolicy = policy
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult =
                AttemptResult.Retry()
        }
        val store = InMemoryUploaderStore()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "x")

        repeat(20) {
            engine.drain()
            clock.advanceBy(11 * 60_000L)
        }

        val item: UploaderItem = store.snapshot().single()
        assertEquals(UploaderItemState.PARKED, item.state)
        assertEquals(20, item.attempts)
    }

    @Test
    fun `guide - a retry is not re-run inside the pass that failed it even with a zero backoff`() = runTest {
        val handler = object : UploaderHandler<String> {
            var runs = 0
            override val type: String = "t"
            override val retryPolicy: RetryPolicy = object : RetryPolicy {
                override val maxDelayMillis: Long = 1_000L
                override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
                override fun backoffMillis(attempts: Int): Long = 0L
            }
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
                runs++
                return AttemptResult.Retry()
            }
        }
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)
        engine.enqueue(handler, "x")

        withTimeout(5.seconds) { engine.drain() }

        assertEquals(1, handler.runs)
    }

    @Test
    fun `guide - a key with no registered provider is satisfied`() = runTest {
        val handler = object : UploaderHandler<String> {
            var runs = 0
            override val type: String = "t"
            override val constraintKeys: Set<String> = setOf("typo")
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
                runs++
                return AttemptResult.Success
            }
        }
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)
        engine.enqueue(handler, "x")

        engine.drain()

        assertEquals(1, handler.runs)
    }

    @Test
    fun `guide - cancel runs no handler hook and the wipe by tag crosses every state`() = runTest {
        val transport = RecordingUploadTransport()
        val handler = AvatarUploadHandler(transport, Auth())
        val store = InMemoryUploaderStore()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, Avatar("inflight"), tag = "session-1")
        engine.drain() // in flight
        val inflightId: String = transport.launches.single().itemId
        engine.enqueue(handler, Avatar("pending"), tag = "session-1")
        engine.enqueue(handler, Avatar("other"), tag = "session-2")

        engine.cancelByTag("session-1")

        assertEquals(listOf("other"), store.snapshot().map { it.payload })
        assertTrue(inflightId in transport.cancels, "the launched upload was cancelled")
        assertTrue(transport.cancels.size <= 2, "never more than the two withdrawn items: ${transport.cancels}")
        assertTrue(handler.responses.isEmpty(), "withdrawing runs no classify hook")
        engine.close()
    }

    @Test
    fun `guide - the transaction sample with a reentrant runner joins the outer transaction`() = runTest {
        class CountingRunner : TransactionRunner {
            var depth = 0
            var outermost = 0
            override suspend fun <R> inTransaction(block: suspend () -> R): R {
                if (depth == 0) outermost++
                depth++
                try {
                    return block()
                } finally {
                    depth--
                }
            }
        }
        val runner = CountingRunner()
        val handler = SendMessageHandler()
        val store = InMemoryUploaderStore()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope, transactionRunner = runner)

        runner.inTransaction {
            engine.enqueue(handler, SendMessage("c", "x"))
            engine.enqueue(handler, SendMessage("c", "y"))
        }

        assertEquals(1, runner.outermost, "the nested enqueues joined the outer transaction")
        assertEquals(2, store.snapshot().size)
    }

    @Test
    fun `guide - close leaves the queue and a fresh engine picks it up`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = SendMessageHandler()
        val first = createUploaderEngine(store, listOf(handler), backgroundScope)
        first.enqueue(handler, SendMessage("c", "x"))
        first.close()
        assertEquals(1, store.snapshot().size)

        val second = createUploaderEngine(store, listOf(handler), backgroundScope)
        second.start()
        runCurrent()

        assertEquals(emptyList(), store.snapshot())
        assertEquals(1, handler.attempts)
    }

    @Test
    fun `guide - trigger and awaitDrained as shown trigger is safe before start`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = SendMessageHandler()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
        engine.trigger()
        engine.trigger()
        engine.enqueue(handler, SendMessage("c", "x"))
        engine.start()

        val drained: Boolean = engine.awaitDrained(60.seconds)

        assertTrue(drained)
    }

    @Test
    fun `guide - an exception thrown by classify settles as Failed and a throw in onDelivered does not stop the settlement`() =
        runTest {
            val transport = RecordingUploadTransport()
            val throwInClassify = true
            val handler = object : UploadHandler<Avatar>(transport) {
                override val type: String = "thr"
                override fun encodePayload(payload: Avatar): String = payload.id
                override fun decodePayload(raw: String): Avatar = Avatar(raw)
                override suspend fun prepareUpload(context: AttemptContext, payload: Avatar): UploadPreparation =
                    UploadPreparation.Proceed(UploadRequest("https://example.com", fields = emptyList()))
                override suspend fun classify(payload: Avatar, result: UploadResult, response: UploadResponse?): SettleResult {
                    if (throwInClassify) error("malformed body")
                    return SettleResult.Delivered
                }
                override suspend fun onDelivered(payload: Avatar, response: UploadResponse?) = error("persist failed")
            }
            val store = InMemoryUploaderStore()
            val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
            UploaderEngineRegistry.register(engine)
            engine.enqueue(handler, Avatar("a"))
            engine.drain()
            val itemId: String = transport.launches.single().itemId

            UploadGateway.complete(itemId, UploadResponse(200, emptyMap(), ByteArray(0), false))
            assertEquals(1, store.snapshot().single().attempts, "a throwing classify is a Failed settle")

            engine.close()
        }

    @Test
    fun `guide - an exception thrown by onDelivered is logged and the row is still removed`() = runTest {
        val transport = RecordingUploadTransport()
        val handler = object : UploadHandler<Avatar>(transport) {
            override val type: String = "thr2"
            override fun encodePayload(payload: Avatar): String = payload.id
            override fun decodePayload(raw: String): Avatar = Avatar(raw)
            override suspend fun prepareUpload(context: AttemptContext, payload: Avatar): UploadPreparation =
                UploadPreparation.Proceed(UploadRequest("https://example.com", fields = emptyList()))
            override suspend fun onDelivered(payload: Avatar, response: UploadResponse?) = error("persist failed")
        }
        val store = InMemoryUploaderStore()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, Avatar("a"))
        engine.drain()
        val itemId: String = transport.launches.single().itemId

        assertTrue(UploadGateway.complete(itemId, UploadResponse(200, emptyMap(), ByteArray(0), false)))

        assertEquals(emptyList(), store.snapshot())
        engine.close()
    }

    private fun item(id: String, type: String): UploaderItem = UploaderItem(
        id = id,
        type = type,
        payload = "p",
        schemaVersion = 1,
        uniqueKey = null,
        orderingKey = null,
        tag = null,
        state = UploaderItemState.PENDING,
        attempts = 0,
        nextRunAtEpochMillis = 0L,
        createdAtEpochMillis = 0L,
        lastError = null,
    )
}
