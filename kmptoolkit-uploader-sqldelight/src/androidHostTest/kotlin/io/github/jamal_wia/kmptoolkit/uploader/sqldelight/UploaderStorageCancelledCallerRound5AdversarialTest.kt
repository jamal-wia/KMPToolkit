package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
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
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.runner.RunWith

/**
 * Round 5 (lifecycle chaos): callers of `cancel`, `cancelByTag` and a REPLACE enqueue that are cancelled while the
 * call runs, against the real SQLite storage, whose calls hop to the database thread and report a cancelled caller
 * when they return even though the write committed.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStorageCancelledCallerRound5AdversarialTest {
    private val seed: Long = testSeed(20261113L)
    private val random: Random = Random(seed)

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
        override val leaseMillis: Long = 600_000L
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

    private class SqlUploadHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "adv.r5.upload"
        override val retryPolicy: RetryPolicy = QuickPolicy
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("a", payload))))
    }

    private fun open(): UploaderStorage {
        // timing-hygiene-ignore: TH3 name a unique database file name, not a scenario choice
        val name = "adv.a1r5.${Random.nextLong(0L, Long.MAX_VALUE)}"
        files += name
        return createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
    }

    private fun engineOver(storage: UploaderStorage, handler: UploadHandler<*>, direct: Boolean = false): UploaderEngine {
        val job = Job().also { jobs += it }
        return createUploaderEngine(
            store = storage.store,
            handlers = listOf(handler),
            scope = CoroutineScope(Dispatchers.Default + job),
            transactionRunner = if (direct) io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner.Direct else storage.transactionRunner,
        ).also { engines += it }
    }

    private fun realTest(seconds: Int = 90, body: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = (seconds + 10).seconds) { withContext(Dispatchers.Default) { withTimeout(seconds.seconds) { body() } } }

    private suspend fun awaitLaunched(transport: Transport, count: Int) {
        withTimeout(30.seconds) { while (transport.events.value.count { it.startsWith("launch:") } < count) delay(5) }
    }

    /** Ids launched whose upload was never cancelled after its last launch. */
    private fun running(transport: Transport, ids: Collection<String>): List<String> {
        val events: List<String> = transport.events.value
        return ids.filter { id ->
            val last: Int = events.indexOfLast { it == "launch:$id" }
            last >= 0 && !events.drop(last + 1).contains("cancel:$id")
        }
    }

    private suspend fun cancelSoon(body: suspend () -> Unit) {
        coroutineScope {
            val call: Job = launch(Dispatchers.Default) { body() }
            repeat(random.nextInt(0, 40)) { yield() }
            call.cancel()
            call.join()
        }
    }

    @Test
    fun `a cancel whose caller is cancelled while the call runs never leaves a withdrawn upload running over SQLite`() = realTest(120) {
        repeat(4) { iteration ->
            val storage: UploaderStorage = open()
            val transport = Transport()
            val handler = SqlUploadHandler(transport)
            val engine: UploaderEngine = engineOver(storage, handler)
            engine.start()
            val ids: List<String> = List(120) { engine.enqueue(handler, "u$it")!! }
            awaitLaunched(transport, ids.size)
            ids.forEach { id -> cancelSoon { engine.cancel(id) } }
            val gone: List<String> = ids.filter { storage.store.getById(it) == null }
            assertEquals(
                emptyList(),
                running(transport, gone),
                "iteration $iteration: ${gone.size} rows were deleted; these uploads still run (seed=$seed)",
            )
            engine.close()
        }
    }

    @Test
    fun `a cancelByTag whose caller is cancelled while the call runs never leaves a wiped upload running over SQLite`() = realTest(120) {
        repeat(4) { iteration ->
            val storage: UploaderStorage = open()
            val transport = Transport()
            val handler = SqlUploadHandler(transport)
            val engine: UploaderEngine = engineOver(storage, handler)
            engine.start()
            val ids: List<String> = List(120) { engine.enqueue(handler, "u$it", tag = "t$it")!! }
            awaitLaunched(transport, ids.size)
            ids.indices.forEach { n -> cancelSoon { engine.cancelByTag("t$n") } }
            val gone: List<String> = ids.filter { storage.store.getById(it) == null }
            assertEquals(emptyList(), running(transport, gone), "iteration $iteration: ${gone.size} rows were wiped; these uploads still run (seed=$seed)")
            engine.close()
        }
    }

    @Test
    fun `a REPLACE whose caller is cancelled while the call runs never leaves the superseded upload running over SQLite`() = realTest(120) {
        repeat(4) { iteration ->
            val storage: UploaderStorage = open()
            val transport = Transport()
            val handler = SqlUploadHandler(transport)
            val engine: UploaderEngine = engineOver(storage, handler)
            engine.start()
            val ids: List<String> = List(120) { engine.enqueue(handler, "old$it", uniqueKey = "k$it")!! }
            awaitLaunched(transport, ids.size)
            ids.indices.forEach { n ->
                cancelSoon { engine.enqueue(handler, "new$n", uniqueKey = "k$n", conflictPolicy = ConflictPolicy.REPLACE) }
            }
            val gone: List<String> = ids.filter { storage.store.getById(it) == null }
            assertEquals(emptyList(), running(transport, gone), "iteration $iteration: ${gone.size} rows were replaced; these uploads still run (seed=$seed)")
            engine.close()
        }
    }

    @Test
    fun `a REPLACE through the default direct runner whose caller is cancelled while the call runs never leaves the superseded upload running over SQLite`() = realTest(120) {
        repeat(4) { iteration ->
            val storage: UploaderStorage = open()
            val transport = Transport()
            val handler = SqlUploadHandler(transport)
            val engine: UploaderEngine = engineOver(storage, handler, direct = true)
            engine.start()
            val ids: List<String> = List(120) { engine.enqueue(handler, "old$it", uniqueKey = "k$it")!! }
            awaitLaunched(transport, ids.size)
            ids.indices.forEach { n ->
                cancelSoon { engine.enqueue(handler, "new$n", uniqueKey = "k$n", conflictPolicy = ConflictPolicy.REPLACE) }
            }
            val gone: List<String> = ids.filter { storage.store.getById(it) == null }
            assertEquals(emptyList(), running(transport, gone), "iteration $iteration: ${gone.size} rows were replaced; these uploads still run (seed=$seed)")
            engine.close()
        }
    }
}
