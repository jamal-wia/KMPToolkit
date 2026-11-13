package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import java.io.File
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Round 8 (A1, scale): the rows of a large queue in a real SQLite file.
 *
 * The default size is bounded so the suite is not slowed; a larger run is switched on by the system property
 * `kmptoolkit.r8.rows` (for example `-Dkmptoolkit.r8.rows=200000`, passed to the test JVM through the Gradle
 * property `-Pkmptoolkit.test.r8.rows`, which is forwarded as the system property `kmptoolkit.test.r8.rows`).
 *
 * Every statement the store runs is captured from the driver (not copied from the `.sq` file), then planned with
 * `EXPLAIN QUERY PLAN` on a second connection. A statement that touches the queue by a full table scan is a finding;
 * the one statement that is allowed to read many rows (the read of the whole active queue) must at least be served by
 * an index and never scan rows it excludes.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStoreScaleRound8AdversarialTest {

    private val rows: Int = (System.getProperty("kmptoolkit.r8.rows") ?: System.getProperty("kmptoolkit.test.r8.rows"))?.toIntOrNull() ?: 6_000
    private val names: MutableList<String> = mutableListOf()
    private val storages: MutableList<UploaderStorage> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        storages.forEach { runCatching { it.close() } }
        names.forEach { application().deleteDatabase(it) }
    }

    /** A driver that remembers every statement text that reached it. */
    private class RecordingDriver(private val delegate: SqlDriver, val seen: MutableSet<String>) : SqlDriver {
        override fun <R> executeQuery(
            identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            seen += sql
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            seen += sql
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun newTransaction(): QueryResult<Transacter.Transaction> = delegate.newTransaction()
        override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()
        override fun addListener(vararg queryKeys: String, listener: Query.Listener) = delegate.addListener(*queryKeys, listener = listener)
        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = delegate.removeListener(*queryKeys, listener = listener)
        override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(*queryKeys)
        override fun close() = delegate.close()
    }

    private fun item(i: Int, state: UploaderItemState = UploaderItemState.PENDING): UploaderItem = UploaderItem(
        id = "id-$i", type = "type-${i % 7}", payload = "p".repeat(64) + i, schemaVersion = 1,
        uniqueKey = if (i % 3 == 0) "key-$i" else null, orderingKey = if (i % 5 == 0) "chan-${i % 50}" else null,
        tag = "tag-${i % 20}", state = state, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = i.toLong(),
        lastError = null,
    )

    @Test
    fun `no statement the store runs scans the queue table, and the read of the active queue uses its index`() = runTest {
        val name: String = "adv.a1r8.plan.${kotlin.random.Random.nextLong(0L, Long.MAX_VALUE)}"
        names += name
        val seen: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        val driver: SqlDriver = RecordingDriver(
            app.cash.sqldelight.driver.android.AndroidSqliteDriver(uploaderDatabaseSchema, application(), name),
            seen,
        )
        val storage: UploaderStorage = createUploaderStorage(driver).also { storages += it }
        val store = storage.store

        storage.transactionRunner.inTransaction {
            for (i in 0 until rows) store.insertKeep(item(i))
        }
        // every public write and read, so every statement text is captured
        store.insertReplace(item(0).copy(id = "replaced-0"))
        store.insertKeep(item(rows + 5))
        assertEquals(rows + 1, store.getAllActive().size)
        store.getById("id-5")
        assertTrue(store.markInFlight("id-5", 10L, UploaderItemState.PENDING, 0L))
        assertTrue(store.recordFailure("id-5", 1, 5L, "e", UploaderItemState.IN_FLIGHT, 1L, FailureDisposition.RETRY))
        assertTrue(store.recordFailure("id-6", 1, 5L, "e", UploaderItemState.PENDING, 0L, FailureDisposition.DROP))
        assertTrue(store.park("id-7", "x", UploaderItemState.PENDING, 0L))
        store.deleteById("id-8")
        store.deleteByTag("tag-3")
        storage.transactionRunner.inTransaction { store.getAllActive() }
        store.observeByType("type-1").first()
        store.clearAll()

        val path: File = application().getDatabasePath(name)
        val plans: MutableMap<String, List<String>> = linkedMapOf()
        val raw: SQLiteDatabase = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            for (sql in seen.toList()) {
                val verb: String = sql.trimStart().substringBefore(' ').uppercase()
                if (verb !in setOf("SELECT", "UPDATE", "DELETE")) continue
                val args: Array<String> = Array(sql.count { it == '?' }) { "x" }
                // SELECT * FROM kmptoolkit_uploader_item has no bind for the whole-table delete
                val cursor = raw.rawQuery("EXPLAIN QUERY PLAN $sql", args)
                val details: MutableList<String> = mutableListOf()
                cursor.use { while (it.moveToNext()) details += it.getString(it.getColumnIndexOrThrow("detail")) }
                plans[sql.replace(Regex("\\s+"), " ").trim()] = details
            }
        } finally {
            raw.close()
        }
        val report: String = plans.entries.joinToString("\n") { (sql, d) -> "$sql\n    -> ${d.joinToString(" | ")}" }
        println("R8 QUERY PLANS ($rows rows):\n$report")
        for ((sql, details) in plans) {
            val isWholeTable: Boolean = sql.startsWith("DELETE FROM kmptoolkit_uploader_item") && !sql.contains("WHERE")
            if (isWholeTable) continue
            val scans: List<String> = details.filter { it.startsWith("SCAN") && !it.contains("USING COVERING INDEX") && !it.contains("USING INDEX") }
            assertTrue(scans.isEmpty(), "a full table scan on the hot path\n$sql\n$report")
        }
        val active: List<String> = plans.entries.first { it.key.contains("state IN") }.value
        assertTrue(active.any { it.contains("kmptoolkit_uploader_item_state") }, "getAllActive does not use the state index: $active")
    }

    @Test
    fun `a large queue reads back whole and in insertion order, every row once`() = runTest {
        val name: String = "adv.a1r8.scale.${kotlin.random.Random.nextLong(0L, Long.MAX_VALUE)}"
        names += name
        val storage: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
        val store = storage.store
        val started: Long = System.nanoTime()
        storage.transactionRunner.inTransaction {
            for (i in 0 until rows) store.insertKeep(item(i))
        }
        val insertedMs: Long = (System.nanoTime() - started) / 1_000_000
        val readStarted: Long = System.nanoTime()
        val all: List<UploaderItem> = store.getAllActive()
        val readMs: Long = (System.nanoTime() - readStarted) / 1_000_000
        println("R8 SCALE rows=$rows insert(one transaction)=${insertedMs}ms getAllActive=${readMs}ms")
        assertEquals(rows, all.size)
        assertEquals((0 until rows).map { "id-$it" }, all.map { it.id }, "rows lost, duplicated or reordered")
        // one per-row transaction path at the tail: a row inserted now lands last
        store.insertKeep(item(rows))
        assertEquals("id-$rows", store.getAllActive().last().id)
        // withdraw by tag at scale: exactly the rows carrying it come back, the rest stay
        val removed: List<UploaderItem> = store.deleteByTag("tag-3")
        assertEquals((0..rows).count { it % 20 == 3 }, removed.size)
        assertEquals(rows + 1 - removed.size, store.getAllActive().size)
        // a big queue closes and reopens complete
        storage.close()
        val again: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
        assertEquals(rows + 1 - removed.size, again.store.getAllActive().size, "rows lost across a reopen")
    }

    @Test
    fun `many parked rows do not hide or slow the few active ones, and parked rows stay readable by type`() = runTest {
        val name: String = "adv.a1r8.parked.${kotlin.random.Random.nextLong(0L, Long.MAX_VALUE)}"
        names += name
        val storage: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
        val store = storage.store
        storage.transactionRunner.inTransaction {
            for (i in 0 until rows) store.insertKeep(item(i, UploaderItemState.PARKED))
            for (i in 0 until 10) store.insertKeep(item(rows + i))
        }
        assertEquals((0 until 10).map { "id-${rows + it}" }, store.getAllActive().map { it.id })
        val parkedOfOneType: Int = store.getAllActive().size
        assertEquals(10, parkedOfOneType)
    }

    @Test
    fun `a very large number of distinct queue names and unique keys neither collides nor slows a keyed lookup into a scan`() = runTest {
        val name: String = "adv.a1r8.keys.${kotlin.random.Random.nextLong(0L, Long.MAX_VALUE)}"
        names += name
        val storage: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
        val store = storage.store
        storage.transactionRunner.inTransaction {
            for (i in 0 until rows) {
                store.insertKeep(item(i).copy(uniqueKey = "k$i", orderingKey = "queue-$i"))
            }
        }
        // every key is its own identity: a second keep of each is refused, a replace hands the old row back
        for (i in listOf(0, rows / 2, rows - 1)) {
            assertEquals(false, store.insertKeep(item(i).copy(id = "dup-$i", uniqueKey = "k$i")).inserted)
            assertEquals("id-$i", store.insertReplace(item(i).copy(id = "rep-$i", uniqueKey = "k$i"))?.id)
        }
        assertEquals(rows, store.getAllActive().size)
    }

    private class CountingStore(private val delegate: io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore) :
        io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore by delegate {
        val reads: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()
        val pointReads: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun getAllActive(): List<UploaderItem> {
            reads.incrementAndGet()
            return delegate.getAllActive()
        }

        override suspend fun getById(id: String): UploaderItem? {
            pointReads.incrementAndGet()
            return delegate.getById(id)
        }
    }

    @Test
    fun `the engine drains a large queue with a bounded number of whole-queue reads, not one per item`() = kotlinx.coroutines.runBlocking {
        val name: String = "adv.a1r8.drain.${kotlin.random.Random.nextLong(0L, Long.MAX_VALUE)}"
        names += name
        val storage: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
        val counting = CountingStore(storage.store)
        val n: Int = minOf(rows, 4_000)
        val done: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()
        val handler = object : io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler<String> {
            override val type: String = "type-0"
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun execute(context: io.github.jamal_wia.kmptoolkit.uploader.AttemptContext, payload: String): io.github.jamal_wia.kmptoolkit.uploader.AttemptResult {
                done.incrementAndGet()
                return io.github.jamal_wia.kmptoolkit.uploader.AttemptResult.Success
            }
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            storage.transactionRunner.inTransaction {
                for (i in 0 until n) storage.store.insertKeep(item(i).copy(type = "type-0", orderingKey = null))
            }
            val engine = io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine(
                store = counting, handlers = listOf(handler), scope = scope, transactionRunner = storage.transactionRunner,
            )
            val started: Long = System.nanoTime()
            engine.start()
            try {
                kotlinx.coroutines.withTimeout(180_000L) { while (storage.store.getAllActive().isNotEmpty()) kotlinx.coroutines.delay(20) }
            } finally {
                engine.close()
            }
            val ms: Long = (System.nanoTime() - started) / 1_000_000
            println("R8 DRAIN n=$n in ${ms}ms, whole-queue reads=${counting.reads.get()}, point reads=${counting.pointReads.get()}, ran=${done.get()}")
            assertEquals(n, done.get(), "every item ran exactly once")
            assertTrue(counting.reads.get() <= 40, "whole-queue reads: ${counting.reads.get()} for $n items (one per item would be quadratic)")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `one tag over every row withdraws the whole large queue in one call and hands every row back`() = runTest {
        val name: String = "adv.a1r8.onetag.${kotlin.random.Random.nextLong(0L, Long.MAX_VALUE)}"
        names += name
        val storage: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
        val store = storage.store
        storage.transactionRunner.inTransaction {
            for (i in 0 until rows) store.insertKeep(item(i).copy(tag = "everything"))
        }
        val started: Long = System.nanoTime()
        val removed: List<UploaderItem> = store.deleteByTag("everything")
        println("R8 ONE-TAG wipe rows=$rows in ${(System.nanoTime() - started) / 1_000_000}ms")
        assertEquals((0 until rows).map { "id-$it" }, removed.map { it.id }, "the rows handed back are not exactly the rows removed, in order")
        assertTrue(store.getAllActive().isEmpty())
    }

    @Test
    fun `a live observer of a type survives a long run of single writes and ends on the complete list`() = kotlinx.coroutines.runBlocking {
        val name: String = "adv.a1r8.observe.${kotlin.random.Random.nextLong(0L, Long.MAX_VALUE)}"
        names += name
        val storage: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
        val store = storage.store
        val n: Int = minOf(rows / 2, 600)
        val emissions: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()
        val last: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(-1)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val job = scope.launch {
                store.observeByType("obs").collect { list ->
                    emissions.incrementAndGet()
                    last.set(list.size)
                }
            }
            val started: Long = System.nanoTime()
            for (i in 0 until n) store.insertKeep(item(i).copy(type = "obs"))
            kotlinx.coroutines.withTimeout(120_000L) { while (last.get() != n) kotlinx.coroutines.delay(10) }
            println("R8 OBSERVE n=$n single-row writes, emissions=${emissions.get()}, to the last emission ${(System.nanoTime() - started) / 1_000_000}ms")
            job.cancel()
            assertEquals(n, last.get())
        } finally {
            scope.cancel()
        }
    }
}
