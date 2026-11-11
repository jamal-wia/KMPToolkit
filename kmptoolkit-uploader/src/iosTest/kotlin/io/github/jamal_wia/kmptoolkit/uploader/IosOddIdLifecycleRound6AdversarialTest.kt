package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.concurrent.AtomicInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
 * Round 6 (resource accounting + contract): an item whose id is not a plain file name ("Any id works on both platforms",
 * docs 05) goes through the whole iOS lifecycle with a real engine, a real journal and a scripted session. The multipart
 * body file exists while the upload runs and is gone after the answer and after a cancel, and the journal holds nothing
 * after either; none of the ids may leave a `kmptoolkit_upload_*` file in the temporary directory.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosOddIdLifecycleRound6AdversarialTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-r6-life-" + NSUUID().UUIDString()
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

    private class Handler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "ios-odd"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        val delivered = AtomicInt(0)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "https://upload.invalid/x", fields = listOf(UploadField.Text("a", "b"))))

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered.incrementAndGet()
        }
    }

    private suspend fun until(what: String, timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMillis) { while (!condition()) delay(10L) }
        }
    }

    private val ids: List<String> = listOf(
        "Photo-AbC", "photo-abc", "photo abc", "\u0416".repeat(60), "\u65E5\u672C\u8A9E", "a/b", "a\\b", "..", "a:b|c*d", "e\u0301", "\uD83D\uDE00", "~_~a", "~x",
        "x".repeat(200), "X".repeat(150), "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF", "abcdefab-abcd-abcd-abcd-abcdefabcdef",
    )

    @Test
    fun `an upload of every kind of id leaves no body file and no journal entry after its answer`() = runTest {
        val dir: String = "$root/outcomes"
        val prefix = "kmptoolkit.test.r6.${NSUUID().UUIDString()}."
        val sessions = mutableListOf<FakeSession>()
        val transport = BackgroundSessionUploadTransport(
            identifierPrefix = prefix,
            config = BackgroundUploadConfig(sessionIdentifierPrefix = prefix),
            logger = NoopLogger,
            journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System),
            sessionFactory = UploadSessionFactory { _, delegate -> FakeSession(delegate).also { sessions += it } },
        )
        val handler = Handler(transport)
        val rows = TestUploaderStore()
        val queue: ArrayDeque<String> = ArrayDeque()
        val engine: UploaderEngine = testEngine(rows, listOf(handler), scope, idGenerator = { queue.removeFirst() })
        UploaderEngineRegistry.register(engine)
        val failures: MutableList<String> = mutableListOf()
        for (id in ids) {
            bodies += temporaryBodyPath(id)
            queue.addLast(id)
            sessions.clear()
            engine.enqueue(handler, "payload")
            engine.drain()
            if (sessions.size != 1 || sessions.single().tasksCreated != 1) {
                failures += "'$id': ${sessions.size} sessions, no single task started"
                continue
            }
            if (!NSFileManager.defaultManager.fileExistsAtPath(temporaryBodyPath(id))) failures += "'$id': no body file while the upload runs"
            val session: FakeSession = sessions.single()
            session.delegate.complete(201L, mapOf("Content-Type" to "text/plain"), null, session.taskDescription) {}
            until("'$id' settles") { rows.find(id) == null }
            until("'$id' loses its body file") { !NSFileManager.defaultManager.fileExistsAtPath(temporaryBodyPath(id)) }
            val left: List<String> = IosOutcomeStorage(dir).itemIds()
            if (left.isNotEmpty()) failures += "'$id': journal still holds $left"
        }
        assertEquals(emptyList(), failures)
        assertEquals(ids.size, handler.delivered.value)
    }

    @Test
    fun `a cancel of every kind of id removes its body file and journals nothing`() = runTest {
        val dir: String = "$root/outcomes"
        val prefix = "kmptoolkit.test.r6c.${NSUUID().UUIDString()}."
        val sessions = mutableListOf<FakeSession>()
        val transport = BackgroundSessionUploadTransport(
            identifierPrefix = prefix,
            config = BackgroundUploadConfig(sessionIdentifierPrefix = prefix),
            logger = NoopLogger,
            journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System),
            sessionFactory = UploadSessionFactory { _, delegate -> FakeSession(delegate).also { sessions += it } },
        )
        val handler = Handler(transport)
        val rows = TestUploaderStore()
        val queue: ArrayDeque<String> = ArrayDeque()
        val engine: UploaderEngine = testEngine(rows, listOf(handler), scope, idGenerator = { queue.removeFirst() })
        UploaderEngineRegistry.register(engine)
        val failures: MutableList<String> = mutableListOf()
        for (id in ids) {
            bodies += temporaryBodyPath(id)
            queue.addLast(id)
            sessions.clear()
            engine.enqueue(handler, "payload")
            engine.drain()
            if (!NSFileManager.defaultManager.fileExistsAtPath(temporaryBodyPath(id))) failures += "'$id': no body file while the upload runs"
            engine.cancel(id)
            try {
                until("'$id' loses its body file", 3_000L) { !NSFileManager.defaultManager.fileExistsAtPath(temporaryBodyPath(id)) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                failures += "'$id': the body file survived the cancel"
            }
            if (rows.find(id) != null) failures += "'$id': the row survived the cancel"
        }
        assertEquals(emptyList(), failures)
        assertNull(IosOutcomeStorage(dir).itemIds().firstOrNull())
    }
}
