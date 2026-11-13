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
 * B round 11, Q2 (D-157 addendum, SHOULD-1 of reviews/b-r10-fix-opus.md). The documented sentence (04-api-reference,
 * 05-platform-notes): an archive that cannot be laid out as delivered, an unsafe entry name or a path longer than the
 * volume allows among the reasons, fails the commit with [ResourceIntegrityException]; the temp file is deleted and
 * nothing of the entry stays behind. Here the RAW entry name is longer than any PATH_MAX (above 4,096 bytes, so the JDK
 * and the kernel refuse the string itself before any file system walk) while the name RESOLVED lexically is either
 * climbing (unsafe) or short (laid out by iOS from its segments).
 *
 * No test here builds a path of 1,024 to 4,095 bytes on the file system, and none creates a deep directory: a refused
 * name fails before the file system, and the accepted controls stay below 900 bytes.
 */
abstract class ZipRawNameRound11Contract {

    protected abstract fun newStorage(): DownloaderStorage
    protected abstract fun writeFile(path: String, bytes: ByteArray)
    protected abstract fun readFile(path: String): ByteArray?
    protected abstract fun pathExists(path: String): Boolean
    protected abstract fun deleteTestDirectory()

    /**
     * Called first by every test with a raw name over 4,096 bytes. The Android storage resolves the RAW name with
     * `File.canonicalFile`, and an OpenJDK host refuses such a string with a plain IOException ("File name too long")
     * where the JetBrains runtime the build normally tests with does not (B round 11 suspect r11-S1, in the report).
     * The Android subclass skips these tests on such a host, so the suite does not depend on the vendor of the JDK.
     */
    protected open fun assumeLongRawNamesCanBeResolved() {}

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

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, name: String) {
        storage.beginTempFile(unit, null)
        val entries: List<ZipEntryRound6> = listOf(ZipEntryRound6("marker.txt", "marker\n"), ZipEntryRound6(name, "payload\n"))
        writeFile(storage.getTempFilePath(unit), ZipBytesRound6.build(entries).bytes)
        storage.markTempFileComplete(unit)
    }

    private fun stagingOf(storage: DownloaderStorage, unit: DownloadUnit): String {
        val base: String = Paths.parentOf(Paths.parentOf(storage.getResourcePath(unit))!!)!!
        return TestPaths.join(base, "tmp/staging-${unit.id}")
    }

    /** The commit must fail as [ResourceIntegrityException] and leave nothing: no temp file, no staging, no resource. */
    private suspend fun assertCleanRefusal(id: String, name: String) {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit(id)
        stage(storage, unit, name)
        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()

        assertTrue(
            failure is ResourceIntegrityException,
            "expected ResourceIntegrityException for a ${name.length}-character entry name, was $failure",
        )
        assertEquals(TempFileState.None, storage.tempFileState(unit), "the worthless temp file must be deleted")
        assertFalse(pathExists(stagingOf(storage, unit)), "no staging directory may stay behind")
        assertFalse(storage.isResourceAvailable(unit), "a rejected archive must not be available")
    }

    @Test
    fun `a climbing entry name of more than four thousand bytes fails the commit as an integrity failure`() = runTest {
        assumeLongRawNamesCanBeResolved()
        assertCleanRefusal("climbinglong", "../" + "x".repeat(4_200))
    }

    @Test
    fun `a name that climbs deep below the top and out again is a traversal whatever its length`() = runTest {
        assumeLongRawNamesCanBeResolved()
        assertCleanRefusal("climbingdeep", "a/".repeat(2_100) + "../".repeat(2_200) + "f")
    }

    @Test
    fun `a long name that resolves to a short path is extracted whole or fails as an integrity failure`() = runTest {
        assumeLongRawNamesCanBeResolved()
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("resolvesshort")
        val name: String = "a/../".repeat(2_000) + "f.txt"
        stage(storage, unit, name)

        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()

        if (failure == null) {
            val bytes: ByteArray? = readFile(TestPaths.join(storage.getResourcePath(unit), "f.txt"))
            assertNotNull(bytes, "an archive that commits must hold the entry under its resolved name")
            assertEquals("payload\n", bytes.decodeToString())
        } else {
            assertTrue(failure is ResourceIntegrityException, "a refusal must be a ResourceIntegrityException, was $failure")
            assertEquals(TempFileState.None, storage.tempFileState(unit))
            assertFalse(pathExists(stagingOf(storage, unit)))
        }
    }

    @Test
    fun `a name padded with current directory segments beyond the path limit is extracted whole or fails as an integrity failure`() = runTest {
        assumeLongRawNamesCanBeResolved()
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("dotpadded")
        val name: String = "./".repeat(2_100) + "f.txt"
        stage(storage, unit, name)

        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()

        if (failure == null) {
            assertEquals("payload\n", readFile(TestPaths.join(storage.getResourcePath(unit), "f.txt"))?.decodeToString())
        } else {
            assertTrue(failure is ResourceIntegrityException, "a refusal must be a ResourceIntegrityException, was $failure")
            assertEquals(TempFileState.None, storage.tempFileState(unit))
            assertFalse(pathExists(stagingOf(storage, unit)))
        }
    }

    @Test
    fun `a padded name below every path limit extracts under its resolved name`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("paddedshort")
        val name: String = "./".repeat(100) + "a/../b/" + "./".repeat(100) + "f.txt"
        assertTrue(name.length < 900)
        stage(storage, unit, name)

        storage.commitResource(unit)

        assertEquals("payload\n", readFile(TestPaths.join(storage.getResourcePath(unit), "b/f.txt"))?.decodeToString())
    }
}
