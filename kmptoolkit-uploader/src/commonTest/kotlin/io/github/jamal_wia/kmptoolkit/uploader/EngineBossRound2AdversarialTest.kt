package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A1 final boss round 2: the three pieces boss round 1 added (the process-wide delivered-but-unconfirmed memory, the
 * release wake of [ClaimsInDoubt], drain() as a pass scope close() cancels) read against each other and against the
 * older protocol rules P2-P6. Every test is bounded by virtual time or by a real-time withTimeout.
 */
class EngineBossRound2AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    private class Transport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 15 * 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events += "launch:$itemId"
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += if (isRehandOff) "relaunch:$itemId" else "launch:$itemId"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }
    }

    private class UploadingHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "boss2.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    /**
     * A store that hops threads like the SQLDelight one: the first claim commits and its closed caller hears of the
     * cancellation on the way back. The release that follows writes through a second gate, so a test can look at the
     * world while the release write is still running.
     */
    private class GatedReleaseStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        val claimEntered: CompletableDeferred<Unit> = CompletableDeferred()
        val claimGate: CompletableDeferred<Unit> = CompletableDeferred()
        val releaseEntered: CompletableDeferred<Unit> = CompletableDeferred()
        val releaseGate: CompletableDeferred<Unit> = CompletableDeferred()
        var gateRelease: Boolean = false
        private var firstClaim = true
        private var releasing = false

        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            if (releasing) {
                // The re-hand branch of a release: a markInFlight back to the expired lease.
                releaseEntered.complete(Unit)
                if (gateRelease) withContext(NonCancellable) { releaseGate.await() }
                return inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
            }
            val written: Boolean = inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
            if (firstClaim) {
                firstClaim = false
                claimEntered.complete(Unit)
                withContext(NonCancellable) { claimGate.await() }
                releasing = true
                currentCoroutineContext().ensureActive()
            }
            return written
        }

        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean {
            if (releasing) {
                releaseEntered.complete(Unit)
                if (gateRelease) withContext(NonCancellable) { releaseGate.await() }
                releasing = false
            }
            return inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }
    }

    /** Fails the next [failures] confirming deletes as a locked database would: nothing written. */
    private class FlakyDeleteStore(private val inner: TestUploaderStore, var failures: Int) : UploaderStore by inner {
        override suspend fun deleteById(id: String) {
            if (failures > 0) {
                failures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    /**
     * Rule P3 plus D-31: the release is announced after its write. A replacement woken while the write is still running
     * would read the claim live, arm its alarm for the lease and never hear of the release again.
     */
    @Test
    fun `a release announced only after its write reaches the replacement while the write is still running`() = runTest {
        val parents: List<Job> = List(2) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = GatedReleaseStore(inner).apply { gateRelease = true }
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val clock = TestClock(1_000_000L)
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[0]), clock)
            val id: String = first.enqueue(handler, "avatar")!!
            first.start()
            runCurrent()
            assertTrue(store.claimEntered.isCompleted, "setup: the claim committed and its caller has not heard back")
            first.close()
            val second: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parents[1]), clock)
            second.start()
            runCurrent()
            assertEquals(emptyList(), transport.events, "setup: the replacement sees the live claim and waits")

            store.claimGate.complete(Unit)
            runCurrent()
            assertTrue(store.releaseEntered.isCompleted, "setup: the release write is running")
            assertEquals(emptyList(), transport.events, "the replacement launched while the claim was still written")
            store.releaseGate.complete(Unit)
            runCurrent()
            assertEquals(listOf("launch:$id"), transport.events, "the release write finished but the replacement was not woken after it")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    /** D-31: an engine that ends disposes only its own wake; the replacement's registration must survive it. */
    @Test
    fun `the end of an unrelated engine leaves the replacement's release wake in place`() = runTest {
        val parents: List<Job> = List(3) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = GatedReleaseStore(inner)
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val clock = TestClock(1_000_000L)
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[0]), clock)
            val id: String = first.enqueue(handler, "avatar")!!
            first.start()
            runCurrent()
            first.close()
            val replacement: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parents[1]), clock)
            replacement.start()
            runCurrent()
            val unrelated: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), scopeOn(parents[2]), clock)
            unrelated.start()
            runCurrent()
            unrelated.close()
            parents[2].cancel()
            runCurrent()
            assertEquals(emptyList(), transport.events, "setup: the replacement waits on the live claim")

            store.claimGate.complete(Unit)
            runCurrent()
            assertEquals(listOf("launch:$id"), transport.events, "an unrelated engine's end took the replacement's wake with it")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    /**
     * The release's other branch: a re-hand of an expired lease that was interrupted by close() goes back to IN_FLIGHT
     * under the old, expired lease. The woken replacement re-hands at once and tells the transport it is a re-hand-off.
     */
    @Test
    fun `a released re-hand of an expired lease is re-handed by the replacement at once as a re-hand-off`() = runTest {
        val parents: List<Job> = List(2) { Job() }
        try {
            val inner = TestUploaderStore()
            val clock = TestClock(1_000_000L)
            inner.insertKeep(
                UploaderItem(
                    id = "boss2-rehand", type = "boss2.upload", payload = "p", schemaVersion = 1, uniqueKey = null,
                    orderingKey = null, tag = null, state = UploaderItemState.IN_FLIGHT, attempts = 2,
                    nextRunAtEpochMillis = 0L, createdAtEpochMillis = 1L, lastError = "earlier",
                    leaseUntilEpochMillis = clock.millis - 1L, claim = 7L,
                ),
            )
            val store = GatedReleaseStore(inner)
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[0]), clock)
            first.start()
            runCurrent()
            assertTrue(store.claimEntered.isCompleted, "setup: the re-hand claim committed")
            first.close()
            val second: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parents[1]), clock)
            second.start()
            runCurrent()
            assertEquals(emptyList(), transport.events, "setup: the replacement waits on the live re-hand claim")

            store.claimGate.complete(Unit)
            runCurrent()
            assertEquals(listOf("relaunch:boss2-rehand"), transport.events, "the released re-hand was not re-handed at once")
            val row: UploaderItem? = inner.find("boss2-rehand")
            assertEquals(UploaderItemState.IN_FLIGHT, row?.state)
            assertEquals(2, row?.attempts, "the interrupted re-hand spent an attempt")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    /** S3 read with S4: the claim write of a drain() the owner called is interrupted by close(); the drain returns. */
    @Test
    fun `a manual drain closed during its claim returns and its release wakes the started replacement`() = runTest {
        val parents: List<Job> = List(2) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = GatedReleaseStore(inner)
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val clock = TestClock(1_000_000L)
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[0]), clock)
            val id: String = first.enqueue(handler, "avatar")!!
            var failure: Throwable? = null
            val drain: Job = launch {
                try {
                    first.drain()
                } catch (e: Throwable) {
                    failure = e
                }
            }
            runCurrent()
            assertTrue(store.claimEntered.isCompleted, "setup: the manual drain's claim committed")
            val second: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parents[1]), clock)
            second.start()
            runCurrent()
            first.close()
            store.claimGate.complete(Unit)
            runCurrent()
            assertTrue(drain.isCompleted, "the manual drain did not return after close")
            assertNull(failure, "the manual drain threw after a close")
            assertEquals(listOf("launch:$id"), transport.events, "the closed manual drain launched, or the replacement was not woken")
            assertEquals(UploaderItemState.IN_FLIGHT, inner.find(id)?.state)
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    /** P2 with S1: a started engine closed by its own handler; the success is recorded, the failed delete remembered. */
    @Test
    fun `a success answered by a handler that closed its engine is remembered when the delete fails`() = runTest {
        val parents: List<Job> = List(2) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 1)
            val clock = TestClock(1_000_000L)
            lateinit var first: UploaderEngine
            val handler = TestHandler(onExecute = { _, _ ->
                first.close()
                AttemptResult.Success
            })
            first = testEngine(store, listOf(handler), scopeOn(parents[0]), clock, idGenerator = SequentialIds("b2-p2")::next)
            val id: String = first.enqueue(handler, "send")!!
            first.start()
            runCurrent()
            assertEquals(1, handler.payloads.size, "setup: the effect ran once")
            assertEquals(UploaderItemState.PENDING, inner.find(id)?.state, "setup: the delete failed")

            clock.millis += 60_000L
            val second: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[1]), clock)
            second.drain()
            assertEquals(1, handler.payloads.size, "the replacement ran an effect a closed engine had delivered")
            assertNull(inner.find(id), "the replacement did not confirm the delivery")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    /** P6 bound: a closed engine stays registered for the release wake only while an attempt ignores its cancellation. */
    @Test
    fun `a closed engine whose attempt ignores the cancellation unregisters its wake when the attempt ends`() = runTest {
        val parent = Job()
        try {
            val before: Int = ClaimsInDoubt.wakeCount
            val inner = TestUploaderStore()
            val stuck = CompletableDeferred<Unit>()
            val handler = TestHandler(onExecute = { _, _ ->
                withContext(NonCancellable) { stuck.await() }
                AttemptResult.Success
            })
            val engine: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parent), TestClock(1_000L), idGenerator = SequentialIds("b2-p6")::next)
            val id: String = engine.enqueue(handler, "x")!!
            engine.start()
            runCurrent()
            assertEquals(before + 1, ClaimsInDoubt.wakeCount)
            engine.close()
            runCurrent()
            assertEquals(before + 1, ClaimsInDoubt.wakeCount, "setup: the engine job waits for its stuck attempt")
            stuck.complete(Unit)
            runCurrent()
            assertEquals(before, ClaimsInDoubt.wakeCount, "the closed engine stayed registered after its last attempt ended")
            assertNull(inner.find(id), "the success of the attempt that outlived close was not recorded (P2)")
        } finally {
            parent.cancel()
        }
    }

    /** start() racing close() on real threads never leaves a release wake registered once the scope is gone. */
    @Test
    fun `start racing close on real threads leaves no release wake behind`() = runTest(timeout = 60.seconds) {
        val before: Int = ClaimsInDoubt.wakeCount
        withContext(Dispatchers.Default) {
            withTimeout(45_000L) {
                repeat(300) { round ->
                    val job = SupervisorJob()
                    val scope = CoroutineScope(Dispatchers.Default + job)
                    val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), scope, TestClock(1_000L))
                    val calls = listOf(
                        async { engine.start() },
                        async { engine.close() },
                        async { if (round % 2 == 0) engine.start() else engine.trigger() },
                    )
                    calls.awaitAll()
                    job.cancel()
                    job.join()
                }
                while (ClaimsInDoubt.wakeCount != before) delay(5L)
            }
        }
        assertEquals(before, ClaimsInDoubt.wakeCount)
    }

    /**
     * A wake announced from many threads while engines register and end: no callback is lost for an engine that stays
     * registered, and nothing throws (the snapshot is taken outside the update).
     */
    @Test
    fun `announcing releases while engines come and go on real threads wakes the engine that stays`() = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Default) {
            withTimeout(45_000L) {
                val woken = CompletableDeferred<Unit>()
                var armed = false
                val stay: kotlinx.coroutines.DisposableHandle = ClaimsInDoubt.onRelease { if (armed) woken.complete(Unit) }
                try {
                    val churn = List(4) {
                        launch {
                            repeat(2_000) {
                                val handle: kotlinx.coroutines.DisposableHandle = ClaimsInDoubt.onRelease { }
                                ClaimsInDoubt.announceRelease()
                                handle.dispose()
                            }
                        }
                    }
                    churn.forEach { it.join() }
                    armed = true
                    ClaimsInDoubt.announceRelease()
                    woken.await()
                } finally {
                    stay.dispose()
                }
            }
        }
        assertFalse(ClaimsInDoubt.wakeCount < 0)
    }

    /**
     * Heartbeat sanity for the wake: once the replacement launched the released item, ten minutes of heartbeats and
     * releases of other items do not launch it a second time while its lease runs.
     */
    @Test
    fun `releases of other items do not launch the replacement's live claim again`() = runTest {
        val parents: List<Job> = List(2) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = GatedReleaseStore(inner)
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val clock = TestClock(1_000_000L)
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[0]), clock)
            val id: String = first.enqueue(handler, "avatar")!!
            first.start()
            runCurrent()
            first.close()
            val second: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parents[1]), clock)
            second.start()
            runCurrent()
            store.claimGate.complete(Unit)
            runCurrent()
            assertEquals(listOf("launch:$id"), transport.events, "setup: the replacement launched once")
            repeat(20) {
                ClaimsInDoubt.announceRelease()
                advanceTimeBy(30_000L)
                clock.millis += 30_000L
                runCurrent()
            }
            assertEquals(listOf("launch:$id"), transport.events, "a wake re-handed a claim whose lease is still running")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    /**
     * A REPLACE keeps the delivered entry of the row it supersedes because it may be rolled back (D-29 S1 as ruled by
     * boss 2): a reused id with a later creation time is another item, so the replacement runs.
     */
    @Test
    fun `a REPLACE under a reused id runs the replacement`() = runTest {
        val parents: List<Job> = List(2) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 1)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val sameId: () -> String = { "b2-replace-reused-id" }
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[0]), clock, idGenerator = sameId)
            first.enqueue(handler, "payload", uniqueKey = "draft")!!
            first.drain()
            first.close()
            assertEquals(1, handler.payloads.size, "setup: the effect ran and its delete failed")

            val second: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[1]), clock, idGenerator = sameId)
            clock.millis += 1
            second.enqueue(handler, "payload", uniqueKey = "draft", conflictPolicy = ConflictPolicy.REPLACE)!!
            second.drain()
            assertEquals(2, handler.payloads.size, "the replacement was taken for the superseded delivered row and removed unrun")
            assertNull(inner.find("b2-replace-reused-id"))
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    /** S4 kept what drain() did before 4.1.0 for a store failure: it reaches the caller, a leaked cancellation too. */
    @Test
    fun `a manual drain passes a store failure and a leaked cancellation to its live caller`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val handler = TestHandler()
            val engine: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parent), TestClock(1_000L))
            engine.enqueue(handler, "x")!!
            inner.failNextGetAllActive = IllegalStateException("database is locked")
            assertEquals("database is locked", kotlin.runCatching { engine.drain() }.exceptionOrNull()?.message)
            inner.failNextGetAllActive = kotlinx.coroutines.CancellationException("leaked by the store")
            val leaked: Throwable? = kotlin.runCatching { engine.drain() }.exceptionOrNull()
            assertEquals("leaked by the store", leaked?.message, "a live caller of an open engine lost the store's leaked cancellation")
            engine.drain()
            assertEquals(1, handler.payloads.size)
        } finally {
            parent.cancel()
        }
    }
}
