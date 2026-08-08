package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.github.jamal_wia.kmptoolkit.outbox.OutboxItem
import io.github.jamal_wia.kmptoolkit.outbox.OutboxItemState
import io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore
import io.github.jamal_wia.kmptoolkit.outbox.testing.OutboxStoreContract
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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
     * The sequential version of this is in the contract. This one issues the two calls from two
     * coroutines on different threads — the shape the SPI actually warns about, a settle from a
     * detached executor arriving while the drain is doing something else — and asserts that the
     * loser changed nothing at all rather than half of the row.
     *
     * What it deliberately does not claim: this cannot, on its own, prove the guard is one SQL
     * statement. Every statement of a storage is confined to one thread, so the two calls are
     * serialized before they reach SQLite, and a read-then-write inside a transaction would pass
     * here too. That the guard is a single `UPDATE … WHERE lease_until = :expected` is visible in
     * `OutboxItem.sq`; what this check is for is the outcome the SPI specifies — exactly one write
     * applies, and the row is never left holding a mixture of the two callers' fields.
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
            deleteDatabaseFile(name)
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
            deleteDatabaseFile(name)
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
            deleteDatabaseFile(name)
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
            deleteDatabaseFile(name)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Observation and lifecycle.
    // ---------------------------------------------------------------------------------------

    /**
     * The observation flow re-emits to a **live** collector when a row of that type changes.
     *
     * Collecting `.first()` three times would not check this at all: each call is a fresh
     * collection, so a flow that emits once and then goes silent forever would pass. That is the
     * failure mode the SPI singles out — "emitting more often than strictly necessary is
     * acceptable; missing a change is not" — so the collector here stays open across the writes.
     */
    suspend fun observeByTypeReEmitsToALiveCollector() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            withContext(Dispatchers.Default) {
                val emissions = Channel<List<String>>(capacity = Channel.UNLIMITED)
                val collector = launch {
                    store.observeByType("one").collect { items ->
                        emissions.send(items.map { it.id })
                    }
                }
                try {
                    assertEquals(emptyList(), emissions.awaitNext(), "the initial contents")

                    store.insertKeep(item(id = "a", type = "one"))
                    assertEquals(listOf("a"), emissions.awaitSettled(listOf("a")))

                    // A row of another type must not wake this collector into a wrong value; if it
                    // emits at all, the contents still have to be right.
                    store.insertKeep(item(id = "other", type = "two"))
                    store.insertKeep(item(id = "b", type = "one"))
                    assertEquals(listOf("a", "b"), emissions.awaitSettled(listOf("a", "b")))

                    store.park("a", "gave up")
                    assertEquals(
                        listOf("a", "b"),
                        emissions.awaitSettled(listOf("a", "b")),
                        "parking changes a row of this type so it must re-emit — and must still " +
                            "include the parked item",
                    )

                    store.deleteById("a")
                    assertEquals(listOf("b"), emissions.awaitSettled(listOf("b")))
                } finally {
                    collector.cancel()
                }
            }
        }
    }

    /**
     * A `state` string this build does not recognize reads back as PARKED instead of throwing.
     *
     * Reachable without any corruption: a user on a newer build enqueues a row in a state that build
     * added, then downgrades. The row is written here through the driver directly, because the store
     * has no way to produce a state it does not know about — which is the point.
     */
    suspend fun anUnrecognizedStateReadsBackAsParked() {
        val driver: SqlDriver = createDriver(outboxDatabaseSchema, name = null)
        val storage: OutboxStorage = createOutboxStorage(driver)
        try {
            val store: OutboxStore = storage.store
            store.insertKeep(item(id = "known"))
            driver.execute(
                identifier = null,
                sql = "UPDATE kmptoolkit_outbox_item SET state = 'FROM_THE_FUTURE' WHERE id = ?",
                parameters = 1,
            ) { bindString(0, "known") }

            assertEquals(
                OutboxItemState.PARKED,
                store.getById("known")?.state,
                "an undecodable state must render as PARKED rather than fail the read",
            )
            assertTrue(
                store.getAllActive().isEmpty(),
                "an undecodable state is not PENDING or IN_FLIGHT so it must leave the active set",
            )
            assertEquals(
                listOf("known"),
                store.observeByType("checks.type").first().map { it.id },
                "the row must stay visible — nothing is lost, it is just out of rotation",
            )
        } finally {
            storage.close()
            driver.close()
        }
    }

    /** Closing twice is not an error — a consumer's teardown path may well run twice. */
    fun closingTwiceIsANoOp() {
        val storage: OutboxStorage = createInMemoryOutboxStorage()
        storage.close()
        storage.close()
    }

    /**
     * Closing while statements are still running waits for them instead of pulling the connection
     * out from under them.
     *
     * Releasing the database thread does not join it, so a `close()` that returned immediately would
     * close the connection under a live cursor — a crash inside SQLite rather than an exception this
     * module could report. The check is written as a race so that a non-draining implementation
     * fails here rather than on somebody's device at teardown.
     */
    suspend fun closingWaitsForStatementsAlreadyRunning() {
        repeat(CLOSE_RACE_ROUNDS) {
            val storage: OutboxStorage = createInMemoryOutboxStorage()
            withContext(Dispatchers.Default) {
                val writes = launch {
                    // Ignore whatever the released thread does to a call issued after close() —
                    // that case is explicitly undefined. What must not happen is a crash inside a
                    // statement that was *already running* when close() was called.
                    runCatching { repeat(WRITES_PER_CLOSE_RACE) { i -> storage.store.insertKeep(item(id = "id-$i")) } }
                }
                storage.close()
                writes.join()
            }
        }
    }

    /**
     * Collecting the observation flow from inside a transaction does not deadlock.
     *
     * It would, on any implementation that dispatched the query onto the database thread without
     * noticing it is already there: that thread is the one the open transaction is occupying, so the
     * dispatch would wait for a transaction that is waiting for the dispatch. The check has a
     * timeout precisely so that a regression fails instead of hanging the suite forever.
     */
    suspend fun observeByTypeCanBeCollectedInsideATransaction() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            // Dispatchers.Default, so the timeout is wall-clock: under runTest's virtual clock a
            // `withTimeout` fires the instant the test scheduler runs out of work, which is exactly
            // what happens while the real database thread is busy — it would report a deadlock that
            // is not there, and could never report one that is.
            val observed: List<String> = withContext(Dispatchers.Default) {
                withTimeout(TIMEOUT_MILLIS) {
                    storage.transactionRunner.inTransaction {
                        store.insertKeep(item(id = "a", type = "one"))
                        store.observeByType("one").first().map { it.id }
                    }
                }
            }
            assertEquals(listOf("a"), observed)
        }
    }

    /**
     * Leaving the database thread inside a transaction fails loudly instead of writing outside it.
     *
     * A coroutine context element survives a dispatcher switch; the invariant it stands for does
     * not. Without the thread check, this statement would run on an IO thread while the transaction
     * sat open on the database thread — the caller's write and the queue's committing separately,
     * silently. That is the failure a transactional outbox exists to prevent, so it has to be the
     * one thing this module refuses to do quietly.
     */
    suspend fun leavingTheDatabaseThreadInsideATransactionFailsLoudly() {
        withStorage { storage ->
            val thrown: IllegalStateException = assertFailsWith {
                storage.transactionRunner.inTransaction {
                    withContext(Dispatchers.Default) {
                        storage.store.insertKeep(item(id = "a"))
                    }
                }
            }
            assertTrue(
                thrown.message.orEmpty().contains("database thread"),
                "the failure must say what went wrong, was: ${thrown.message}",
            )
            assertTrue(
                storage.store.getAllActive().isEmpty(),
                "the rejected write must not have landed",
            )
        }
    }

    /**
     * A driver you supplied is still yours after the storage is closed.
     *
     * The documented promise of the shared-driver route, and the one whose breach would be worst:
     * closing a driver the consumer's whole app is using would take their database down when they
     * released a queue.
     */
    suspend fun closingAStorageDoesNotCloseASuppliedDriver() {
        val driver: SqlDriver = createDriver(outboxDatabaseSchema, name = null)
        try {
            val storage: OutboxStorage = createOutboxStorage(driver)
            storage.store.insertKeep(item(id = "a"))
            storage.close()

            // Still usable: a closed driver would throw here.
            val rows: Long = driver.executeQuery(
                identifier = null,
                sql = "SELECT count(*) FROM kmptoolkit_outbox_item",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0) ?: 0L)
                },
                parameters = 0,
            ).value
            assertEquals(1L, rows, "the supplied driver must still be open and hold the row")
        } finally {
            driver.close()
        }
    }

    /**
     * The queue table can be added to a database this module did not create — the shared-driver
     * route in full, which is the only one that makes the outbox genuinely transactional.
     */
    suspend fun theQueueCanBeCreatedInsideAnotherDatabase() {
        // A database whose schema is somebody else's: nothing of ours exists in it yet.
        val driver: SqlDriver = createDriver(emptySchema, name = null)
        try {
            outboxDatabaseSchema.create(driver)
            val storage: OutboxStorage = createOutboxStorage(driver)
            try {
                storage.transactionRunner.inTransaction {
                    storage.store.insertKeep(item(id = "a"))
                    storage.store.insertKeep(item(id = "b"))
                }
                assertEquals(listOf("a", "b"), storage.store.getAllActive().map { it.id })
            } finally {
                storage.close()
            }
        } finally {
            driver.close()
        }
    }

    /**
     * A platform database failure arrives as [OutboxStorageException], naming the operation.
     *
     * Not merely tidier: `OutboxStore` has no result type, so a consumer's only handle on a failed
     * write is what gets thrown. If that were a `SQLiteException` on Android and a Darwin error on
     * iOS, every consumer's error handling would have to be platform-specific — in a module whose
     * entire purpose is to keep shared code from being.
     */
    suspend fun aDatabaseFailureArrivesAsAnOutboxStorageException() {
        val failure = FakeSqliteException()
        val storage: OutboxStorage = createOutboxStorage(FailingSqlDriver(failure))
        try {
            val store: OutboxStore = storage.store
            val expected: List<Pair<OutboxStorageOperation, suspend () -> Any?>> = listOf(
                OutboxStorageOperation.INSERT_KEEP to { store.insertKeep(item(id = "a")) },
                OutboxStorageOperation.INSERT_REPLACE to { store.insertReplace(item(id = "a")) },
                OutboxStorageOperation.GET_ALL_ACTIVE to { store.getAllActive() },
                OutboxStorageOperation.GET_BY_ID to { store.getById("a") },
                OutboxStorageOperation.RECORD_FAILURE to { store.recordFailure("a", 1, 0L, null) },
                OutboxStorageOperation.MARK_IN_FLIGHT to { store.markInFlight("a", 1L) },
                OutboxStorageOperation.PARK to { store.park("a", null) },
                OutboxStorageOperation.DELETE_BY_ID to { store.deleteById("a") },
                OutboxStorageOperation.DELETE_BY_TAG to { store.deleteByTag("t") },
                OutboxStorageOperation.CLEAR_ALL to { store.clearAll() },
            )
            expected.forEach { (operation, call) ->
                val thrown: OutboxStorageException = assertFailsWith { call() }
                assertEquals(operation, thrown.operation)
                assertEquals(failure, thrown.cause, "the platform failure must be kept as the cause")
            }

            val fromTransaction: OutboxStorageException = assertFailsWith {
                storage.transactionRunner.inTransaction { }
            }
            assertEquals(OutboxStorageOperation.TRANSACTION, fromTransaction.operation)
        } finally {
            storage.close()
        }
    }

    /**
     * A cancellation surfacing from the database layer stays a cancellation.
     *
     * Wrapping it would break structured concurrency: a cancelled drain would look like a database
     * fault to every `catch` up the stack, and would be "handled" instead of unwinding.
     */
    suspend fun aCancellationIsNotWrappedAsADatabaseFailure() {
        val storage: OutboxStorage = createOutboxStorage(
            FailingSqlDriver(CancellationException("cancelled")),
        )
        try {
            assertFailsWith<CancellationException> { storage.store.getAllActive() }
        } finally {
            storage.close()
        }
    }

    /**
     * An empty unique key is a real key, not a synonym for "no key".
     *
     * The distinction is load-bearing and easy to lose: the identity index is partial on
     * `unique_key IS NOT NULL`, so `""` is indexed and deduplicates, while `null` is not indexed and
     * never conflicts. A store that normalized one into the other would either merge unrelated
     * effects or stop deduplicating.
     */
    suspend fun anEmptyUniqueKeyIsARealIdentity() {
        withStorage { storage ->
            val store: OutboxStore = storage.store
            assertTrue(store.insertKeep(item(id = "a", uniqueKey = "")))
            assertFalse(
                store.insertKeep(item(id = "b", uniqueKey = "")),
                "an empty unique key must deduplicate like any other key",
            )
            assertTrue(
                store.insertKeep(item(id = "c", uniqueKey = null)),
                "a null unique key must still never conflict with an empty one",
            )
            assertEquals(listOf("a", "c"), store.getAllActive().map { it.id })
        }
    }

    /** The next emission, or a failure rather than a hang if the flow has gone silent. */
    private suspend fun Channel<List<String>>.awaitNext(): List<String> =
        withTimeout(TIMEOUT_MILLIS) { receive() }

    /**
     * Emissions until [expected] arrives, failing rather than hanging if it never does.
     *
     * The SPI allows a store to emit more often than strictly necessary — SQLDelight notifies per
     * table, so an unrelated write can wake this collector — so a check that demanded the very next
     * emission would be asserting something the contract does not promise. What it does promise is
     * that the change is not *missed*, which is what a bounded wait for the right value tests.
     */
    private suspend fun Channel<List<String>>.awaitSettled(expected: List<String>): List<String> =
        withTimeout(TIMEOUT_MILLIS) {
            var latest: List<String> = receive()
            while (latest != expected) latest = receive()
            latest
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
    ): OutboxItem = anItem(
        id = id,
        type = type,
        uniqueKey = uniqueKey,
        orderingKey = orderingKey,
        tag = tag,
        attempts = attempts,
        nextRunAtEpochMillis = nextRunAtEpochMillis,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    private companion object {

        /** What [OutboxStoreContract.verifyAll] returns today. */
        const val EXPECTED_CONTRACT_CHECKS: Int = 30

        /** Enough repetitions that a lost race would have to be lucky thirty times running. */
        const val CONTENTION_ROUNDS: Int = 30

        const val BURST_SIZE: Int = 200

        const val TIMEOUT_MILLIS: Long = 10_000L

        /** Enough attempts that a close() which did not drain would hit a running statement. */
        const val CLOSE_RACE_ROUNDS: Int = 20

        const val WRITES_PER_CLOSE_RACE: Int = 50
    }
}
