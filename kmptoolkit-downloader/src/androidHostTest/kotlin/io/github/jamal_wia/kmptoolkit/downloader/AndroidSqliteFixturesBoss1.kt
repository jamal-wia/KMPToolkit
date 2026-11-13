package io.github.jamal_wia.kmptoolkit.downloader

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** The main file, the write-ahead log and the index of a WAL database as a killed process leaves them (no checkpoint). */
internal class WalSnapshotBoss1(val main: ByteArray, val wal: ByteArray, val shm: ByteArray)

/** The main file and the journal of a rollback-mode database whose transaction was cut mid-write. */
internal class JournalSnapshotBoss1(val main: ByteArray, val journal: ByteArray)

/** The databases of the boss round 1 tests of replacing and deleting a `SqliteDatabase` resource, made in [scratch]. */
internal class AndroidSqliteFixturesBoss1(private val scratch: File) {

    /** [name] in the scratch directory, with nothing of an earlier database of that name left. */
    private fun emptyFile(name: String): File {
        scratch.mkdirs()
        val file = File(scratch, name)
        for (path: String in listOf(file.path) + listOf("-wal", "-shm", "-journal").map { suffix: String -> file.path + suffix }) File(path).delete()
        return file
    }

    /** Version 1: `old0` in the main file, then 50 `consumerN` rows that live only in the write-ahead log. */
    fun liveWalSnapshot(): WalSnapshotBoss1 {
        val file: File = emptyFile("old.db")
        val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.enableWriteAheadLogging()
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, payload TEXT)")
            db.execSQL("INSERT INTO t (payload) VALUES ('old0')")
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            // Written after the checkpoint: these frames live only in the -wal file.
            repeat(50) { index: Int -> db.execSQL("INSERT INTO t (payload) VALUES ('consumer$index')") }
            val wal = File(file.path + "-wal")
            val shm = File(file.path + "-shm")
            check(wal.length() > 0L) { "the fixture needs frames in the write-ahead log" }
            return WalSnapshotBoss1(main = file.readBytes(), wal = wal.readBytes(), shm = shm.readBytes())
        } finally {
            db.close()
        }
    }

    /** A rollback-mode database whose transaction spilled to the file: a hot journal beside a half-written main file. */
    fun hotJournalSnapshot(): JournalSnapshotBoss1 {
        val file: File = emptyFile("hot.db")
        val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, payload TEXT)")
            db.beginTransaction()
            try {
                repeat(300) { index: Int -> db.execSQL("INSERT INTO t (payload) VALUES ('old$index')") }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.rawQuery("PRAGMA cache_size=2", null).use { it.moveToFirst() }
            db.beginTransaction()
            try {
                db.execSQL("UPDATE t SET payload = payload || '-torn-torn-torn-torn-torn-torn-torn-torn-torn-torn-torn-torn'")
                val journal = File(file.path + "-journal")
                check(journal.length() > 0L) { "the fixture needs a journal with content" }
                return JournalSnapshotBoss1(main = file.readBytes(), journal = journal.readBytes())
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    fun freshDatabase(rows: Int): ByteArray {
        val file: File = emptyFile("new-$rows.db")
        val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, payload TEXT)")
            repeat(rows) { index: Int -> db.execSQL("INSERT INTO t (payload) VALUES ('new$index')") }
        } finally {
            db.close()
        }
        return file.readBytes()
    }

    fun readPayloads(path: String): List<String> {
        val db: SQLiteDatabase = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            return db.rawQuery("SELECT payload FROM t ORDER BY id", null).use { cursor ->
                val out: ArrayList<String> = ArrayList()
                while (cursor.moveToNext()) out.add(cursor.getString(0))
                out
            }
        } finally {
            db.close()
        }
    }

    /** [readPayloads], or one `unreadable: ...` element when the file is no readable database. */
    fun payloadsOrFailure(path: String): List<String> =
        runCatching { readPayloads(path) }.getOrElse { e: Throwable -> listOf("unreadable: $e") }

    companion object {
        /** `old0` and the 50 rows only the write-ahead log of [AndroidSqliteFixturesBoss1.liveWalSnapshot] holds. */
        val OLD_WITH_LOG: List<String> = listOf("old0") + (0 until 50).map { index: Int -> "consumer$index" }
    }
}
