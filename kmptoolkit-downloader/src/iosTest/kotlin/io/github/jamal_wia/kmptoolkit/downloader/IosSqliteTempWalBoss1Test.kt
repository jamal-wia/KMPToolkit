package io.github.jamal_wia.kmptoolkit.downloader

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.memcpy

/**
 * B boss round 1: the iOS twin of `AndroidSqliteWalRound7Test`'s concern, which had no iOS test (a mutant that drops the
 * removal of a stale `-wal` before verification survived the whole iOS suite). Contract (05-platform-notes): verifying a
 * `SqliteDatabase` leaves nothing beside the temp file, and a stale `-wal` left there by an earlier verification that a
 * process death cut short is removed before the check, so it is never replayed into the delivered file. The committed
 * database is the delivered one, byte for byte.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSqliteTempWalBoss1Test {

    private val config = DownloaderStorageConfig(baseDirectoryName = "sqlite_temp_wal_boss1")
    // timing-hygiene-ignore: TH4 create makes the test's own uniquely named directory and deletes it in deleteAfter
    private val scratch: String = "${NSTemporaryDirectory()}sqlite-temp-wal-boss1-${NSProcessInfo.processInfo.globallyUniqueString}"

    private class DbUnit(override val id: String) : DownloadUnit {
        override val apiPath: String = "/d/$id"
        override val relativePath: String = "d/$id.db"
        override val format: ResourceFormat = ResourceFormat.SqliteDatabase()
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@DbUnit)
        }
    }

    @BeforeTest
    fun deleteBefore() {
        deleteAll()
        NSFileManager.defaultManager.createDirectoryAtPath(scratch, true, null, null)
    }

    @AfterTest
    fun deleteAfter() {
        deleteAll()
        LegacyTempMigrations.resetForTests()
    }

    private fun deleteAll() {
        val appSupport: String = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .first() as String
        NSFileManager.defaultManager.removeItemAtPath("$appSupport/${config.baseDirectoryName}", error = null)
        NSFileManager.defaultManager.removeItemAtPath(scratch, error = null)
    }

    private fun run(connection: SQLiteConnection, sql: String) {
        val statement = connection.prepare(sql)
        try {
            statement.step()
        } finally {
            statement.close()
        }
    }

    private fun copyBytes(from: String, to: String) {
        val data: NSData = NSData.dataWithContentsOfFile(from) ?: error("no $from")
        val file = fopen(to, "wb") ?: error("could not open $to")
        try {
            if (data.length > 0u) fwrite(data.bytes, 1u.convert(), data.length.convert(), file)
        } finally {
            fclose(file)
        }
    }

    /** Copies of the main file, the log and the index of a WAL database whose log holds frames, taken while it is open. */
    private fun liveWalSnapshot(): Triple<String, String, String> {
        val path = "$scratch/old.db"
        val connection: SQLiteConnection = BundledSQLiteDriver().open(path)
        try {
            run(connection, "PRAGMA journal_mode=WAL")
            run(connection, "PRAGMA wal_autocheckpoint=0")
            run(connection, "CREATE TABLE t (id INTEGER PRIMARY KEY, payload TEXT)")
            run(connection, "INSERT INTO t (payload) VALUES ('old0')")
            run(connection, "PRAGMA wal_checkpoint(TRUNCATE)")
            repeat(50) { index: Int -> run(connection, "INSERT INTO t (payload) VALUES ('consumer$index')") }
            copyBytes(path, "$scratch/snap.db")
            copyBytes("$path-wal", "$scratch/snap.db-wal")
            copyBytes("$path-shm", "$scratch/snap.db-shm")
        } finally {
            connection.close()
        }
        return Triple("$scratch/snap.db", "$scratch/snap.db-wal", "$scratch/snap.db-shm")
    }

    private fun freshDatabase(rows: Int): String {
        val path = "$scratch/new-$rows.db"
        val connection: SQLiteConnection = BundledSQLiteDriver().open(path)
        try {
            run(connection, "CREATE TABLE t (id INTEGER PRIMARY KEY, payload TEXT)")
            repeat(rows) { index: Int -> run(connection, "INSERT INTO t (payload) VALUES ('new$index')") }
        } finally {
            connection.close()
        }
        return path
    }

    private fun readPayloads(path: String): List<String> {
        val connection: SQLiteConnection = BundledSQLiteDriver().open(path)
        try {
            val statement = connection.prepare("SELECT payload FROM t ORDER BY id")
            try {
                val out: ArrayList<String> = ArrayList()
                while (statement.step()) out.add(statement.getText(0))
                return out
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
    }

    private fun bytesOf(path: String): ByteArray {
        val data: NSData = NSData.dataWithContentsOfFile(path) ?: error("no $path")
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return bytes
    }

    @Test
    fun aStaleWriteAheadLogBesideTheCompleteTempFileIsNeverReplayedIntoTheDeliveredDatabase() = runTest {
        val (_: String, wal: String, shm: String) = liveWalSnapshot()
        val fresh: String = freshDatabase(rows = 3)
        val delivered: ByteArray = bytesOf(fresh)
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("content")
        storage.beginTempFile(unit, null)
        val temp: String = storage.getTempFilePath(unit)
        NSFileManager.defaultManager.createDirectoryAtPath(temp.substringBeforeLast('/'), true, null, null)
        copyBytes(fresh, temp)
        storage.markTempFileComplete(unit)
        // An earlier verification of this unit was killed with its log open: the 4.x complete file is the partial
        // path plus `.complete` (the name AndroidSqliteWalRound7Test observed).
        copyBytes(wal, "$temp.complete-wal")
        copyBytes(shm, "$temp.complete-shm")

        storage.commitResource(unit)

        val resource: String = storage.getResourcePath(unit)
        assertContentEquals(delivered, bytesOf(resource), "verification replayed a stale write-ahead log into the delivered file")
        assertEquals(listOf("new0", "new1", "new2"), readPayloads(resource))
    }
}
