package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploadField
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Many callers on real threads against the real engine over [InMemoryUploaderStore]: unique-key races,
 * hand-offs displaced by a REPLACE, a settle racing a withdrawal, concurrent starts, concurrent closes.
 *
 * Hazard: several features enqueue, settle and cancel at once on `Dispatchers.Default`. Every double in
 * here is thread-safe on its own; the engine and the store are the only things under test.
 */
@OptIn(ExperimentalAtomicApi::class)
class UploaderEngineConcurrencyAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    // --- doubles ---------------------------------------------------------------------------------

    private class EventLog {
        private val state: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        fun add(event: String) {
            state.update { it + event }
        }

        val all: List<String> get() = state.value
    }

    private class ConcurrentTransport : UploadTransport {
        val log = EventLog()
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            log.add("launch:$itemId")
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            log.add("launch:$itemId")
        }

        override fun cancel(itemId: String) {
            log.add("cancel:$itemId")
        }
    }

    private object OneSecondPolicy : RetryPolicy {
        override val maxDelayMillis: Long = 1_000L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 1_000L
    }

    private class UploadingHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "adv.upload"
        override val retryPolicy: RetryPolicy = OneSecondPolicy
        val delivered: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        val settled = AtomicInt(0)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("a", payload))))

        override suspend fun onDelivered(payload: String) {
            delivered.update { it + payload }
        }

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            settled.fetchAndIncrement()
        }
    }

    private class CountingHandler : UploaderHandler<String> {
        override val type: String = "adv.plain"
        override val retryPolicy: RetryPolicy = OneSecondPolicy
        val executed: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            executed.update { it + payload }
            return AttemptResult.Success
        }
    }

    private fun realScope(job: Job = Job()): CoroutineScope = CoroutineScope(Dispatchers.Default + job)

    /** Runs [body] on real threads, failing after [seconds] of real time. */
    private fun realTest(seconds: Int = 60, body: suspend CoroutineScope.() -> Unit) = runTest(timeout = (seconds + 10).seconds) {
        withContext(Dispatchers.Default) { withTimeout(seconds.seconds) { body() } }
    }

    /** Releases [count] callers at the same instant and collects their results. */
    private suspend fun <T> CoroutineScope.together(count: Int, call: suspend (Int) -> T): List<T> {
        val start = CompletableDeferred<Unit>()
        val callers: List<Deferred<T>> = List(count) { index ->
            async { start.await(); call(index) }
        }
        start.complete(Unit)
        return callers.awaitAll()
    }

    private suspend fun awaitCondition(what: String, condition: () -> Boolean) {
        withTimeout(15.seconds) { while (!condition()) delay(5) }
    }

    // --- tests -----------------------------------------------------------------------------------

    @Test
    fun `sixty-four concurrent KEEP enqueues of one key insert one row and exactly one caller gets its id`() = realTest {
        repeat(50) { iteration ->
            val store = InMemoryUploaderStore()
            val handler = CountingHandler()
            val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), realScope())

            val ids: List<String?> = together(64) { engine.enqueue(handler, "p$it", uniqueKey = "same") }

            val winners: List<String> = ids.filterNotNull()
            assertEquals(1, winners.size, "iteration $iteration: ${winners.size} callers were told their item was inserted")
            assertEquals(listOf(winners.single()), store.snapshot().map { it.id }, "iteration $iteration: rows != the winner")
            engine.close()
        }
    }

    @Test
    fun `sixty-four concurrent REPLACE enqueues leave one live row and every displaced hand-off is cancelled after its launch`() =
        realTest(120) {
            repeat(15) { iteration ->
                val store = InMemoryUploaderStore()
                val transport = ConcurrentTransport()
                val handler = UploadingHandler(transport)
                val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), realScope())
                engine.start()
                val first: String = assertNotNull(engine.enqueue(handler, "first", uniqueKey = "k"))
                awaitCondition("first launched") { "launch:$first" in transport.log.all }

                val replacementIds: List<String?> = together(64) {
                    engine.enqueue(handler, "r$it", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE)
                }
                delay(300) // let the started engine finish handing off whatever survived

                val live: List<UploaderItem> = store.snapshot().filter { it.uniqueKey == "k" }
                assertEquals(1, live.size, "iteration $iteration: ${live.size} live rows for one unique key")
                val survivor: String = live.single().id
                val all: List<String> = listOf(first) + replacementIds.map { assertNotNull(it, "REPLACE always inserts") }
                assertTrue(survivor in all)
                val events: List<String> = transport.log.all
                for (id in all - survivor) {
                    val lastLaunch: Int = events.indexOfLast { it == "launch:$id" }
                    if (lastLaunch >= 0) {
                        assertTrue(
                            events.drop(lastLaunch + 1).any { it == "cancel:$id" },
                            "iteration $iteration: displaced item $id was launched and never cancelled afterwards",
                        )
                    }
                }
                engine.close()
            }
        }

    @Test
    fun `a settle racing a withdrawal of the same item has exactly one terminal effect over three hundred iterations`() =
        realTest(120) {
            repeat(300) { iteration ->
                val store = InMemoryUploaderStore()
                val transport = ConcurrentTransport()
                val handler = UploadingHandler(transport)
                val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), realScope())
                UploaderEngineRegistry.register(engine)
                val id: String = assertNotNull(engine.enqueue(handler, "p"))
                engine.drain()
                assertEquals(1, transport.log.all.count { it == "launch:$id" })

                val start = CompletableDeferred<Unit>()
                val settle: Deferred<Boolean> = async { start.await(); UploadGateway.complete(id, UploadResult.Completed(200)) }
                val cancel: Deferred<Unit> = async { start.await(); engine.cancel(id) }
                start.complete(Unit)
                settle.await()
                cancel.await()

                assertTrue(handler.delivered.value.size <= 1, "iteration $iteration: onDelivered ran ${handler.delivered.value.size} times")
                assertTrue(handler.settled.load() <= 1, "iteration $iteration: onSettled ran ${handler.settled.load()} times")
                assertEquals(
                    handler.delivered.value.size,
                    handler.settled.load(),
                    "iteration $iteration: a delivery must be followed by exactly one settled hook",
                )
                assertEquals(emptyList(), store.snapshot(), "iteration $iteration: the item stayed in the queue")
                UploaderEngineRegistry.unregister(engine)
                engine.close()
            }
        }

    @Test
    fun `sixteen concurrent starts run every item exactly once`() = realTest {
        repeat(30) { iteration ->
            val store = InMemoryUploaderStore()
            val handler = CountingHandler()
            val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), realScope())
            repeat(20) { engine.enqueue(handler, "i$it") }

            together(16) { engine.start() }
            awaitCondition("drained") { store.snapshot().isEmpty() }
            delay(20)

            assertEquals(
                (0 until 20).map { "i$it" }.sorted(),
                handler.executed.value.sorted(),
                "iteration $iteration: items ran a wrong number of times after concurrent starts",
            )
            engine.close()
        }
    }

    @Test
    fun `sixty-four uploads completed at once are each delivered exactly once and the queue empties`() = realTest {
        val store = InMemoryUploaderStore()
        val transport = ConcurrentTransport()
        val handler = UploadingHandler(transport)
        val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), realScope())
        UploaderEngineRegistry.register(engine)
        val ids: List<String> = List(64) { assertNotNull(engine.enqueue(handler, "p$it")) }
        engine.drain()
        assertEquals(64, transport.log.all.count { it.startsWith("launch:") })

        val results: List<Boolean> = together(64) { UploadGateway.complete(ids[it], UploadResult.Completed(200)) }

        assertTrue(results.all { it })
        assertEquals((0 until 64).map { "p$it" }.sorted(), handler.delivered.value.sorted())
        assertEquals(64, handler.settled.load())
        assertEquals(emptyList(), store.snapshot())
        engine.close()
    }

    @Test
    fun `starting and closing from two threads at once leaves no coroutine behind`() = realTest {
        repeat(100) { iteration ->
            val parent = Job()
            val engine: UploaderEngine = createUploaderEngine(InMemoryUploaderStore(), listOf(CountingHandler()), realScope(parent))

            val start = CompletableDeferred<Unit>()
            val starter: Deferred<Unit> = async { start.await(); engine.start() }
            val closer: Deferred<Unit> = async { start.await(); engine.close() }
            start.complete(Unit)
            starter.await()
            closer.await()
            engine.close()

            withTimeout(5.seconds) { while (parent.children.any()) delay(5) }
            assertTrue(parent.isActive, "iteration $iteration: parent scope must survive the engine")
            engine.start() // a start after close is inert
            delay(1)
            assertEquals(0, parent.children.count(), "iteration $iteration: a start after close launched a coroutine")
            parent.cancel()
        }
    }

    @Test
    fun `enqueues racing a close are never lost and never throw`() = realTest {
        repeat(30) { iteration ->
            val store = InMemoryUploaderStore()
            val handler = CountingHandler()
            val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), realScope())
            engine.start()

            val start = CompletableDeferred<Unit>()
            val closer: Deferred<Unit> = async { start.await(); delay(1); engine.close() }
            val callers: List<Deferred<String?>> = List(64) { async { start.await(); engine.enqueue(handler, "p$it") } }
            start.complete(Unit)
            val ids: List<String> = callers.awaitAll().filterNotNull()
            closer.await()

            val executed: Set<String> = handler.executed.value.toSet()
            val stored: Set<String> = store.snapshot().map { it.payload }.toSet()
            assertEquals(64, ids.size, "iteration $iteration: an enqueue reported no insert")
            assertEquals((0 until 64).map { "p$it" }.toSet(), executed + stored, "iteration $iteration: an enqueued item vanished")
        }
    }
}
