package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.files.Paths
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * B round 12, Q4: re-attack of the r11 layout from resolved segments (Android `fold`, iOS segment walk), both platforms through
 * one table. Contract (04-api-reference, 05-platform-notes): names are resolved lexically on `/` only (`a/../inner.txt` is
 * `inner.txt`, `release..notes.txt` is an ordinary name, `../evil.txt` fails); every entry is extracted or the commit fails
 * with [ResourceIntegrityException]; a name over the segment or path limit is refused as one; the same entry name twice is
 * not a conflict (the later bytes win). A backslash and a drive letter are ordinary characters on a POSIX volume.
 * No test here builds a path of 1,024 to 4,095 bytes: the long cases are over 4,095 bytes (refused before the file system) or
 * a single segment of at most 256 bytes.
 */
abstract class ZipFoldRound12Contract {

    protected abstract fun newStorage(): DownloaderStorage
    protected abstract fun writeFile(path: String, bytes: ByteArray)
    protected abstract fun readFile(path: String): ByteArray?
    protected abstract fun pathExists(path: String): Boolean
    protected abstract fun isDirectory(path: String): Boolean
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

    private class Outcome(val storage: DownloaderStorage, val unit: DownloadUnit, val failure: Throwable?) {
        fun at(relative: String): String = TestPaths.join(storage.getResourcePath(unit), relative)
    }

    private suspend fun commit(id: String, entries: List<ZipEntryRound6>): Outcome {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit(id)
        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), ZipBytesRound6.build(listOf(ZipEntryRound6("marker.txt", "m")) + entries).bytes)
        storage.markTempFileComplete(unit)
        return Outcome(storage, unit, runCatching { storage.commitResource(unit) }.exceptionOrNull())
    }

    private fun stagingOf(o: Outcome): String {
        val base: String = Paths.parentOf(Paths.parentOf(o.storage.getResourcePath(o.unit))!!)!!
        return TestPaths.join(base, "tmp/staging-${o.unit.id}")
    }

    private fun assertRefused(o: Outcome, what: String) {
        assertTrue(o.failure is ResourceIntegrityException, "$what: expected ResourceIntegrityException, was ${o.failure}")
        assertEquals(TempFileState.None, o.storage.tempFileState(o.unit), "$what: the worthless temp file must be deleted")
        assertFalse(pathExists(stagingOf(o)), "$what: no staging directory may stay behind")
        assertFalse(o.storage.isResourceAvailable(o.unit), "$what: a rejected archive must not be available")
    }

    private fun assertExtracted(o: Outcome, what: String) {
        assertNull(o.failure, "$what: expected a clean commit, was ${o.failure}")
        assertTrue(o.storage.isResourceAvailable(o.unit), "$what: not available")
        assertEquals(TempFileState.None, o.storage.tempFileState(o.unit))
        assertFalse(pathExists(stagingOf(o)), "$what: staging left behind")
    }

    @Test
    fun `current directory and empty segments and inner climbs are resolved lexically`() = runTest {
        val o: Outcome = commit(
            "lexical",
            listOf(
                ZipEntryRound6("./a", "1"),
                ZipEntryRound6("b/./c", "2"),
                ZipEntryRound6("d/../e", "3"),
                ZipEntryRound6("f//g", "4"),
                ZipEntryRound6("h/i/../../j", "5"),
                ZipEntryRound6("release..notes.txt", "6"),
            ),
        )
        assertExtracted(o, "lexical names")
        assertEquals("1", readFile(o.at("a"))?.decodeToString())
        assertEquals("2", readFile(o.at("b/c"))?.decodeToString())
        assertEquals("3", readFile(o.at("e"))?.decodeToString())
        assertEquals("4", readFile(o.at("f/g"))?.decodeToString())
        assertEquals("5", readFile(o.at("j"))?.decodeToString())
        assertEquals("6", readFile(o.at("release..notes.txt"))?.decodeToString())
        // `d` was never created: a lexical climb is not a walk through a directory.
        assertFalse(pathExists(o.at("d")), "a/.. must not create a")
        assertFalse(pathExists(o.at("h")), "h/i/../.. must not create h")
    }

    @Test
    fun `directory entries with trailing slashes and dots become empty directories`() = runTest {
        val o: Outcome = commit(
            "dirs",
            listOf(
                ZipEntryRound6("d1/", ""),
                ZipEntryRound6("d2//", ""),
                ZipEntryRound6("d3/./", ""),
                ZipEntryRound6("x/../d4/", ""),
                ZipEntryRound6("./", ""),
                ZipEntryRound6("/", ""),
            ),
        )
        assertExtracted(o, "directory entries")
        for (name: String in listOf("d1", "d2", "d3", "d4")) assertTrue(isDirectory(o.at(name)), "$name must be a directory")
        assertFalse(pathExists(o.at("x")), "x/../ must not create x")
    }

    @Test
    fun `a backslash and a drive letter are ordinary characters of a name`() = runTest {
        val o: Outcome = commit(
            "backslash",
            listOf(
                ZipEntryRound6("x\\y.txt", "1"),
                ZipEntryRound6("C:/dr.txt", "2"),
                ZipEntryRound6("D:\\z.txt", "3"),
                ZipEntryRound6("..\\up.txt", "4"),
            ),
        )
        assertExtracted(o, "backslash and drive names")
        assertEquals("1", readFile(o.at("x\\y.txt"))?.decodeToString())
        assertEquals("2", readFile(o.at("C:/dr.txt"))?.decodeToString())
        assertEquals("3", readFile(o.at("D:\\z.txt"))?.decodeToString())
        assertEquals("4", readFile(o.at("..\\up.txt"))?.decodeToString())
    }

    @Test
    fun `a name that resolves to the extraction directory itself as a file is refused`() = runTest {
        for ((index: Int, name: String) in listOf("a/..", ".", "", "./.", "x/y/../..").withIndex()) {
            val o: Outcome = commit("selffile$index", listOf(ZipEntryRound6(name, "payload")))
            assertRefused(o, "file entry named '$name'")
        }
    }

    @Test
    fun `a segment of 255 bytes is laid out and one of 256 bytes is refused`() = runTest {
        val ok: Outcome = commit("seg255", listOf(ZipEntryRound6("x".repeat(255), "p")))
        assertExtracted(ok, "255-byte file name")
        assertEquals("p", readFile(ok.at("x".repeat(255)))?.decodeToString())
        val okDir: Outcome = commit("seg255dir", listOf(ZipEntryRound6("y".repeat(255) + "/f.txt", "p")))
        assertExtracted(okDir, "255-byte directory name")
        val tooLong: Outcome = commit("seg256", listOf(ZipEntryRound6("x".repeat(256), "p")))
        assertRefused(tooLong, "256-byte file name")
        val tooLongDir: Outcome = commit("seg256dir", listOf(ZipEntryRound6("y".repeat(256) + "/f.txt", "p")))
        assertRefused(tooLongDir, "256-byte directory name")
        val tooLongEmptyDir: Outcome = commit("seg256emptydir", listOf(ZipEntryRound6("z".repeat(256) + "/", "")))
        assertRefused(tooLongEmptyDir, "256-byte directory entry")
    }

    @Test
    fun `a segment of 127 two byte characters is laid out`() = runTest {
        // 254 bytes fit NAME_MAX of every volume; the 256-byte spelling is not tested because APFS counts characters, ext4 bytes.
        val ok: Outcome = commit("seg254b", listOf(ZipEntryRound6("\u00e9".repeat(127), "p")))
        assertExtracted(ok, "254-byte name of two-byte characters")
    }

    @Test
    fun `four thousand segments are refused before the file system and leave nothing`() = runTest {
        assertRefused(commit("segs4000", listOf(ZipEntryRound6("a/".repeat(4_000) + "f", "p"))), "4000 segments")
        assertRefused(commit("segs4000dir", listOf(ZipEntryRound6("a/".repeat(4_000), ""))), "4000 directory segments")
    }

    @Test
    fun `the same name reached by two spellings is one entry and the later bytes win`() = runTest {
        val o: Outcome = commit("dupspell", listOf(ZipEntryRound6("a/../b", "first"), ZipEntryRound6("b", "second"), ZipEntryRound6("./b", "third")))
        assertExtracted(o, "duplicate after resolution")
        assertEquals("third", readFile(o.at("b"))?.decodeToString())
    }

    @Test
    fun `a file and a directory of one resolved name are refused in either order`() = runTest {
        assertRefused(commit("fd1", listOf(ZipEntryRound6("a", "p"), ZipEntryRound6("x/../a/b", "q"))), "file then deeper file")
        assertRefused(commit("fd2", listOf(ZipEntryRound6("x/../a/b", "q"), ZipEntryRound6("a", "p"))), "deeper file then file")
        assertRefused(commit("fd3", listOf(ZipEntryRound6("a", "p"), ZipEntryRound6("y/../a/", ""))), "file then directory entry")
        assertRefused(commit("fd4", listOf(ZipEntryRound6("a/", ""), ZipEntryRound6("./a", "p"))), "directory entry then file")
    }

    @Test
    fun `a climbing name is refused wherever the climb sits`() = runTest {
        val names: List<String> = listOf("../x", "a/../../x", "./../x", "a/b/../../../x", "../", "a/../../", "..", "a//..//..//x")
        for ((index: Int, name: String) in names.withIndex()) {
            assertRefused(commit("climb$index", listOf(ZipEntryRound6(name, "p"))), "climbing name '$name'")
        }
    }

    @Test
    fun `a NUL in a name is refused wherever it sits`() = runTest {
        for ((index: Int, name: String) in listOf("a\u0000b", "\u0000", "d\u0000/f", "x/\u0000").withIndex()) {
            assertRefused(commit("nul$index", listOf(ZipEntryRound6(name, "p"))), "NUL name #$index")
        }
    }

    @Test
    fun `the marker itself spelled with a dot segment still marks the archive`() = runTest {
        val o: Outcome = commit("markerdot", listOf(ZipEntryRound6("./marker.txt", "again")))
        assertExtracted(o, "marker spelled with a dot")
        assertNotNull(readFile(o.at("marker.txt")))
        assertEquals("again", readFile(o.at("marker.txt"))?.decodeToString())
    }

    @Test
    fun `a directory entry that carries data is a directory and its data is checked like any entry`() = runTest {
        val ok: Outcome = commit("dirdata", listOf(ZipEntryRound6("dd/", "ignored bytes")))
        assertExtracted(ok, "directory entry with data")
        assertTrue(isDirectory(ok.at("dd")), "dd must be a directory")
        val broken: Outcome = commit(
            "dirdatabadcrc",
            listOf(ZipEntryRound6("de/".encodeToByteArray(), "ignored bytes".encodeToByteArray(), headerCrc = 0x1234L)),
        )
        assertRefused(broken, "directory entry with a wrong CRC-32")
    }
}
