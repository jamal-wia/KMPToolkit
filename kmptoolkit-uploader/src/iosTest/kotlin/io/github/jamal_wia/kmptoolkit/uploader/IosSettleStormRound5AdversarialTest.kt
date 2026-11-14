package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.concurrent.AtomicInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Many items on one iOS transport and one journal, all answered at once, with the process dying after the k-th journal
 * operation counted across ALL of them: the relaunch must settle every item exactly once (a delivery commits once, a
 * failure is charged once), none stranded, no file uploaded again, no entry left behind. The single-item matrix lives
 * in [IosSettleExactlyOnceTest]; this one adds the concurrency of one channel and a death that lands in the middle of
 * other items' settlements.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosSettleStormRound5AdversarialTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-storm-" + NSUUID().UUIDString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bodies = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        scope.cancel()
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        bodies.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private class DyingStorage(private val inner: OutcomeStorage) : OutcomeStorage {
        private val left = AtomicInt(Int.MAX_VALUE)
        private val used = AtomicInt(0)

        val operations: Int get() = used.value

        fun dieAfter(operations: Int) {
            left.value = operations
        }

        fun revive() {
            left.value = Int.MAX_VALUE
        }

        private fun step() {
            used.incrementAndGet()
            check(left.getAndAdd(-1) > 0) { "the process died" }
        }

        override fun write(itemId: String, bytes: ByteArray): Boolean = run { step(); inner.write(itemId, bytes) }
        override fun read(itemId: String): ByteArray? = run { step(); inner.read(itemId) }
        override fun delete(itemId: String) = run { step(); inner.delete(itemId) }
        override fun itemIds(): List<String> = run { step(); inner.itemIds() }
        override fun purgeLeftovers() = run { step(); inner.purgeLeftovers() }
    }

    /** A thread-safe row store (the test fake is single-threaded) that counts every applied removal. */
    private class SerializedStore(private val inner: TestUploaderStore = TestUploaderStore()) : UploaderStore {
        private val lock = Mutex()
        private val commits = AtomicInt(0)

        val committed: Int get() = commits.value

        fun find(id: String): UploaderItem? = inner.find(id)

        override suspend fun insertKeep(record: UploaderItem): io.github.jamal_wia.kmptoolkit.uploader.spi.KeepResult = lock.withLock { inner.insertKeep(record) }
        override suspend fun insertReplace(record: UploaderItem): UploaderItem? = lock.withLock { inner.insertReplace(record) }
        override suspend fun getAllActive(): List<UploaderItem> = lock.withLock { inner.getAllActive() }
        override suspend fun getById(id: String): UploaderItem? = lock.withLock { inner.getById(id) }
        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean = lock.withLock {
            inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }

        override suspend fun markInFlight(
            id: String,
            leaseUntilEpochMillis: Long,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean = lock.withLock { inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim) }

        override suspend fun park(
            id: String,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean = lock.withLock { inner.park(id, lastError, expectedState, expectedClaim) }

        override suspend fun deleteById(id: String) {
            lock.withLock {
                val existed: Boolean = inner.find(id) != null
                inner.deleteById(id)
                if (existed) commits.incrementAndGet()
            }
        }

        override suspend fun deleteByTag(tag: String): List<UploaderItem> = lock.withLock { inner.deleteByTag(tag) }
        override fun observeByType(type: String): kotlinx.coroutines.flow.Flow<List<UploaderItem>> = inner.observeByType(type)
        override suspend fun clearAll() = lock.withLock { inner.clearAll() }
    }

    private class FakeSession(val delegate: UploadSessionDelegate) : UploadSession {
        private val created = AtomicInt(0)
        private val description = kotlin.concurrent.AtomicReference<String?>(null)

        val tasksCreated: Int get() = created.value
        val taskDescription: String? get() = description.value

        override fun getTaskCount(callback: (Int) -> Unit) = callback(0)
        override fun cancelTasks(callback: (Int) -> Unit) = callback(0)
        override fun startUploadTask(request: NSMutableURLRequest, bodyPath: String, taskDescription: String?) {
            description.value = taskDescription
            created.incrementAndGet()
        }

        override fun invalidateAndCancel() = Unit
        override fun finishTasksAndInvalidate() = Unit
    }

    private class Handler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "ios-storm"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        val delivered = AtomicInt(0)
        val settled = AtomicInt(0)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw

        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "https://upload.invalid/x", fields = listOf(UploadField.Text("a", "b"))))

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered.incrementAndGet()
        }

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            settled.incrementAndGet()
        }
    }

    private class Outcome(
        val rows: Map<String, UploaderItem?>,
        val commits: Int,
        val delivered: Int,
        val settled: Int,
        val tasks: Int,
        val operationsAtDeath: Int,
        val leftovers: List<String>,
    )

    private fun transportOver(
        storage: OutcomeStorage,
        sessions: MutableList<FakeSession>,
        prefix: String,
    ): BackgroundSessionUploadTransport = BackgroundSessionUploadTransport(
        identifierPrefix = prefix,
        config = BackgroundUploadConfig(sessionIdentifierPrefix = prefix),
        logger = NoopLogger,
        journal = OutcomeJournal(storage, UploaderClock.System),
        sessionFactory = UploadSessionFactory { _, delegate -> FakeSession(delegate).also { synchronizedAdd(sessions, it) } },
    )

    private fun synchronizedAdd(sessions: MutableList<FakeSession>, session: FakeSession) {
        // Sessions are created under the transport's own lock, one at a time; the list is read after the drain.
        sessions += session
    }

    private suspend fun until(timeoutMillis: Long = 20_000L, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMillis) { while (!condition()) delay(10L) }
        }
    }

    /** [items] answered at once with [statusOf]; the process dies after [dieAfter] journal operations; then the relaunch. */
    private suspend fun scenario(items: Int, dieAfter: Int, statusOf: (Int) -> Long): Outcome {
        val dir: String = root + "/" + NSUUID().UUIDString() + "/outcomes"
        val prefix = "kmptoolkit.test.storm.${NSUUID().UUIDString}."
        val store = SerializedStore()
        val dying = DyingStorage(IosOutcomeStorage(dir))
        val sessions = mutableListOf<FakeSession>()
        val first: BackgroundSessionUploadTransport = transportOver(dying, sessions, prefix)
        until { dying.operations >= 2 }
        val handler = Handler(first)
        val ids = SequentialIds("storm-${NSUUID().UUIDString}")
        val engine: UploaderEngine = testEngine(store, listOf(handler), scope, idGenerator = ids::next)
        UploaderEngineRegistry.register(engine)
        val itemIds: List<String> = (1..items).map { checkNotNull(engine.enqueue(handler, "payload-$it")) }
        itemIds.forEach { bodies += temporaryBodyPath(it) }
        engine.drain()
        assertEquals(items, sessions.size, "every item is handed off on its own session")
        assertTrue(itemIds.all { store.find(it)?.state == UploaderItemState.IN_FLIGHT })

        val operationsBefore: Int = dying.operations
        dying.dieAfter(dieAfter)
        val drained: List<CompletableDeferred<Unit>> = sessions.map { CompletableDeferred<Unit>().also { d -> it.delegate.notifyWhenDrained { d.complete(Unit) } } }
        // All answers at once, from many threads.
        withContext(Dispatchers.Default) {
            sessions.map { session ->
                async {
                    session.delegate.complete(
                        statusCode = statusOf(itemIds.indexOf(session.delegate.itemId)),
                        headerFields = mapOf("Content-Type" to "application/json"),
                        errorDescription = null,
                        taskDescription = session.taskDescription,
                    ) {}
                }
            }.awaitAll()
            withTimeout(20_000L) { drained.awaitAll() }
        }
        val operationsAtDeath: Int = dying.operations - operationsBefore

        dying.revive()
        transportOver(IosOutcomeStorage(dir), sessions, prefix)
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        until { itemIds.all { journal.inspect(it) is JournalEntry.Absent && store.find(it)?.state != UploaderItemState.IN_FLIGHT } }
        withContext(Dispatchers.Default) { delay(200L) } // a stray second replay would land here
        return Outcome(
            rows = itemIds.associateWith { store.find(it) },
            commits = store.committed,
            delivered = handler.delivered.value,
            settled = handler.settled.value,
            tasks = sessions.sumOf { it.tasksCreated },
            operationsAtDeath = operationsAtDeath,
            leftovers = itemIds.filter { journal.inspect(it) !is JournalEntry.Absent },
        )
    }

    @Test
    fun `ten deliveries answered at once settle exactly once wherever the process dies in the storm`() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
        val items = 10
        val baseline: Outcome = scenario(items, Int.MAX_VALUE) { 201L }
        assertEquals(items, baseline.commits)
        val steps: Int = baseline.operationsAtDeath
        assertTrue(steps >= items * 3, "ten settlements touch the journal several times each; saw $steps")
        // Every position of the death across the interleaved settlements, stepping so the run stays bounded.
        val stride: Int = maxOf(1, steps / 24)
        var dieAfter = 0
        while (dieAfter <= steps) {
            val o: Outcome = scenario(items, dieAfter) { 201L }
            assertTrue(o.rows.values.all { it == null }, "dying after operation $dieAfter stranded ${o.rows.filterValues { it != null }.keys}")
            assertEquals(items, o.commits, "dying after operation $dieAfter: ${o.commits} commits for $items items")
            assertEquals(items, o.settled, "dying after operation $dieAfter: onSettled ran ${o.settled} times for $items items")
            assertEquals(items, o.tasks, "dying after operation $dieAfter: a file was uploaded again")
            assertTrue(o.leftovers.isEmpty(), "dying after operation $dieAfter left entries ${o.leftovers}")
            assertTrue(o.delivered in items..items * 2, "dying after operation $dieAfter: onDelivered ran ${o.delivered} times")
            dieAfter += stride
        }
    }

    @Test
    fun `ten mixed answers settle once each wherever the process dies - failures are charged once`() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
        val items = 10
        val statusOf: (Int) -> Long = { if (it % 2 == 0) 201L else 500L }
        val baseline: Outcome = scenario(items, Int.MAX_VALUE, statusOf)
        val steps: Int = baseline.operationsAtDeath
        val stride: Int = maxOf(1, steps / 20)
        var dieAfter = 0
        while (dieAfter <= steps) {
            val o: Outcome = scenario(items, dieAfter, statusOf)
            o.rows.entries.forEachIndexed { index, (id, row) ->
                if (index % 2 == 0) {
                    assertNull(row, "dying after operation $dieAfter: delivered item $id stranded")
                } else {
                    val kept: UploaderItem = assertNotNull(row, "a failed item keeps its row")
                    assertEquals(1, kept.attempts, "dying after operation $dieAfter: failure of $id charged ${kept.attempts} times")
                }
            }
            assertEquals(items / 2, o.commits, "dying after operation $dieAfter: ${o.commits} delivery commits")
            assertEquals(items, o.tasks, "dying after operation $dieAfter: a file was uploaded again")
            assertTrue(o.leftovers.isEmpty(), "dying after operation $dieAfter left entries ${o.leftovers}")
            dieAfter += stride
        }
    }
}
