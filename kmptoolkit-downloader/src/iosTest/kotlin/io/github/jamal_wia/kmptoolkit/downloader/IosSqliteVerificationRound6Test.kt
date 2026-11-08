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
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * The SQLite checks of `commitResource` on iOS (round 6): the 16-byte header rule, table and meta key used as values,
 * and the 3.0.0 forms that must keep working. Databases are built through the bundled driver the storage itself uses.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSqliteVerificationRound6Test {

    private val config = DownloaderStorageConfig(baseDirectoryName = "sqlite_round6")

    private class DbUnit(override val id: String, override val format: ResourceFormat) : DownloadUnit {
        override val apiPath: String = "/d/$id"
        override val relativePath: String = "d/$id.db"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@DbUnit)
        }
    }

    @BeforeTest
    fun deleteBefore() {
        deleteBase()
    }

    @AfterTest
    fun deleteAfter() {
        deleteBase()
        LegacyTempMigrations.resetForTests()
    }

    private fun deleteBase() {
        val appSupport: String = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .first() as String
        NSFileManager.defaultManager.removeItemAtPath("$appSupport/${config.baseDirectoryName}", error = null)
    }

    private fun run(connection: SQLiteConnection, sql: String, vararg texts: String) {
        val statement = connection.prepare(sql)
        try {
            texts.forEachIndexed { index: Int, text: String -> statement.bindText(index + 1, text) }
            statement.step()
        } finally {
            statement.close()
        }
    }

    /** A database file at a fresh path, its table named [table] and `meta` holding [declared] under [metaKey]. */
    private fun databasePath(table: String, metaKey: String, rows: Int, declared: Int): String {
        val path = "${NSTemporaryDirectory()}round6-${table.hashCode()}-${metaKey.hashCode()}-$rows-$declared.db"
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        val connection: SQLiteConnection = BundledSQLiteDriver().open(path)
        try {
            run(connection, "CREATE TABLE \"${table.replace("\"", "\"\"")}\" (id INTEGER PRIMARY KEY)")
            repeat(rows) { run(connection, "INSERT INTO \"${table.replace("\"", "\"\"")}\" DEFAULT VALUES") }
            run(connection, "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
            run(connection, "INSERT INTO meta VALUES (?, ?)", metaKey, declared.toString())
        } finally {
            connection.close()
        }
        return path
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

    private fun writeBytes(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: error("could not open $path")
        try {
            if (bytes.isNotEmpty()) bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1u.convert(), bytes.size.convert(), file) }
        } finally {
            fclose(file)
        }
    }

    private suspend fun commitDatabase(
        id: String,
        table: String,
        metaKey: String,
        rowCountTable: String = table,
        declared: Int = 3,
    ): Throwable? {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit(id, ResourceFormat.SqliteDatabase(rowCountTable, metaKey))
        storage.beginTempFile(unit, null)
        val temp: String = storage.getTempFilePath(unit)
        NSFileManager.defaultManager.createDirectoryAtPath(temp.substringBeforeLast('/'), true, null, null)
        val source: String = databasePath(table, metaKey, 3, declared)
        copyBytes(source, temp)
        NSFileManager.defaultManager.removeItemAtPath(source, error = null)
        storage.markTempFileComplete(unit)
        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
        if (failure == null) assertTrue(storage.isResourceAvailable(unit))
        return failure
    }

    private suspend fun commitBytes(id: String, bytes: ByteArray): Pair<Throwable?, TempFileState> {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit(id, ResourceFormat.SqliteDatabase())
        storage.beginTempFile(unit, null)
        val temp: String = storage.getTempFilePath(unit)
        NSFileManager.defaultManager.createDirectoryAtPath(temp.substringBeforeLast('/'), true, null, null)
        writeBytes(temp, bytes)
        storage.markTempFileComplete(unit)
        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
        assertFalse(storage.isResourceAvailable(unit))
        return failure to storage.tempFileState(unit)
    }

    @Test
    fun aZeroByteFileAndAFileOneByteShortOfAHundredAreNotDatabases() = runTest {
        val empty = commitBytes("empty", ByteArray(0))
        assertTrue(empty.first is ResourceIntegrityException, "an empty body committed as a database: ${empty.first}")
        assertEquals(TempFileState.None, empty.second)

        val header: ByteArray = "SQLite format 3\u0000".encodeToByteArray()
        val short = commitBytes("short", header + ByteArray(99 - header.size))
        assertTrue(short.first is ResourceIntegrityException, "a 99-byte body committed as a database: ${short.first}")
        assertEquals(TempFileState.None, short.second)
    }

    @Test
    fun aMetaKeyWithAnApostropheIsAValue() = runTest {
        val failure: Throwable? = commitDatabase("apostrophe", table = "t", metaKey = "ayah's rows")
        assertTrue(failure == null, "a valid database with the key \"ayah's rows\" was rejected: $failure")
    }

    @Test
    fun aKeywordTableAndATableWithASpaceAreNames() = runTest {
        val keyword: Throwable? = commitDatabase("keyword", table = "order", metaKey = "rows")
        val spaced: Throwable? = commitDatabase("spaced", table = "verse text", metaKey = "rows")
        assertTrue(keyword == null && spaced == null, "valid databases rejected: order -> $keyword; 'verse text' -> $spaced")
    }

    @Test
    fun theThreeOhFormsStillCommitAndAMismatchStillFails() = runTest {
        val quoted: Throwable? = commitDatabase("quoted", table = "order", metaKey = "rows", rowCountTable = "\"order\"")
        val qualified: Throwable? = commitDatabase("qualified", table = "verses", metaKey = "rows", rowCountTable = "main.verses")
        assertTrue(quoted == null && qualified == null, "3.0.0 forms rejected: \"order\" -> $quoted; main.verses -> $qualified")

        val mismatch: Throwable? = commitDatabase("mismatch", table = "order", metaKey = "rows", declared = 4)
        assertTrue(mismatch is ResourceIntegrityException, "expected ResourceIntegrityException, was $mismatch")
        val missing: Throwable? = commitDatabase("missing", table = "verses", metaKey = "rows", rowCountTable = "nothing here")
        assertTrue(missing is ResourceIntegrityException, "expected ResourceIntegrityException, was $missing")
    }
}
