package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Round 9 (documentation as a test, iOS parity): the sentences of docs/kmptoolkit-uploader-sqldelight about the
 * schema, the shared-driver route, use after close and the row limit, as plain suspending checks that both platforms
 * run through their own thin test class, so a difference between the two drivers is a failing test and not a guess.
 */
internal class SqlDelightDocsParityRound9Checks {

    private suspend fun <T> real(millis: Long, block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(millis) { block() } }

    private fun item(id: String, type: String = "t", payload: String = "p", uniqueKey: String? = null, lastError: String? = null) =
        UploaderItem(
            id = id, type = type, payload = payload, schemaVersion = 1, uniqueKey = uniqueKey, orderingKey = null, tag = null,
            state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 1L, lastError = lastError,
        )

    private fun <T> SqlDriver.rows(sql: String, read: (SqlCursor) -> T): List<T> = executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val out: MutableList<T> = mutableListOf()
            while (cursor.next().value) out += read(cursor)
            QueryResult.Value(out)
        },
        parameters = 0,
    ).value

    private fun SqlDriver.run(sql: String) {
        execute(identifier = null, sql = sql, parameters = 0)
    }

    private fun SqlDriver.count(table: String): Long =
        rows("SELECT COUNT(*) FROM $table") { it.getLong(0) ?: 0L }.single()

    /** A consumer's own database at version 7: a messages table, and (the "fresh install" sample) the queue created beside it. */
    private fun yourSchema(withQueue: Boolean): SqlSchema<QueryResult.Value<Unit>> = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 7L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.run("CREATE TABLE messages (id TEXT PRIMARY KEY NOT NULL, status TEXT NOT NULL)")
            if (withQueue) uploaderDatabaseSchema.create(driver)
            return QueryResult.Unit
        }

        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
            callbacks.filter { it.afterVersion in oldVersion until newVersion }.forEach { it.block(driver) }
            return QueryResult.Unit
        }
    }

    // ---- 03-guide "The schema": the table and the four indices as printed ----------------------------------------

    suspend fun theSchemaIsExactlyTheOnePrintedInTheGuide() {
        val driver: SqlDriver = createDriver(uploaderDatabaseSchema, name = null)
        try {
            assertEquals(2L, uploaderDatabaseSchema.version, "the guide and the API reference say the version is currently 2")
            data class Column(val name: String, val type: String, val notNull: Boolean, val default: String?, val primaryKey: Boolean)
            val columns: List<Column> = driver.rows("PRAGMA table_info(kmptoolkit_uploader_item)") {
                Column(it.getString(1) ?: "", (it.getString(2) ?: "").uppercase(), (it.getLong(3) ?: 0L) == 1L, it.getString(4), (it.getLong(5) ?: 0L) > 0L)
            }
            val expected: List<Column> = listOf(
                Column("sequence", "INTEGER", false, null, true),
                Column("id", "TEXT", true, null, false),
                Column("type", "TEXT", true, null, false),
                Column("payload", "TEXT", true, null, false),
                Column("schema_version", "INTEGER", true, null, false),
                Column("unique_key", "TEXT", false, null, false),
                Column("ordering_key", "TEXT", false, null, false),
                Column("tag", "TEXT", false, null, false),
                Column("state", "TEXT", true, null, false),
                Column("attempts", "INTEGER", true, null, false),
                Column("next_run_at", "INTEGER", true, null, false),
                Column("created_at", "INTEGER", true, null, false),
                Column("last_error", "TEXT", false, null, false),
                Column("lease_until", "INTEGER", true, "0", false),
                Column("claim", "INTEGER", true, "0", false),
            )
            assertEquals(expected, columns)

            data class Index(val name: String, val unique: Boolean, val partial: Boolean)
            val indices: Set<Index> = driver.rows("PRAGMA index_list(kmptoolkit_uploader_item)") {
                Index(it.getString(1) ?: "", (it.getLong(2) ?: 0L) == 1L, (it.getLong(4) ?: 0L) == 1L) to (it.getString(3) ?: "")
            }.filter { (_, origin) -> origin == "c" }.map { it.first }.toSet()
            assertEquals(
                setOf(
                    Index("kmptoolkit_uploader_item_identity", unique = true, partial = true),
                    Index("kmptoolkit_uploader_item_state", unique = false, partial = false),
                    Index("kmptoolkit_uploader_item_type", unique = false, partial = false),
                    Index("kmptoolkit_uploader_item_tag", unique = false, partial = false),
                ),
                indices,
            )
            val objects: List<String> = driver.rows("SELECT name FROM sqlite_master") { it.getString(0) ?: "" }
            assertTrue(
                objects.all { it.startsWith("kmptoolkit_uploader_") || it.startsWith("sqlite_") || it == "android_metadata" },
                "nothing the schema creates may collide with a table of the consumer's: $objects",
            )
        } finally {
            driver.close()
        }
    }

    // ---- 03-guide "Shared driver": the queue inside the consumer's own database ---------------------------------------

    suspend fun aDomainWriteAndAnEnqueueCommitOrRollBackTogetherOnTheConsumersDriver() {
        val driver: SqlDriver = createDriver(yourSchema(withQueue = true), name = null)
        val storage: UploaderStorage = createUploaderStorage(driver)
        try {
            real(20_000) {
                storage.transactionRunner.inTransaction {
                    driver.run("INSERT INTO messages VALUES ('m1', 'sending')")
                    storage.store.insertKeep(item("q1"))
                }
                val failure: Throwable? = runCatching {
                    storage.transactionRunner.inTransaction {
                        driver.run("INSERT INTO messages VALUES ('m2', 'sending')")
                        storage.store.insertKeep(item("q2"))
                        throw IllegalArgumentException("the block fails after both writes")
                    }
                }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException && failure.message == "the block fails after both writes", "got $failure")
                // an oversized row is refused by the store inside the block; the consumer's own row goes with it
                val refused: Throwable? = runCatching {
                    storage.transactionRunner.inTransaction {
                        driver.run("INSERT INTO messages VALUES ('m3', 'sending')")
                        storage.store.insertKeep(item("q3", payload = "x".repeat(MAX_ROW_TEXT_BYTES)))
                    }
                }.exceptionOrNull()
                assertTrue(refused is UploaderStorageException, "got $refused")
            }
            assertEquals(listOf("q1"), storage.store.getAllActive().map { it.id })
            assertEquals(listOf("m1"), driver.rows("SELECT id FROM messages") { it.getString(0) })
        } finally {
            storage.close()
            driver.close()
        }
    }

    suspend fun theAfterVersionSampleCreatesTheQueueOnceInAnExistingDatabase() {
        val driver: SqlDriver = createDriver(yourSchema(withQueue = false), name = null)
        try {
            assertEquals(0L, driver.rows("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'kmptoolkit_uploader_%'") { it.getLong(0) ?: 0L }.single())
            // the guide's sample: AfterVersion(7) { uploaderDatabaseSchema.create(it) }, run by the consumer's own migrate
            // SQLDelight runs AfterVersion(n) once the step n -> n+1 has been applied, so a migration 6 -> 8 passes AfterVersion(7).
            // (A migration 6 -> 7 does not run it: the guide's wording "the version which first has a queue" is looser than that.)
            yourSchema(withQueue = false).migrate(driver, 6L, 7L, AfterVersion(7L) { uploaderDatabaseSchema.create(it) })
            assertEquals(0L, driver.rows("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'kmptoolkit_uploader_%'") { it.getLong(0) ?: 0L }.single())
            yourSchema(withQueue = false).migrate(driver, 7L, 8L, AfterVersion(7L) { uploaderDatabaseSchema.create(it) })
            val storage: UploaderStorage = createUploaderStorage(driver)
            try {
                storage.store.insertKeep(item("a"))
                assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
                assertEquals(0L, driver.count("messages"), "the consumer's own table was not touched")
            } finally {
                storage.close()
            }
        } finally {
            driver.close()
        }
    }

    suspend fun aDriverWithoutTheQueueTableFailsOnTheFirstOperationAndNeverInTheFactory() {
        val driver: SqlDriver = createDriver(emptySchema, name = null)
        val storage: UploaderStorage = createUploaderStorage(driver) // "Throws nothing on its own"
        try {
            val read = assertFailsWith<UploaderStorageException> { storage.store.getAllActive() }
            assertEquals(UploaderStorageOperation.GET_ALL_ACTIVE, read.operation)
            assertNotNull(read.cause)
            val write = assertFailsWith<UploaderStorageException> { storage.store.insertKeep(item("a")) }
            assertEquals(UploaderStorageOperation.INSERT_KEEP, write.operation)
        } finally {
            storage.close()
            driver.close()
        }
    }

    suspend fun observingFromInsideATransactionSeesTheUncommittedStateAndNothingElseDoes() {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            var seenInside: List<String> = emptyList()
            val failure: Throwable? = runCatching {
                real(20_000) {
                    storage.transactionRunner.inTransaction {
                        storage.store.insertKeep(item("inside", type = "o"))
                        seenInside = storage.store.observeByType("o").first().map { it.id }
                        throw CancellationException("roll back")
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException, "got $failure")
            assertEquals(listOf("inside"), seenInside, "the guide: it shows the uncommitted state your own transaction has written so far")
            assertEquals(emptyList(), storage.store.observeByType("o").first(), "after the rollback nobody sees it")
        } finally {
            storage.close()
        }
    }

    // ---- 03-guide "Failures": use after close, on every function of the store -----------------------------------------

    suspend fun everyFunctionOfAClosedStorageFailsAsAStorageExceptionWithAnIllegalStateCause() {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        storage.store.insertKeep(item("a"))
        storage.close()
        storage.close() // "Calling it twice is a no-op"

        val calls: List<Pair<UploaderStorageOperation, suspend () -> Unit>> = listOf(
            UploaderStorageOperation.INSERT_KEEP to { storage.store.insertKeep(item("b")); Unit },
            UploaderStorageOperation.INSERT_REPLACE to { storage.store.insertReplace(item("b", uniqueKey = "k")); Unit },
            UploaderStorageOperation.GET_ALL_ACTIVE to { storage.store.getAllActive(); Unit },
            UploaderStorageOperation.GET_BY_ID to { storage.store.getById("a"); Unit },
            UploaderStorageOperation.RECORD_FAILURE to {
                storage.store.recordFailure("a", 1, 0L, null, UploaderItemState.PENDING, 0L, FailureDisposition.RETRY); Unit
            },
            UploaderStorageOperation.MARK_IN_FLIGHT to { storage.store.markInFlight("a", 1L, UploaderItemState.PENDING, 0L); Unit },
            UploaderStorageOperation.PARK to { storage.store.park("a", null, UploaderItemState.PENDING, 0L); Unit },
            UploaderStorageOperation.DELETE_BY_ID to { storage.store.deleteById("a") },
            UploaderStorageOperation.DELETE_BY_TAG to { storage.store.deleteByTag("x"); Unit },
            UploaderStorageOperation.CLEAR_ALL to { storage.store.clearAll() },
        )
        for ((operation, call) in calls) {
            val failure: Throwable? = runCatching { real(10_000) { call() } }.exceptionOrNull()
            assertTrue(failure is UploaderStorageException, "$operation: got $failure")
            assertEquals(operation, failure.operation)
            assertTrue(failure.cause is IllegalStateException, "$operation: cause ${failure.cause}")
        }
        val observed: Throwable? = runCatching { real(10_000) { storage.store.observeByType("t").first() } }.exceptionOrNull()
        assertTrue(observed is UploaderStorageException, "observeByType after close: got $observed")
        assertTrue(observed.cause is IllegalStateException, "observeByType cause ${observed.cause}")
        val inTx: Throwable? = runCatching { real(10_000) { storage.transactionRunner.inTransaction { 1 } } }.exceptionOrNull()
        assertTrue(inTx is UploaderStorageException && inTx.cause is IllegalStateException, "inTransaction after close: got $inTx")
    }

    // ---- 03-guide "One row is at most 1 MiB" -------------------------------------------------------------------

    suspend fun theRowLimitIsTheSameOnThisPlatformAndALargeNulTextStaysReadable() {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val room: Int = MAX_ROW_TEXT_BYTES - "idt".length - "ty".length
            storage.store.insertKeep(item("idt", type = "ty", payload = "a".repeat(room))) // exactly 1 MiB of text
            val over = assertFailsWith<UploaderStorageException> {
                storage.store.insertKeep(item("idu", type = "ty", payload = "a".repeat(room + 1)))
            }
            assertEquals(UploaderStorageOperation.INSERT_KEEP, over.operation)
            assertEquals(listOf("idt"), storage.store.getAllActive().map { it.id }, "the refused row left nothing behind")

            val nul: String = "\u0000".repeat(1_000_000) // 1 MB as given, about 2 MB once escaped: inside both limits
            storage.store.insertKeep(item("nul", payload = nul))
            val back: UploaderItem = storage.store.getById("nul") ?: error("the NUL row did not come back")
            assertEquals(nul, back.payload)
            assertEquals(2, storage.store.getAllActive().size, "the queue as a whole is still readable")

            assertFailsWith<UploaderStorageException> { storage.store.insertKeep(item("nul2", payload = "\u0000".repeat(MAX_ROW_TEXT_BYTES))) }
            assertEquals(2, storage.store.getAllActive().size)

            // later writes never push an accepted row past the limit: a huge failure text is cut to the room left
            val ok = storage.store.recordFailure("idt", 1, 0L, "e".repeat(5_000_000), UploaderItemState.PENDING, 0L, FailureDisposition.RETRY)
            assertTrue(ok)
            assertEquals(2, storage.store.getAllActive().size, "still readable after an oversized failure text")
            assertNull(storage.store.getById("missing"))
        } finally {
            storage.close()
        }
    }

    // ---- parity: contention on the pooled native connections and on Android's alike -------------------------------------

    suspend fun manyThreadsInsertingOneKeyAdmitExactlyOneAndDistinctRowsAreAllKept() {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val keyed: List<Boolean> = real(60_000) {
                coroutineScope { (0 until 120).map { i -> async(Dispatchers.Default) { storage.store.insertKeep(item("k$i", uniqueKey = "same")).inserted } }.awaitAll() }
            }
            assertEquals(1, keyed.count { it }, "exactly one KEEP insert of a key wins")
            val distinct: List<Boolean> = real(60_000) {
                coroutineScope { (0 until 200).map { i -> async(Dispatchers.Default) { storage.store.insertKeep(item("d$i")).inserted } }.awaitAll() }
            }
            assertTrue(distinct.all { it })
            val all: List<UploaderItem> = storage.store.getAllActive()
            assertEquals(201, all.size)
            assertEquals(201, all.map { it.id }.toSet().size)
        } finally {
            storage.close()
        }
    }
}
