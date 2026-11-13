package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploadField
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import kotlinx.coroutines.yield
import org.junit.runner.RunWith

/**
 * Round 3 (concurrency-storm) over a real SQLite file: enqueue, replace, withdraw and wipe storms, several
 * engines over one storage, awaitDrained under contention, and an outer transaction that rolls back after
 * the engine has already cancelled an upload.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStorageStormRound3AdversarialTest {
    private val seed: Long = testSeed(20261113L)

    private val storages: MutableList<UploaderStorage> = mutableListOf()
    private val engines: MutableList<UploaderEngine> = mutableListOf()
    private val jobs: MutableList<Job> = mutableListOf()
    private val files: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
        engines.forEach { it.close() }
        jobs.forEach { it.cancel() }
        storages.forEach { runCatching { it.close() } }
        files.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    private class Transport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        val events: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        override fun launch(itemId: String, request: UploadRequest) {
            events.update { it + "launch:$itemId" }
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events.update { it + "launch:$itemId" }
        }

        override fun cancel(itemId: String) {
            events.update { it + "cancel:$itemId" }
        }
    }

    private object QuickPolicy : RetryPolicy {
        override val maxDelayMillis: Long = 50L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 20L
    }

    private class PlainHandler : UploaderHandler<String> {
        override val type: String = "adv.sql.plain"
        override val retryPolicy: RetryPolicy = QuickPolicy
        val executed: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            executed.update { it + payload }
            return AttemptResult.Success
        }
    }

    private class SqlUploadHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "adv.sql.upload"
        override val retryPolicy: RetryPolicy = QuickPolicy
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("a", payload))))
    }

    private fun open(): UploaderStorage {
        // timing-hygiene-ignore: TH3 name a unique database file name, not a scenario choice
        val name = "adv.a1r3.${Random.nextLong(0L, Long.MAX_VALUE)}"
        files += name
        return createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
    }

    private fun engineOver(storage: UploaderStorage, handler: UploaderHandler<*>): UploaderEngine {
        val job = Job().also { jobs += it }
        return createUploaderEngine(
            store = storage.store,
            handlers = listOf(handler),
            scope = CoroutineScope(Dispatchers.Default + job),
            transactionRunner = storage.transactionRunner,
        ).also { engines += it }
    }

    private fun realTest(seconds: Int = 90, body: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = (seconds + 10).seconds) { withContext(Dispatchers.Default) { withTimeout(seconds.seconds) { body() } } }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(20.seconds) { while (!condition()) delay(5) }
    }

    private fun dbThreads(): Int = Thread.getAllStackTraces().keys.count { it.name == "kmptoolkit-uploader-db" && it.isAlive }

    @Test
    fun `an enqueue replace withdraw and wipe storm over SQLite drains to empty without a payload running twice`() = realTest {
        val storage: UploaderStorage = open()
        val handler = PlainHandler()
        val engine: UploaderEngine = engineOver(storage, handler)
        engine.start()
        val start = CompletableDeferred<Unit>()
        val enqueuers: List<Deferred<Unit>> = List(6) { w ->
            async {
                start.await()
                repeat(80) { i ->
                    engine.enqueue(
                        handler,
                        "s$w-$i",
                        uniqueKey = if (i % 3 == 0) "k${i % 4}" else null,
                        tag = "t${i % 3}",
                        conflictPolicy = if (i % 2 == 0) ConflictPolicy.REPLACE else ConflictPolicy.KEEP,
                    )
                }
            }
        }
        val cancellers: List<Deferred<Unit>> = List(3) { n ->
            val random = Random(seed + n) // one Random per canceller: none is shared between coroutines
            async {
                start.await()
                repeat(40) { step ->
                    if (step % 2 == 0) engine.cancelByTag("t${(n + step) % 3}") else storage.store.getAllActive().randomOrNull(random)?.let { engine.cancel(it.id) }
                    yield()
                }
            }
        }
        start.complete(Unit)
        (enqueuers + cancellers).awaitAll()

        assertTrue(engine.awaitDrained(30.seconds), "the queue never drained: ${storage.store.getAllActive().size} left (seed=$seed)")
        val ran: List<String> = handler.executed.value
        assertEquals(ran.size, ran.toSet().size, "a payload ran twice (seed=$seed)")
        engine.close()
        storage.close()
        awaitCondition { dbThreads() == 0 }
    }

    @Test
    fun `four engines over one storage hand each of four hundred uploads off exactly once`() = realTest {
        val storage: UploaderStorage = open()
        val transport = Transport()
        val handler = SqlUploadHandler(transport)
        val fleet: List<UploaderEngine> = List(4) { engineOver(storage, handler) }
        fleet.forEach { it.start() }
        val start = CompletableDeferred<Unit>()
        val ids: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        val producers: List<Deferred<Unit>> = List(4) { w ->
            async {
                start.await()
                repeat(100) { i ->
                    val id: String? = fleet[(w + i) % fleet.size].enqueue(handler, "u$w-$i")
                    if (id != null) ids.update { it + id }
                    yield()
                }
            }
        }
        start.complete(Unit)
        producers.awaitAll()
        awaitCondition { transport.events.value.count { it.startsWith("launch:") } >= 400 }
        delay(300)

        val launches: Map<String, Int> = transport.events.value.filter { it.startsWith("launch:") }.groupingBy { it }.eachCount()
        assertEquals(400, ids.value.size)
        val doubled: Map<String, Int> = launches.filterValues { it > 1 }
        assertEquals(emptyMap(), doubled, "items handed off more than once under an unexpired lease")
    }

    @Test
    fun `thirty awaitDrained callers over SQLite during an enqueue stream all see true once it stops`() = realTest {
        val storage: UploaderStorage = open()
        val handler = PlainHandler()
        val engine: UploaderEngine = engineOver(storage, handler)
        engine.start()
        val start = CompletableDeferred<Unit>()
        val producers: List<Deferred<Unit>> = List(4) { w -> async { start.await(); repeat(60) { engine.enqueue(handler, "a$w-$it"); yield() } } }
        val early: List<Deferred<Boolean>> = List(8) { async { start.await(); engine.awaitDrained(kotlin.time.Duration.ZERO) } }
        start.complete(Unit)
        early.awaitAll()
        producers.awaitAll()
        val waiters: List<Deferred<Boolean>> = List(30) { async { engine.awaitDrained(30.seconds) } }
        assertTrue(waiters.awaitAll().all { it })
        assertEquals(240, handler.executed.value.size)
    }

    @Test
    fun `a wipe racing the hand-off of two hundred tagged uploads cancels every upload it launched`() = realTest {
        repeat(3) { iteration ->
            val storage: UploaderStorage = open()
            val transport = Transport()
            val handler = SqlUploadHandler(transport)
            val engine: UploaderEngine = engineOver(storage, handler)
            val ids: List<String> = List(200) { assertNotNull(engine.enqueue(handler, "w$it", tag = "acct")) }
            engine.start()
            delay(Random(seed + iteration).nextLong(0, 15))
            engine.cancelByTag("acct")
            delay(300) // let a drain that read the queue before the wipe finish whatever it does

            assertEquals(emptyList(), storage.store.getAllActive().map { it.id }, "iteration $iteration: rows survived the wipe (seed=$seed)")
            val events: List<String> = transport.events.value
            for (id in ids) {
                val lastLaunch: Int = events.indexOfLast { it == "launch:$id" }
                if (lastLaunch >= 0) {
                    assertTrue(
                        events.drop(lastLaunch + 1).any { it == "cancel:$id" },
                        "iteration $iteration: $id was launched and never cancelled after the wipe (seed=$seed)",
                    )
                }
            }
            engine.close()
        }
    }

    @Test
    fun `eight threads opening a fresh database file at the same moment all end up with a working storage`() = realTest {
        // timing-hygiene-ignore: TH3 name a unique database file name, not a scenario choice
        val name = "adv.a1r3.open.${Random.nextLong(0L, Long.MAX_VALUE)}"
        files += name
        val start = CompletableDeferred<Unit>()
        val openers: List<Deferred<Result<UploaderStorage>>> = List(8) {
            async {
                start.await()
                runCatching { createUploaderStorage(application(), UploaderDatabaseConfig(name)) }
            }
        }
        start.complete(Unit)
        val results: List<Result<UploaderStorage>> = openers.awaitAll()
        results.forEach { r -> r.getOrNull()?.let { storages += it } }

        val failures: List<String> = results.mapNotNull { it.exceptionOrNull()?.let { e -> "${e::class.simpleName}: ${e.message}" } }
        assertEquals(emptyList(), failures, "concurrent first opens of one file must all succeed")
        val handler = PlainHandler()
        results.map { it.getOrThrow() }.forEachIndexed { index, storage ->
            val engine: UploaderEngine = engineOver(storage, handler)
            assertNotNull(engine.enqueue(handler, "o$index"))
        }
        assertEquals(8, storages.first().store.getAllActive().size)
    }

    @Test
    fun `payload unique key and tag come back from SQLite exactly as written`() = realTest {
        val storage: UploaderStorage = open()
        val handler = PlainHandler()
        val engine: UploaderEngine = engineOver(storage, handler)
        val awkward: List<String> = listOf(
            "nul\u0000inside",
            "emoji \uD83D\uDE00 pair",
            "quote ' and \" and % and _ and \\",
            "line\r\nbreak\ttab",
        )
        awkward.forEachIndexed { index, text ->
            val id: String = assertNotNull(engine.enqueue(handler, text, uniqueKey = "u:$text", tag = "g:$text"), "case $index")
            val row = assertNotNull(storage.store.getById(id), "case $index")
            assertEquals(text, row.payload, "payload of case $index changed in storage")
            assertEquals("u:$text", row.uniqueKey, "unique key of case $index changed in storage")
            assertEquals("g:$text", row.tag, "tag of case $index changed in storage")
        }
        // The identity lookup must find the row it just wrote.
        awkward.forEachIndexed { index, text ->
            assertNull(engine.enqueue(handler, "again", uniqueKey = "u:$text"), "case $index: KEEP did not find its own key")
        }
    }

    @Test
    fun `eight threads closing one storage at once all return and release the database thread`() = realTest {
        val storage: UploaderStorage = open()
        val handler = PlainHandler()
        val engine: UploaderEngine = engineOver(storage, handler)
        engine.enqueue(handler, "p")
        engine.close()
        val before: Int = dbThreads()
        assertTrue(before >= 1)
        val start = CompletableDeferred<Unit>()
        val closers: List<Deferred<Result<Unit>>> = List(8) { async { start.await(); runCatching { storage.close() } } }
        start.complete(Unit)
        val results: List<Result<Unit>> = closers.awaitAll()

        assertEquals(emptyList(), results.mapNotNull { it.exceptionOrNull()?.toString() })
        awaitCondition { dbThreads() < before }
    }

    @Test
    fun `four threads healing the same corrupt file at once leave no acknowledged write lost`() = realTest {
        repeat(5) { iteration ->
            // timing-hygiene-ignore: TH3 name a unique database file name, not a scenario choice
            val name = "adv.a1r3.heal.$iteration.${Random.nextLong(0L, Long.MAX_VALUE)}"
            files += name
            val file = application().getDatabasePath(uploaderDatabaseFileName(name)).also { it.parentFile?.mkdirs() }
            file.writeBytes(ByteArray(8_192) { (it * 7).toByte() })
            val config = UploaderDatabaseConfig(name, recreateOnCorruption = true)
            val start = CompletableDeferred<Unit>()
            val handler = PlainHandler()
            val workers: List<Deferred<Result<String?>>> = List(4) { w ->
                async {
                    start.await()
                    runCatching {
                        val storage: UploaderStorage = createUploaderStorage(application(), config).also { storages += it }
                        val engine: UploaderEngine = engineOver(storage, handler)
                        engine.enqueue(handler, "w$w")
                    }
                }
            }
            start.complete(Unit)
            val results: List<Result<String?>> = workers.awaitAll()
            val acknowledged: List<String> = results.mapNotNull { it.getOrNull() }
            assertTrue(
                acknowledged.isNotEmpty(),
                "iteration $iteration: no opener succeeded: ${results.map { it.exceptionOrNull()?.toString() }}",
            )

            val reader: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
            val rows: List<String> = reader.store.getAllActive().map { it.id }
            assertEquals(
                emptyList(),
                acknowledged.filter { it !in rows },
                "iteration $iteration: enqueues acknowledged to their callers are not in the file",
            )
        }
    }

    // --- fixer round 3 ---

    @Test
    fun `a failure whose message is three megabytes cannot poison the queue for every later read`() = realTest {
        val storage: UploaderStorage = open()
        val handler = BigErrorHandler(3_000_000)
        val engine: UploaderEngine = engineOver(storage, handler)
        assertNotNull(engine.enqueue(handler, "p"))
        engine.drain()

        val readable: Result<Int> = runCatching { storage.store.getAllActive().size }

        assertTrue(readable.isSuccess, "the queue can no longer be read after one failed attempt: ${readable.exceptionOrNull()}")
        assertEquals(1, readable.getOrNull())
    }


    private class BigErrorHandler(private val size: Int) : UploaderHandler<String> {
        override val type: String = "adv.sql.bigerror"
        override val retryPolicy: RetryPolicy = QuickPolicy
        val executed: MutableStateFlow<Int> = MutableStateFlow(0)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            executed.update { it + 1 }
            return if (executed.value == 1) AttemptResult.Retry(IllegalStateException("e".repeat(size))) else AttemptResult.Success
        }
    }

    // --- fixer round 3, group 7 ---

    @Test
    fun `one oversized payload does not make the whole queue unreadable for every other item`() = realTest {
        val storage: UploaderStorage = open()
        val handler = PlainHandler()
        val engine: UploaderEngine = engineOver(storage, handler)
        val outcome: Result<String?> = runCatching { engine.enqueue(handler, "x".repeat(3_000_000)) }
        // Either answer is honest: refuse at the door, or accept and keep the queue usable.
        val refused: Boolean = outcome.isFailure
        engine.enqueue(handler, "normal")

        val readable: Result<Int> = runCatching { storage.store.getAllActive().size }
        engine.start()
        runCatching { engine.awaitDrained(5.seconds) }

        assertTrue(
            refused || ("normal" in handler.executed.value),
            "an accepted 3 MB payload made the queue unreadable (${readable.exceptionOrNull()?.cause?.message}); " +
                "the normal item never ran and cannot even be withdrawn",
        )
    }

    @Test
    fun `an enqueue on a closed storage fails with an error the caller can tell from its own cancellation`() = realTest {
        val storage: UploaderStorage = open()
        val handler = PlainHandler()
        val engine: UploaderEngine = engineOver(storage, handler)
        storage.close()

        val failure: Throwable? = runCatching { engine.enqueue(handler, "late") }.exceptionOrNull()

        assertTrue(failure != null, "an enqueue over a closed storage reported success")
        assertTrue(
            failure !is kotlinx.coroutines.CancellationException,
            "the caller was not cancelled, but its enqueue ended in $failure, which a coroutine treats as its own cancellation",
        )
    }

    @Test
    fun `a payload just under the row limit is accepted and read back`() = realTest {
        val storage: UploaderStorage = open()
        val handler = PlainHandler()
        val engine: UploaderEngine = engineOver(storage, handler)
        val payload: String = "x".repeat(900_000)

        assertNotNull(engine.enqueue(handler, payload))

        val rows = storage.store.getAllActive()
        assertEquals(1, rows.size)
        assertEquals(payload, rows.single().payload)
    }

    @Test
    fun `an enqueue racing close never fails as a cancellation`() = realTest {
        repeat(20) { iteration ->
            val storage: UploaderStorage = open()
            val handler = PlainHandler()
            val engine: UploaderEngine = engineOver(storage, handler)
            val start = CompletableDeferred<Unit>()
            val enqueues: List<Deferred<Throwable?>> = List(4) {
                async { start.await(); runCatching { engine.enqueue(handler, "p") }.exceptionOrNull() }
            }
            val closer: Deferred<Unit> = async { start.await(); storage.close() }
            start.complete(Unit)
            closer.await()
            enqueues.awaitAll().filterNotNull().forEach { failure ->
                assertTrue(
                    failure is UploaderStorageException,
                    "iteration $iteration: an enqueue racing close failed as $failure, not as UploaderStorageException",
                )
            }
        }
    }
}
