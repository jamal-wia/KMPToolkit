package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadField
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Round 5 (concurrency storm, lifecycle chaos) on real threads: withdrawals of uploads against stores that behave
 * like a hopping SQL store (every call is a suspension point that notices cancellation), and settlements racing
 * withdrawals and replacements of the same item.
 */
class UploaderEngineWithdrawalStormRound5AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class EventLog {
        private val state: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        fun add(event: String) {
            state.update { it + event }
        }

        val all: List<String> get() = state.value
    }

    private class LoggingTransport : UploadTransport {
        val log = EventLog()
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = log.add("launch:$itemId")
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            // A platform enqueue takes a moment before the job exists to be cancelled.
            val mark = TimeSource.Monotonic.markNow()
            while (mark.elapsedNow() < 2.milliseconds) { /* bounded busy wait */ }
            log.add("launch:$itemId")
        }
        override fun cancel(itemId: String) = log.add("cancel:$itemId")
    }

    private object LongPolicy : RetryPolicy {
        override val maxDelayMillis: Long = 60_000L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 60_000L
    }

    private class Hooks {
        val delivered: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        val settled: MutableStateFlow<List<Pair<String, SettleResult>>> = MutableStateFlow(emptyList())
    }

    private class UploadingHandler(transport: UploadTransport, val hooks: Hooks = Hooks()) : UploadHandler<String>(transport) {
        override val type: String = "r5.storm"
        override val retryPolicy: RetryPolicy = LongPolicy
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("a", payload))))

        override suspend fun onDelivered(payload: String) {
            hooks.delivered.update { it + payload }
        }

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            hooks.settled.update { it + (payload to result) }
        }
    }

    /**
     * A store behind a dispatcher hop: every call suspends first and notices a cancelled caller, and the claim
     * yields once more after it wrote, as the return from a hop does.
     */
    private class HoppingStore(private val inner: UploaderStore) : UploaderStore by inner {
        override suspend fun getById(id: String): UploaderItem? {
            yield()
            currentCoroutineContext().ensureActive()
            return inner.getById(id)
        }

        override suspend fun markInFlight(
            id: String,
            leaseUntilEpochMillis: Long,
            expectedState: io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState,
            expectedClaim: Long,
        ): Boolean {
            val claimed: Boolean = inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
            yield()
            return claimed
        }
    }

    private fun realScope(job: Job): CoroutineScope = CoroutineScope(Dispatchers.Default + job)

    private fun realTest(seconds: Int = 90, body: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = (seconds + 10).seconds) { withContext(Dispatchers.Default) { withTimeout(seconds.seconds) { body() } } }

    /** Every launched upload whose row is gone must have a cancel after its last launch. */
    private fun orphans(events: List<String>, gone: Set<String>): List<String> =
        events.filter { it.startsWith("launch:") }.map { it.removePrefix("launch:") }.toSet()
            .filter { it in gone }
            .filter { id -> !events.drop(events.indexOfLast { it == "launch:$id" } + 1).contains("cancel:$id") }

    private suspend fun stormOnce(wrap: (UploaderStore) -> UploaderStore): List<String> {
        val inner = InMemoryUploaderStore()
        val transport = LoggingTransport()
        val handler = UploadingHandler(transport)
        val job = Job()
        val engine: UploaderEngine = createUploaderEngine(wrap(inner), listOf(handler), realScope(job))
        try {
            engine.start()
            coroutineScopeOf(job).run {
                val ids: List<Deferred<String?>> = List(48) { n ->
                    async {
                        val id: String? = engine.enqueue(handler, "p$n", tag = "acct")
                        delay(Random.nextLong(0, 3))
                        if (id != null) {
                            if (n % 4 == 0) engine.cancelByTag("acct") else engine.cancel(id)
                        }
                        id
                    }
                }
                val all: List<String> = ids.awaitAll().filterNotNull()
                delay(300)
                val gone: Set<String> = all.filter { inner.getById(it) == null }.toSet()
                return orphans(transport.log.all, gone)
            }
        } finally {
            engine.close()
            job.cancel()
        }
    }

    private fun coroutineScopeOf(job: Job): CoroutineScope = CoroutineScope(Dispatchers.Default + job)

    @Test
    fun `withdrawals racing the drain over the plain store leave no upload running without its row`() = realTest(120) {
        repeat(25) { iteration ->
            val orphaned: List<String> = stormOnce { it }
            assertEquals(emptyList(), orphaned, "iteration $iteration: withdrawn uploads left running")
        }
    }

    @Test
    fun `a settlement racing a withdrawal and a replacement pairs onDelivered with onSettled once`() = realTest(120) {
        repeat(40) { iteration ->
            val store = InMemoryUploaderStore()
            val transport = LoggingTransport()
            val handler = UploadingHandler(transport)
            val job = Job()
            val engine: UploaderEngine = createUploaderEngine(HoppingStore(store), listOf(handler), realScope(job))
            try {
                val old: String = engine.enqueue(handler, "old", uniqueKey = "k", tag = "acct")!!
                engine.start()
                withTimeout(10.seconds) { while (transport.log.all.none { it == "launch:$old" }) delay(2) }
                UploaderEngineRegistry.register(engine)
                val start = CompletableDeferred<Unit>()
                val calls: List<Deferred<Any?>> = listOf<suspend () -> Any?>(
                    { UploadGateway.complete(old, UploadResult.Completed(201)) },
                    { UploadGateway.complete(old, UploadResult.Completed(201)) },
                    { UploadGateway.complete(old, UploadResult.Completed(500)) },
                    { UploadGateway.complete(old, UploadResult.TransportFailure("x")) },
                    { engine.cancel(old) },
                    { engine.cancelByTag("acct") },
                    { engine.enqueue(handler, "new", uniqueKey = "k", tag = "acct", conflictPolicy = ConflictPolicy.REPLACE) },
                ).shuffled().map { call -> async { start.await(); delay(Random.nextLong(0, 2)); call() } }
                start.complete(Unit)
                calls.awaitAll()
                delay(100)

                val delivered: List<String> = handler.hooks.delivered.value.filter { it == "old" }
                val deliveredSettled: List<String> =
                    handler.hooks.settled.value.filter { it.first == "old" && it.second is SettleResult.Delivered }.map { it.first }
                assertTrue(delivered.size <= 1, "iteration $iteration: onDelivered ran ${delivered.size} times")
                assertEquals(delivered.size, deliveredSettled.size, "iteration $iteration: onDelivered and its onSettled do not pair")
                val events: List<String> = transport.log.all
                if (store.getById(old) == null && "old" !in handler.hooks.delivered.value) {
                    // withdrawn or replaced and never delivered: its upload must have been cancelled after its launch
                    assertTrue(
                        events.drop(events.indexOfLast { it == "launch:$old" } + 1).contains("cancel:$old"),
                        "iteration $iteration: $old was removed undelivered and its upload runs on",
                    )
                }
            } finally {
                engine.close()
                job.cancel()
                UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
            }
        }
    }
}
