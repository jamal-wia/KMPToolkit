package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round 6 API-level matrix: every other test of this module pins SDK 35, but the storage touches `SQLiteDatabase`
 * and the file APIs of each release. Each test here runs once per level listed, besides the default 35.
 *
 * Why not every level from the minSdk (24): below 26 the storage links files with `android.system.Os.link`, a native
 * call that Robolectric turns into a silent no-op (measured: at SDK 24 `renameNoReplace` returns true, creates nothing and
 * deletes the source), so that branch cannot be exercised on the host at all. And one JVM that loads twelve SDK images
 * runs out of heap (OutOfMemoryError while loading the twelfth), which would break every later test class.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 29, 33])
class AndroidApiMatrixRound6Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "matrix_round6")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class Unit6(
        override val id: String,
        override val format: ResourceFormat = ResourceFormat.Opaque,
        override val sha256: Sha256? = null,
    ) : DownloadUnit {
        override val apiPath: String = "/m/$id"
        override val relativePath: String = "m/$id"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@Unit6)
        }

        override fun toString(): String = "Unit6($id)"
    }

    private fun storage(): DownloaderStorage = createDownloaderStorage(context, config)

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray, expected: Sha256? = null) {
        storage.beginTempFile(unit, expected)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
    }

    @Test
    fun `a transfer with a recorded hash commits and a wrong hash is an integrity failure`() = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = storage()
            val good = Unit6("hash-ok")
            stage(storage, good, "abc".encodeToByteArray(), Sha256.parse("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
            storage.commitResource(good)
            assertTrue(storage.isResourceAvailable(good))
            assertEquals(3L, storage.getResourceSize(good))
            assertFalse(File(root, "tmp/expect/hash-ok.sha256").exists(), "the record goes with the commit")

            val bad = Unit6("hash-bad")
            stage(storage, bad, "abd".encodeToByteArray(), Sha256.parse("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
            assertFailsWith<ResourceIntegrityException> { storage.commitResource(bad) }
            assertEquals(TempFileState.None, storage.tempFileState(bad))
        }
    }

    @Test
    fun `renameNoReplace moves onto a free name and refuses an existing one without touching either`() {
        root.mkdirs()
        val from = File(root, "from").apply { writeText("source") }
        val taken = File(root, "taken").apply { writeText("keep") }
        val free = File(root, "free")

        assertFalse(renameNoReplace(from.path, taken.path))
        assertEquals("source", from.readText())
        assertEquals("keep", taken.readText())

        assertTrue(renameNoReplace(from.path, free.path))
        assertFalse(from.exists())
        assertEquals("source", free.readText())

        assertFalse(renameNoReplace(File(root, "missing").path, File(root, "never").path))
        assertFalse(File(root, "never").exists())
    }

    @Test
    fun `an archive written by java util zip with nested root directory entries and a comment commits`() = runBlocking {
        withTimeout(30_000) {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { out: ZipOutputStream ->
                out.setComment("PK\u0003\u0004 comment")
                out.putNextEntry(ZipEntry("pack/")); out.closeEntry()
                out.putNextEntry(ZipEntry("pack/marker.txt")); out.write("m\n".encodeToByteArray()); out.closeEntry()
                out.putNextEntry(ZipEntry("pack/sub/")); out.closeEntry()
                out.putNextEntry(ZipEntry("pack/sub/x.bin")); out.write(ByteArray(200_000) { (it % 251).toByte() }); out.closeEntry()
            }
            val storage: DownloaderStorage = storage()
            val unit = Unit6("zip", ResourceFormat.ZipArchive("marker.txt"))
            stage(storage, unit, bytes.toByteArray())

            storage.commitResource(unit)

            assertEquals("m\n", File(storage.getResourcePath(unit), "marker.txt").readText())
            assertEquals(200_000L, File(storage.getResourcePath(unit), "sub/x.bin").length())
            assertEquals(TempFileState.None, storage.tempFileState(unit))
        }
    }

    private fun database(rows: Int, withMeta: Boolean, declared: Int = rows): File {
        val file = File(context.cacheDir, "round6-${System.nanoTime()}.db")
        val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, payload TEXT)")
            db.beginTransaction()
            try {
                for (i: Int in 0 until rows) db.execSQL("INSERT INTO t (payload) VALUES ('${"x".repeat(180)}$i')")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (withMeta) {
                db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
                db.execSQL("INSERT INTO meta VALUES ('rows', '$declared')")
            }
        } finally {
            db.close()
        }
        return file
    }

    @Test
    fun `a real database commits and a row count that disagrees with the meta table is an integrity failure`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = storage()
            val format = ResourceFormat.SqliteDatabase("t", "rows")
            val good = Unit6("db-ok", format)
            stage(storage, good, database(rows = 50, withMeta = true).readBytes())
            storage.commitResource(good)
            assertTrue(storage.isResourceAvailable(good))

            val off = Unit6("db-off", format)
            stage(storage, off, database(rows = 50, withMeta = true, declared = 51).readBytes())
            assertFailsWith<ResourceIntegrityException> { storage.commitResource(off) }
            assertFalse(storage.isResourceAvailable(off))
            assertEquals(TempFileState.None, storage.tempFileState(off))
        }
    }

    @Test
    fun `a database cut after its first pages is not committed`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = storage()
            val whole: ByteArray = database(rows = 3000, withMeta = false).readBytes()
            assertTrue(whole.size > 200_000, "the fixture must span many pages, was ${whole.size}")
            val problems: ArrayList<String> = ArrayList()
            for (keep: Int in listOf(4096, 8192, whole.size / 2, whole.size - 4096)) {
                val unit = Unit6("db-cut$keep", ResourceFormat.SqliteDatabase())
                stage(storage, unit, whole.copyOf(keep))
                val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
                if (failure !is ResourceIntegrityException) problems += "kept $keep of ${whole.size} bytes: $failure"
            }
            assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        }
    }
}
