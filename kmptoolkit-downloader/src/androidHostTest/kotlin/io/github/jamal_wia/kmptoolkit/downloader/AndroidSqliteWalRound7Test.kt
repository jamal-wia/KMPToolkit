package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 7: a real database whose header says write-ahead logging. Verifying it opens it, which for a WAL
 * database can create `-wal` and `-shm` files next to it; the commit must leave the bytes the transfer
 * delivered untouched and no sidecar file anywhere under the base directory.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSqliteWalRound7Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "wal_round7")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class DbUnit(override val id: String, override val format: ResourceFormat) : DownloadUnit {
        override val apiPath: String = "/m/$id"
        override val relativePath: String = "m/$id.db"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@DbUnit)
        }
    }

    private fun walDatabase(): ByteArray {
        val file = File(context.cacheDir, "wal7-${System.nanoTime()}.db")
        val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, payload TEXT)")
            repeat(200) { db.execSQL("INSERT INTO t (payload) VALUES ('row$it')") }
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } finally {
            db.close()
        }
        val bytes: ByteArray = file.readBytes()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        file.delete()
        return bytes
    }

    private fun sidecars(): List<String> =
        root.walkTopDown().filter { it.isFile && (it.name.endsWith("-wal") || it.name.endsWith("-shm") || it.name.endsWith("-journal")) }
            .map { it.relativeTo(root).path }.toList()

    @Test
    fun `a write-ahead-log database commits byte for byte and leaves no sidecar file`() = runBlocking {
        withTimeout(60_000) {
            val bytes: ByteArray = walDatabase()
            assertEquals(2, bytes[18].toInt(), "the fixture must be in WAL mode (header byte 18)")
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val unit = DbUnit("wal", ResourceFormat.SqliteDatabase())
            storage.beginTempFile(unit, null)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
            storage.markTempFileComplete(unit)

            storage.commitResource(unit)

            assertTrue(storage.isResourceAvailable(unit))
            assertContentEquals(bytes, File(storage.getResourcePath(unit)).readBytes(), "verification changed the delivered file")
            assertEquals(emptyList(), sidecars(), "verification left write-ahead-log sidecar files behind")
        }
    }
}
