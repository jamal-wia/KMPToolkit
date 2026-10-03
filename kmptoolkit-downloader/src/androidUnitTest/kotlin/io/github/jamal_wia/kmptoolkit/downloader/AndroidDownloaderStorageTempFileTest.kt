package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * The temp-file half of the [DownloaderStorage] contract on Android: a partial transfer and a
 * complete one are different states, only a complete one commits, and a commit checks the bytes
 * against [DownloadUnit.sha256] before anything reaches the final path.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderStorageTempFileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "temp_file_test")
    private val storage: DownloaderStorage = createDownloaderStorage(context, config)
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `no temp file and an empty one are both None`() {
        val unit = TestUnit(id = "model")
        assertEquals(TempFileState.None, storage.tempFileState(unit))

        writePartial(unit, ByteArray(0))

        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `bytes written to the temp path are Partial however many there are`() {
        val unit = TestUnit(id = "model")

        writePartial(unit, ByteArray(4096))

        assertEquals(TempFileState.Partial, storage.tempFileState(unit))
        assertEquals(4096L, storage.getTempFileSize(unit))
    }

    @Test
    fun `marking a partial file complete makes it Complete and survives a new storage instance`() {
        val unit = TestUnit(id = "model")
        writePartial(unit, ByteArray(10))

        storage.markTempFileComplete(unit)

        assertEquals(TempFileState.Complete, storage.tempFileState(unit))
        // What process death leaves behind: a fresh instance must read the same state from disk.
        assertEquals(TempFileState.Complete, createDownloaderStorage(context, config).tempFileState(unit))
    }

    @Test
    fun `marking twice is harmless and marking nothing throws`() {
        val unit = TestUnit(id = "model")
        assertFailsWith<IllegalStateException> { storage.markTempFileComplete(unit) }

        writePartial(unit, ByteArray(10))
        storage.markTempFileComplete(unit)
        storage.markTempFileComplete(unit)

        assertEquals(TempFileState.Complete, storage.tempFileState(unit))
    }

    @Test
    fun `a partial temp file is never committed`() = runTest {
        val unit = TestUnit(id = "model")
        writePartial(unit, ByteArray(10))

        assertFailsWith<IllegalStateException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.Partial, storage.tempFileState(unit), "the partial bytes stay for a resume")
    }

    @Test
    fun `deleteTempFile removes a complete file as well as a partial one`() {
        val unit = TestUnit(id = "model")
        writePartial(unit, ByteArray(10))
        storage.markTempFileComplete(unit)
        writePartial(unit, ByteArray(5))

        storage.deleteTempFile(unit)

        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(0L, storage.getTempFileSize(unit))
    }

    @Test
    fun `a complete file whose SHA-256 matches is committed`() = runTest {
        val unit = TestUnit(id = "model", sha256 = ABC_SHA256.uppercase())
        writePartial(unit, "abc".toByteArray())
        storage.markTempFileComplete(unit)

        storage.commitResource(unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals("abc", File(storage.getResourcePath(unit)).readText())
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `a complete file whose SHA-256 differs is deleted and nothing is committed`() = runTest {
        val unit = TestUnit(id = "model", sha256 = ABC_SHA256)
        writePartial(unit, "abd".toByteArray())
        storage.markTempFileComplete(unit)

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertFalse(File(storage.getResourcePath(unit)).exists())
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `a stated hash that is not 64 hex digits is a host error, not an integrity failure`() = runTest {
        val unit = TestUnit(id = "model", sha256 = "not-a-hash")
        writePartial(unit, "abc".toByteArray())
        storage.markTempFileComplete(unit)

        assertFailsWith<IllegalArgumentException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
    }

    @Test
    fun `bytes that are not a database fail as an integrity failure`() = runTest {
        val unit = TestUnit(id = "quran-db", format = ResourceFormat.SqliteDatabase())
        writePartial(unit, ByteArray(2048) { 7 })
        storage.markTempFileComplete(unit)

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    private fun writePartial(unit: DownloadUnit, bytes: ByteArray) {
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    private class TestUnit(
        override val id: String,
        override val sha256: String? = null,
        override val format: ResourceFormat = ResourceFormat.Opaque,
    ) : DownloadUnit {
        override val apiPath: String = "/resources/$id"
        override val relativePath: String = "resources/$id.bin"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@TestUnit)
        }
        override fun toString(): String = "TestUnit($id)"
    }

    private companion object {
        /** SHA-256 of the three ASCII bytes "abc", the FIPS 180-2 test vector. */
        const val ABC_SHA256: String = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
