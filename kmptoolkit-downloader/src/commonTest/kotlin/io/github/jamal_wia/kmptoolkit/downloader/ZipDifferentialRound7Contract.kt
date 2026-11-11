package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round 7 differential contract, second pass: archives whose entries collide in ways that depend on what
 * the file system compares (case, Unicode normalization, NUL, name length, entry type), and a marker that
 * the archive did not produce as a file. The promise they are derived from (ResourceFormat.ZipArchive KDoc,
 * 04-api-reference): every entry is extracted or the commit fails with [ResourceIntegrityException] and
 * leaves nothing behind; the availability marker "is a file the extraction is known to produce".
 *
 * A commit may either succeed with every entry readable under its own name and content, or fail cleanly:
 * what it must never do is succeed with an entry missing, merged into another, or renamed.
 */
abstract class ZipDifferentialRound7Contract {

    protected abstract fun newStorage(): DownloaderStorage
    protected abstract fun writeFile(path: String, bytes: ByteArray)
    protected abstract fun readFile(path: String): ByteArray?
    protected abstract fun pathExists(path: String): Boolean
    protected abstract fun listNames(path: String): List<String>
    protected abstract fun deleteTestDirectory()

    /**
     * True when the volume the test runs on is not the volume the platform runs on: Robolectric runs the
     * Android storage on the developer's (case- and normalization-insensitive) volume, where a real device
     * has a case-sensitive one, so the tests about folded names say nothing about Android there.
     */
    protected open val hostVolumeIsNotTheTarget: Boolean = false

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
    fun `a directory named like the marker does not satisfy the availability marker`() = runTest {
        val (storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?) = commitOutcome(
            "markerdir",
            listOf(
                ZipEntryRound6("marker.txt/".encodeToByteArray(), ByteArray(0)),
                ZipEntryRound6("other.txt", "other\n"),
            ),
        )

        assertRejectedCleanly(storage, unit, failure, "an archive whose 'marker.txt' is a directory")
    }

    @Test
    fun `a marker spelled in other capitals is not the marker`() = runTest {
        if (hostVolumeIsNotTheTarget) return@runTest
        val (storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?) = commitOutcome(
            "markercase",
            listOf(ZipEntryRound6("MARKER.TXT", "marker\n"), ZipEntryRound6("other.txt", "o\n")),
        )

        assertRejectedCleanly(storage, unit, failure, "an archive that only has MARKER.TXT")
    }

    @Test
    fun `a directory entry and a file entry of one name never both succeed silently`() = runTest {
        val (storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?) = commitOutcome(
            "dirthenfile",
            listOf(
                marker(),
                ZipEntryRound6("a/".encodeToByteArray(), ByteArray(0)),
                ZipEntryRound6("a", "file\n"),
            ),
        )

        assertRejectedCleanly(storage, unit, failure, "a directory entry then a file of the same name")
    }

    @Test
    fun `a file entry and a child under that name fail the commit`() = runTest {
        val (storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?) = commitOutcome(
            "filethenchild",
            listOf(marker(), ZipEntryRound6("a", "file\n"), ZipEntryRound6("a/b.txt", "child\n")),
        )

        assertRejectedCleanly(storage, unit, failure, "a file entry then an entry below it")
    }

    @Test
    fun `entry names differing only in case are both extracted or the commit fails`() = runTest {
        if (hostVolumeIsNotTheTarget) return@runTest
        val (storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?) = commitOutcome(
            "twocases",
            listOf(marker(), ZipEntryRound6("Data.txt", "upper\n"), ZipEntryRound6("data.txt", "lower\n")),
        )

        if (failure != null) {
            assertRejectedCleanly(storage, unit, failure, "names that differ only in case")
        } else {
            assertEquals("upper\n", text(storage, unit, "Data.txt"), "Data.txt lost its own content")
            assertEquals("lower\n", text(storage, unit, "data.txt"), "data.txt lost its own content")
        }
    }

    @Test
    fun `entry names differing only in Unicode normalization are both extracted or the commit fails`() = runTest {
        if (hostVolumeIsNotTheTarget) return@runTest
        val composed = "café.txt"
        val decomposed = "café.txt"
        val (storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?) = commitOutcome(
            "twoforms",
            listOf(marker(), ZipEntryRound6(composed, "composed\n"), ZipEntryRound6(decomposed, "decomposed\n")),
        )

        if (failure != null) {
            assertRejectedCleanly(storage, unit, failure, "names that differ only in normalization")
        } else {
            val names: List<String> = listNames(storage.getResourcePath(unit))
            assertTrue(names.contains(composed) && names.contains(decomposed), "an entry was merged into the other: $names")
        }
    }

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
    fun `a name component of 300 bytes fails the commit cleanly or extracts under its whole name`() = runTest {
        val long: String = "n".repeat(300) + ".txt"
        val (storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?) = commitOutcome(
            "longname",
            listOf(marker(), ZipEntryRound6(long, "long\n")),
        )

        if (failure != null) {
            assertRejectedCleanly(storage, unit, failure, "a 300-byte name component")
        } else {
            assertEquals("long\n", text(storage, unit, long))
        }
    }

    @Test
    fun `a path of six hundred nested directories fails cleanly or extracts whole`() = runTest {
        if (hostVolumeIsNotTheTarget) return@runTest
        val deep: String = "d/".repeat(600) + "leaf.txt"
        val (storage: DownloaderStorage, unit: DownloadUnit, failure: Throwable?) = commitOutcome(
            "deep",
            listOf(marker(), ZipEntryRound6(deep, "leaf\n")),
        )

        if (failure != null) {
            assertRejectedCleanly(storage, unit, failure, "a path longer than a path limit")
        } else {
            assertEquals("leaf\n", text(storage, unit, deep))
        }
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
