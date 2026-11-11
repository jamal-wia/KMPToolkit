package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ResourceFormat.SqliteDatabase: "give rowCountTable and declaredRowCountMetaKey and the row count of that table must equal
 * the value stored under that key in a meta(key, value) table. Which table and which key those are is the host's domain
 * knowledge, which is why they are values here". Values, so any string: a table that is an SQL keyword and a key with an
 * apostrophe are legitimate names of a valid database.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 35])
class AndroidSqliteQuotingRound6Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "sqlquote_round6")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class DbUnit(override val id: String, override val format: ResourceFormat) : DownloadUnit {
        override val apiPath: String = "/d/$id"
        override val relativePath: String = "d/$id.db"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@DbUnit)
        }
    }

    private fun database(table: String, metaKey: String, rows: Int, declared: Int = rows): ByteArray {
        val file = File(context.cacheDir, "q6-${System.nanoTime()}.db")
        TestHost.assumeNativePathFits(file.path)
        val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE \"$table\" (id INTEGER PRIMARY KEY)")
            repeat(rows) { db.execSQL("INSERT INTO \"$table\" DEFAULT VALUES") }
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
            db.compileStatement("INSERT INTO meta VALUES (?, ?)").apply {
                bindString(1, metaKey)
                bindString(2, declared.toString())
                executeInsert()
            }
        } finally {
            db.close()
        }
        return file.readBytes()
    }

    /** Commits a database whose table is [table], naming it to the format as [rowCountTable] (the table itself by default). */
    private fun commit(
        id: String,
        table: String,
        metaKey: String,
        rowCountTable: String = table,
        declared: Int = 3,
    ): Throwable? = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val unit = DbUnit(id, ResourceFormat.SqliteDatabase(rowCountTable, metaKey))
            storage.beginTempFile(unit, null)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }
                .writeBytes(database(table, metaKey, 3, declared))
            storage.markTempFileComplete(unit)
            val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
            if (failure == null) assertTrue(storage.isResourceAvailable(unit))
            failure
        }
    }

    @Test
    fun `a meta key with an apostrophe is a value not SQL`() {
        val failure: Throwable? = commit("apostrophe", table = "t", metaKey = "ayah's rows")
        assertTrue(failure == null, "a valid database with the key \"ayah's rows\" was rejected: $failure")
    }

    @Test
    fun `a table that is an SQL keyword or holds a space is a name not SQL`() {
        val keyword: Throwable? = commit("keyword", table = "order", metaKey = "rows")
        val spaced: Throwable? = commit("spaced", table = "verse text", metaKey = "rows")
        assertTrue(keyword == null && spaced == null, "valid databases rejected: order -> $keyword; 'verse text' -> $spaced")
    }

    // Compatibility with 3.0.0: a value that is SQL text keeps working (green before and after the quoting fix).
    @Test
    fun `a pre-quoted table name and a schema-qualified one still commit`() {
        val quoted: Throwable? = commit("quoted", table = "order", metaKey = "rows", rowCountTable = "\"order\"")
        val qualified: Throwable? = commit("qualified", table = "verses", metaKey = "rows", rowCountTable = "main.verses")
        val bracketed: Throwable? = commit("bracketed", table = "order", metaKey = "rows", rowCountTable = "[order]")
        assertTrue(
            quoted == null && qualified == null && bracketed == null,
            "3.0.0 forms rejected: \"order\" -> $quoted; main.verses -> $qualified; [order] -> $bracketed",
        )
    }

    @Test
    fun `a table whose name differs only in case is resolved as stored`() {
        val failure: Throwable? = commit("nocase", table = "Verses", metaKey = "rows", rowCountTable = "verses")
        assertTrue(failure == null, "a case-different table name was rejected: $failure")
    }

    @Test
    fun `a keyword table whose meta count differs is still an integrity failure through the quoted path`() {
        val failure: Throwable? = commit("mismatch", table = "order", metaKey = "rows", declared = 4)
        assertTrue(failure is ResourceIntegrityException, "expected ResourceIntegrityException, was $failure")
    }

    @Test
    fun `a table that does not exist is an integrity failure`() {
        val failure: Throwable? = commit("missing", table = "verses", metaKey = "rows", rowCountTable = "nothing here")
        assertTrue(failure is ResourceIntegrityException, "expected ResourceIntegrityException, was $failure")
    }
}
