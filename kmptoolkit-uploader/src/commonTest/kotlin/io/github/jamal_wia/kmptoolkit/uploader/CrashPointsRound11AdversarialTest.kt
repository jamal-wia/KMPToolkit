package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.KeepResult
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A2 round 11, Q1: a process death at every step boundary of the upload hand-off and of the journal settle protocol.
 *
 * The process is simulated by fault injection, deterministically: one counter runs across the process's store, its
 * journal storage and its transport; the call number N throws [ProcessDied] (an [Error], which no `catch (Exception)`
 * of the library swallows) before it executes, and from then on every one of those collaborators refuses everything
 * and changes nothing, like a process that is gone. A second engine, journal and handler over the same store and the
 * same storage is the restarted process; it sweeps the journal, lets the answer of every launch the system still owes
 * arrive, and lets leases expire. Both journal protocols run: the stamped one (the Android worker, the iOS delegate with
 * a labelled task) and the unstamped one (a task without a label). Delivery answers (201) and failure answers (503).
 *
 * The outcome of each step is printed as a table line (`CRASH-TABLE`); the assertions are the documented contract:
 * at least once and no more than the documented windows, no answer charged twice, no leftover entry, nothing lost.
 */
class CrashPointsRound11AdversarialTest {

    private class ProcessDied : Error("the process died")

    /** One counter for everything a process touches; call [dieAt] (1-based) dies before it executes. */
    private class Reaper(private val dieAt: Int) {
        var calls: Int = 0
            private set
        var dead: Boolean = false
            private set
        val trace: MutableList<String> = mutableListOf()

        fun step(label: String) {
            if (dead) throw ProcessDied()
            calls++
            if (calls == dieAt) {
                dead = true
                trace += "DIED before $label"
                throw ProcessDied()
            }
            trace += label
        }
    }

    private class MemStorage : OutcomeStorage {
        val files: MutableMap<String, ByteArray> = LinkedHashMap()
        override fun write(itemId: String, bytes: ByteArray): Boolean {
            files[itemId] = bytes.copyOf()
            return true
        }

        override fun read(itemId: String): ByteArray? = files[itemId]?.copyOf()
        override fun delete(itemId: String) {
            files.remove(itemId)
        }

        override fun itemIds(): List<String> = files.keys.toList()
        override fun purgeLeftovers() {}
    }

    private class ReapingStorage(private val inner: OutcomeStorage, private val reaper: Reaper) : OutcomeStorage {
        override fun write(itemId: String, bytes: ByteArray): Boolean {
            reaper.step("journal.write")
            return inner.write(itemId, bytes)
        }

        override fun read(itemId: String): ByteArray? {
            reaper.step("journal.read")
            return inner.read(itemId)
        }

        override fun delete(itemId: String) {
            reaper.step("journal.delete")
            inner.delete(itemId)
        }

        override fun itemIds(): List<String> {
            reaper.step("journal.list")
            return inner.itemIds()
        }

        override fun purgeLeftovers() {
            reaper.step("journal.purge")
            inner.purgeLeftovers()
        }
    }

    private class ReapingStore(private val inner: UploaderStore, private val reaper: Reaper) : UploaderStore by inner {
        override suspend fun insertKeep(record: UploaderItem): KeepResult {
            reaper.step("store.insertKeep")
            return inner.insertKeep(record)
        }

        override suspend fun getAllActive(): List<UploaderItem> {
            reaper.step("store.getAllActive")
            return inner.getAllActive()
        }

        override suspend fun getById(id: String): UploaderItem? {
            reaper.step("store.getById")
            return inner.getById(id)
        }

        override suspend fun markInFlight(
            id: String,
            leaseUntilEpochMillis: Long,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean {
            reaper.step("store.markInFlight")
            return inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
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
            reaper.step("store.recordFailure")
            return inner.recordFailure(
                id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition,
            )
        }

        override suspend fun park(
            id: String,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean {
            reaper.step("store.park")
            return inner.park(id, lastError, expectedState, expectedClaim)
        }

        override suspend fun deleteById(id: String) {
            reaper.step("store.deleteById")
            inner.deleteById(id)
        }
    }

    /** What the system and the server did, across both processes. */
    private class Ledger {
        var launches: Int = 0
        var rehandLaunches: Int = 0
        var answersConsumed: Int = 0
        var delivered: Int = 0
    }

    private class LedgerTransport(private val ledger: Ledger, private val reaper: Reaper) : UploadTransport {
        override val leaseMillis: Long = LEASE
        override fun launch(itemId: String, request: UploadRequest) = error("re-hand-aware overload expected")
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            reaper.step("transport.launch")
            ledger.launches++
            if (isRehandOff) ledger.rehandLaunches++
            reaper.step("after transport.launch")
        }

        override fun cancel(itemId: String) {}
    }

    private class CrashHandler(transport: UploadTransport, private val ledger: Ledger) :
        UploadHandler<String>(transport) {
        override val type: String = "r11.crash"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = emptyList()))

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            ledger.delivered++
        }
    }

    private class Snapshot(
        val rowPresent: Boolean,
        val attempts: Int,
        val state: UploaderItemState?,
        val entry: Boolean,
        val launches: Int,
        val delivered: Int,
    )

    private class CaseResult(
        val label: String,
        val died: Boolean,
        val atDeath: Snapshot,
        val answerBeganInA: Boolean,
        val journalWrittenInA: Boolean,
        val journalDeleteBeforeDeath: Boolean,
        val trace: List<String>,
        val end: Snapshot,
        val rehandLaunches: Int,
    )

    private val clock = TestClock(10_000_000L)
    private val registered: MutableList<UploaderEngine> = mutableListOf()
    private val scopes: MutableList<CoroutineScope> = mutableListOf()
    private var caseNumber: Int = 0

    @AfterTest
    fun cleanUp() {
        registered.forEach { UploaderEngineRegistry.unregister(it); it.close() }
        scopes.forEach { it.cancel() }
    }

    private fun TestScope.processScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, _ -> })
        scopes += scope
        return scope
    }

    private fun snapshot(inner: TestUploaderStore, storage: MemStorage, id: String, ledger: Ledger): Snapshot {
        val row: UploaderItem? = inner.find(id)
        return Snapshot(
            rowPresent = row != null,
            attempts = row?.attempts ?: 0,
            state = row?.state,
            entry = storage.files.containsKey(id),
            launches = ledger.launches,
            delivered = ledger.delivered,
        )
    }

    private suspend fun answerOne(
        journal: OutcomeJournal,
        id: String,
        status: Int,
        stamped: Boolean,
        reaper: Reaper,
        ledger: Ledger,
        flags: BooleanArray,
    ) {
        val prepared: PreparedUpload = UploadGateway.prepareStampedAttempt(id, 1.seconds)
        if (prepared.attempt !is UploadAttempt.Ready) return
        ledger.answersConsumed++
        flags[0] = true
        reaper.step("server.answer")
        val response = UploadResponse(status, mapOf("etag" to "x"), "ok".encodeToByteArray(), false)
        val stamp: Int? = if (stamped) prepared.attemptStamp else null
        val written: Boolean = journal.record(id, response, prepared.bodyLimit, stamp)
        if (written) flags[1] = true
        journal.settleRecorded(id, response, stamp, prepared.bodyLimit, gatewaySettle(1.seconds))
    }

    private suspend fun TestScope.runCase(stamped: Boolean, status: Int, dieAt: Int, fullRecovery: Boolean): CaseResult {
        val id = "r11-crash-${caseNumber++}"
        val inner = TestUploaderStore()
        val storage = MemStorage()
        val ledger = Ledger()
        val flags = BooleanArray(2) // [answer began, journal write done]

        // Process A, with the fault.
        val reaperA = Reaper(dieAt)
        val journalA = OutcomeJournal(ReapingStorage(storage, reaperA), clock)
        val handlerA = CrashHandler(LedgerTransport(ledger, reaperA), ledger)
        val engineA: UploaderEngine = testEngine(
            ReapingStore(inner, reaperA), listOf(handlerA), processScope(), clock = clock, idGenerator = { id },
        )
        registered += engineA
        UploaderEngineRegistry.register(engineA)
        var journalDeletedBeforeDeath = false
        try {
            engineA.enqueue(handlerA, "payload")
            engineA.drain()
            while (ledger.answersConsumed < ledger.launches) {
                val before: Int = ledger.answersConsumed
                answerOne(journalA, id, status, stamped, reaperA, ledger, flags)
                if (ledger.answersConsumed == before) break
            }
        } catch (_: ProcessDied) {
        }
        val answerBeganInA: Boolean = flags[0] && reaperA.dead
        val journalWrittenInA: Boolean = flags[1]
        journalDeletedBeforeDeath = reaperA.trace.contains("journal.delete")
        val atDeath: Snapshot = snapshot(inner, storage, id, ledger)
        UploaderEngineRegistry.unregister(engineA)
        engineA.close()

        // Process B, restarted over the same store and journal files.
        val reaperB = Reaper(Int.MAX_VALUE)
        val journalB = OutcomeJournal(ReapingStorage(storage, reaperB), clock)
        val handlerB = CrashHandler(LedgerTransport(ledger, reaperB), ledger)
        val engineB: UploaderEngine = testEngine(
            ReapingStore(inner, reaperB), listOf(handlerB), processScope(), clock = clock, idGenerator = { id },
        )
        registered += engineB
        UploaderEngineRegistry.register(engineB)
        journalB.sweep(gatewaySettle(1.seconds))
        if (fullRecovery) {
            repeat(5) {
                while (ledger.answersConsumed < ledger.launches) {
                    val before: Int = ledger.answersConsumed
                    answerOne(journalB, id, status, stamped, reaperB, ledger, flags)
                    if (ledger.answersConsumed == before) {
                        // The row is gone or owes nothing: the answer of this launch arrives as a stale duplicate.
                        ledger.answersConsumed++
                    }
                }
                journalB.sweep(gatewaySettle(1.seconds))
                clock.millis += LEASE * 2
                engineB.drain()
            }
            journalB.sweep(gatewaySettle(1.seconds))
        }
        val end: Snapshot = snapshot(inner, storage, id, ledger)
        UploaderEngineRegistry.unregister(engineB)
        engineB.close()
        clock.millis += 10 * LEASE
        return CaseResult(
            label = "stamped=$stamped status=$status dieAt=$dieAt",
            died = reaperA.dead,
            atDeath = atDeath,
            answerBeganInA = answerBeganInA,
            journalWrittenInA = journalWrittenInA,
            journalDeleteBeforeDeath = journalDeletedBeforeDeath,
            trace = reaperA.trace.toList(),
            end = end,
            rehandLaunches = ledger.rehandLaunches,
        )
    }

    /** The enqueue call itself died before its insert: nothing was ever promised to the caller. */
    private fun neverEnqueued(r: CaseResult): Boolean =
        r.died && !r.atDeath.rowPresent && r.atDeath.launches == 0 && !r.atDeath.entry

    private fun describe(result: CaseResult): String =
        "${result.label} | died=${result.died} | last=${result.trace.lastOrNull()} | atDeath: row=${result.atDeath.rowPresent}" +
            "/${result.atDeath.state}/attempts=${result.atDeath.attempts} entry=${result.atDeath.entry} " +
            "launches=${result.atDeath.launches} delivered=${result.atDeath.delivered} | end: row=${result.end.rowPresent}" +
            "/${result.end.state}/attempts=${result.end.attempts} entry=${result.end.entry} launches=${result.end.launches} " +
            "delivered=${result.end.delivered} rehand=${result.rehandLaunches}"

    private suspend fun TestScope.steps(stamped: Boolean, status: Int, fullRecovery: Boolean): Int {
        // Count the steps of a death-free process A: a first run with dieAt beyond any count.
        val probe: CaseResult = runCase(stamped, status, Int.MAX_VALUE, fullRecovery)
        check(!probe.died)
        return probe.trace.size
    }

    @Test
    fun `a delivery answered by a stamped protocol survives a death at every step with no lost upload and no leftover`() = runTest {
        val total: Int = steps(stamped = true, status = 201, fullRecovery = true)
        assertTrue(total >= 12, "the process under test must show its steps, saw $total")
        for (dieAt in 1..total + 1) {
            val r: CaseResult = runCase(stamped = true, status = 201, dieAt = dieAt, fullRecovery = true)
            println("CRASH-TABLE ${describe(r)}")
            if (neverEnqueued(r)) {
                assertEquals(0, r.end.launches + r.end.delivered, "an enqueue that died before its insert leaves nothing; ${describe(r)}")
                assertEquals(false, r.end.rowPresent, describe(r))
                continue
            }
            assertEquals(false, r.end.rowPresent, "item must be delivered and gone; ${describe(r)}")
            assertEquals(false, r.end.entry, "no journal entry may be left; ${describe(r)}")
            val lost: Boolean = r.answerBeganInA && !r.atDeath.entry && r.atDeath.rowPresent
            if (lost) {
                assertTrue(!r.journalWrittenInA, "a stamped answer is lost only before its journal write completed; ${describe(r)}")
            }
            val expectedLaunches: Int = if (lost) 2 else 1
            assertEquals(expectedLaunches, r.end.launches, "uploads: $expectedLaunches expected; ${describe(r)}")
            assertTrue(r.end.delivered in 1..2, "delivery hook count must be 1 or 2; ${describe(r)}")
            if (r.atDeath.delivered >= 1 && r.atDeath.rowPresent) {
                // The hook ran before the death but the row was not deleted: the next process may deliver once more.
                assertTrue(r.end.delivered <= 2, describe(r))
            } else {
                assertEquals(1, r.end.delivered, "exactly one delivery hook when the death did not fall between hook and delete; ${describe(r)}")
            }
        }
    }

    @Test
    fun `a delivery answered by the unstamped protocol is lost only in its documented window`() = runTest {
        val total: Int = steps(stamped = false, status = 201, fullRecovery = true)
        assertTrue(total >= 12, "saw $total")
        var lostWindows = 0
        for (dieAt in 1..total + 1) {
            val r: CaseResult = runCase(stamped = false, status = 201, dieAt = dieAt, fullRecovery = true)
            println("CRASH-TABLE ${describe(r)}")
            if (neverEnqueued(r)) {
                assertEquals(0, r.end.launches + r.end.delivered, "an enqueue that died before its insert leaves nothing; ${describe(r)}")
                assertEquals(false, r.end.rowPresent, describe(r))
                continue
            }
            assertEquals(false, r.end.rowPresent, "item must be delivered and gone; ${describe(r)}")
            assertEquals(false, r.end.entry, "no journal entry may be left; ${describe(r)}")
            val lost: Boolean = r.answerBeganInA && !r.atDeath.entry && r.atDeath.rowPresent
            if (lost) {
                val window: Boolean = r.journalDeleteBeforeDeath && (r.trace.last().startsWith("DIED before store."))
                assertTrue(!r.journalWrittenInA || window, "an unstamped answer is lost only between its delete and its commit; ${describe(r)}")
                if (window) lostWindows++
            }
            if (lost && r.atDeath.delivered >= 1) {
                // The hook ran in the dead process and its delete died. A real restart loses the process-wide memory of
                // that unconfirmed delivery and uploads again; this simulation keeps the memory (DeliveredUnconfirmed is
                // one object per JVM), so the new engine only deletes the row: one or two uploads are both honest.
                assertTrue(r.end.launches in 1..2, describe(r))
            } else {
                assertEquals(if (lost) 2 else 1, r.end.launches, "uploads; ${describe(r)}")
            }
            assertTrue(r.end.delivered in 1..2, describe(r))
        }
        assertTrue(lostWindows >= 1, "the documented window must be reached, or the test is vacuous")
    }

    @Test
    fun `a failure answer is charged exactly once whatever step the process dies at`() = runTest {
        for (stamped in listOf(true, false)) {
            val total: Int = steps(stamped = stamped, status = 503, fullRecovery = false)
            assertTrue(total >= 10, "saw $total")
            for (dieAt in 1..total + 1) {
                val r: CaseResult = runCase(stamped = stamped, status = 503, dieAt = dieAt, fullRecovery = false)
                println("CRASH-TABLE ${describe(r)}")
                val charged: Boolean = r.atDeath.entry || r.atDeath.attempts >= 1
                if (stamped) {
                    assertEquals(
                        if (charged) 1 else 0, r.end.attempts,
                        "the failure is charged once, never twice, never dropped once journaled; ${describe(r)}",
                    )
                } else {
                    assertTrue(r.end.attempts in 0..1, "never charged twice; ${describe(r)}")
                    if (r.atDeath.entry || r.atDeath.attempts >= 1) assertEquals(1, r.end.attempts, describe(r))
                }
                assertEquals(false, r.end.entry, "no leftover entry after the restart sweep; ${describe(r)}")
                assertEquals(0, r.end.delivered, "a failure is never a delivery; ${describe(r)}")
            }
        }
    }

    private companion object {
        const val LEASE: Long = 60_000L
    }
}
