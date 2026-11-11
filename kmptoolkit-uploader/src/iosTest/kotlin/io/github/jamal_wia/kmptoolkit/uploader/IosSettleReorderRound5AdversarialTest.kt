package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.concurrent.AtomicInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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
 * Late and reordered callbacks across the sessions of one item: the answer of attempt 0 arrives (again) after the retry
 * of attempt 1 is already in flight or even answered. A stale answer must never be charged to the newer attempt, and a
 * stale journal entry must never stand in for the upload a re-hand of the newer attempt owes.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosSettleReorderRound5AdversarialTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-reorder-" + NSUUID().UUIDString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bodies = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        scope.cancel()
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        bodies.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
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

    private class ZeroBackoff : RetryPolicy {
        override val maxDelayMillis: Long = 1L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 0L
    }

    private class Handler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "ios-reorder"
        override val retryPolicy: RetryPolicy = ZeroBackoff()
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

    private class Setup(
        val rows: TestUploaderStore,
        val handler: Handler,
        val sessions: MutableList<FakeSession>,
        val engine: UploaderEngine,
        val itemId: String,
        val dir: String,
        val prefix: String,
    )

    private fun transportOver(
        dir: String,
        sessions: MutableList<FakeSession>,
        prefix: String,
        flushWindowMillis: Long = 100L,
    ): BackgroundSessionUploadTransport = BackgroundSessionUploadTransport(
        identifierPrefix = prefix,
        config = BackgroundUploadConfig(sessionIdentifierPrefix = prefix, rehandFlushWindow = flushWindowMillis.milliseconds),
        logger = NoopLogger,
        journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System),
        sessionFactory = UploadSessionFactory { _, delegate -> FakeSession(delegate).also { sessions += it } },
    )

    private suspend fun until(timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        withContext(Dispatchers.Default) { withTimeout(timeoutMillis) { while (!condition()) delay(10L) } }
    }

    private suspend fun setup(): Setup {
        val dir: String = root + "/" + NSUUID().UUIDString() + "/outcomes"
        val itemId = "reorder-" + NSUUID().UUIDString()
        bodies += temporaryBodyPath(itemId)
        val rows = TestUploaderStore()
        val sessions = mutableListOf<FakeSession>()
        val prefix = "kmptoolkit.test.reorder.${NSUUID().UUIDString}."
        val transport: BackgroundSessionUploadTransport = transportOver(dir, sessions, prefix)
        val handler = Handler(transport)
        val engine: UploaderEngine = testEngine(rows, listOf(handler), scope, idGenerator = { itemId })
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, "payload")
        engine.drain()
        assertEquals(1, sessions.size)
        return Setup(rows, handler, sessions, engine, itemId, dir, prefix)
    }

    private suspend fun deliver(session: FakeSession, status: Long, taskDescription: String?) {
        val drained = CompletableDeferred<Unit>()
        session.delegate.notifyWhenDrained { drained.complete(Unit) }
        session.delegate.complete(
            statusCode = status,
            headerFields = mapOf("Content-Type" to "application/json"),
            errorDescription = null,
            taskDescription = taskDescription,
        ) {}
        withContext(Dispatchers.Default) { withTimeout(10_000L) { drained.await() } }
    }

    /** Attempt 0 fails with a 500 and the engine hands attempt 1 off (a fresh session, labelled 1). */
    private suspend fun failFirstAttempt(s: Setup): FakeSession {
        val first: FakeSession = s.sessions.single()
        deliver(first, 500L, first.taskDescription)
        until { s.rows.find(s.itemId)?.state == UploaderItemState.PENDING }
        s.engine.drain()
        until { s.sessions.size == 2 && s.rows.find(s.itemId)?.state == UploaderItemState.IN_FLIGHT }
        assertEquals(1, s.rows.find(s.itemId)?.attempts, "the first failure is charged once")
        return first
    }

    @Test
    fun `a late duplicate of attempt zero's failure is not charged to attempt one in flight`() = runTest {
        val s: Setup = setup()
        val first: FakeSession = failFirstAttempt(s)

        deliver(first, 500L, first.taskDescription) // the late duplicate, stamped 0
        withContext(Dispatchers.Default) { delay(300L) }

        val row: UploaderItem = assertNotNull(s.rows.find(s.itemId))
        assertEquals(1, row.attempts, "the stale duplicate was charged to the retry: attempts=${row.attempts}")
        assertEquals(UploaderItemState.IN_FLIGHT, row.state, "the stale duplicate moved the retry out of flight")
        // The retry still delivers.
        deliver(s.sessions.last(), 201L, s.sessions.last().taskDescription)
        until { s.rows.find(s.itemId) == null }
        assertEquals(1, s.handler.delivered.value)
    }

    @Test
    fun `a late duplicate of attempt zero after attempt one was delivered changes nothing`() = runTest {
        val s: Setup = setup()
        val first: FakeSession = failFirstAttempt(s)
        deliver(s.sessions.last(), 201L, s.sessions.last().taskDescription)
        until { s.rows.find(s.itemId) == null }
        withContext(Dispatchers.Default) { delay(200L) }
        val settledBefore: Int = s.handler.settled.value // the failure of attempt 0 and the delivery of attempt 1

        deliver(first, 500L, first.taskDescription)
        withContext(Dispatchers.Default) { delay(300L) }

        assertNull(s.rows.find(s.itemId))
        assertEquals(1, s.handler.delivered.value, "the delivery ran ${s.handler.delivered.value} times")
        assertEquals(settledBefore, s.handler.settled.value, "the stale duplicate ran onSettled again")
        val journal = OutcomeJournal(IosOutcomeStorage(s.dir), UploaderClock.System)
        assertTrue(journal.inspect(s.itemId) is JournalEntry.Absent, "the stale duplicate left an entry behind")
    }
}
