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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

/**
 * B boss round 1, iOS twin of the Android destination write-ahead-log test: a `SqliteDatabase` replaced in place must
 * read, after the commit, as the database that was verified, even when the previous version's reader died with frames
 * in its `-wal` beside the resource. The old file leaves with its `-wal`, `-shm` and `-journal` on a replace and on a
 * delete (also when only the sidecars are left), and files named like sidecars beside an opaque resource are never
 * touched. iOS has no rename seam: the kill points of the quarantine are argued in the fixer report, not tested.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSqliteDestinationWalBoss1Test {

    private val config = DownloaderStorageConfig(baseDirectoryName = "sqlite_destination_wal_boss1")
    // timing-hygiene-ignore: TH4 create makes the test's own uniquely named directory and deletes it in deleteAfter
    private val scratch: String = "${NSTemporaryDirectory()}sqlite-destination-wal-boss1-${NSProcessInfo.processInfo.globallyUniqueString}"

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

    private suspend fun commit(storage: DownloaderStorage, unit: DownloadUnit, source: String) {
        storage.beginTempFile(unit, null)
        val temp: String = storage.getTempFilePath(unit)
        NSFileManager.defaultManager.createDirectoryAtPath(temp.substringBeforeLast('/'), true, null, null)
        copyBytes(source, temp)
        storage.markTempFileComplete(unit)
        storage.commitResource(unit)
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

    @Test
    fun aNewVersionReplacingADatabaseWhoseReaderDiedWithAnOpenWriteAheadLogReadsAsTheNewVersion() = runTest {
        val (main: String, wal: String, shm: String) = liveWalSnapshot()
        val fresh: String = freshDatabase(rows = 3)
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("content")
        commit(storage, unit, main)
        val resource: String = storage.getResourcePath(unit)
        copyBytes(wal, "$resource-wal")
        copyBytes(shm, "$resource-shm")

        commit(storage, unit, fresh)

        val payloads: List<String> = runCatching { readPayloads(resource) }.getOrElse { e: Throwable -> listOf("unreadable: $e") }
        assertEquals(listOf("new0", "new1", "new2"), payloads, "the previous version's write-ahead log was replayed into the committed database")
    }

    private fun write(path: String, bytes: ByteArray) {
        NSFileManager.defaultManager.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null)
        val file = fopen(path, "wb") ?: error("could not open $path")
        try {
            if (bytes.isNotEmpty()) bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1u.convert(), bytes.size.convert(), file) }
        } finally {
            fclose(file)
        }
    }

    private fun exists(path: String): Boolean = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) != null

    private fun sidecarsAt(resource: String): List<String> =
        listOf("-wal", "-shm", "-journal").filter { suffix: String -> exists(resource + suffix) }

    @Test
    fun aDatabaseDeletedAndDownloadedAgainReadsAsTheNewDownloadWhateverTheDeletedOneLeftBesideIt() = runTest {
        val (main: String, wal: String, shm: String) = liveWalSnapshot()
        val fresh: String = freshDatabase(rows = 2)
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("deleted")
        commit(storage, unit, main)
        val resource: String = storage.getResourcePath(unit)
        copyBytes(wal, "$resource-wal")
        copyBytes(shm, "$resource-shm")

        storage.deleteResource(unit)
        commit(storage, unit, fresh)

        val payloads: List<String> = runCatching { readPayloads(resource) }.getOrElse { e: Throwable -> listOf("unreadable: $e") }
        assertEquals(listOf("new0", "new1"), payloads, "deleteResource left the old write-ahead log, replayed into the new download")
    }

    @Test
    fun aHotRollbackJournalBesideTheReplacedDatabaseIsGoneAfterTheCommit() = runTest {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("journal")
        commit(storage, unit, freshDatabase(rows = 7))
        val resource: String = storage.getResourcePath(unit)
        write("$resource-journal", ByteArray(64) { 7 })

        commit(storage, unit, freshDatabase(rows = 3))

        assertFalse(exists("$resource-journal"), "the journal of the replaced database still stands at the path")
        val payloads: List<String> = runCatching { readPayloads(resource) }.getOrElse { e: Throwable -> listOf("unreadable: $e") }
        assertEquals(listOf("new0", "new1", "new2"), payloads)
    }

    @Test
    fun aJournalOfADeletedDatabaseIsGoneWithIt() = runTest {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("journaldelete")
        commit(storage, unit, freshDatabase(rows = 7))
        val resource: String = storage.getResourcePath(unit)
        write("$resource-journal", ByteArray(64) { 7 })

        storage.deleteResource(unit)

        assertEquals(emptyList(), sidecarsAt(resource))
        assertFalse(exists(resource))
    }

    @Test
    fun aDatabaseThatIsGoneButLeftALogIsCleanedByTheNextCommit() = runTest {
        val (main: String, wal: String, shm: String) = liveWalSnapshot()
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("gone")
        commit(storage, unit, main)
        val resource: String = storage.getResourcePath(unit)
        copyBytes(wal, "$resource-wal")
        copyBytes(shm, "$resource-shm")
        assertTrue(NSFileManager.defaultManager.removeItemAtPath(resource, error = null), "the fixture removes the main file only")

        commit(storage, unit, freshDatabase(rows = 3))

        assertEquals(emptyList(), sidecarsAt(resource))
        val payloads: List<String> = runCatching { readPayloads(resource) }.getOrElse { e: Throwable -> listOf("unreadable: $e") }
        assertEquals(listOf("new0", "new1", "new2"), payloads)
    }

    @Test
    fun aDeleteOfADatabaseWhoseFileIsGoneStillRemovesTheLogItLeft() = runTest {
        val (main: String, wal: String, shm: String) = liveWalSnapshot()
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("gonedelete")
        commit(storage, unit, main)
        val resource: String = storage.getResourcePath(unit)
        copyBytes(wal, "$resource-wal")
        copyBytes(shm, "$resource-shm")
        assertTrue(NSFileManager.defaultManager.removeItemAtPath(resource, error = null), "the fixture removes the main file only")

        storage.deleteResource(unit)

        assertEquals(emptyList(), sidecarsAt(resource))
    }

    private class OpaqueUnit : DownloadUnit {
        override val id: String = "blob"
        override val apiPath: String = "/d/blob"
        override val relativePath: String = "d/blob.bin"
        override val format: ResourceFormat = ResourceFormat.Opaque
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@OpaqueUnit)
        }
    }

    @Test
    fun filesNamedLikeSidecarsBesideAnOpaqueResourceSurviveItsCommitAndItsDelete() = runTest {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = OpaqueUnit()
        write("$scratch/one", "one".encodeToByteArray())
        commit(storage, unit, "$scratch/one")
        val resource: String = storage.getResourcePath(unit)
        for (suffix: String in listOf("-wal", "-shm", "-journal")) write(resource + suffix, suffix.encodeToByteArray())

        write("$scratch/two", "two".encodeToByteArray())
        commit(storage, unit, "$scratch/two")
        assertEquals(listOf("-wal", "-shm", "-journal"), sidecarsAt(resource), "an opaque commit touched a file named like a sidecar")
        storage.deleteResource(unit)
        assertEquals(listOf("-wal", "-shm", "-journal"), sidecarsAt(resource), "an opaque delete touched a file named like a sidecar")
    }
}
