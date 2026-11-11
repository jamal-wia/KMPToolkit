package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round 7 differential contract, second pass: archive layouts a real writer or a hostile server can
 * produce that the first pass did not lay out, committed through both shipped storages with one expected
 * outcome. Derived from the documented promise (ResourceFormat.ZipArchive KDoc, 04-api-reference): every
 * entry is extracted or the commit fails with [ResourceIntegrityException] and leaves nothing behind.
 */
abstract class ZipDifferentialRound7Contract {

    protected abstract fun newStorage(): DownloaderStorage
    protected abstract fun writeFile(path: String, bytes: ByteArray)
    protected abstract fun readFile(path: String): ByteArray?
    protected abstract fun pathExists(path: String): Boolean
    protected abstract fun listNames(path: String): List<String>
    protected abstract fun deleteTestDirectory()

    @AfterTest
    fun cleanUp() {
        deleteTestDirectory()
        LegacyTempMigrations.resetForTests()
    }

    private class ArchiveUnit(override val id: String) : DownloadUnit {
        override val apiPath: String = "/archives/$id"
        override val relativePath: String = "archives/$id"
        override val format: ResourceFormat = ResourceFormat.ZipArchive("marker.txt")
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@ArchiveUnit)
        }

        override fun toString(): String = "ArchiveUnit($id)"
    }

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, zip: ByteArray) {
        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), zip)
        storage.markTempFileComplete(unit)
    }

    private fun baseOf(storage: DownloaderStorage, unit: DownloadUnit): String =
        storage.getResourcePath(unit).substringBeforeLast('/').substringBeforeLast('/')

    private fun marker(): ZipEntryRound6 = ZipEntryRound6("marker.txt", "marker\n")

    private suspend fun commitOutcome(id: String, entries: List<ZipEntryRound6>): Triple<DownloaderStorage, DownloadUnit, Throwable?> {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit(id)
        stage(storage, unit, ZipBytesRound6.build(entries).bytes)
        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
        return Triple(storage, unit, failure)
    }

    private fun assertRejectedCleanly(storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?, what: String) {
        assertEquals(TempFileState.None, storage.tempFileState(unit), "$what: the worthless temp file must be deleted (failure: $failure)")
        assertFalse(pathExists("${baseOf(storage, unit)}/tmp/staging-${unit.id}"), "$what: staging left behind (failure: $failure)")
        assertFalse(storage.isResourceAvailable(unit), "$what: a rejected archive must not be available (failure: $failure)")
        assertTrue(failure is ResourceIntegrityException, "$what: expected ResourceIntegrityException, was $failure")
    }

    private fun text(storage: DownloaderStorage, unit: DownloadUnit, entry: String): String? =
        readFile(storage.getResourcePath(unit) + "/" + entry)?.decodeToString()

    @Test
    fun `a NUL inside an entry name fails the commit instead of cutting the name`() = runTest {
        val name: ByteArray = byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte())
        val (storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?) = commitOutcome(
            "nulname",
            listOf(marker(), ZipEntryRound6(name, "nul\n".encodeToByteArray())),
        )

        assertRejectedCleanly(storage, unit, failure, "an entry name holding a NUL")
        assertFalse(pathExists(storage.getResourcePath(unit)), "a cut name must not leave a half extracted tree")
    }

    @Test
    fun `a directory entry that carries data bytes is a directory and the rest of the archive is read`() = runTest {
        val entries: List<ZipEntryRound6> = listOf(
            ZipEntryRound6("d/".encodeToByteArray(), "junk!".encodeToByteArray()),
            marker(),
            ZipEntryRound6("d/x.txt", "x\n"),
        )
        val (storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?) = commitOutcome("dirdata", entries)

        if (failure != null) {
            assertRejectedCleanly(storage, unit, failure, "a directory entry with data")
        } else {
            assertEquals("x\n", text(storage, unit, "d/x.txt"))
            assertEquals("marker\n", text(storage, unit, "marker.txt"))
        }
    }

    @Test
    fun `five thousand entries commit with every entry in place`() = runTest {
        val entries: List<ZipEntryRound6> =
            listOf(marker()) + List(5000) { index: Int -> ZipEntryRound6("bulk/f$index.txt", "v$index\n") }
        val (storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?) = commitOutcome("bulk", entries)

        assertTrue(failure == null, "five thousand small entries failed with $failure")
        assertEquals(5000, listNames(storage.getResourcePath(unit) + "/bulk").size)
        assertEquals("v4999\n", text(storage, unit, "bulk/f4999.txt"))
        assertNotNull(text(storage, unit, "marker.txt"))
    }
}
