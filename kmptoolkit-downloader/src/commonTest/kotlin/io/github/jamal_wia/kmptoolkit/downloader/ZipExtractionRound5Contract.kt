package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Archive extraction is `java.util.zip` on Android and a hand-written extractor over zlib on iOS, so
 * the two must agree on every valid archive and on every broken one. Written once, run against both
 * shipped storages (the Android and iOS subclasses supply the file access).
 *
 * The documented promise (`ResourceFormat`): a half-written archive "exists and is worthless" and is
 * to be caught at commit, and a ZIP commit extracts every entry of the archive.
 */
@OptIn(ExperimentalEncodingApi::class)
abstract class ZipExtractionRound5Contract {
    // Archives ZIP_I .. ZIP_Q were generated offline by a script kept out of the repository (local headers, central
    // directory and end record written by hand; the deliberate defects are named by the tests that use them).


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

    private fun commit(storage: DownloaderStorage, unit: DownloadUnit, zip: ByteArray) {
        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), zip)
        storage.markTempFileComplete(unit)
    }

    private fun text(storage: DownloaderStorage, unit: DownloadUnit, entry: String): String? =
        readFile(storage.getResourcePath(unit) + "/" + entry)?.decodeToString()

    /** The commit failed as an archive that cannot be extracted as delivered: integrity failure, nothing left behind. */
    private suspend fun assertRejected(storage: DownloaderStorage, unit: DownloadUnit) {
        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }
        assertEquals(TempFileState.None, storage.tempFileState(unit), "the worthless temp file must be deleted")
        val base: String = TestPaths.parent(TestPaths.parent(storage.getResourcePath(unit))!!)!!
        assertFalse(pathExists(TestPaths.join(base, "tmp/staging-${unit.id}")), "no staging directory may stay behind")
        assertFalse(storage.isResourceAvailable(unit), "a rejected archive must not be available")
    }

    @Test
    fun `entries written with a data descriptor are all extracted`() = runTest {
        // The way a streaming writer (java.util.zip.ZipOutputStream) lays out DEFLATED entries: the
        // local header carries sizes of zero (bit 3), the real ones follow the data.
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("descriptor")
        commit(storage, unit, Base64.decode(ZIP_A))

        storage.commitResource(unit)

        assertEquals("marker\n", text(storage, unit, "marker.txt"))
        assertEquals("payload line\n".repeat(400), text(storage, unit, "dir/payload.txt"))
        assertEquals("tail\n", text(storage, unit, "dir/tail.txt"))
    }

    @Test
    fun `a file name that merely contains two dots is extracted`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("dots")
        commit(storage, unit, Base64.decode(ZIP_B))

        storage.commitResource(unit)

        assertEquals("notes\n", text(storage, unit, "release..notes.txt"))
    }

    @Test
    fun `an entry with a compression method the platform cannot read fails the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("method")
        commit(storage, unit, Base64.decode(ZIP_C))

        assertRejected(storage, unit)
    }

    @Test
    fun `an archive cut in the middle of an entry fails the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("truncated")
        commit(storage, unit, Base64.decode(ZIP_D))

        assertRejected(storage, unit)
    }

    @Test
    fun `names that are not ASCII are extracted under the names they have`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("unicode")
        commit(storage, unit, Base64.decode(ZIP_E))

        storage.commitResource(unit)

        assertEquals("cafe\n", text(storage, unit, "caf\u00e9.txt"))
        assertEquals("nihon\n", text(storage, unit, "\u65e5\u672c/\u8a9e.txt"))
    }

    @Test
    fun `a traversal entry fails the commit and nothing is written outside the staging tree`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("slip")
        commit(storage, unit, Base64.decode(ZIP_F))

        assertRejected(storage, unit)

        val base: String = TestPaths.parent(TestPaths.parent(storage.getResourcePath(unit))!!)!!
        assertFalse(pathExists(TestPaths.join(base, "tmp/evil.txt")), "an entry must never leave the extraction tree")
    }

    @Test
    fun `empty directory entries are extracted`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("emptydirs")
        commit(storage, unit, Base64.decode(ZIP_G))

        storage.commitResource(unit)

        assertTrue(pathExists(TestPaths.join(storage.getResourcePath(unit), "empty")))
        assertTrue(pathExists(TestPaths.join(storage.getResourcePath(unit), "deep/er")))
    }

    @Test
    fun `a replacing archive without the marker keeps the committed one`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("replace")
        commit(storage, unit, Base64.decode(ZIP_B))
        storage.commitResource(unit)
        assertTrue(storage.isResourceAvailable(unit))
        commit(storage, unit, Base64.decode(ZIP_H))

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

        assertTrue(storage.isResourceAvailable(unit), "the committed archive must survive")
        assertEquals("marker\n", text(storage, unit, "marker.txt"))
    }

    @Test
    fun `an archive unit whose id is not a file name commits and leaves no staging behind`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("pack/v1")
        commit(storage, unit, Base64.decode(ZIP_B))

        storage.commitResource(unit)

        assertEquals("marker\n", text(storage, unit, "marker.txt"))
        storage.deleteTempFile(unit)
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `a STORED entry with a data descriptor fails the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("storeddesc")
        commit(storage, unit, Base64.decode(ZIP_I))

        assertRejected(storage, unit)
    }

    @Test
    fun `a STORED entry with one data byte flipped fails the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("crcflip")
        commit(storage, unit, Base64.decode(ZIP_J))

        assertRejected(storage, unit)
    }

    @Test
    fun `a DEFLATED entry whose declared size is off by one fails the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("sizeoff")
        commit(storage, unit, Base64.decode(ZIP_K))

        assertRejected(storage, unit)
    }

    @Test
    fun `an archive cut inside the header of the second entry fails the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("cutheader")
        commit(storage, unit, Base64.decode(ZIP_L))

        assertRejected(storage, unit)
    }

    @Test
    fun `an archive cut inside a data descriptor fails the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("cutdesc")
        commit(storage, unit, Base64.decode(ZIP_M))

        assertRejected(storage, unit)
    }

    @Test
    fun `a name that climbs and comes back extracts under its normalized name`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("normalized")
        commit(storage, unit, Base64.decode(ZIP_N))

        storage.commitResource(unit)

        assertEquals("inner\n", text(storage, unit, "inner.txt"))
    }

    @Test
    fun `a name that climbs out of the extraction directory after coming back fails the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("climb")
        commit(storage, unit, Base64.decode(ZIP_O))

        assertRejected(storage, unit)
    }

    @Test
    fun `an entry flagged as encrypted fails the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("encrypted")
        commit(storage, unit, Base64.decode(ZIP_P))

        assertRejected(storage, unit)
    }

    @Test
    fun `a data descriptor with a wrong CRC fails the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("desccrc")
        commit(storage, unit, Base64.decode(ZIP_Q))

        assertRejected(storage, unit)
    }

    @Test
    fun `a replacing archive that is cut short keeps the committed one`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("replacecut")
        commit(storage, unit, Base64.decode(ZIP_B))
        storage.commitResource(unit)
        commit(storage, unit, Base64.decode(ZIP_D))

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

        assertTrue(storage.isResourceAvailable(unit), "the committed archive must survive")
        assertEquals("marker\n", text(storage, unit, "marker.txt"))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `an archive cut two bytes into the next local header fails the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("cut2")
        commit(storage, unit, Base64.decode(ZIP_R))

        assertRejected(storage, unit)
    }

    @Test
    fun `an archive whose comment ends with the local header signature commits`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("commentsig")
        commit(storage, unit, Base64.decode(ZIP_S))

        storage.commitResource(unit)

        assertEquals("marker\n", text(storage, unit, "marker.txt"))
    }

    private companion object {
    private val ZIP_A: String =
        "UEsDBBQACAAIAAAAIQAAAAAAAAAAAAAAAAAKAAAAbWFya2VyLnR4dMtNLMpOLeICAFBLBwioFlBoCQAAAAcAAABQSwMEFAAIAAgAAAAhAAAAAA" +
        "AAAAAAAAAAAA8AAABkaXIvcGF5bG9hZC50eHTtxzENACAMALAfFVhbAgfJAntxjwIctF8rbp4YPdeerURERERERERERET+eVBLBwgA+vmpLAAA" +
        "AFAUAABQSwMEFAAIAAgAAAAhAAAAAAAAAAAAAAAAAAwAAABkaXIvdGFpbC50eHQrSczM4QIAUEsHCG4ccScHAAAABQAAAFBLAQIUABQACAAIAA" +
        "AAIQCoFlBoCQAAAAcAAAAKAAAAAAAAAAAAAAAAAAAAAABtYXJrZXIudHh0UEsBAhQAFAAIAAgAAAAhAAD6+aksAAAAUBQAAA8AAAAAAAAAAAAA" +
        "AAAAQQAAAGRpci9wYXlsb2FkLnR4dFBLAQIUABQACAAIAAAAIQBuHHEnBwAAAAUAAAAMAAAAAAAAAAAAAAAAAKoAAABkaXIvdGFpbC50eHRQSw" +
        "UGAAAAAAMAAwCvAAAA6wAAAAAA"

    private val ZIP_B: String =
        "UEsDBBQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwMEFAAAAAAAAAAhAD7S2NYGAAAABgAAABIAAAByZWxlYX" +
        "NlLi5ub3Rlcy50eHRub3RlcwpQSwECFAAUAAAAAAAAACEAqBZQaAcAAAAHAAAACgAAAAAAAAAAAAAAAAAAAAAAbWFya2VyLnR4dFBLAQIUABQA" +
        "AAAAAAAAIQA+0tjWBgAAAAYAAAASAAAAAAAAAAAAAAAAAC8AAAByZWxlYXNlLi5ub3Rlcy50eHRQSwUGAAAAAAIAAgB4AAAAZQAAAAAA"

    private val ZIP_C: String =
        "UEsDBBQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwMEFAAAAA4AAAAhAPrGnYYsAQAALAEAAAcAAABiaWcuYm" +
        "luAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQID" +
        "AQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQ" +
        "IDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQID" +
        "AQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDAQIDUEsBAhQAFAAAAAAAAAAhAKgWUGgHAAAABwAAAA" +
        "oAAAAAAAAAAAAAAAAAAAAAAG1hcmtlci50eHRQSwECFAAUAAAADgAAACEA+sadhiwBAAAsAQAABwAAAAAAAAAAAAAAAAAvAAAAYmlnLmJpblBL" +
        "BQYAAAAAAgACAG0AAACAAQAAAAA="

    private val ZIP_D: String =
        "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"

    private val ZIP_E: String =
        "UEsDBBQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwMEFAAACAgAAAAhAIirLy8HAAAABQAAAAkAAABjYWbDqS" +
        "50eHRLTkxL5QIAUEsDBBQAAAgIAAAAIQBzJqyxCAAAAAYAAAAOAAAA5pel5pysL+iqni50eHTLy8zIz+MCAFBLAQIUABQAAAAAAAAAIQCoFlBo" +
        "BwAAAAcAAAAKAAAAAAAAAAAAAAAAAAAAAABtYXJrZXIudHh0UEsBAhQAFAAACAgAAAAhAIirLy8HAAAABQAAAAkAAAAAAAAAAAAAAAAALwAAAG" +
        "NhZsOpLnR4dFBLAQIUABQAAAgIAAAAIQBzJqyxCAAAAAYAAAAOAAAAAAAAAAAAAAAAAF0AAADml6XmnKwv6KqeLnR4dFBLBQYAAAAAAwADAKsA" +
        "AACRAAAAAAA="

    private val ZIP_F: String =
        "UEsDBBQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwMEFAAAAAAAAAAhAHrNP7cFAAAABQAAAAsAAAAuLi9ldm" +
        "lsLnR4dGV2aWwKUEsBAhQAFAAAAAAAAAAhAKgWUGgHAAAABwAAAAoAAAAAAAAAAAAAAAAAAAAAAG1hcmtlci50eHRQSwECFAAUAAAAAAAAACEA" +
        "es0/twUAAAAFAAAACwAAAAAAAAAAAAAAAAAvAAAALi4vZXZpbC50eHRQSwUGAAAAAAIAAgBxAAAAXQAAAAAA"

    private val ZIP_G: String =
        "UEsDBBQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwMEFAAAAAAAAAAhAAAAAAAAAAAAAAAAAAYAAABlbXB0eS" +
        "9QSwMEFAAAAAAAAAAhAAAAAAAAAAAAAAAAAAgAAABkZWVwL2VyL1BLAQIUABQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAAAAAAAAAAAAAAAAA" +
        "AABtYXJrZXIudHh0UEsBAhQAFAAAAAAAAAAhAAAAAAAAAAAAAAAAAAYAAAAAAAAAAAAAAAAALwAAAGVtcHR5L1BLAQIUABQAAAAAAAAAIQAAAA" +
        "AAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAFMAAABkZWVwL2VyL1BLBQYAAAAAAwADAKIAAAB5AAAAAAA="

    private val ZIP_H: String =
        "UEsDBBQAAAAAAAAAIQBufmAJBgAAAAYAAAAJAAAAb3RoZXIudHh0b3RoZXIKUEsBAhQAFAAAAAAAAAAhAG5+YAkGAAAABgAAAAkAAAAAAAAAAA" +
        "AAAAAAAAAAAG90aGVyLnR4dFBLBQYAAAAAAQABADcAAAAtAAAAAAA="

    private val ZIP_I: String =
        "UEsDBBQACAAAAAAAIQAAAAAAAAAAAAAAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwcIqBZQaAcAAAAHAAAAUEsBAhQAFAAIAAAAAAAhAKgWUG" +
        "gHAAAABwAAAAoAAAAAAAAAAAAAAAAAAAAAAG1hcmtlci50eHRQSwUGAAAAAAEAAQA4AAAAPwAAAAAA"

    private val ZIP_J: String =
        "UEsDBBQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwMEFAAAAAAAAAAhAIzODhBAAAAAQAAAAAgAAABkYXRhLm" +
        "JpbgABAgMEBQYHCAn1CwwNDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj9QSwECFAAUAAAAAAAAACEA" +
        "qBZQaAcAAAAHAAAACgAAAAAAAAAAAAAAAAAAAAAAbWFya2VyLnR4dFBLAQIUABQAAAAAAAAAIQCMzg4QQAAAAEAAAAAIAAAAAAAAAAAAAAAAAC" +
        "8AAABkYXRhLmJpblBLBQYAAAAAAgACAG4AAACVAAAAAAA="

    private val ZIP_K: String =
        "UEsDBBQAAAAIAAAAIQCoFlBoCQAAAAgAAAAKAAAAbWFya2VyLnR4dMtNLMpOLeICAFBLAQIUABQAAAAIAAAAIQCoFlBoCQAAAAgAAAAKAAAAAA" +
        "AAAAAAAAAAAAAAAABtYXJrZXIudHh0UEsFBgAAAAABAAEAOAAAADEAAAAAAA=="

    private val ZIP_L: String =
        "UEsDBBQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwMEFAAAAAAA"

    private val ZIP_M: String =
        "UEsDBBQACAAIAAAAIQAAAAAAAAAAAAAAAAAKAAAAbWFya2VyLnR4dMtNLMpOLeICAFBLBwioFg=="

    private val ZIP_N: String =
        "UEsDBBQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwMEFAAAAAAAAAAhAIxNAtAGAAAABgAAAA4AAABhLy4uL2" +
        "lubmVyLnR4dGlubmVyClBLAQIUABQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAAAAAAAAAAAAAAAAAAABtYXJrZXIudHh0UEsBAhQAFAAAAAAA" +
        "AAAhAIxNAtAGAAAABgAAAA4AAAAAAAAAAAAAAAAALwAAAGEvLi4vaW5uZXIudHh0UEsFBgAAAAACAAIAdAAAAGEAAAAAAA=="

    private val ZIP_O: String =
        "UEsDBBQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwMEFAAAAAAAAAAhAB8I6kYCAAAAAgAAAAkAAABhLy4uLy" +
        "4uL3h4ClBLAQIUABQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAAAAAAAAAAAAAAAAAAABtYXJrZXIudHh0UEsBAhQAFAAAAAAAAAAhAB8I6kYC" +
        "AAAAAgAAAAkAAAAAAAAAAAAAAAAALwAAAGEvLi4vLi4veFBLBQYAAAAAAgACAG8AAABYAAAAAAA="

    private val ZIP_P: String =
        "UEsDBBQAAQAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwECFAAUAAEAAAAAACEAqBZQaAcAAAAHAAAACgAAAAAAAA" +
        "AAAAAAAAAAAAAAbWFya2VyLnR4dFBLBQYAAAAAAQABADgAAAAvAAAAAAA="

    private val ZIP_Q: String =
        "UEsDBBQACAAIAAAAIQAAAAAAAAAAAAAAAAAKAAAAbWFya2VyLnR4dMtNLMpOLeICAFBLBwipFlBoCQAAAAcAAABQSwECFAAUAAgACAAAACEAqB" +
        "ZQaAkAAAAHAAAACgAAAAAAAAAAAAAAAAAAAAAAbWFya2VyLnR4dFBLBQYAAAAAAQABADgAAABBAAAAAAA="

    private val ZIP_R: String =
        "UEsDBBQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSw=="

    private val ZIP_S: String =
        "UEsDBBQAAAAAAAAAIQCoFlBoBwAAAAcAAAAKAAAAbWFya2VyLnR4dG1hcmtlcgpQSwECFAAUAAAAAAAAACEAqBZQaAcAAAAHAAAACgAAAAAAAA" +
        "AAAAAAAAAAAAAAbWFya2VyLnR4dFBLBQYAAAAAAQABADgAAAAvAAAAGgB6aXAgY29tbWVudCBQSwMEMDEyMzQ1Njc4OQ=="
    }
}
