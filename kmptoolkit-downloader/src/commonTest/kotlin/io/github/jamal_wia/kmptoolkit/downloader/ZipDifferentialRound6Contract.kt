package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
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
        readFile(TestPaths.join(storage.getResourcePath(unit), entry))?.decodeToString()

    private fun baseOf(storage: DownloaderStorage, unit: DownloadUnit): String =
        TestPaths.parent(TestPaths.parent(storage.getResourcePath(unit))!!)!!

    private suspend fun assertRejected(storage: DownloaderStorage, unit: DownloadUnit, what: String) {
        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
        assertTrue(failure is ResourceIntegrityException, "$what: expected ResourceIntegrityException, was $failure")
        assertEquals(TempFileState.None, storage.tempFileState(unit), "$what: the worthless temp file must be deleted")
        assertFalse(pathExists(TestPaths.join(baseOf(storage, unit), "tmp/staging-${unit.id}")), "$what: staging left behind")
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
    fun `an archive that lost its central directory and has junk after the last entry is rejected`() = runTest {
        // D-83 (B6-02) replaces D-74: an archive must end with its end of central directory record. Before, this
        // archive committed on both platforms.
        val junk = ByteArray(300) { index: Int -> (index * 7 + 3).toByte() }
        junk[0] = 0x00 // not the start of a header
        val built: BuiltZipRound6 = ZipBytesRound6.build(listOf(marker()), withCentralDirectory = false, trailing = junk)
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("junkcd")
        stage(storage, unit, built.bytes)

        assertRejected(storage, unit, "an archive without an end record")
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
    fun `absolute dotted and doubled-slash names land where the lexical rules put them`() = runTest {
        val entries: List<ZipEntryRound6> = listOf(
            ZipEntryRound6("./marker.txt", "marker\n"),
            ZipEntryRound6("/abs.txt", "abs\n"),
            ZipEntryRound6("a//b.txt", "ab\n"),
            ZipEntryRound6("c/./d/../e.txt", "ce\n"),
            ZipEntryRound6("..x", "dotx\n"),
        )

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("lex", ZipBytesRound6.build(entries).bytes)

        assertEquals("marker\n", text(storage, unit, "marker.txt"))
        assertEquals("abs\n", text(storage, unit, "abs.txt"))
        assertEquals("ab\n", text(storage, unit, "a/b.txt"))
        assertEquals("ce\n", text(storage, unit, "c/e.txt"))
        assertEquals("dotx\n", text(storage, unit, "..x"))
        assertFalse(pathExists(TestPaths.join(baseOf(storage, unit), "abs.txt")), "an absolute name must not leave the extraction tree")
    }

    @Test
    fun `names with a backslash or a trailing dot are ordinary file names and stay inside the extraction tree`() = runTest {
        // Only a POSIX file system (a device, macOS, Linux) keeps a backslash as a name character and
        // can create "..." or "y..": on Windows a backslash is a separator and a trailing dot is dropped.
        TestHost.assumePosixFileSemantics("a backslash or a trailing dot is part of a file name")
        val entries: List<ZipEntryRound6> = listOf(
            marker(),
            ZipEntryRound6("back\\slash.txt", "bs\n"),
            ZipEntryRound6("..\\up.txt", "up\n"),
            ZipEntryRound6("...", "dots\n"),
            ZipEntryRound6("y..", "ydot\n"),
            ZipEntryRound6("f/.../g.txt", "fg\n"),
        )

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("backslash", ZipBytesRound6.build(entries).bytes)

        assertEquals("bs\n", text(storage, unit, "back\\slash.txt"))
        assertEquals("up\n", text(storage, unit, "..\\up.txt"))
        assertEquals("dots\n", text(storage, unit, "..."))
        assertEquals("ydot\n", text(storage, unit, "y.."))
        assertEquals("fg\n", text(storage, unit, "f/.../g.txt"))
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
        assertFalse(pathExists(TestPaths.join(baseOf(storage, ArchiveUnit("out0")), "x.txt")))
        assertFalse(pathExists(TestPaths.join(baseOf(storage, ArchiveUnit("out0")), "tmp/x.txt")))
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

    // ---- B6-02: one end-of-central-directory rule on both platforms (D-83) ---------------------------------

    @Test
    fun `an archive cut before its end record is complete is rejected and one cut inside the comment commits`() = runTest {
        val first = ZipEntryRound6(
            "marker.txt".encodeToByteArray(), "marker\n".encodeToByteArray(),
            deflated = true, descriptor = DescriptorRound6.WithSignature,
        )
        // A name ending in P: a cut right after it used to be mistaken for the start of a header on one platform only.
        val second = ZipEntryRound6("dir/TOP", "second\n")
        val built: BuiltZipRound6 = ZipBytesRound6.build(listOf(first, second), comment = "end".encodeToByteArray())
        val storage: DownloaderStorage = newStorage()
        val problems: ArrayList<String> = ArrayList()
        for (length: Int in 0..built.bytes.size) {
            val unit = ArchiveUnit("cut$length")
            stage(storage, unit, built.bytes.copyOf(length))
            val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
            if (length < built.endRecordStart + 22) {
                if (failure !is ResourceIntegrityException) problems += "cut $length: expected rejection, was $failure"
                else if (storage.tempFileState(unit) != TempFileState.None) problems += "cut $length: temp file kept"
                else if (pathExists(TestPaths.join(baseOf(storage, unit), "tmp/staging-cut$length"))) problems += "cut $length: staging left"
            } else {
                if (failure != null) problems += "cut $length: expected the archive to commit, was $failure"
                else if (text(storage, unit, "marker.txt") != "marker\n" || text(storage, unit, "dir/TOP") != "second\n") {
                    problems += "cut $length: entries missing"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `an archive that lost its whole central directory right after the last entry is rejected whatever the data ends with`() = runTest {
        val tails: List<ByteArray> = listOf(
            "GROUP".encodeToByteArray(),
            "PK".encodeToByteArray(),
            byteArrayOf(0x50, 0x4B, 0x03),
            byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x41, 0x42),
        )
        val storage: DownloaderStorage = newStorage()
        for ((index: Int, tail: ByteArray) in tails.withIndex()) {
            val built: BuiltZipRound6 = ZipBytesRound6.build(
                listOf(marker(), ZipEntryRound6("notes.bin".encodeToByteArray(), "see ".encodeToByteArray() + tail)),
                withCentralDirectory = false,
            )
            val unit = ArchiveUnit("tail$index")
            stage(storage, unit, built.bytes)
            assertRejected(storage, unit, "no central directory, data ending ${tail.toList()}")
        }
    }

    @Test
    fun `an archive whose comment ends with the local header signature commits and junk in place of the central directory with an intact end record commits`() = runTest {
        val signed: BuiltZipRound6 = ZipBytesRound6.build(listOf(marker()), comment = byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("signedcomment", signed.bytes)
        assertEquals("marker\n", text(storage, unit, "marker.txt"))

        // The central directory replaced by junk of the same length: the end record is intact and consistent.
        val built: BuiltZipRound6 = ZipBytesRound6.build(listOf(marker()))
        val bytes: ByteArray = built.bytes.copyOf()
        for (index: Int in built.centralDirectoryStart until built.endRecordStart) bytes[index] = 0x11
        val (storage2: DownloaderStorage, unit2: DownloadUnit) = commitOk("junkcdintact", bytes)
        assertEquals("marker\n", text(storage2, unit2, "marker.txt"))
    }

    // ---- B6-04: names are UTF-8 on both platforms ---------------------------------------------------------

    @Test
    fun `a name that is not valid UTF-8 fails the commit on both platforms whatever the flag says`() = runTest {
        val variants: List<Pair<String, ZipEntryRound6>> = listOf(
            "latin-1 name without the UTF-8 flag" to ZipEntryRound6(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x2E, 0x74), "latin\n".encodeToByteArray()),
            "UTF-8 flag with an invalid sequence" to ZipEntryRound6(byteArrayOf(0x63, 0xC3.toByte(), 0x28, 0x2E, 0x74), "bad\n".encodeToByteArray(), extraFlags = 0x0800),
            "an encoded surrogate" to ZipEntryRound6(byteArrayOf(0x73, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x2E, 0x74), "sur\n".encodeToByteArray()),
            "an overlong slash" to ZipEntryRound6(byteArrayOf(0x6F, 0xC0.toByte(), 0xAF.toByte(), 0x2E, 0x74), "over\n".encodeToByteArray()),
            "a sequence cut at the end of the name" to ZipEntryRound6(byteArrayOf(0x63, 0x61, 0x66, 0xC3.toByte()), "cut\n".encodeToByteArray()),
            "a four-byte sequence above U+10FFFF" to ZipEntryRound6(byteArrayOf(0x68, 0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), "big\n".encodeToByteArray()),
        )
        val storage: DownloaderStorage = newStorage()
        for ((index: Int, variant: Pair<String, ZipEntryRound6>) in variants.withIndex()) {
            val unit = ArchiveUnit("latin$index")
            stage(storage, unit, ZipBytesRound6.build(listOf(marker(), variant.second)).bytes)
            assertRejected(storage, unit, variant.first)
        }
    }

    @Test
    fun `valid UTF-8 without the flag extracts under its own name`() = runTest {
        val name: ByteArray = byteArrayOf(0x63, 0x61, 0x66, 0xC3.toByte(), 0xA9.toByte())
        val entries: List<ZipEntryRound6> = listOf(marker(), ZipEntryRound6(name, "coffee\n".encodeToByteArray()))

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("utf8", ZipBytesRound6.build(entries).bytes)

        assertEquals("coffee\n", text(storage, unit, "caf\u00e9"))
    }

    // ---- B6-01 / B6-03: one flattening rule (D-83) ----------------------------------------------------------

    @Test
    fun `a single root directory is moved up even when it holds a directory of the same name`() = runTest {
        val entries: List<ZipEntryRound6> = listOf(
            ZipEntryRound6("root/marker.txt", "marker\n"),
            ZipEntryRound6("root/root/inner.txt", "inner\n"),
        )

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("samename", ZipBytesRound6.build(entries).bytes)

        assertEquals("marker\n", text(storage, unit, "marker.txt"))
        assertEquals("inner\n", text(storage, unit, "root/inner.txt"))
        assertNull(text(storage, unit, "root/root/inner.txt"))
    }

    @Test
    fun `a single root directory is moved up over an empty directory of the same name at the top`() = runTest {
        val entries: List<ZipEntryRound6> = listOf(
            ZipEntryRound6("empty/".encodeToByteArray(), ByteArray(0)),
            ZipEntryRound6("root/marker.txt", "marker\n"),
            ZipEntryRound6("root/empty/x.txt", "x\n"),
        )

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("emptysame", ZipBytesRound6.build(entries).bytes)

        assertEquals("marker\n", text(storage, unit, "marker.txt"))
        assertEquals("x\n", text(storage, unit, "empty/x.txt"))
    }

    @Test
    fun `a single root directory next to a file is moved up and the file stays where it is`() = runTest {
        val entries: List<ZipEntryRound6> = listOf(
            ZipEntryRound6("root/marker.txt", "m\n"),
            ZipEntryRound6("README", "r\n"),
        )

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("beside", ZipBytesRound6.build(entries).bytes)

        assertEquals("m\n", text(storage, unit, "marker.txt"))
        assertEquals("r\n", text(storage, unit, "README"))
    }

    @Test
    fun `a root child colliding with a top-level file fails the commit`() = runTest {
        val entries: List<ZipEntryRound6> = listOf(
            ZipEntryRound6("root/marker.txt", "m\n"),
            ZipEntryRound6("root/README", "inner\n"),
            ZipEntryRound6("README", "outer\n"),
        )
        val storage: DownloaderStorage = newStorage()
        val unit = ArchiveUnit("collide")
        stage(storage, unit, ZipBytesRound6.build(entries).bytes)

        assertRejected(storage, unit, "a root child named like a top-level file")
    }

    @Test
    fun `a root whose child is named like the temporary name commits`() = runTest {
        val entries: List<ZipEntryRound6> = listOf(
            ZipEntryRound6("root/marker.txt", "m\n"),
            ZipEntryRound6("root/.kmptoolkit-up/x.txt", "x\n"),
            ZipEntryRound6("root/.kmptoolkit-up-1/y.txt", "y\n"),
        )

        val (storage: DownloaderStorage, unit: DownloadUnit) = commitOk("tempname", ZipBytesRound6.build(entries).bytes)

        assertEquals("m\n", text(storage, unit, "marker.txt"))
        assertEquals("x\n", text(storage, unit, ".kmptoolkit-up/x.txt"))
        assertEquals("y\n", text(storage, unit, ".kmptoolkit-up-1/y.txt"))
    }
}
