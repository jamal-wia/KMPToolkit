package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * The iOS transport settles a delivered upload exactly once, also across a process death between the steps of
 * the settlement, by the protocol the Android worker has (outcome journal: an entry stamped with the attempt it
 * answers, deleted only after the engine's commit; a leftover of a settled attempt is recognised by its stamp).
 *
 * Each case runs a real engine over a real [OutcomeJournal] and the real delegate, with a scripted session. A
 * process death is simulated at every step of the settlement: the journal's storage "dies" after the k-th
 * operation (every later read, write and delete fails, so nothing a `finally` block would do survives it), and
 * the store can refuse the commit. Then a new transport over the same directory — the relaunched process —
 * replays what is left through the same engine. The row must be settled once and never stranded.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosSettleExactlyOnceTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-once-" + NSUUID().UUIDString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bodies = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        scope.cancel()
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        bodies.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    /** A journal storage that serves [dieAfter] operations and then fails every one, like a process that is gone. */
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

    /** The row store, able to refuse the commit of a delivery; every successful removal is counted. */
    private class CommitStore(val inner: TestUploaderStore) : UploaderStore by inner {
        private val refusals = AtomicInt(0)
        private val commits = AtomicInt(0)

        val committed: Int get() = commits.value

        fun refuseNextCommits(count: Int) {
            refusals.value = count
        }

        override suspend fun deleteById(id: String) {
            if (refusals.getAndAdd(-1) > 0) error("the store refused the commit")
            inner.deleteById(id)
            commits.incrementAndGet()
        }
    }

    private class FakeSession(val delegate: UploadSessionDelegate) : UploadSession {
        private val created = AtomicInt(0)
        private val description = kotlin.concurrent.AtomicReference<String?>(null)

        val tasksCreated: Int get() = created.value

        /** What Foundation reports back as the finished task's `taskDescription`. */
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
        override val type: String = "ios-once"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        val delivered = kotlin.concurrent.AtomicInt(0)
        val settled = kotlin.concurrent.AtomicInt(0)
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

    /** What a scenario left behind once the relaunched process was quiet. */
    private class Outcome(
        val row: UploaderItem?,
        val commits: Int,
        val delivered: Int,
        val settled: Int,
        val tasks: Int,
        val storageOperations: Int,
        val leftover: Boolean,
    )

    private fun transportOver(
        dir: String,
        storage: OutcomeStorage,
        sessions: MutableList<FakeSession>,
    ): BackgroundSessionUploadTransport {
        val prefix = "kmptoolkit.test.once.${NSUUID().UUIDString}."
        return BackgroundSessionUploadTransport(
            identifierPrefix = prefix,
            config = BackgroundUploadConfig(sessionIdentifierPrefix = prefix),
            logger = NoopLogger,
            journal = OutcomeJournal(storage, UploaderClock.System),
            sessionFactory = UploadSessionFactory { _, delegate -> FakeSession(delegate).also { sessions += it } },
        )
    }

    private suspend fun until(timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMillis) { while (!condition()) delay(10L) }
        }
    }

    /**
     * One upload answered with [status], with the journal's storage dying after [dieAfter] operations and the
     * store refusing the first [refuseCommits] commits; then the relaunch.
     */
    private suspend fun scenario(status: Long, dieAfter: Int, refuseCommits: Int): Outcome {
        val dir: String = root + "/" + NSUUID().UUIDString() + "/outcomes"
        val itemId = "once-" + NSUUID().UUIDString()
        bodies += temporaryBodyPath(itemId)
        val rows = TestUploaderStore()
        val store = CommitStore(rows)
        val dying = DyingStorage(IosOutcomeStorage(dir))
        val sessions = mutableListOf<FakeSession>()
        val first: BackgroundSessionUploadTransport = transportOver(dir, dying, sessions)
        // The transport sweeps its journal once when it is created (two storage operations); let it finish so
        // that the death below is counted from the settlement alone.
        until { dying.operations >= 2 }
        val handler = Handler(first)
        val engine: UploaderEngine = testEngine(store, listOf(handler), scope, idGenerator = { itemId })
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, "payload")
        engine.drain() // hands the item off: one session, one task, labelled with the attempt
        assertEquals(1, sessions.size)
        assertEquals(1, sessions.single().tasksCreated)
        assertEquals(IN_FLIGHT, rows.find(itemId)?.state, "handed off and in flight")

        // The answer arrives; the process dies somewhere in its settlement.
        val operationsBefore: Int = dying.operations
        store.refuseNextCommits(refuseCommits)
        dying.dieAfter(dieAfter)
        val settledFirst = CompletableDeferred<Unit>()
        val session: FakeSession = sessions.single()
        session.delegate.notifyWhenDrained { settledFirst.complete(Unit) }
        session.delegate.complete(
            statusCode = status,
            headerFields = mapOf("Content-Type" to "application/json"),
            errorDescription = null,
            taskDescription = session.taskDescription,
        ) {}
        withContext(Dispatchers.Default) { withTimeout(10_000L) { settledFirst.await() } }
        val operationsAtDeath: Int = dying.operations - operationsBefore

        // The relaunch: a new transport over the same directory sweeps the journal through the engine.
        dying.revive()
        store.refuseNextCommits(0)
        transportOver(dir, IosOutcomeStorage(dir), sessions)
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        until { journal.inspect(itemId) is JournalEntry.Absent && rows.find(itemId)?.state != IN_FLIGHT }
        withContext(Dispatchers.Default) { delay(300L) } // a stray second replay would land here
        return Outcome(
            row = rows.find(itemId),
            commits = store.committed,
            delivered = handler.delivered.value,
            settled = handler.settled.value,
            tasks = sessions.sumOf { it.tasksCreated },
            storageOperations = operationsAtDeath,
            leftover = journal.inspect(itemId) !is JournalEntry.Absent,
        )
    }

    private companion object {
        val IN_FLIGHT: UploaderItemState = UploaderItemState.IN_FLIGHT
    }

    // --- A delivered upload is committed once, wherever the process dies ----------------------------------

    @Test
    fun `a delivered answer is committed exactly once whichever step of its settlement the process dies at`() = runTest {
        val baseline: Outcome = scenario(status = 201L, dieAfter = Int.MAX_VALUE, refuseCommits = 0)
        assertNull(baseline.row)
        assertEquals(1, baseline.commits)
        val steps: Int = baseline.storageOperations
        assertTrue(steps >= 3, "the settlement touches the journal at several steps; saw $steps")
        for (dieAfter in 0..steps) {
            val outcome: Outcome = scenario(status = 201L, dieAfter = dieAfter, refuseCommits = 0)
            assertNull(outcome.row, "dying after journal operation $dieAfter stranded the item")
            assertEquals(1, outcome.commits, "dying after journal operation $dieAfter: the delivery must commit once")
            assertEquals(1, outcome.delivered, "dying after journal operation $dieAfter: onDelivered ran ${outcome.delivered} times")
            assertEquals(1, outcome.settled, "dying after journal operation $dieAfter: onSettled ran ${outcome.settled} times")
            assertEquals(1, outcome.tasks, "dying after journal operation $dieAfter: the file was uploaded again")
            assertTrue(!outcome.leftover, "dying after journal operation $dieAfter left an entry behind for good")
        }
    }

    @Test
    fun `a commit the store refused is replayed by the relaunch from the entry the failed settlement kept`() = runTest {
        val steps: Int = scenario(status = 201L, dieAfter = Int.MAX_VALUE, refuseCommits = 0).storageOperations
        // From 1: with the first operation (the entry's write) dead, nothing protects the answer and a refused
        // commit leaves the item to its lease, as without a journal.
        for (dieAfter in 1..steps) {
            val outcome: Outcome = scenario(status = 201L, dieAfter = dieAfter, refuseCommits = 1)
            // The first settlement could not commit: it may have hooked onDelivered once, and the replay again.
            // What may never happen is a lost answer or a second commit.
            assertNull(outcome.row, "dying after journal operation $dieAfter with a refused commit stranded the item")
            assertEquals(1, outcome.commits, "dying after journal operation $dieAfter: the delivery must commit once")
            assertEquals(1, outcome.settled, "dying after journal operation $dieAfter: onSettled must run once")
            assertEquals(1, outcome.tasks, "dying after journal operation $dieAfter: the file was uploaded again")
            assertTrue(outcome.delivered in 1..2, "onDelivered ran ${outcome.delivered} times")
        }
    }

    // --- A leftover of a settled attempt never replays into the retry --------------------------------------

    /** Guard of a contract the old protocol kept by deleting early: a stamped leftover is dropped, not applied. */
    @Test
    fun `an entry left over from a settled attempt does not charge the retry a second time`() = runTest {
        val steps: Int = scenario(status = 500L, dieAfter = Int.MAX_VALUE, refuseCommits = 0).storageOperations
        for (dieAfter in 0..steps) {
            val outcome: Outcome = scenario(status = 500L, dieAfter = dieAfter, refuseCommits = 0)
            val row: UploaderItem = assertNotNull(outcome.row, "a failed attempt keeps its row")
            assertEquals(1, row.attempts, "dying after journal operation $dieAfter: the one failed attempt was charged ${row.attempts} times")
            assertEquals(0, outcome.commits)
            assertTrue(!outcome.leftover, "dying after journal operation $dieAfter left an entry behind for good")
        }
    }
}
