package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import io.github.jamal_wia.kmptoolkit.outbox.OutboxItem
import io.github.jamal_wia.kmptoolkit.outbox.OutboxItemState
import io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore
import io.github.jamal_wia.kmptoolkit.outbox.testing.OutboxStoreContract
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Everything this module has to prove, as plain suspending functions.
 *
 * They are not `@Test` methods and this is not a base class, for the same reason
 * [OutboxStoreContract] is neither: a database test needs a driver, a driver needs a platform, and
 * on Android it needs Robolectric's runner, which is an annotation `commonTest` cannot write. Each
 * platform's test class declares the `@Test` methods and calls in here, so the assertions
 * themselves exist once and both platforms are held to exactly the same ones.
 *
 * Each check opens its own storage and closes it, so no check can be influenced by another's
 * leftovers — or by another's still-running database thread.
 */
internal class SqlDelightOutboxStoreChecks {

    // ---------------------------------------------------------------------------------------
    // The SPI contract, unmodified.
    // ---------------------------------------------------------------------------------------

    /**
     * Every invariant `OutboxStore` documents, run against a real SQLite database.
     *
     * The count is asserted rather than ignored: a future version of the contract that adds checks
     * should make somebody look at this module again, and a version that silently ran *fewer* would
     * otherwise pass unnoticed.
     */
    suspend fun satisfiesTheOutboxStoreContract() {
        val open: MutableList<OutboxStorage> = mutableListOf()
        try {
            val passed: Int = OutboxStoreContract {
                createInMemoryOutboxStorage().also(open::add).store
            }.verifyAll()
            assertEquals(EXPECTED_CONTRACT_CHECKS, passed)
        } finally {
            open.forEach(OutboxStorage::close)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The compare-and-set, under real contention.
    // ---------------------------------------------------------------------------------------

    /**
     * Two executors reporting the same failure for the same lease: exactly one write lands.
     *
     * The sequential version of this is in the contract. This one runs the two calls on different
     * threads, which is the shape the SPI actually warns about — a settle from a detached executor
     * arriving while the drain is doing something else — and it is the only version that can catch
     * a compare-and-set implemented as a read followed by a write.
     */
    suspend fun concurrentRecordFailuresLetExactlyOneThrough() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            repeat(CONTENTION_ROUNDS) { round ->
                val id = "id-$round"
                store.insertKeep(item(id = id))
                store.markInFlight(id, leaseUntilEpochMillis = 900L)

                val outcomes: List<Boolean> = withContext(Dispatchers.Default) {
                    coroutineScope {
                        val first = async {
                            store.recordFailure(id, 1, 10L, "first", expectedLeaseUntilEpochMillis = 900L)
                        }
                        val second = async {
                            store.recordFailure(id, 2, 20L, "second", expectedLeaseUntilEpochMillis = 900L)
                        }
                        listOf(first.await(), second.await())
                    }
                }

                assertEquals(
                    1,
                    outcomes.count { it },
                    "exactly one concurrent recordFailure must apply, round $round",
                )
                val settled: OutboxItem? = store.getById(id)
                assertEquals(OutboxItemState.PENDING, settled?.state)
                assertEquals(0L, settled?.leaseUntilEpochMillis)
                // Whichever won wrote all of its own fields and none of the loser's — a torn write
                // would pair one caller's attempts with the other's error.
                val consistent: Boolean = (settled?.attempts == 1 && settled.lastError == "first") ||
                    (settled?.attempts == 2 && settled.lastError == "second")
                assertTrue(consistent, "the losing write must not have partially applied: $settled")
            }
        }
    }

    /**
     * A stale settle racing the drain's re-hand: whoever runs second sees a coherent row.
     *
     * The two orderings give different answers, and both are correct — what must never happen is
     * the failure report clearing a lease the drain has just replaced, which would leave two
     * executors believing they own the delivery.
     */
    suspend fun aStaleSettleNeverClobbersAFreshClaim() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            repeat(CONTENTION_ROUNDS) { round ->
                val id = "id-$round"
                store.insertKeep(item(id = id))
                store.markInFlight(id, leaseUntilEpochMillis = 900L)

                val applied: Boolean = withContext(Dispatchers.Default) {
                    coroutineScope {
                        val settle = async {
                            store.recordFailure(id, 7, 70L, "stale", expectedLeaseUntilEpochMillis = 900L)
                        }
                        launch { store.markInFlight(id, leaseUntilEpochMillis = 1_800L) }
                        settle.await()
                    }
                }

                val row: OutboxItem? = store.getById(id)
                assertEquals(OutboxItemState.IN_FLIGHT, row?.state, "round $round")
                assertEquals(1_800L, row?.leaseUntilEpochMillis, "round $round")
                if (applied) {
                    assertEquals(7, row?.attempts, "an applied write must be visible, round $round")
                } else {
                    assertEquals(0, row?.attempts, "a rejected write must change nothing, round $round")
                    assertNull(row?.lastError, "a rejected write must store no error, round $round")
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Insertion order as a sequence.
    // ---------------------------------------------------------------------------------------

    /**
     * A deleted row's position is never handed to a later one.
     *
     * This is what `AUTOINCREMENT` buys over a plain `INTEGER PRIMARY KEY`, and the contract cannot
     * see it: SQLite reuses a bare rowid after the highest row is deleted, so an item inserted
     * *after* the deletion would sort *before* one that was already there — a queue that silently
     * reorders itself after every successful delivery, which is the common case.
     */
    suspend fun insertionOrderSurvivesDeletionOfTheNewestRow() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            store.insertKeep(item(id = "first"))
            store.insertKeep(item(id = "newest"))
            store.deleteById("newest")
            store.insertKeep(item(id = "after"))
            assertEquals(listOf("first", "after"), store.getAllActive().map { it.id })
        }
    }

    /** And a full wipe does not rewind it either. */
    suspend fun insertionOrderSurvivesClearAll() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            repeat(5) { index -> store.insertKeep(item(id = "old-$index")) }
            store.clearAll()
            store.insertKeep(item(id = "new-a"))
            store.insertKeep(item(id = "new-b"))
            assertEquals(listOf("new-a", "new-b"), store.getAllActive().map { it.id })
        }
    }

    /**
     * Insertion order under a burst large enough that a timestamp could not resolve it.
     *
     * The contract checks twenty; this checks two hundred, and asserts on the whole list rather
     * than on its endpoints, because a sequence that is merely *mostly* right still breaks FIFO.
     */
    suspend fun insertionOrderHoldsUnderALargeBurst() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            val ids: List<String> = List(BURST_SIZE) { "id-$it" }
            ids.forEach { id -> store.insertKeep(item(id = id, createdAtEpochMillis = 1_000L)) }
            assertEquals(ids, store.getAllActive().map { it.id })
        }
    }

    // ---------------------------------------------------------------------------------------
    // Transactions.
    // ---------------------------------------------------------------------------------------

    /** Writes made inside a committed transaction are all there afterwards. */
    suspend fun aCommittedTransactionPersistsEverythingInIt() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            storage.transactionRunner.inTransaction {
                store.insertKeep(item(id = "a"))
                store.insertKeep(item(id = "b"))
            }
            assertEquals(listOf("a", "b"), store.getAllActive().map { it.id })
        }
    }

    /** And none of them are, if it does not commit. */
    suspend fun aFailedTransactionRollsBackEveryWriteInIt() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            val thrown: IllegalStateException = assertFailsWith {
                storage.transactionRunner.inTransaction {
                    store.insertKeep(item(id = "a"))
                    store.insertKeep(item(id = "b"))
                    throw IllegalStateException("boom")
                }
            }
            // The caller's own exception, not rewritten into a storage error: a transaction that
            // rolled back because the block threw did not itself fail.
            //
            // Asserted by type and message rather than by identity, because kotlinx-coroutines'
            // stack-trace recovery replaces an exception crossing a coroutine boundary with a copy
            // carrying the async stack. Identity would therefore fail on the JVM for a correct
            // implementation — but a *wrapped* exception would still be caught here, since
            // OutboxStorageException is not an IllegalStateException and would never carry this
            // message.
            assertEquals("boom", thrown.message)
            assertTrue(store.getAllActive().isEmpty(), "a rolled-back transaction must leave nothing")
        }
    }

    /**
     * A nested `inTransaction` joins the outer one instead of opening a second.
     *
     * Asserted through rollback rather than by inspecting the driver, because joining is only
     * observable in its consequence: if the inner call opened and committed a transaction of its
     * own, the outer failure would leave the inner write behind — and the atomicity `enqueue`
     * depends on would be gone, with nothing reporting it.
     */
    suspend fun aNestedTransactionJoinsTheOuterOne() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            val runner = storage.transactionRunner
            assertFailsWith<IllegalStateException> {
                runner.inTransaction {
                    store.insertKeep(item(id = "outer"))
                    runner.inTransaction {
                        store.insertKeep(item(id = "inner"))
                    }
                    error("boom")
                }
            }
            assertNull(store.getById("inner"), "the nested write must roll back with the outer one")
            assertNull(store.getById("outer"))
        }
    }

    /** Nesting also has to work when nothing fails — three levels deep, all committed, in order. */
    suspend fun aNestedTransactionCommitsWithTheOuterOne() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            val runner = storage.transactionRunner
            val depth: Int = runner.inTransaction {
                store.insertKeep(item(id = "a"))
                runner.inTransaction {
                    store.insertKeep(item(id = "b"))
                    runner.inTransaction {
                        store.insertKeep(item(id = "c"))
                        3
                    }
                }
            }
            assertEquals(3, depth, "a nested transaction must return its block's value")
            assertEquals(listOf("a", "b", "c"), store.getAllActive().map { it.id })
        }
    }

    /**
     * Cancelling the caller rolls the transaction back and propagates the cancellation.
     *
     * A half-applied transaction left behind by a cancelled coroutine is the worst version of this
     * bug: nothing throws, nothing logs, and the queue is simply wrong from then on.
     */
    suspend fun cancellingInsideATransactionRollsItBack() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            withContext(Dispatchers.Default) {
                val entered = CompletableDeferred<Unit>()
                val neverCompletes = CompletableDeferred<Unit>()
                val job = launch {
                    storage.transactionRunner.inTransaction {
                        store.insertKeep(item(id = "a"))
                        entered.complete(Unit)
                        neverCompletes.await()
                    }
                }
                withTimeout(TIMEOUT_MILLIS) { entered.await() }
                job.cancel()
                job.join()
            }
            assertTrue(
                store.getAllActive().isEmpty(),
                "a cancelled transaction must not leave its writes behind",
            )
        }
    }

    /** Enqueueing from inside a transaction is the whole point, so it has to actually work. */
    suspend fun storeCallsInsideATransactionSeeTheirOwnWrites() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            val seen: List<String> = storage.transactionRunner.inTransaction {
                store.insertKeep(item(id = "a", uniqueKey = "u"))
                // Read-your-writes inside the transaction: the KEEP refusal below depends on the
                // insert above being visible to a statement in the same transaction.
                val refused: Boolean = store.insertKeep(item(id = "b", uniqueKey = "u"))
                assertFalse(refused, "the uncommitted row must already hold its identity")
                store.getAllActive().map { it.id }
            }
            assertEquals(listOf("a"), seen)
            assertEquals(listOf("a"), store.getAllActive().map { it.id })
        }
    }

    // ---------------------------------------------------------------------------------------
    // Durability and schema.
    // ---------------------------------------------------------------------------------------

    /**
     * The queue is still there after the storage that wrote it is gone.
     *
     * The one invariant `OutboxStoreContract` explicitly says it cannot check, and the one this
     * module exists for: an outbox that loses its rows on restart is a retry queue for the current
     * process only.
     */
    suspend fun aQueueSurvivesCloseAndReopen() {
        val name: String = uniqueDatabaseName("reopen")
        try {
            val original: OutboxItem = item(
                id = "a",
                uniqueKey = "u",
                orderingKey = "o",
                tag = "t",
                attempts = 4,
                nextRunAtEpochMillis = 77L,
                createdAtEpochMillis = 1_234L,
            )
            createFileOutboxStorage(name).use { storage ->
                storage.store.insertKeep(original)
                storage.store.insertKeep(item(id = "b"))
                storage.store.park("b", "gave up")
            }
            createFileOutboxStorage(name).use { storage ->
                assertEquals(original, storage.store.getById("a"), "every field must survive")
                assertEquals(
                    OutboxItemState.PARKED,
                    storage.store.getById("b")?.state,
                    "state must survive too, not just the row",
                )
                assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
            }
        } finally {
            deleteFileOutboxStorage(name)
        }
    }

    /**
     * And so does the insertion sequence — a reopened queue does not start counting again.
     *
     * A sequence that resets on reopen would put every row written after a restart *before* the
     * rows that were waiting, which is a FIFO violation that only ever shows up in production.
     */
    suspend fun theInsertionSequenceContinuesAcrossAReopen() {
        val name: String = uniqueDatabaseName("sequence")
        try {
            createFileOutboxStorage(name).use { storage ->
                storage.store.insertKeep(item(id = "before-restart"))
            }
            createFileOutboxStorage(name).use { storage ->
                storage.store.insertKeep(item(id = "after-restart"))
                assertEquals(
                    listOf("before-restart", "after-restart"),
                    storage.store.getAllActive().map { it.id },
                )
            }
        } finally {
            deleteFileOutboxStorage(name)
        }
    }

    /**
     * Opening a database file that does not exist yet creates the schema rather than failing.
     *
     * Trivial to get right and catastrophic to get wrong: the first launch after an install is the
     * one run in which nothing has a table yet.
     */
    suspend fun openingAFreshFileCreatesTheSchema() {
        val name: String = uniqueDatabaseName("fresh")
        try {
            createFileOutboxStorage(name).use { storage ->
                assertTrue(storage.store.getAllActive().isEmpty())
                storage.store.insertKeep(item(id = "a"))
                assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
            }
        } finally {
            deleteFileOutboxStorage(name)
        }
    }

    /** Opening it a second time migrates rather than recreates — reopening must not wipe the queue. */
    suspend fun openingAnExistingFileDoesNotRecreateTheSchema() {
        val name: String = uniqueDatabaseName("existing")
        try {
            createFileOutboxStorage(name).use { storage -> storage.store.insertKeep(item(id = "a")) }
            repeat(3) {
                createFileOutboxStorage(name).use { storage ->
                    assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
                }
            }
        } finally {
            deleteFileOutboxStorage(name)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Observation and lifecycle.
    // ---------------------------------------------------------------------------------------

    /** The observation flow re-emits when a row of that type changes, not only on collection. */
    suspend fun observeByTypeEmitsAgainAfterAWrite() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            store.insertKeep(item(id = "a", type = "one"))
            assertEquals(listOf("a"), store.observeByType("one").first().map { it.id })
            store.insertKeep(item(id = "b", type = "one"))
            assertEquals(listOf("a", "b"), store.observeByType("one").first().map { it.id })
            store.deleteById("a")
            assertEquals(listOf("b"), store.observeByType("one").first().map { it.id })
        }
    }

    /** Closing twice is not an error — a consumer's teardown path may well run twice. */
    fun closingTwiceIsANoOp() {
        val storage: OutboxStorage = createInMemoryOutboxStorage()
        storage.close()
        storage.close()
    }

    private suspend fun withStorage(block: suspend (OutboxStorage) -> Unit) {
        val storage: OutboxStorage = createInMemoryOutboxStorage()
        try {
            block(storage)
        } finally {
            storage.close()
        }
    }

    private suspend fun OutboxStorage.use(block: suspend (OutboxStorage) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun item(
        id: String,
        type: String = "checks.type",
        uniqueKey: String? = null,
        orderingKey: String? = null,
        tag: String? = null,
        attempts: Int = 0,
        nextRunAtEpochMillis: Long = 0L,
        createdAtEpochMillis: Long = 0L,
    ): OutboxItem = OutboxItem(
        id = id,
        type = type,
        payload = "payload-$id",
        schemaVersion = 1,
        uniqueKey = uniqueKey,
        orderingKey = orderingKey,
        tag = tag,
        state = OutboxItemState.PENDING,
        attempts = attempts,
        nextRunAtEpochMillis = nextRunAtEpochMillis,
        createdAtEpochMillis = createdAtEpochMillis,
        lastError = null,
        leaseUntilEpochMillis = 0L,
    )

    private companion object {

        /** What [OutboxStoreContract.verifyAll] returns today. */
        const val EXPECTED_CONTRACT_CHECKS: Int = 30

        /** Enough repetitions that a lost race would have to be lucky thirty times running. */
        const val CONTENTION_ROUNDS: Int = 30

        const val BURST_SIZE: Int = 200

        const val TIMEOUT_MILLIS: Long = 10_000L
    }
}
