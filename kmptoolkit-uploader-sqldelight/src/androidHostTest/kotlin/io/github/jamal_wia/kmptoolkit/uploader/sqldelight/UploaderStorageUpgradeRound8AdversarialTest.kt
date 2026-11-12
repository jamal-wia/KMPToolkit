package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderClock
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 8 (A1, upgrade): queue files made by earlier releases and damaged or foreign files, opened by this version.
 *
 * The base of every old file is the schema dump the build keeps for its migration check
 * (`src/commonMain/sqldelight/databases/1.db` is what releases up to 2.2.x created, `2.db` what 3.0.0 and 4.0.0
 * create; the `.sq` file of 3.0.0, 4.0.0 and this tree is the same text). Nothing here opens a file by a different
 * route than a consumer would.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStorageUpgradeRound8AdversarialTest {

    private val storages: MutableList<UploaderStorage> = mutableListOf()
    private val names: MutableList<String> = mutableListOf()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: UploaderEngine? = null

    @AfterTest
    fun cleanUp() {
        engine?.close()
        scope.cancel()
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
        storages.forEach { runCatching { it.close() } }
        names.forEach { name ->
            val file: File = application().getDatabasePath(uploaderDatabaseFileName(name))
            file.setWritable(true)
            application().deleteDatabase(uploaderDatabaseFileName(name))
        }
    }

    private fun freshName(tag: String): String = "adv.a1r8.$tag.${Random.nextLong(0L, Long.MAX_VALUE)}".also { names += it }

    private fun dumpFile(version: Int): File = File(File(File(File("src"), "commonMain"), "sqldelight"), "databases").let { File(it, "$version.db") }

    private fun dbPath(name: String): File = application().getDatabasePath(uploaderDatabaseFileName(name))

    /** An old queue file: the dump of schema [version], [rows] rows written by raw SQL the way that release wrote them. */
    private fun writeOldFile(name: String, version: Int, rows: Int, state: (Int) -> String = { "PENDING" }): File {
        val target: File = dbPath(name)
        target.parentFile?.mkdirs()
        val source: File = dumpFile(version)
        assertTrue(source.isFile, "the schema dump ${source.absolutePath} is part of the repository")
        source.copyTo(target, overwrite = true)
        val db: SQLiteDatabase = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            for (i in 0 until rows) {
                val columns: String = "id, type, payload, schema_version, unique_key, ordering_key, tag, state, attempts, next_run_at, created_at, last_error, lease_until"
                val inFlight: Boolean = state(i) == "IN_FLIGHT"
                db.execSQL(
                    "INSERT INTO kmptoolkit_uploader_item($columns) VALUES (?, 'old.type', ?, 1, ?, NULL, 'acct', ?, ?, 0, ?, NULL, ?)",
                    arrayOf<Any?>("old-$i", "payload-$i", if (i % 4 == 0) "key-$i" else null, state(i), i % 3, i.toLong(), if (inFlight) 9_000_000_000_000L else 0L),
                )
            }
            db.setTransactionSuccessful()
            db.endTransaction()
            db.version = version
        } finally {
            db.close()
        }
        return target
    }

    private fun schemaOf(path: File): Map<String, String> {
        val db: SQLiteDatabase = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val out: MutableMap<String, String> = sortedMapOf()
            db.rawQuery("SELECT name, sql FROM sqlite_master WHERE name LIKE 'kmptoolkit_uploader_item%'", null).use {
                while (it.moveToNext()) out[it.getString(0)] = it.getString(1) ?: ""
            }
            db.rawQuery("PRAGMA table_info(kmptoolkit_uploader_item)", null).use {
                while (it.moveToNext()) out["col:" + it.getString(1)] = "${it.getString(2)}|notnull=${it.getInt(3)}|dflt=${it.getString(4)}|pk=${it.getInt(5)}"
            }
            return out
        } finally {
            db.close()
        }
    }

    private fun userVersion(path: File): Int {
        val db: SQLiteDatabase = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return db.version
        } finally {
            db.close()
        }
    }

    private fun sha(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun dbThreads(): Int = Thread.getAllStackTraces().keys.count { it.name == "kmptoolkit-uploader-db" && it.isAlive }

    private fun open(name: String, recreate: Boolean = false): UploaderStorage =
        createUploaderStorage(application(), UploaderDatabaseConfig(name, recreate)).also { storages += it }

    @Test
    fun `a 2_2 file (schema 1) with many rows migrates to exactly the schema a fresh file has, keeping every row in order`() = runBlocking {
        val oldName: String = freshName("v1")
        val path: File = writeOldFile(oldName, version = 1, rows = 12_000) { if (it % 7 == 0) "PARKED" else "PENDING" }
        val storage: UploaderStorage = open(oldName)
        val active: List<UploaderItem> = storage.store.getAllActive()
        assertEquals((0 until 12_000).filter { it % 7 != 0 }.map { "old-$it" }, active.map { it.id }, "rows lost or reordered by the migration")
        assertTrue(active.all { it.claim == 0L }, "claim of a migrated row")
        assertEquals(UploaderItemState.PARKED, storage.store.getById("old-0")?.state)
        storage.close()
        assertEquals(2, userVersion(path))

        val freshNameValue: String = freshName("fresh")
        open(freshNameValue).close()
        val migrated: Map<String, String> = schemaOf(path)
        val fresh: Map<String, String> = schemaOf(dbPath(freshNameValue))
        assertEquals(fresh.keys, migrated.keys, "the migrated file's indices and columns differ from a fresh file's")
        for (key in fresh.keys.filter { it.startsWith("col:") || it.contains("_item_") }) {
            assertEquals(fresh[key], migrated[key], "definition of $key differs after migration")
        }
    }

    @Test
    fun `a 3_0 or 4_0 file (schema 2 dump) opens untouched and takes writes`() = runBlocking {
        val name: String = freshName("v2")
        val path: File = writeOldFile(name, version = 2, rows = 50) // the dump already has the claim column; the raw insert leaves it 0
        val before: String = sha(path)
        val storage: UploaderStorage = open(name)
        assertEquals(50, storage.store.getAllActive().size)
        assertTrue(storage.store.markInFlight("old-1", 5L, UploaderItemState.PENDING, 0L))
        assertEquals(1L, storage.store.getById("old-1")?.claim)
        storage.close()
        assertEquals(2, userVersion(path))
        assertTrue(before != sha(path), "a write changed the file")
    }

    @Test
    fun `a file of a newer schema version is refused with a storage exception, in both configurations, and is left exactly as found`() = runBlocking {
        for (recreate in listOf(false, true)) {
            val name: String = freshName("newer$recreate")
            val path: File = writeOldFile(name, version = 2, rows = 30)
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 3 }
            val before: String = sha(path)
            val threadsBefore: Int = dbThreads()
            val failure: UploaderStorageException = assertFailsWith<UploaderStorageException>("a newer file must not open (recreate=$recreate)") {
                open(name, recreate)
            }
            assertEquals(UploaderStorageOperation.OPEN, failure.operation)
            assertTrue(path.isFile, "the newer file was deleted (recreate=$recreate)")
            assertEquals(3, userVersion(path), "the newer file's version was rewritten (recreate=$recreate)")
            assertEquals(before, sha(path), "the newer file's bytes changed (recreate=$recreate)")
            assertEquals(threadsBefore, dbThreads(), "a failed open leaked its database thread")
            val raw: SQLiteDatabase = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
            raw.rawQuery("SELECT COUNT(*) FROM kmptoolkit_uploader_item", null).use { it.moveToFirst(); assertEquals(30, it.getInt(0)) }
            raw.close()
        }
    }

    @Test
    // A file of exactly one byte is not cut here: SQLite itself (the sqlite3 shell too) reads it as an empty database.
    fun `a file cut at any length is refused or read completely, never silently emptied, and a refused file is left as found`() = runBlocking {
        val source: String = freshName("whole")
        run {
            val s: UploaderStorage = open(source)
            s.transactionRunner.inTransaction {
                for (i in 0 until 400) s.store.insertKeep(UploaderItem(
                    id = "w-$i", type = "t", payload = "x".repeat(300), schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
                    state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = i.toLong(), lastError = null,
                ))
            }
            s.close()
        }
        val whole: ByteArray = dbPath(source).readBytes()
        val outcomes: MutableList<String> = mutableListOf()
        val cuts: List<Int> = listOf(2, 16, 50, 99, 100, 101, 512, 4095, 4096, 4097, 8192, whole.size / 2, whole.size - 1)
        for (cut in cuts.filter { it < whole.size }) {
            val name: String = freshName("cut$cut")
            val path: File = dbPath(name)
            path.parentFile?.mkdirs()
            path.writeBytes(whole.copyOf(cut))
            File(path.path + "-wal").delete()
            File(path.path + "-shm").delete()
            val before: String = sha(path)
            val outcome: String = try {
                val storage: UploaderStorage = open(name)
                try {
                    val rows: List<UploaderItem> = storage.store.getAllActive()
                    if (rows.size == 400) "read-whole" else "SILENT-LOSS ${rows.size}/400"
                } catch (e: UploaderStorageException) {
                    "read-refused"
                } finally {
                    storage.close()
                }
            } catch (e: UploaderStorageException) {
                if (sha(path) == before) "open-refused-untouched" else "open-refused-FILE-CHANGED"
            }
            outcomes += "cut=$cut $outcome"
        }
        println("R8 TRUNCATION OUTCOMES: $outcomes")
        assertTrue(outcomes.none { "SILENT-LOSS" in it }, "a cut file opened with fewer rows and no error: $outcomes")
        assertTrue(outcomes.none { "FILE-CHANGED" in it }, "a refused file was modified: $outcomes")
    }

    @Test
    fun `a read-only file is refused as a storage exception and nothing is lost, and it opens again once writable`() = runBlocking {
        for (version in listOf(1, 2)) {
            val name: String = freshName("ro$version")
            val path: File = writeOldFile(name, version = version, rows = 20)
            val before: String = sha(path)
            assertTrue(path.setReadOnly(), "could not make the file read-only on this host")
            try {
                val outcome: String = try {
                    val s: UploaderStorage = open(name)
                    try {
                        // a schema-2 file may open read-only on this platform; a read must then be right
                        "opened rows=${s.store.getAllActive().size}"
                    } finally {
                        s.close()
                    }
                } catch (e: UploaderStorageException) {
                    "refused"
                }
                println("R8 READ-ONLY v$version: $outcome")
                if (version == 1) assertEquals("refused", outcome, "a v1 file needs a migration and cannot be written")
            } finally {
                path.setWritable(true)
            }
            assertEquals(before, sha(path), "the read-only file's bytes changed (v$version)")
            val storage: UploaderStorage = open(name)
            assertEquals(20, storage.store.getAllActive().size, "rows lost across a read-only episode (v$version)")
        }
    }

    /** The real migration, then a failure: what the process dying inside the migration looks like to the next open. */
    private class FailAfterMigration(private val inner: SqlSchema<QueryResult.Value<Unit>>) : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = inner.version
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = inner.create(driver)
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
            inner.migrate(driver, oldVersion, newVersion, *callbacks)
            throw IllegalStateException("killed inside the migration, after its last statement")
        }
    }

    /** The real migration, then a copy of every file of the database as they are at that moment (a kill's leftovers), then a failure. */
    private inner class SnapshotThenFail(private val inner: SqlSchema<QueryResult.Value<Unit>>, private val from: File, private val to: File) : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = inner.version
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = inner.create(driver)
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
            inner.migrate(driver, oldVersion, newVersion, *callbacks)
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val source = File(from.path + suffix)
                if (source.exists()) source.copyTo(File(to.path + suffix), overwrite = true)
            }
            throw IllegalStateException("killed inside the migration")
        }
    }

    @Test
    fun `the files of a database as a kill inside its migration leaves them open, finish the migration, and keep every row`() = runBlocking {
        val name: String = freshName("killsrc")
        val killed: String = freshName("killed")
        val path: File = writeOldFile(name, version = 1, rows = 2_000)
        val target: File = dbPath(killed)
        target.parentFile?.mkdirs()
        val driver = AndroidSqliteDriver(SnapshotThenFail(uploaderDatabaseSchema, path, target), application(), uploaderDatabaseFileName(name))
        assertFailsWith<Throwable> { forceOpen(driver) }
        runCatching { driver.close() }
        assertTrue(target.isFile, "the snapshot was not taken")
        val storage: UploaderStorage = open(killed)
        assertEquals(2_000, storage.store.getAllActive().size, "rows lost by finishing a migration that was cut off")
        assertEquals(0L, storage.store.getById("old-5")?.claim)
        storage.close()
        assertEquals(2, userVersion(target))
        val integrity: String = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); it.getString(0) }
        }
        assertEquals("ok", integrity)
    }

    @Test
    fun `a migration that dies after its last statement rolls back whole, and the next open completes it with every row`() = runBlocking {
        val name: String = freshName("midmig")
        val path: File = writeOldFile(name, version = 1, rows = 500)
        repeat(3) {
            val driver = AndroidSqliteDriver(FailAfterMigration(uploaderDatabaseSchema), application(), uploaderDatabaseFileName(name))
            assertFailsWith<Throwable> { forceOpen(driver) }
            runCatching { driver.close() }
            assertEquals(1, userVersion(path), "a half-run migration advanced the version")
            val cols: Set<String> = schemaOf(path).keys.filter { it.startsWith("col:") }.toSet()
            assertTrue("col:claim" !in cols, "the claim column survived a rolled-back migration")
        }
        val storage: UploaderStorage = open(name)
        assertEquals(500, storage.store.getAllActive().size)
        storage.close()
        assertEquals(2, userVersion(path))
    }

    @Test
    fun `a full disk fails an enqueue as a storage exception, loses nothing accepted, rolls a whole transaction back, and recovers after a delete`() = runBlocking {
        val name: String = freshName("full")
        // measure the file after a small queue, then cap the page count a little above it (what a full volume does: SQLITE_FULL)
        open(name).close()
        val file: File = dbPath(name)
        val pagesNow: Long = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) }
        }
        val capped = object : AndroidSqliteDriver.Callback(uploaderDatabaseSchema) {
            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                super.onOpen(db)
                db.query("PRAGMA max_page_count=${pagesNow + 40}").use { it.moveToFirst() }
            }
        }
        val driver = AndroidSqliteDriver(uploaderDatabaseSchema, application(), uploaderDatabaseFileName(name), callback = capped)
        val storage: UploaderStorage = createUploaderStorage(driver).also { storages += it }
        val accepted: MutableList<String> = mutableListOf()
        var refused: UploaderStorageException? = null
        for (i in 0 until 5_000) {
            val item = UploaderItem(
                id = "f-$i", type = "t", payload = "y".repeat(2_000), schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
                state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = i.toLong(), lastError = null,
            )
            try {
                assertTrue(storage.store.insertKeep(item).inserted, "insert $i answered false")
                accepted += item.id
            } catch (e: UploaderStorageException) {
                refused = e
                break
            }
        }
        assertNotNull(refused, "the page cap never filled; the test did not exercise a full disk")
        assertTrue(accepted.isNotEmpty(), "nothing fitted under the cap (pages=$pagesNow), first refusal: ${refused?.cause}")
        assertEquals(accepted, storage.store.getAllActive().map { it.id }, "an accepted row was lost or a refused one appeared")
        // a transaction that cannot finish leaves nothing of itself behind
        val txFailure: UploaderStorageException? = try {
            storage.transactionRunner.inTransaction {
                for (j in 0 until 200) {
                    storage.store.insertKeep(UploaderItem(
                        id = "tx-$j", type = "t", payload = "z".repeat(2_000), schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
                        state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
                    ))
                }
            }
            null
        } catch (e: UploaderStorageException) {
            e
        }
        assertNotNull(txFailure, "200 more rows fitted although the cap was reached")
        assertEquals(accepted, storage.store.getAllActive().map { it.id }, "rows of a rolled-back transaction are visible")
        // freeing rows makes room again
        // the tail of the table is where inserts go: free the last rows (whole pages), not the first one
        val freed: List<String> = accepted.takeLast(20)
        freed.forEach { storage.store.deleteById(it) }
        println("R8 FULL DISK accepted=${accepted.size} rows before the cap, refused=${refused.cause} suppressed=${refused.cause?.suppressed?.toList()} root=${generateSequence<Throwable>(refused) { it.cause }.last()}")
        assertTrue(storage.store.insertKeep(UploaderItem(
            id = "after", type = "t", payload = "q", schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
            state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
        )).inserted, "an enqueue after a delete freed room was refused")
        assertEquals(accepted.dropLast(20) + "after", storage.store.getAllActive().map { it.id })
    }

    private class RunLog : UploaderHandler<String> {
        override val type: String = "old.type"
        val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val contexts: MutableList<AttemptContext> = Collections.synchronizedList(mutableListOf())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            ran += context.id
            contexts += context
            return AttemptResult.Success
        }
    }

    @Test
    fun `the engine drains a migrated 3_x file, re-running an in-flight row whose lease is absurdly far ahead, and leaves parked rows alone`() = runBlocking {
        val name: String = freshName("engine")
        writeOldFile(name, version = 1, rows = 300) {
            when (it % 10) {
                0 -> "PARKED"
                1 -> "IN_FLIGHT"
                else -> "PENDING"
            }
        }
        val storage: UploaderStorage = open(name)
        val handler = RunLog()
        val clock: UploaderClock = UploaderClock { System.currentTimeMillis() }
        engine = createUploaderEngine(
            store = storage.store, handlers = listOf(handler), scope = scope, transactionRunner = storage.transactionRunner, clock = clock,
        ).also { it.start() }
        withTimeout(60.seconds) {
            while (storage.store.getAllActive().isNotEmpty()) delay(50)
        }
        val expected: Set<String> = (0 until 300).filter { it % 10 != 0 }.map { "old-$it" }.toSet()
        assertEquals(expected, handler.ran.toSet(), "every pending and in-flight row of the old file runs")
        assertEquals(handler.ran.size, handler.ran.toSet().size, "a row ran twice")
        assertEquals(UploaderItemState.PARKED, storage.store.getById("old-0")?.state, "a parked row of the old file was touched")
    }
}
