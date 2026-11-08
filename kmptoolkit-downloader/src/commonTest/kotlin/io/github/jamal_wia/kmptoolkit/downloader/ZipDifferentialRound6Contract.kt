package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round 6 differential contract: the same hand-laid archives are committed through both shipped storages
 * (Android over java.util.zip, iOS over a hand-written parser) and must come out the same. Archives are built
 * byte by byte by [ZipBytesRound6], so every layout a real writer (or a broken one) produces can be tested,
 * and every truncation point of one archive can be walked.
 *
 * The expectation is derived from the documented promise, not from either implementation: an archive is
 * extracted entry for entry or the commit fails with [ResourceIntegrityException] and leaves nothing behind
 * (ResourceFormat KDoc, docs/kmptoolkit-downloader 03-guide and 04-api-reference), and ZipInputStream is the
 * reference for what a valid archive is (D-70, D-74).
 */
abstract class ZipDifferentialRound6Contract {

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

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, zip: ByteArray) {
        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), zip)
        storage.markTempFileComplete(unit)
    }

    private fun text(storage: DownloaderStorage, unit: DownloadUnit, entry: String): String? =
        readFile(storage.getResourcePath(unit) + "/" + entry)?.decodeToString()

    private fun baseOf(storage: DownloaderStorage, unit: DownloadUnit): String =
        storage.getResourcePath(unit).substringBeforeLast('/').substringBeforeLast('/')

    private suspend fun assertRejected(storage: DownloaderStorage, unit: DownloadUnit, what: String) {
        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
        assertTrue(failure is ResourceIntegrityException, "$what: expected ResourceIntegrityException, was $failure")
        assertEquals(TempFileState.None, storage.tempFileState(unit), "$what: the worthless temp file must be deleted")
        assertFalse(pathExists("${baseOf(storage, unit)}/tmp/staging-${unit.id}"), "$what: staging left behind")
        assertFalse(storage.isResourceAvailable(unit), "$what: a rejected archive must not be available")
    }

    private fun marker(): ZipEntryRound6 = ZipEntryRound6("marker.txt", "marker\n")

    private suspend fun commitOk(id: String, zip: ByteArray): Pair<DownloaderStorage, DownloadUnit> {
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit(id)
        stage(storage, unit, zip)
        storage.commitResource(unit)
        return storage to unit
    }

    // ---- truncation at every byte boundary ---------------------------------------------------------------

    @Test
    fun `an archive that lost its central directory and has junk after the last entry commits`() = runTest {
        val junk = ByteArray(300) { index: Int -> (index * 7 + 3).toByte() }
        junk[0] = 0x00 // not the start of a header
        val built: BuiltZipRound6 = ZipBytesRound6.build(listOf(marker()), withCentralDirectory = false, trailing = junk)

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("junkcd", built.bytes)

        assertEquals("marker\n", text(storage, unit, "marker.txt"))
    }

    @Test
    fun `an archive with junk before the first header and an empty archive are rejected`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val lead = ArchiveUnit("lead")
        stage(storage, lead, "stub".encodeToByteArray() + ZipBytesRound6.build(listOf(marker())).bytes)
        assertRejected(storage, lead, "junk before the first header")

        val empty = ArchiveUnit("emptyzip")
        stage(storage, empty, ZipBytesRound6.build(emptyList()).bytes)
        assertRejected(storage, empty, "an archive of no entries (end record only)")

        val signatureComment = ArchiveUnit("emptycomment")
        stage(
            storage, signatureComment,
            ZipBytesRound6.build(emptyList(), comment = byteArrayOf(0x50, 0x4B, 0x03, 0x04)).bytes,
        )
        assertRejected(storage, signatureComment, "an archive of no entries whose comment is a header signature")
    }

    @Test
    fun `junk after the end record is ignored`() = runTest {
        val built: BuiltZipRound6 = ZipBytesRound6.build(listOf(marker()), trailing = ByteArray(2000))

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("pad", built.bytes)

        assertEquals("marker\n", text(storage, unit, "marker.txt"))
    }

    // ---- data descriptors ------------------------------------------------------------------------------

    @Test
    fun `a descriptor without a signature one holding header-like bytes in its data an empty entry and sizes in both places all extract`() = runTest {
        val trap: ByteArray = "PK\u0007\u0008 not a descriptor PK\u0003\u0004".encodeToByteArray()
        val entries: List<ZipEntryRound6> = listOf(
            ZipEntryRound6(
                "marker.txt".encodeToByteArray(), "marker\n".encodeToByteArray(),
                deflated = true, descriptor = DescriptorRound6.WithoutSignature,
            ),
            ZipEntryRound6("trap.bin".encodeToByteArray(), trap, deflated = true, descriptor = DescriptorRound6.WithSignature),
            ZipEntryRound6(
                "empty.txt".encodeToByteArray(), ByteArray(0),
                deflated = true, descriptor = DescriptorRound6.WithSignature,
                payloadOverride = byteArrayOf(0x03, 0x00), // fixed-Huffman block holding nothing, what zip writers emit
            ),
            ZipEntryRound6(
                "both.txt".encodeToByteArray(), "both\n".encodeToByteArray(),
                deflated = true, descriptor = DescriptorRound6.WithSignature, sizesInHeaderDespiteDescriptor = true,
            ),
            ZipEntryRound6("last.txt", "last\n"),
        )

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("desc", ZipBytesRound6.build(entries).bytes)

        assertEquals("marker\n", text(storage, unit, "marker.txt"))
        assertEquals(trap.decodeToString(), text(storage, unit, "trap.bin"))
        assertEquals("", text(storage, unit, "empty.txt"))
        assertEquals("both\n", text(storage, unit, "both.txt"))
        assertEquals("last\n", text(storage, unit, "last.txt"))
    }

    @Test
    fun `declared sizes far beyond the archive a name or extra field longer than the file fail the commit without exhausting memory`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val cases: Map<String, ByteArray> = mapOf(
            "huge stored size" to ZipBytesRound6.build(
                listOf(marker(), ZipEntryRound6("big.bin".encodeToByteArray(), "x".encodeToByteArray(), headerCompressedSize = 0x7FFFFFF0L, headerSize = 0x7FFFFFF0L)),
                withCentralDirectory = false,
            ).bytes,
            "huge deflated size" to ZipBytesRound6.build(
                listOf(marker(), ZipEntryRound6("big.bin".encodeToByteArray(), "x".encodeToByteArray(), deflated = true, headerCompressedSize = 0x7FFFFFF0L, headerSize = 0x7FFFFFF0L)),
                withCentralDirectory = false,
            ).bytes,
        )
        for ((index: Int, case: Map.Entry<String, ByteArray>) in cases.entries.withIndex()) {
            val unit = ArchiveUnit("huge$index")
            stage(storage, unit, case.value)
            assertRejected(storage, unit, case.key)
        }
        // A header that promises a 65535-byte name and a 65535-byte extra field in a file that ends there.
        val short: ByteArray = ZipBytesRound6.build(listOf(marker()), withCentralDirectory = false).bytes
        val longName: ByteArray = short.copyOf() + byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(22) +
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + "abc".encodeToByteArray()
        val nameUnit = ArchiveUnit("longname")
        stage(storage, nameUnit, longName)
        assertRejected(storage, nameUnit, "a 65535-byte name in a short file")
    }

    // ---- names -----------------------------------------------------------------------------------------

    @Test
    fun `absolute dotted doubled-slash and backslash names land where the lexical rules put them`() = runTest {
        val entries: List<ZipEntryRound6> = listOf(
            ZipEntryRound6("./marker.txt", "marker\n"),
            ZipEntryRound6("/abs.txt", "abs\n"),
            ZipEntryRound6("a//b.txt", "ab\n"),
            ZipEntryRound6("c/./d/../e.txt", "ce\n"),
            ZipEntryRound6("...", "dots\n"),
            ZipEntryRound6("..x", "dotx\n"),
            ZipEntryRound6("y..", "ydot\n"),
            ZipEntryRound6("back\\slash.txt", "bs\n"),
            ZipEntryRound6("..\\up.txt", "up\n"),
            ZipEntryRound6("f/.../g.txt", "fg\n"),
        )

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("lex", ZipBytesRound6.build(entries).bytes)

        assertEquals("marker\n", text(storage, unit, "marker.txt"))
        assertEquals("abs\n", text(storage, unit, "abs.txt"))
        assertEquals("ab\n", text(storage, unit, "a/b.txt"))
        assertEquals("ce\n", text(storage, unit, "c/e.txt"))
        assertEquals("dots\n", text(storage, unit, "..."))
        assertEquals("dotx\n", text(storage, unit, "..x"))
        assertEquals("ydot\n", text(storage, unit, "y.."))
        assertEquals("bs\n", text(storage, unit, "back\\slash.txt"))
        assertEquals("up\n", text(storage, unit, "..\\up.txt"))
        assertEquals("fg\n", text(storage, unit, "f/.../g.txt"))
        assertFalse(pathExists("${baseOf(storage, unit)}/abs.txt"), "an absolute name must not leave the extraction tree")
    }

    @Test
    fun `entries whose names resolve outside to nothing or to the target itself as a file fail the commit`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val names: List<String> = listOf("../x.txt", "/../x.txt", "a/../../x.txt", "a/..", ".", "./", "a/b/../../..", "")
        val problems: ArrayList<String> = ArrayList()
        for ((index: Int, name: String) in names.withIndex()) {
            val unit = ArchiveUnit("out$index")
            // "./" and "/" are directory entries for the target itself and are fine; the others are not.
            val zip: ByteArray = ZipBytesRound6.build(listOf(marker(), ZipEntryRound6(name.encodeToByteArray(), "x".encodeToByteArray()))).bytes
            stage(storage, unit, zip)
            val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
            val mustFail: Boolean = name != "./"
            if (mustFail && failure !is ResourceIntegrityException) problems += "'$name': expected ResourceIntegrityException, was $failure"
            if (!mustFail && failure != null) problems += "'$name': expected success, was $failure"
        }
        assertFalse(pathExists("${baseOf(storage, ArchiveUnit("out0"))}/x.txt"))
        assertFalse(pathExists("${baseOf(storage, ArchiveUnit("out0"))}/tmp/x.txt"))
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `the later of two entries with one name wins`() = runTest {
        val entries: List<ZipEntryRound6> = listOf(marker(), ZipEntryRound6("dup.txt", "one\n"), ZipEntryRound6("dup.txt", "two\n"))

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("dup", ZipBytesRound6.build(entries).bytes)

        assertEquals("two\n", text(storage, unit, "dup.txt"))
    }

    // ---- the single-root move ------------------------------------------------------------------------

    @Test
    fun `a single root is moved up two roots are not`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val single = ArchiveUnit("single")
        stage(storage, single, ZipBytesRound6.build(listOf(ZipEntryRound6("empty/".encodeToByteArray(), ByteArray(0)), ZipEntryRound6("root/marker.txt", "m\n"), ZipEntryRound6("root/f/g.txt", "g\n"))).bytes)
        storage.commitResource(single)
        assertEquals("m\n", text(storage, single, "marker.txt"))
        assertEquals("g\n", text(storage, single, "f/g.txt"))

        val two = ArchiveUnit("tworoots")
        stage(storage, two, ZipBytesRound6.build(listOf(ZipEntryRound6("one/marker.txt", "m\n"), ZipEntryRound6("two/other.txt", "o\n"))).bytes)
        assertRejected(storage, two, "two roots")
    }
}
