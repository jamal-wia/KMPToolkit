package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
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
import io.github.jamal_wia.kmptoolkit.uploader.UploaderClock
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.testing.MutableUploaderClock
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.junit.runner.RunWith

/**
 * Restart, re-initialisation and concurrent callers over a real SQLite file: a process that dies with an
 * item handed off, an engine and a storage rebuilt over the same file, two storages open on one file at
 * once, and a storage closed while statements are running.
 *
 * Process death is modelled by abandoning engine A: it is never closed, its coroutines are cancelled (a
 * gentler model than a kill, since cancellation runs finally blocks), its storage stays open, the registry
 * is reset, and engine B is built over a second storage on the same file.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStorageRestartAdversarialTest {

    private val openStorages: MutableList<UploaderStorage> = mutableListOf()
    private val openEngines: MutableList<UploaderEngine> = mutableListOf()
    private val files: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
        openEngines.forEach { it.close() }
        openStorages.forEach { it.close() }
        files.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    // --- doubles ---------------------------------------------------------------------------------

    private class SafeTransport(override val leaseMillis: Long = LEASE) : UploadTransport {
        val events: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        override fun launch(itemId: String, request: UploadRequest) {
            events.update { it + "launch:$itemId:false" }
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events.update { it + "launch:$itemId:$isRehandOff" }
        }

        override fun cancel(itemId: String) {
            events.update { it + "cancel:$itemId" }
        }

        companion object {
            const val LEASE: Long = 60_000L
        }
    }

    private object OneSecondPolicy : RetryPolicy {
        override val maxDelayMillis: Long = 1_000L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 1_000L
    }

    private class UploadingHandler(
        transport: UploadTransport,
        private val channel: String? = null,
    ) : UploadHandler<String>(transport) {
        override val type: String = "adv.upload"
        override fun orderingKey(payload: String): String? = channel
        override val retryPolicy: RetryPolicy = OneSecondPolicy
        val delivered: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        val settled: MutableStateFlow<Int> = MutableStateFlow(0)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("a", payload))))

        override suspend fun onDelivered(payload: String) {
            delivered.update { it + payload }
        }

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            settled.update { it + 1 }
        }
    }

    private class FailingHandler(override val retryPolicy: RetryPolicy) : UploaderHandler<String> {
        override val type: String = "adv.failing"
        val attempts: MutableStateFlow<List<Int>> = MutableStateFlow(emptyList())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            attempts.update { it + context.attempts }
            return AttemptResult.Retry(IllegalStateException("no"))
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun newConfig(): UploaderDatabaseConfig {
        val name = "adv.a1.${Random.nextLong(0L, Long.MAX_VALUE)}"
        files += name
        return UploaderDatabaseConfig(name)
    }

    private fun open(config: UploaderDatabaseConfig): UploaderStorage =
        createUploaderStorage(application(), config).also { openStorages += it }

    private fun engineOver(
        storage: UploaderStorage,
        handler: UploaderHandler<*>,
        job: Job,
        clock: UploaderClock,
    ): UploaderEngine = createUploaderEngine(
        store = storage.store,
        handlers = listOf(handler),
        scope = CoroutineScope(Dispatchers.Default + job),
        transactionRunner = storage.transactionRunner,
        clock = clock,
    ).also { openEngines += it }

    private fun realTest(seconds: Int = 60, body: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = (seconds + 10).seconds) {
            withContext(Dispatchers.Default) { withTimeout(seconds.seconds) { body() } }
        }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(15.seconds) { while (!condition()) delay(5) }
    }

    private suspend fun <T> CoroutineScope.together(count: Int, call: suspend (Int) -> T): List<T> {
        val start = CompletableDeferred<Unit>()
        val callers: List<Deferred<T>> = List(count) { index -> async { start.await(); call(index) } }
        start.complete(Unit)
        return callers.awaitAll()
    }

    private fun dbThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "kmptoolkit-uploader-db" && it.isAlive }

    private fun threadDump(): String = Thread.getAllStackTraces().entries
        .filter { (t, _) -> t.name.startsWith("kmptoolkit-") || t.name.startsWith("DefaultDispatcher") || t.name == "main" }
        .joinToString("\n") { (t, frames) -> "${t.name}:\n" + frames.take(12).joinToString("\n") { "    at $it" } }

    /** Engine A hands items off and dies; what B needs to take over. */
    private class DeadProcess(val ids: List<String>, val claim: Long, val clock: MutableUploaderClock) {
        val id: String get() = ids.first()
    }

    private suspend fun processDiesWithItemInFlight(
        config: UploaderDatabaseConfig,
        items: Int = 1,
        channel: String? = null,
        uniqueKey: String? = null,
    ): DeadProcess {
        val clock = MutableUploaderClock(1_000L)
        val storageA: UploaderStorage = open(config)
        val transportA = SafeTransport()
        val jobA = Job()
        val handlerA = UploadingHandler(transportA, channel)
        val engineA: UploaderEngine = engineOver(storageA, handlerA, jobA, clock)
        val ids: List<String> = List(items) { assertNotNull(engineA.enqueue(handlerA, "p$it", uniqueKey = uniqueKey)) }
        engineA.drain()
        assertEquals(listOf("launch:${ids.first()}:false"), transportA.events.value, "A must hand off only the head, once")
        val claim: Long = assertNotNull(storageA.store.getById(ids.first())).claim
        jobA.cancel() // the process is gone: no close(), no unregister; storageA stays open
        return DeadProcess(ids, claim, clock)
    }

    // --- process death ---------------------------------------------------------------------------

    @Test
    fun `an item in flight when the process dies is re-handed exactly once by the next engine and settles once`() = realTest {
        val config: UploaderDatabaseConfig = newConfig()
        val dead: DeadProcess = processDiesWithItemInFlight(config)
        dead.clock.advanceBy(SafeTransport.LEASE + 1) // the lease the dead process held has expired

        val storageB: UploaderStorage = open(config)
        val transportB = SafeTransport()
        val handlerB = UploadingHandler(transportB)
        val engineB: UploaderEngine = engineOver(storageB, handlerB, Job(), dead.clock)
        UploaderEngineRegistry.register(engineB)
        engineB.start()

        awaitCondition { transportB.events.value.isNotEmpty() }
        assertEquals(listOf("launch:${dead.id}:true"), transportB.events.value)
        val rows: List<UploaderItem> = storageB.store.getAllActive()
        assertEquals(1, rows.size, "two rows for one item after the restart")
        assertEquals(UploaderItemState.IN_FLIGHT, rows.single().state)
        assertEquals(dead.claim + 1, rows.single().claim)

        repeat(5) { engineB.trigger() }
        delay(300)
        assertEquals(1, transportB.events.value.size, "the live lease was re-handed again")

        assertTrue(UploadGateway.complete(dead.id, UploadResult.Completed(200)))
        assertTrue(engineB.awaitDrained(10.seconds), "queue not empty after the outcome")
        assertEquals(listOf("p0"), handlerB.delivered.value)
        assertEquals(1, handlerB.settled.value)
        assertEquals(emptyList(), storageB.store.getAllActive())
    }

    @Test
    fun `a stale failure or park from the dead process executor never clobbers the new engine's claim`() = realTest {
        val config: UploaderDatabaseConfig = newConfig()
        val dead: DeadProcess = processDiesWithItemInFlight(config)
        dead.clock.advanceBy(SafeTransport.LEASE + 1) // the lease the dead process held has expired
        val storageB: UploaderStorage = open(config)
        val transportB = SafeTransport()
        val handlerB = UploadingHandler(transportB)
        val engineB: UploaderEngine = engineOver(storageB, handlerB, Job(), dead.clock)
        UploaderEngineRegistry.register(engineB)
        engineB.start()
        awaitCondition { transportB.events.value.isNotEmpty() }
        val claimed: UploaderItem = assertNotNull(storageB.store.getById(dead.id))
        assertEquals(dead.claim + 1, claimed.claim)

        engineB.settle(dead.id, dead.claim, SettleResult.Failed(IllegalStateException("late, from the dead executor")))
        engineB.settle(dead.id, dead.claim, SettleResult.Park("late park"))

        val after: UploaderItem = assertNotNull(storageB.store.getById(dead.id))
        assertEquals(UploaderItemState.IN_FLIGHT, after.state, "a stale verdict changed the state")
        assertEquals(claimed.claim, after.claim, "a stale verdict moved the claim")
        assertEquals(0, after.attempts, "a stale failure spent retry budget")

        assertTrue(UploadGateway.complete(dead.id, UploadResult.Completed(200)))
        assertTrue(engineB.awaitDrained(10.seconds))
        assertEquals(listOf("p0"), handlerB.delivered.value, "the new engine's own outcome must win")
    }

    @Test
    fun `a stale delivery report from the dead process executor still stands`() = realTest {
        val config: UploaderDatabaseConfig = newConfig()
        val dead: DeadProcess = processDiesWithItemInFlight(config)
        dead.clock.advanceBy(SafeTransport.LEASE + 1) // the lease the dead process held has expired
        val storageB: UploaderStorage = open(config)
        val transportB = SafeTransport()
        val engineB: UploaderEngine = engineOver(storageB, UploadingHandler(transportB), Job(), dead.clock)
        engineB.start()
        awaitCondition { transportB.events.value.isNotEmpty() }

        engineB.settle(dead.id, dead.claim, SettleResult.Delivered)

        assertEquals(emptyList(), storageB.store.getAllActive(), "a delivery is a fact and must remove the row from any claim")
    }

    @Test
    fun `a backoff gate and the attempt count survive a restart`() = realTest {
        val config: UploaderDatabaseConfig = newConfig()
        val clock = MutableUploaderClock(1_000L)
        val firstHandler = FailingHandler(OneSecondPolicy)
        val first: UploaderEngine = engineOver(open(config), firstHandler, Job(), clock)
        first.enqueue(firstHandler, "p")
        first.drain() // fails once: attempts = 1, gate = 2_000
        assertEquals(listOf(0), firstHandler.attempts.value)

        clock.nowMillis = 1_500L
        val secondHandler = FailingHandler(OneSecondPolicy)
        engineOver(open(config), secondHandler, Job(), clock).drain()
        assertEquals(emptyList(), secondHandler.attempts.value, "the restarted engine ignored a persisted gate")

        clock.nowMillis = 2_500L
        engineOver(open(config), secondHandler, Job(), clock).drain()
        assertEquals(listOf(1), secondHandler.attempts.value, "the attempt count was reset by the restart")
    }

    @Test
    fun `a give-up budget is spent across restarts and a parked item is never attempted again`() = realTest {
        val config: UploaderDatabaseConfig = newConfig()
        val policy = object : RetryPolicy {
            override val maxDelayMillis: Long = 1_000L
            override val giveUp: GiveUpPolicy = GiveUpPolicy.ParkAfterAttempts(3)
            override fun backoffMillis(attempts: Int): Long = 1_000L
        }
        val clock = MutableUploaderClock(1_000L)
        val handler = FailingHandler(policy)
        var id: String? = null
        repeat(5) { round ->
            val storage: UploaderStorage = open(config)
            val engine: UploaderEngine = engineOver(storage, handler, Job(), clock)
            if (round == 0) id = engine.enqueue(handler, "p")
            engine.drain()
            clock.advanceBy(10_000L)
        }

        assertEquals(listOf(0, 1, 2), handler.attempts.value, "attempts across restarts must be 0, 1, 2 and then stop")
        val item: UploaderItem = assertNotNull(open(config).store.getById(assertNotNull(id)))
        assertEquals(UploaderItemState.PARKED, item.state)
    }

    @Test
    fun `two live engines on two storages over one file hand each item off exactly once`() = realTest {
        val config: UploaderDatabaseConfig = newConfig()
        val clock = MutableUploaderClock(1_000L)
        val transportA = SafeTransport()
        val transportB = SafeTransport()
        val handlerA = UploadingHandler(transportA)
        val handlerB = UploadingHandler(transportB)
        val engineA: UploaderEngine = engineOver(open(config), handlerA, Job(), clock)
        val engineB: UploaderEngine = engineOver(open(config), handlerB, Job(), clock)
        val ids: List<String> = List(20) { assertNotNull(engineA.enqueue(handlerA, "p$it")) }

        engineA.start()
        engineB.start()
        engineB.trigger()
        awaitCondition { (transportA.events.value + transportB.events.value).size >= 20 }
        delay(500)

        val launches: List<String> = (transportA.events.value + transportB.events.value).filter { it.startsWith("launch:") }
        assertEquals(ids.sorted(), launches.map { it.removePrefix("launch:").substringBefore(':') }.sorted(), "an item was handed off twice or not at all")
    }

    // --- many callers over SQLite ----------------------------------------------------------------

    @Test
    fun `sixty-four concurrent KEEP enqueues over SQLite insert one row`() = realTest {
        repeat(15) { iteration ->
            val storage: UploaderStorage = createInMemoryUploaderStorage().also { openStorages += it }
            val handler = UploadingHandler(SafeTransport())
            val engine: UploaderEngine = engineOver(storage, handler, Job(), MutableUploaderClock())

            val ids: List<String?> = together(64) { engine.enqueue(handler, "p$it", uniqueKey = "same") }

            assertEquals(1, ids.filterNotNull().size, "iteration $iteration: callers disagree on who inserted")
            assertEquals(listOf(ids.filterNotNull().single()), storage.store.getAllActive().map { it.id })
            engine.close()
        }
    }

    @Test
    fun `sixty-four concurrent REPLACE enqueues over SQLite leave one live row and cancel every displaced hand-off after its launch`() =
        realTest(120) {
            repeat(8) { iteration ->
                val storage: UploaderStorage = createInMemoryUploaderStorage().also { openStorages += it }
                val transport = SafeTransport()
                val handler = UploadingHandler(transport)
                val engine: UploaderEngine = engineOver(storage, handler, Job(), MutableUploaderClock())
                engine.start()
                val first: String = assertNotNull(engine.enqueue(handler, "first", uniqueKey = "k"))
                awaitCondition { transport.events.value.any { it.startsWith("launch:$first") } }

                val replacements: List<String?> = together(64) {
                    engine.enqueue(handler, "r$it", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE)
                }
                delay(500)

                val live: List<UploaderItem> = storage.store.getAllActive().filter { it.uniqueKey == "k" }
                assertEquals(1, live.size, "iteration $iteration: ${live.size} live rows")
                val survivor: String = live.single().id
                val events: List<String> = transport.events.value
                for (id in listOf(first) + replacements.map { assertNotNull(it) } - survivor) {
                    val lastLaunch: Int = events.indexOfLast { it.startsWith("launch:$id:") }
                    if (lastLaunch >= 0) {
                        assertTrue(
                            events.drop(lastLaunch + 1).contains("cancel:$id"),
                            "iteration $iteration: displaced item $id was launched and never cancelled afterwards",
                        )
                    }
                }
                engine.close()
            }
        }

    @Test
    fun `two storages open on one file and written concurrently lose no committed insert and fail only typed`() = realTest {
        val config: UploaderDatabaseConfig = newConfig()
        val first: UploaderStorage = open(config)
        val second: UploaderStorage = open(config)

        val outcomes: List<Pair<String, Result<Boolean>>> = together(128) { index ->
            val storage: UploaderStorage = if (index % 2 == 0) first else second
            val id = "w$index"
            id to runCatching { storage.store.insertKeep(anItem(id)) }
        }

        val failures: List<Throwable> = outcomes.mapNotNull { it.second.exceptionOrNull() }
        val untyped: List<Throwable> = failures.filterNot { it is UploaderStorageException }
        assertTrue(untyped.isEmpty(), "untyped failures from concurrent storages: ${untyped.map { it::class.simpleName + ": " + it.message }.distinct()}")
        val committed: Set<String> = outcomes.filter { it.second.getOrNull() == true }.map { it.first }.toSet()
        val seenByFirst: Set<String> = first.store.getAllActive().map { it.id }.toSet()
        val seenBySecond: Set<String> = second.store.getAllActive().map { it.id }.toSet()
        assertTrue(seenByFirst.containsAll(committed), "inserts missing from the first storage: ${committed - seenByFirst}")
        assertTrue(seenBySecond.containsAll(committed), "inserts missing from the second storage: ${committed - seenBySecond}")
    }

    // --- close and the database thread -----------------------------------------------------------

    @Test
    fun `close racing sixteen statements neither hangs nor loses a committed insert and returns the thread`() = realTest {
        val baseline: Int = dbThreads()
        repeat(20) { round ->
            val config: UploaderDatabaseConfig = newConfig()
            val storage: UploaderStorage = createUploaderStorage(application(), config)
            val gate = CountDownLatch(1)
            val closers: List<Thread> = List(2) { thread { gate.await(); storage.close() } }

            val start = CompletableDeferred<Unit>()
            val statements: List<Deferred<Pair<String, Result<Boolean>>>> = List(16) { index ->
                async { start.await(); "s$index" to runCatching { storage.store.insertKeep(anItem("s$round-$index")) } }
            }
            start.complete(Unit)
            gate.countDown()
            val results: List<Pair<String, Result<Boolean>>> = try {
                withTimeout(10.seconds) { statements.map { it.await() } }
            } catch (e: Throwable) {
                throw AssertionError("round $round: statements hung or failed to report:\n" + threadDump(), e)
            }
            closers.forEach { it.join(10_000) }
            assertTrue(closers.none { it.isAlive }, "round $round: close() did not return:\n" + threadDump())

            val reopened: UploaderStorage = createUploaderStorage(application(), config)
            try {
                val stored: Set<String> = reopened.store.getAllActive().map { it.id }.toSet()
                val committed: Set<String> = results.indices.filter { results[it].second.getOrNull() == true }
                    .map { "s$round-$it" }.toSet()
                assertTrue(stored.containsAll(committed), "round $round: a statement that reported success is not on disk")
            } finally {
                reopened.close()
            }
        }
        withTimeout(10.seconds) { while (dbThreads() > baseline) delay(20) }
        assertEquals(baseline, dbThreads(), "database threads leaked:\n" + threadDump())
    }

    @Test
    fun `fifty create and close cycles return the database thread count to the baseline`() = realTest {
        val baseline: Int = dbThreads()
        repeat(50) { cycle ->
            val config: UploaderDatabaseConfig = newConfig()
            val storage: UploaderStorage = createUploaderStorage(application(), config)
            storage.store.insertKeep(anItem("c$cycle"))
            storage.close()
            storage.close()
        }
        withTimeout(10.seconds) { while (dbThreads() > baseline) delay(20) }
        assertEquals(baseline, dbThreads(), "database threads after 50 cycles:\n" + threadDump())
    }

    // --- more restart and re-entrancy attacks ----------------------------------------------------

    @Test
    fun `an in-flight head keeps blocking its ordering channel across a restart`() = realTest {
        val config: UploaderDatabaseConfig = newConfig()
        val dead: DeadProcess = processDiesWithItemInFlight(config, items = 3, channel = "c")
        dead.clock.nowMillis = 1_000L // the lease is still live in the new process
        val transportB = SafeTransport()
        val handlerB = UploadingHandler(transportB, "c")
        val engineB: UploaderEngine = engineOver(open(config), handlerB, Job(), dead.clock)
        UploaderEngineRegistry.register(engineB)
        engineB.start()
        delay(300)
        assertEquals(emptyList(), transportB.events.value, "a tail item ran while its channel head was in flight")

        dead.clock.advanceBy(SafeTransport.LEASE + 1)
        engineB.trigger()
        awaitCondition { transportB.events.value.isNotEmpty() }
        delay(300)
        assertEquals(listOf("launch:${dead.ids[0]}:true"), transportB.events.value, "only the head may be re-handed")

        assertTrue(UploadGateway.complete(dead.ids[0], UploadResult.Completed(200)))
        awaitCondition { transportB.events.value.size >= 2 }
        delay(200)
        assertEquals("launch:${dead.ids[1]}:false", transportB.events.value[1])
        assertEquals(2, transportB.events.value.size, "the third item ran before the second settled")
    }

    @Test
    fun `after a restart KEEP honours the dead process's in-flight key and REPLACE cancels its upload`() = realTest {
        val config: UploaderDatabaseConfig = newConfig()
        val dead: DeadProcess = processDiesWithItemInFlight(config, uniqueKey = "k")
        dead.clock.nowMillis = 1_000L
        val transportB = SafeTransport()
        val handlerB = UploadingHandler(transportB)
        val storageB: UploaderStorage = open(config)
        val engineB: UploaderEngine = engineOver(storageB, handlerB, Job(), dead.clock)

        assertEquals(null, engineB.enqueue(handlerB, "dup", uniqueKey = "k"), "KEEP must not duplicate an in-flight key")
        assertEquals(1, storageB.store.getAllActive().size)

        val replacement: String = assertNotNull(engineB.enqueue(handlerB, "new", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE))
        assertTrue("cancel:${dead.id}" in transportB.events.value, "the dead process's upload was not cancelled: ${transportB.events.value}")
        assertEquals(listOf(replacement), storageB.store.getAllActive().map { it.id })
    }

    @Test
    fun `an observer sees all sixty-four concurrent enqueues and observer churn leaves the storage usable`() = realTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage().also { openStorages += it }
        val handler = UploadingHandler(SafeTransport())
        val engine: UploaderEngine = engineOver(storage, handler, Job(), MutableUploaderClock())
        val last: MutableStateFlow<Int> = MutableStateFlow(-1)
        val collector: Job = launch { engine.observe(handler.type).collect { last.value = it.size } }

        together(64) { engine.enqueue(handler, "p$it") }
        try {
            withTimeout(10.seconds) { while (last.value != 64) delay(5) }
        } catch (e: Throwable) {
            throw AssertionError("the observer stopped at ${last.value} of 64 items", e)
        }
        collector.cancel()

        repeat(200) { withTimeout(5.seconds) { engine.observe(handler.type).first() } }
        assertNotNull(withTimeout(5.seconds) { engine.enqueue(handler, "after") })
    }

    @Test
    fun `handlers that enqueue follow-ups from inside an attempt over SQLite finish the whole chain`() = realTest {
        val config: UploaderDatabaseConfig = newConfig()
        val storage: UploaderStorage = open(config)
        val executed: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        lateinit var engine: UploaderEngine
        lateinit var handler: UploaderHandler<String>
        handler = object : UploaderHandler<String> {
            override val type: String = "adv.chain"
            override val retryPolicy: RetryPolicy = OneSecondPolicy
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
                executed.update { it + payload }
                val remaining: Int = payload.removePrefix("n").toInt()
                if (remaining > 0) {
                    val next = "n${remaining - 1}"
                    if (remaining % 2 == 0) {
                        storage.transactionRunner.inTransaction { engine.enqueue(handler, next) }
                    } else {
                        engine.enqueue(handler, next)
                    }
                }
                return AttemptResult.Success
            }
        }
        engine = engineOver(storage, handler, Job(), MutableUploaderClock())
        engine.enqueue(handler, "n12")
        engine.start()

        awaitCondition { executed.value.size == 13 }
        assertEquals((12 downTo 0).map { "n$it" }, executed.value)
        assertTrue(engine.awaitDrained(10.seconds))
    }

    @Test
    fun `sixteen concurrent transactions commit or roll back as units`() = realTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage().also { openStorages += it }
        val handler = UploadingHandler(SafeTransport())
        val engine: UploaderEngine = engineOver(storage, handler, Job(), MutableUploaderClock())

        val results: List<Result<Unit>> = together(16) { i ->
            runCatching {
                storage.transactionRunner.inTransaction {
                    engine.enqueue(handler, "a$i")
                    engine.enqueue(handler, "b$i")
                    if (i % 4 == 0) throw IllegalStateException("rollback $i")
                }
            }
        }

        val stored: Set<String> = storage.store.getAllActive().map { it.payload }.toSet()
        for (i in 0 until 16) {
            val failed: Boolean = i % 4 == 0
            assertEquals(failed, results[i].isFailure, "transaction $i outcome")
            assertEquals(!failed, "a$i" in stored, "a$i committed state")
            assertEquals(!failed, "b$i" in stored, "b$i committed state")
        }
    }

    @Test
    fun `a cancelled transaction rolls back and releases the database thread for a queued writer`() = realTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage().also { openStorages += it }
        val tx: Job = launch {
            storage.transactionRunner.inTransaction {
                storage.store.insertKeep(anItem("t1"))
                awaitCancellation()
            }
        }
        delay(150)
        val waiter: Deferred<Boolean> = async { storage.store.insertKeep(anItem("t2")) }
        delay(150)

        tx.cancelAndJoin()

        assertTrue(withTimeout(5.seconds) { waiter.await() }, "the queued writer never got the database thread back:\n" + threadDump())
        assertEquals(listOf("t2"), storage.store.getAllActive().map { it.id }, "the cancelled transaction left its write behind")
    }

    @Test
    fun `fifty storages created and closed over one driver never close the driver and never leak a thread`() = realTest {
        val baseline: Int = dbThreads()
        val driver = createDriver(uploaderDatabaseSchema, name = null)
        try {
            repeat(50) { cycle ->
                val storage: UploaderStorage = createUploaderStorage(driver)
                assertEquals(cycle, storage.store.getAllActive().size, "cycle $cycle: the earlier rows must still be there")
                storage.store.insertKeep(anItem("d$cycle"))
                storage.close()
            }
            val last: UploaderStorage = createUploaderStorage(driver)
            assertEquals(50, last.store.getAllActive().size, "the driver was closed or reset by an earlier storage's close")
            last.close()
        } finally {
            driver.close()
        }
        withTimeout(10.seconds) { while (dbThreads() > baseline) delay(20) }
        assertEquals(baseline, dbThreads(), "database threads leaked:\n" + threadDump())
    }

    @Test
    fun `an engine left running over a closed storage does not spin on the failing store`() = realTest {
        val storage: UploaderStorage = open(newConfig())
        val reads: MutableStateFlow<Int> = MutableStateFlow(0)
        val counting = object : io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore by storage.store {
            override suspend fun getAllActive(): List<UploaderItem> {
                reads.update { it + 1 }
                return storage.store.getAllActive()
            }
        }
        val handler = UploadingHandler(SafeTransport())
        val engine: UploaderEngine = createUploaderEngine(
            store = counting,
            handlers = listOf(handler),
            scope = CoroutineScope(Dispatchers.Default + Job()),
            transactionRunner = storage.transactionRunner,
        ).also { openEngines += it }
        engine.start()
        delay(200)
        storage.close()
        val atClose: Int = reads.value

        runCatching { engine.enqueue(handler, "after-close") }
        repeat(20) { engine.trigger() }
        delay(1_000)

        assertTrue(reads.value - atClose < 100, "the engine read the closed store ${reads.value - atClose} times in one second")
    }

    private fun channelHandler(executed: MutableStateFlow<List<String>>, failFirst: Set<String> = emptySet()): UploaderHandler<String> =
        object : UploaderHandler<String> {
            private val failed: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
            override val type: String = "adv.channel"
            override val retryPolicy: RetryPolicy = OneSecondPolicy
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override fun orderingKey(payload: String): String? = "c"
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
                executed.update { it + payload }
                if (payload in failFirst && payload !in failed.value) {
                    failed.update { it + payload }
                    return AttemptResult.Retry(IllegalStateException("first time"))
                }
                kotlinx.coroutines.yield()
                return AttemptResult.Success
            }
        }

    @Test
    fun `order within a channel survives engines closed or abandoned at random moments`() = realTest(90) {
        val config: UploaderDatabaseConfig = newConfig()
        val executed: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        val handler: UploaderHandler<String> = channelHandler(executed)
        val clock = MutableUploaderClock(1_000L)
        val seeder: UploaderEngine = engineOver(open(config), handler, Job(), clock)
        repeat(100) { seeder.enqueue(handler, "i${it.toString().padStart(3, '0')}") }

        repeat(8) { round ->
            val job = Job()
            val engine: UploaderEngine = engineOver(open(config), handler, job, clock)
            engine.start()
            delay(Random(round).nextLong(0, 25))
            if (round % 2 == 0) engine.close() else job.cancel() // orderly teardown, then an abandoned one
        }
        val last: UploaderEngine = engineOver(open(config), handler, Job(), clock)
        last.start()
        assertTrue(last.awaitDrained(30.seconds), "the final engine never drained the queue")

        val log: List<String> = executed.value.fold(emptyList()) { acc, next -> if (acc.lastOrNull() == next) acc else acc + next }
        assertEquals((0 until 100).map { "i${it.toString().padStart(3, '0')}" }, log, "items ran out of order or were skipped across restarts")
    }

    @Test
    fun `a persisted backoff gate on a channel head keeps its tail waiting across restarts`() = realTest {
        val config: UploaderDatabaseConfig = newConfig()
        val executed: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        val handler: UploaderHandler<String> = channelHandler(executed, failFirst = setOf("p0"))
        val clock = MutableUploaderClock(1_000L)
        val one: UploaderEngine = engineOver(open(config), handler, Job(), clock)
        one.enqueue(handler, "p0")
        one.enqueue(handler, "p1")
        one.drain()
        assertEquals(listOf("p0"), executed.value)

        clock.nowMillis = 1_500L
        engineOver(open(config), handler, Job(), clock).drain()
        assertEquals(listOf("p0"), executed.value, "the tail ran, or the head ran, before its persisted gate")

        clock.nowMillis = 2_500L
        engineOver(open(config), handler, Job(), clock).drain()
        assertEquals(listOf("p0", "p0", "p1"), executed.value)
    }

    @Test
    fun `closing a storage from inside its own transaction fails fast and leaves the storage usable`() = realTest {
        val storage: UploaderStorage = open(newConfig())

        val result: Result<Unit> = runCatching { storage.transactionRunner.inTransaction { storage.close() } }

        assertTrue(result.exceptionOrNull() is IllegalStateException, "expected a fast IllegalStateException, got ${result.exceptionOrNull()}")
        assertTrue(storage.store.insertKeep(anItem("still-usable")))
    }
}
