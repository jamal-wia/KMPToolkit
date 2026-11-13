package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.files.Paths
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * B round 10, F-1. The documented promise (05-platform-notes, 04-api-reference): an archive that cannot be laid
 * out on the file system as delivered, "a name or path longer than the volume allows" among the reasons, fails the
 * commit with [ResourceIntegrityException], its temp file is deleted, and nothing of the offending entry is created.
 *
 * Written once, run against both shipped storages. No test here builds a path of 1,024 to 4,095 bytes on the host
 * (the JDK `mkdirs` of such a path takes minutes: the first version of this round's budget test ran 6 min 45 s):
 * the accepted paths stay below 900 bytes and the refused ones are above both platform limits (or, on iOS, above 1,023).
 */
abstract class ZipEntryPathLimitRound10Contract {

    protected abstract fun newStorage(): DownloaderStorage
    protected abstract fun writeFile(path: String, bytes: ByteArray)
    protected abstract fun readFile(path: String): ByteArray?
    protected abstract fun pathExists(path: String): Boolean
    protected abstract fun deleteTestDirectory()

    /** The length in bytes of an entry name every platform refuses; above the platform's own limit, never between 1,024 and 4,095 on a host that walks it. */
    protected open val refusedEntryBytes: Int = 4_200

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

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, entries: List<ZipEntryRound6>) {
        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), ZipBytesRound6.build(entries).bytes)
        storage.markTempFileComplete(unit)
    }

    private fun marker(): ZipEntryRound6 = ZipEntryRound6("marker.txt", "marker\n")

    /** Commits an archive whose second entry is [name]; asserts a clean refusal and returns its message. */
    private suspend fun assertRefused(id: String, name: String, firstSegment: String): String {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit(id)
        stage(storage, unit, listOf(marker(), ZipEntryRound6(name, "payload\n")))
        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()

        assertTrue(failure is ResourceIntegrityException, "expected ResourceIntegrityException, was $failure")
        assertEquals(TempFileState.None, storage.tempFileState(unit), "the worthless temp file must be deleted")
        val base: String = Paths.parentOf(Paths.parentOf(storage.getResourcePath(unit))!!)!!
        val staging: String = TestPaths.join(base, "tmp/staging-$id")
        assertFalse(pathExists(TestPaths.join(staging, firstSegment)), "nothing of the entry may be left in the staging directory")
        assertFalse(pathExists(TestPaths.join(storage.getResourcePath(unit), firstSegment)), "nothing of the entry may be at the target")
        assertFalse(pathExists(staging), "no staging directory may stay behind")
        assertFalse(storage.isResourceAvailable(unit), "a rejected archive must not be available")
        return failure.message.orEmpty()
    }

    @Test
    fun `an entry of thousands of path segments fails the commit as an integrity failure and creates nothing`() = runTest {
        val segmentCount: Int = refusedEntryBytes / 2
        val name: String = "a/".repeat(segmentCount) + "f.bin"

        assertRefused("deepsegments", name, "a")
    }

    @Test
    fun `an entry path of twenty segments of two hundred and fifty bytes is refused with a message about its length`() = runTest {
        val first: String = "x".repeat(250)
        val name: String = List(20) { first }.joinToString("/")
        assertEquals(5_019, name.length)

        val message: String = assertRefused("longsegments", name, first)

        assertTrue("longer than" in message, "the message must say why: $message")
        assertFalse(first in message, "the message must not carry the whole entry name: ${message.length} characters")
    }

    @Test
    fun `a path far below the limit with a deep entry name is still extracted`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("deepok")
        val name: String = List(5) { "d".repeat(99) }.joinToString("/") + "/f.txt"
        assertTrue(name.length < 900)
        stage(storage, unit, listOf(marker(), ZipEntryRound6(name, "payload\n")))

        storage.commitResource(unit)

        val bytes: ByteArray? = readFile(TestPaths.join(storage.getResourcePath(unit), name))
        assertNotNull(bytes, "the deep entry must be extracted")
        assertEquals("payload\n", bytes.decodeToString())
    }
}
