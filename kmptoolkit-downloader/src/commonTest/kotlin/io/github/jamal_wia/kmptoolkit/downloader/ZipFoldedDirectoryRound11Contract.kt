package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.files.Paths
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * B round 11, Q2 (mutant A4 of the report: the layout check removed for DIRECTORY entries survived every earlier test).
 * Contract: 05-platform-notes and 04-api-reference: two entries the volume would store as one (names that differ only
 * in case or Unicode normalization) are never merged silently: the commit fails with [ResourceIntegrityException], and on a
 * volume that keeps them apart both are extracted. Here at least one of the two entries is a directory entry.
 * The volume is probed (a file written under one spelling, asked for under the other), so the same test holds on a
 * case sensitive or normalization sensitive volume and on APFS.
 */
abstract class ZipFoldedDirectoryRound11Contract {

    protected abstract fun newStorage(): DownloaderStorage
    protected abstract fun writeFile(path: String, bytes: ByteArray)
    protected abstract fun readFile(path: String): ByteArray?
    protected abstract fun pathExists(path: String): Boolean
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

    /** True when the volume under the storage's base directory treats [first] and [second] as one name. */
    private fun volumeFolds(storage: DownloaderStorage, unit: DownloadUnit, first: String, second: String): Boolean {
        val probeDirectory: String = TestPaths.join(Paths.parentOf(storage.getResourcePath(unit))!!, "fold-probe")
        writeFile(TestPaths.join(probeDirectory, first), ByteArray(1))
        return pathExists(TestPaths.join(probeDirectory, second))
    }

    private suspend fun commitEntries(id: String, first: String, second: String, entries: List<ZipEntryRound6>, extractedPath: String) {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit(id)
        val folds: Boolean = volumeFolds(storage, unit, first, second)
        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), ZipBytesRound6.build(entries).bytes)
        storage.markTempFileComplete(unit)

        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()

        if (folds) {
            assertTrue(failure is ResourceIntegrityException, "the volume stores '$first' and '$second' as one name, expected ResourceIntegrityException, was $failure")
            assertEquals(TempFileState.None, storage.tempFileState(unit))
            assertFalse(storage.isResourceAvailable(unit))
        } else {
            assertEquals(null, failure, "a volume that keeps the names apart must extract both")
            assertTrue(readFile(TestPaths.join(storage.getResourcePath(unit), extractedPath)) != null, "'$extractedPath' is missing")
        }
    }

    private fun marker(): ZipEntryRound6 = ZipEntryRound6("marker.txt", "m")

    @Test
    fun `a directory entry that differs only in case from the directory of a file entry is never merged silently`() = runTest {
        commitEntries("dircasefile", "Dir", "dir", listOf(marker(), ZipEntryRound6("Dir/f.txt", "payload"), ZipEntryRound6("dir/", "")), "Dir/f.txt")
    }

    @Test
    fun `two directory entries that differ only in case are never merged silently`() = runTest {
        commitEntries("dircasedirs", "Dir", "dir", listOf(marker(), ZipEntryRound6("Dir/", ""), ZipEntryRound6("dir/", ""), ZipEntryRound6("Dir/f.txt", "payload")), "Dir/f.txt")
    }

    @Test
    fun `a directory entry that differs only in Unicode normalization from a file entry's directory is never merged silently`() = runTest {
        commitEntries("dirnormfile", "é", "é", listOf(marker(), ZipEntryRound6("é/f.txt", "payload"), ZipEntryRound6("é/", "")), "é/f.txt")
    }
}
