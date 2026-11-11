package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.concurrent.AtomicInt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * `launch` and `cancel` of the iOS transport racing on real threads, over scripted sessions that answer like the real
 * ones: the task count and the invalidation are reported later, from another thread. Two rules of `NSURLSession` that
 * the library must keep, because breaking them raises an Objective-C exception Kotlin cannot catch or is undefined: a
 * task is never created on an invalidated session, and two live sessions never share one identifier.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosCancelLaunchStormRound5AdversarialTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-cancelstorm-" + NSUUID().UUIDString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bodies = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        bodies.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private val violationsTaskOnInvalidated = AtomicInt(0)
    private val violationsTwoLive = AtomicInt(0)
    private val liveByIdentifier = HashMap<String, Int>()
    private val liveLock = platform.Foundation.NSLock()

    private inner class AsyncSession(val identifier: String, val delegate: UploadSessionDelegate) : UploadSession {
        private val invalidated = AtomicInt(0)
        private val tasks = AtomicInt(0)
        val tasksCreated: Int get() = tasks.value
        val isInvalidated: Boolean get() = invalidated.value == 1

        init {
            liveLock.lock()
            try {
                val live: Int = (liveByIdentifier[identifier] ?: 0) + 1
                liveByIdentifier[identifier] = live
                if (live > 1) violationsTwoLive.incrementAndGet()
            } finally {
                liveLock.unlock()
            }
        }

        private fun later(block: () -> Unit) {
            scope.launch {
                delay(Random.nextLong(0, 15))
                block()
            }
        }

        override fun getTaskCount(callback: (Int) -> Unit) = later { callback(tasks.value) }
        override fun cancelTasks(callback: (Int) -> Unit) = later { callback(0) }

        override fun startUploadTask(request: NSMutableURLRequest, bodyPath: String, taskDescription: String?) {
            if (isInvalidated) violationsTaskOnInvalidated.incrementAndGet()
            tasks.incrementAndGet()
        }

        private fun invalidate() {
            if (invalidated.compareAndSet(0, 1)) {
                liveLock.lock()
                try {
                    liveByIdentifier[identifier] = (liveByIdentifier[identifier] ?: 1) - 1
                } finally {
                    liveLock.unlock()
                }
                later { delegate.reportInvalidated() }
            }
        }

        override fun invalidateAndCancel() = invalidate()
        override fun finishTasksAndInvalidate() = invalidate()
    }

    private val sessions = mutableListOf<AsyncSession>()
    private val sessionsLock = platform.Foundation.NSLock()

    private fun transport(prefix: String): BackgroundSessionUploadTransport = BackgroundSessionUploadTransport(
        identifierPrefix = prefix,
        config = BackgroundUploadConfig(sessionIdentifierPrefix = prefix, rehandFlushWindow = 20.milliseconds),
        logger = NoopLogger,
        journal = OutcomeJournal(IosOutcomeStorage(root + "/outcomes"), UploaderClock.System),
        sessionFactory = UploadSessionFactory { identifier, delegate ->
            AsyncSession(identifier, delegate).also {
                sessionsLock.lock()
                try {
                    sessions += it
                } finally {
                    sessionsLock.unlock()
                }
            }
        },
        invalidationWait = 500.milliseconds,
    )

    private inline fun <T> platform.Foundation.NSLock.under(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    private fun request(): UploadRequest = UploadRequest(url = "https://upload.invalid/x", fields = listOf(UploadField.Text("a", "b")))

    @Test
    fun `launches and cancels racing on six items never create a task on an invalidated session nor two live sessions of one identifier`() = runTest {
        val prefix = "kmptoolkit.test.cancelstorm.${NSUUID().UUIDString}."
        val transport: BackgroundSessionUploadTransport = transport(prefix)
        val items: List<String> = (1..6).map { "storm-$it-" + NSUUID().UUIDString }
        items.forEach { bodies += temporaryBodyPath(it) }
        withContext(Dispatchers.Default) {
            val random = Random(7)
            (0 until 25).map { round ->
                async {
                    items.map { item ->
                        async {
                            delay(random.nextLong(0, 10))
                            val ops: List<() -> Unit> = listOf(
                                { transport.launch(item, isRehandOff = false, request = request()) },
                                { transport.cancel(item) },
                                { transport.launch(item, isRehandOff = (round % 2 == 0), request = request()) },
                            ).shuffled(random)
                            ops.map { op ->
                                async {
                                    delay(Random.nextLong(0, 8))
                                    op()
                                }
                            }.awaitAll()
                        }
                    }.awaitAll()
                }
            }.awaitAll()
            delay(1_000L) // let every deferred hand-off, invalidation and task count land
        }

        assertEquals(0, violationsTaskOnInvalidated.value, "a task was created on an invalidated session")
        assertEquals(0, violationsTwoLive.value, "two live sessions shared one identifier")

        // Afterwards the item is usable: one cancel, one fresh hand-off, one task on a live session.
        withContext(Dispatchers.Default) {
            for (item in items) {
                transport.cancel(item)
                delay(100L)
            }
            delay(300L)
            val before: Map<String, Int> = sessionsLock.under { sessions.groupBy { it.identifier }.mapValues { (_, list) -> list.sumOf { it.tasksCreated } } }
            for (item in items) transport.launch(item, isRehandOff = false, request = request())
            delay(1_500L)
            for (item in items) {
                val identifier: String = prefix + item
                val now: Int = sessionsLock.under { sessions.filter { it.identifier == identifier }.sumOf { it.tasksCreated } }
                assertEquals(
                    (before[identifier] ?: 0) + 1,
                    now,
                    "$item: the fresh hand-off after the storm started ${now - (before[identifier] ?: 0)} tasks (expected exactly one)",
                )
                val live: List<AsyncSession> = sessionsLock.under { sessions.filter { it.identifier == identifier && !it.isInvalidated } }
                assertTrue(live.size == 1, "$item: ${live.size} live sessions after the storm")
            }
        }
        assertEquals(0, violationsTaskOnInvalidated.value)
        assertEquals(0, violationsTwoLive.value)
    }
}
