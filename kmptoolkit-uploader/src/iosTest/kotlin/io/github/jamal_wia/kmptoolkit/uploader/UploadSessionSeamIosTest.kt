package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.concurrent.AtomicInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURLSession
import platform.Foundation.NSUUID

/**
 * The iOS transport against a scripted session ([UploadSession]) instead of `nsurlsessiond`: the terminal
 * event racing a hand-off's body write, a replacement session waiting for its predecessor's invalidation,
 * and the drained callback's at-most-once guarantee.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class UploadSessionSeamIosTest {

    private val dir: String = NSTemporaryDirectory() + "/kmptoolkit-seam-test-" + NSUUID().UUIDString() + "/outcomes"
    private val scopes = mutableListOf<CoroutineScope>()
    private val bodies = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
        bodies.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    /** A session that behaves like Foundation where it matters: a task on an invalidated session raises. */
    private class FakeSession(val delegate: UploadSessionDelegate) : UploadSession {
        private val tasks = AtomicInt(0)
        private val created = AtomicInt(0)
        private val invalidationRequested = AtomicInt(0)

        private val lastTaskDescription = kotlin.concurrent.AtomicReference<String?>(null)

        /** The `taskDescription` the latest task was created with, as Foundation would report it back at completion. */
        val taskDescription: String? get() = lastTaskDescription.value

        val tasksCreated: Int get() = created.value
        val invalidationWasRequested: Boolean get() = invalidationRequested.value == 1

        override fun getTaskCount(callback: (Int) -> Unit) = callback(tasks.value)

        override fun cancelTasks(callback: (Int) -> Unit) = callback(0)

        override fun startUploadTask(request: NSMutableURLRequest, bodyPath: String, taskDescription: String?) {
            check(invalidationRequested.value == 0) { "a task was created on an invalidated session" }
            lastTaskDescription.value = taskDescription
            created.incrementAndGet()
            tasks.incrementAndGet()
        }

        // Like the real one, this only REQUESTS invalidation; the test reports it done through the delegate.
        override fun invalidateAndCancel() {
            invalidationRequested.value = 1
        }

        override fun finishTasksAndInvalidate() {
            invalidationRequested.value = 1
        }
    }

    private class Harness(val transport: BackgroundSessionUploadTransport, private val sessions: AtomicReferenceList) {
        val created: List<FakeSession> get() = sessions.items()
    }

    private class AtomicReferenceList {
        private val ref = kotlin.concurrent.AtomicReference<List<FakeSession>>(emptyList())
        fun items(): List<FakeSession> = ref.value
        fun add(item: FakeSession) {
            while (true) {
                val current: List<FakeSession> = ref.value
                if (ref.compareAndSet(current, current + item)) return
            }
        }
    }

    private fun harness(
        invalidationWait: Duration = 30.seconds,
        onBodyWritten: (List<FakeSession>) -> Unit = {},
    ): Harness {
        val prefix = "kmptoolkit.test.seam.${NSUUID().UUIDString}."
        val sessions = AtomicReferenceList()
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val transport = BackgroundSessionUploadTransport(
            identifierPrefix = prefix,
            config = BackgroundUploadConfig(sessionIdentifierPrefix = prefix),
            logger = NoopLogger,
            journal = journal,
            sessionFactory = UploadSessionFactory { _, delegate -> FakeSession(delegate).also(sessions::add) },
            invalidationWait = invalidationWait,
            onBodyWritten = { onBodyWritten(sessions.items()) },
        )
        return Harness(transport, sessions)
    }

    private fun request() = UploadRequest(url = "https://upload.invalid/x", fields = listOf(UploadField.Text("a", "b")))

    private fun newItem(): String = "seam-" + NSUUID().UUIDString().also { bodies += temporaryBodyPath("seam-$it") }

    private suspend fun until(timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMillis) { while (!condition()) delay(10L) }
        }
    }

    private fun FakeSession.terminalError() =
        delegate.complete(statusCode = -1L, headerFields = null, errorDescription = "boom") { invalidateAndCancel() }

    // --- #1: a terminal event while the body is written ---------------------------------------------------

    @Test
    fun `a terminal event arriving while the body is written starts no task on the invalidated session`() {
        val itemId: String = newItem()
        var delivered = false
        val h: Harness = harness(onBodyWritten = { sessions ->
            if (!delivered) {
                delivered = true
                sessions.single().terminalError()
            }
        })

        h.transport.launch(itemId, isRehandOff = false, request = request())

        val session: FakeSession = h.created.single()
        assertEquals(0, session.tasksCreated, "no task may be created on a session the terminal event invalidated")
        assertTrue(session.invalidationWasRequested)
        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath(temporaryBodyPath(itemId)),
            "the body is removed when no task takes it",
        )
    }

    @Test
    fun `any buffered event while the body is written also keeps the hand-off from starting a task`() {
        val itemId: String = newItem()
        val h: Harness = harness(onBodyWritten = { sessions -> sessions.single().delegate.receivedAnyEvent = true })

        h.transport.launch(itemId, isRehandOff = false, request = request())

        assertEquals(0, h.created.single().tasksCreated)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(temporaryBodyPath(itemId)))
    }

    @Test
    fun `without a buffered event the hand-off starts exactly one task`() {
        val itemId: String = newItem()
        val h: Harness = harness()

        h.transport.launch(itemId, isRehandOff = false, request = request())

        assertEquals(1, h.created.single().tasksCreated)
    }

    // --- #8: a replacement session waits for its predecessor ----------------------------------------------

    @Test
    fun `a re-hand opens the next session only after the previous one reported itself invalid`() = runTest {
        val itemId: String = newItem()
        val h: Harness = harness()
        h.transport.launch(itemId, isRehandOff = false, request = request())
        val first: FakeSession = h.created.single()
        first.terminalError() // the attempt failed; its session is invalidated, not yet invalid

        h.transport.launch(itemId, isRehandOff = false, request = request())
        withContext(Dispatchers.Default) { delay(300L) }
        assertEquals(1, h.created.size, "the replacement must wait for the predecessor's invalidation")

        first.delegate.reportInvalidated()
        until { h.created.size == 2 }
        until { h.created[1].tasksCreated == 1 }
        assertEquals(1, h.created[1].tasksCreated)
    }

    @Test
    fun `a predecessor that never reports invalid delays the replacement only for the bounded wait`() = runTest {
        val itemId: String = newItem()
        val h: Harness = harness(invalidationWait = 400.milliseconds)
        h.transport.launch(itemId, isRehandOff = false, request = request())
        h.created.single().terminalError()

        h.transport.launch(itemId, isRehandOff = false, request = request())

        until { h.created.size == 2 }
        until { h.created[1].tasksCreated == 1 }
    }

    @Test
    fun `an already invalid predecessor does not delay the replacement`() = runTest {
        val itemId: String = newItem()
        val h: Harness = harness()
        h.transport.launch(itemId, isRehandOff = false, request = request())
        val first: FakeSession = h.created.single()
        first.terminalError()
        first.delegate.reportInvalidated()

        h.transport.launch(itemId, isRehandOff = false, request = request())

        assertEquals(2, h.created.size, "claimed at once, on the caller's thread")
    }

    // --- review M1: a cancel while the hand-off waits for its predecessor ---------------------------------

    @Test
    fun `a cancel while a hand-off waits for its predecessor keeps the withdrawn item from uploading`() = runTest {
        val itemId: String = newItem()
        val h: Harness = harness()
        h.transport.launch(itemId, isRehandOff = false, request = request())
        val first: FakeSession = h.created.single()
        first.terminalError()
        h.transport.launch(itemId, isRehandOff = false, request = request()) // deferred behind the predecessor
        assertEquals(1, h.created.size)

        h.transport.cancel(itemId)
        assertEquals(1, h.created.size, "cancel must not open an orphan session beside the invalidating one")
        first.delegate.reportInvalidated()
        withContext(Dispatchers.Default) { delay(500L) }

        assertEquals(1, h.created.size, "the withdrawn item must not get a new session")
        assertTrue(h.created.drop(1).all { it.tasksCreated == 0 })
    }

    @Test
    fun `a cancel while the predecessor is silent still withdraws the deferred hand-off after the bounded wait`() = runTest {
        val itemId: String = newItem()
        val h: Harness = harness(invalidationWait = 300.milliseconds)
        h.transport.launch(itemId, isRehandOff = false, request = request())
        h.created.single().terminalError()
        h.transport.launch(itemId, isRehandOff = false, request = request())

        h.transport.cancel(itemId)
        withContext(Dispatchers.Default) { delay(900L) } // the wait timed out meanwhile

        assertTrue(h.created.drop(1).all { it.tasksCreated == 0 }, "no task for a withdrawn item")
    }

    @Test
    fun `a launch after the cancel is not blocked by it`() = runTest {
        val itemId: String = newItem()
        val h: Harness = harness()
        h.transport.launch(itemId, isRehandOff = false, request = request())
        val first: FakeSession = h.created.single()
        first.terminalError()
        h.transport.launch(itemId, isRehandOff = false, request = request())
        h.transport.cancel(itemId)

        h.transport.launch(itemId, isRehandOff = false, request = request()) // a fresh enqueue of the same id
        first.delegate.reportInvalidated()

        until { h.created.size == 2 }
        until { h.created[1].tasksCreated == 1 }
    }

    // --- leaks: a session dropped without a task must end its progress forwarder -------------------------

    private val FakeSession.forwarderEnded: Boolean get() = delegate.forwarder.isCompleted

    @Test
    fun `a session that reports itself invalid ends its progress forwarder`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val delegate = UploadSessionDelegate("seam-leak-invalid", scope, NoopLogger, journal, NSLock(), {})
        assertFalse(delegate.forwarder.isCompleted)

        delegate.reportInvalidated()

        until { delegate.forwarder.isCompleted }
    }

    @Test
    fun `cancelling an item that is not live here ends the orphan session's forwarder`() = runTest {
        val h: Harness = harness()

        h.transport.cancel(newItem()) // the fake reports 0 cancelled tasks and never a completion

        until { h.created.single().forwarderEnded }
    }

    @Test
    fun `cancelling a live session that has no completion ends its forwarder`() = runTest {
        val itemId: String = newItem()
        val h: Harness = harness()
        h.transport.launch(itemId, isRehandOff = false, request = request())
        val session: FakeSession = h.created.single()
        assertFalse(session.forwarderEnded)

        h.transport.cancel(itemId)

        until { session.forwarderEnded }
    }

    @Test
    fun `a hand-off that fails before it creates a task ends its session's forwarder`() = runTest {
        val itemId: String = newItem()
        val h: Harness = harness()
        val unreadable = UploadField.File("f", "f.bin", "application/octet-stream", "/nonexistent/${NSUUID().UUIDString()}")

        h.transport.launch(itemId, isRehandOff = false, request = UploadRequest(url = "https://upload.invalid/x", fields = listOf(unreadable)))

        val session: FakeSession = h.created.single()
        assertEquals(0, session.tasksCreated)
        until { session.forwarderEnded }
    }

    @Test
    fun `an idle relaunch session released after its events drain ends its forwarder`() = runTest {
        val itemId: String = newItem()
        val h: Harness = harness()
        h.transport.handleRelaunchEvents(itemId) {}
        val session: FakeSession = h.created.single()
        assertFalse(session.forwarderEnded)

        session.delegate.URLSessionDidFinishEventsForBackgroundURLSession(NSURLSession.sharedSession)

        until { session.invalidationWasRequested }
        until { session.forwarderEnded }
    }

    @Test
    fun `repeated cancels of idle items leave no forwarder behind`() = runTest {
        val h: Harness = harness()

        repeat(25) { h.transport.cancel(newItem()) }

        until { h.created.size == 25 && h.created.all { it.forwarderEnded } }
    }

    // --- #11: the drained callback runs once -------------------------------------------------------------

    @Test
    fun `racing drained signals run the callback exactly once`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        repeat(300) {
            val delegate = UploadSessionDelegate("seam-drain-$it", scope, NoopLogger, journal, NSLock(), {})
            val runs = AtomicInt(0)
            delegate.notifyWhenDrained { runs.incrementAndGet() }

            withContext(Dispatchers.Default) {
                (1..8).map {
                    async { delegate.URLSessionDidFinishEventsForBackgroundURLSession(NSURLSession.sharedSession) }
                }.awaitAll()
            }

            assertEquals(1, runs.value, "iteration $it")
        }
    }

    @Test
    fun `a drained signal before registration still runs the callback`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val delegate = UploadSessionDelegate("seam-drain-early", scope, NoopLogger, journal, NSLock(), {})
        var runs = 0

        delegate.URLSessionDidFinishEventsForBackgroundURLSession(NSURLSession.sharedSession)
        delegate.notifyWhenDrained { runs++ }

        assertEquals(1, runs)
    }

    @Test
    fun `two registrations both run once`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val delegate = UploadSessionDelegate("seam-drain-two", scope, NoopLogger, journal, NSLock(), {})
        var first = 0
        var second = 0

        delegate.notifyWhenDrained { first++ }
        delegate.notifyWhenDrained { second++ }
        delegate.URLSessionDidFinishEventsForBackgroundURLSession(NSURLSession.sharedSession)
        delegate.URLSessionDidFinishEventsForBackgroundURLSession(NSURLSession.sharedSession)

        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun `a new wake does not fire on an earlier wake's finished events`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val delegate = UploadSessionDelegate("seam-drain-wake", scope, NoopLogger, journal, NSLock(), {})
        var first = 0
        var second = 0

        delegate.notifyWhenDrained { first++ }
        delegate.URLSessionDidFinishEventsForBackgroundURLSession(NSURLSession.sharedSession)
        assertEquals(1, first)

        delegate.startWake()
        delegate.notifyWhenDrained { second++ }
        assertEquals(0, second, "the earlier wake's signal must not count")

        delegate.URLSessionDidFinishEventsForBackgroundURLSession(NSURLSession.sharedSession)
        assertEquals(1, second)
        assertEquals(1, first)
    }

    @Test
    fun `a signal that landed in this wake before registration is kept after startWake`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val delegate = UploadSessionDelegate("seam-drain-wake-early", scope, NoopLogger, journal, NSLock(), {})
        var runs = 0

        delegate.startWake()
        delegate.URLSessionDidFinishEventsForBackgroundURLSession(NSURLSession.sharedSession)
        delegate.notifyWhenDrained { runs++ }

        assertEquals(1, runs)
    }

    @Test
    fun `a relaunch wake on a still live session waits for its own finished events`() = runTest {
        val itemId: String = newItem()
        val h: Harness = harness()
        var first = 0
        var second = 0
        h.transport.handleRelaunchEvents(itemId) { first++ }
        val session: FakeSession = h.created.single()
        // Events were delivered, so the idle release declines and the session stays live for the next wake.
        session.delegate.receivedAnyEvent = true
        session.delegate.URLSessionDidFinishEventsForBackgroundURLSession(NSURLSession.sharedSession)
        until { first == 1 }

        h.transport.handleRelaunchEvents(itemId) { second++ }
        assertEquals(1, h.created.size, "the wake joins the live session")
        assertEquals(0, second, "the earlier wake's finished events must not release this wake's handler")

        session.delegate.URLSessionDidFinishEventsForBackgroundURLSession(NSURLSession.sharedSession)
        until { second == 1 }
        assertEquals(1, first)
    }

    @Test
    fun `a settled terminal event runs a callback registered afterwards`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val delegate = UploadSessionDelegate("seam-drain-settled", scope, NoopLogger, journal, NSLock(), {})
        var early = 0
        var late = 0

        // A cancelled session settles nothing and so reaches the end of the settlement without an engine.
        delegate.cancelled = true
        delegate.notifyWhenDrained { early++ }
        delegate.complete(statusCode = -1L, headerFields = null, errorDescription = "boom") {}
        // The settlement runs on the scope; the registered callback fires once it is settled.
        until { early == 1 }

        delegate.notifyWhenDrained { late++ }
        assertEquals(1, late, "a signal that already happened is remembered")
        delegate.startWake()
        delegate.notifyWhenDrained { late++ }
        assertEquals(2, late, "the settlement is permanent across wakes")
        assertEquals(1, early)
    }
}
