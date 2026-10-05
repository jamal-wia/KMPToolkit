package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The hash-expectation half of the [DownloaderStorage] contract, written once and run against both
 * shipped storages (the Android and iOS subclasses supply only the file access the test needs).
 *
 * Every case is derived from the contract in `DownloaderStorage`'s KDoc — what [beginTempFile]
 * promises, what [DownloaderStorage.commitResource] must check, and what must be left on disk
 * afterwards — not from what an implementation happens to do. Process death is modelled by asking
 * for a second storage instance over the same directory: nothing is shared between instances but
 * the files.
 */
abstract class DownloaderStorageContractTest {

    /** A new storage instance over this test's directory; repeated calls see the same files. */
    protected abstract fun newStorage(): DownloaderStorage

    /** Writes [bytes] to [path], creating missing parent directories. */
    protected abstract fun writeFile(path: String, bytes: ByteArray)

    /** The bytes of the regular file at [path], or null when there is none. */
    protected abstract fun readFile(path: String): ByteArray?

    /** True when [path] exists as a file or a directory. */
    protected abstract fun pathExists(path: String): Boolean

    /** Removes everything this test's storage wrote. */
    protected abstract fun deleteTestDirectory()

    @AfterTest
    fun deleteDirectoryAfterTest() {
        deleteTestDirectory()
    }

    private val storage: DownloaderStorage by lazy { newStorage() }

    // 1
    @Test
    fun `a transfer begun with a hash commits when the bytes match and leaves nothing behind`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, ABC)
        writeTemp(storage, unit, "abc")

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals("abc", readFile(storage.getResourcePath(unit))?.decodeToString())
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(recordPath(storage, unit)), "the record must not outlive the temp file")
    }

    // 2
    @Test
    fun `a transfer begun with a hash that the bytes do not match is rejected and cleaned up`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, OTHER)
        writeTemp(storage, unit, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, unit) }

        assertFalse(pathExists(storage.getResourcePath(unit)), "nothing may reach the final path")
        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(recordPath(storage, unit)))
    }

    // 3
    @Test
    fun `a mismatching archive is rejected before anything is extracted`() = runTest {
        val unit = ContractUnit("pages", format = ResourceFormat.ZipArchive("marker.txt"), relativePath = "archives/pages")
        storage.beginTempFile(unit, OTHER)
        writeTempBytes(storage, unit, zipOf("marker.txt", "hello".encodeToByteArray()))

        assertFailsWith<ResourceIntegrityException> { finish(storage, unit) }

        assertFalse(pathExists(storage.getResourcePath(unit)), "no directory may have been extracted")
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(recordPath(storage, unit)))
    }

    @Test
    fun `an archive that passes its check is extracted and its record is dropped`() = runTest {
        val unit = ContractUnit("pages", format = ResourceFormat.ZipArchive("marker.txt"), relativePath = "archives/pages")
        storage.beginTempFile(unit, null)
        writeTempBytes(storage, unit, zipOf("marker.txt", "hello".encodeToByteArray()))

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(recordPath(storage, unit)))
    }

    @Test
    fun `a database whose hash is right but whose bytes are not a database is rejected and its record dropped`() = runTest {
        val unit = ContractUnit("db", format = ResourceFormat.SqliteDatabase())
        storage.beginTempFile(unit, ABC)
        writeTemp(storage, unit, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(recordPath(storage, unit)))
    }

    // 4
    @Test
    fun `a transfer begun without a hash commits any bytes`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, null)
        writeTemp(storage, unit, "whatever the server sent")

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertFalse(pathExists(recordPath(storage, unit)))
    }

    @Test
    fun `beginning without a hash still enforces the unit's own hash`() = runTest {
        val unit = ContractUnit("model", sha256 = ABC)
        storage.beginTempFile(unit, null)
        writeTemp(storage, unit, "abd")

        assertFailsWith<ResourceIntegrityException> { finish(storage, unit) }

        assertFalse(storage.isResourceAvailable(unit))
    }

    @Test
    fun `bytes must match both the unit's hash and the recorded one`() = runTest {
        // Both state ABC: one comparison satisfies both. A different byte string fails against
        // each of them, whichever was stated where.
        val unit = ContractUnit("model", sha256 = ABC)
        storage.beginTempFile(unit, ABC)
        writeTemp(storage, unit, "abc")
        finish(storage, unit)
        assertTrue(storage.isResourceAvailable(unit))
    }

    // 5
    @Test
    fun `beginning again discards the partial and complete files and the old expectation`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, OTHER)
        writeTemp(storage, unit, "abd")
        storage.markTempFileComplete(unit)
        writeTemp(storage, unit, "partial")

        storage.beginTempFile(unit, ABC)

        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(0L, storage.getTempFileSize(unit))
        // The first hash is no longer enforced: the new bytes satisfy only the second one.
        writeTemp(storage, unit, "abc")
        finish(storage, unit)
        assertTrue(storage.isResourceAvailable(unit))
    }

    @Test
    fun `the expectation of a later begin is the one enforced`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, ABC)
        writeTemp(storage, unit, "abc")

        storage.beginTempFile(unit, OTHER)
        writeTemp(storage, unit, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, unit) }
        assertFalse(storage.isResourceAvailable(unit))
    }

    // 6
    @Test
    fun `deleting the temp file drops the record and a later begin without a hash commits anything`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, OTHER)
        writeTemp(storage, unit, "abc")

        storage.deleteTempFile(unit)

        assertFalse(pathExists(recordPath(storage, unit)))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        storage.beginTempFile(unit, null)
        writeTemp(storage, unit, "arbitrary")
        finish(storage, unit)
        assertTrue(storage.isResourceAvailable(unit))
    }

    // 7
    @Test
    fun `a second instance over the same directory enforces the recorded hash on mismatch`() = runTest {
        val unit = ContractUnit("model")
        val before: DownloaderStorage = newStorage()
        before.beginTempFile(unit, OTHER)
        writeTemp(before, unit, "abc")

        val after: DownloaderStorage = newStorage()
        assertEquals(TempFileState.Partial, after.tempFileState(unit))

        assertFailsWith<ResourceIntegrityException> { finish(after, unit) }
        assertFalse(after.isResourceAvailable(unit))
        assertFalse(pathExists(recordPath(after, unit)))
    }

    @Test
    fun `a second instance over the same directory commits when the recorded hash matches`() = runTest {
        val unit = ContractUnit("model")
        val before: DownloaderStorage = newStorage()
        before.beginTempFile(unit, ABC)
        writeTemp(before, unit, "abc")

        finish(newStorage(), unit)

        assertTrue(newStorage().isResourceAvailable(unit))
    }

    @Test
    fun `a file already complete before the second instance exists is still checked against the record`() = runTest {
        val unit = ContractUnit("model")
        val before: DownloaderStorage = newStorage()
        before.beginTempFile(unit, OTHER)
        writeTemp(before, unit, "abc")
        before.markTempFileComplete(unit)

        val after: DownloaderStorage = newStorage()
        assertEquals(TempFileState.Complete, after.tempFileState(unit))
        assertFailsWith<ResourceIntegrityException> { after.commitResource(unit) }

        assertFalse(after.isResourceAvailable(unit))
        assertEquals(TempFileState.None, after.tempFileState(unit))
        assertFalse(pathExists(recordPath(after, unit)))
    }

    @Test
    fun `a complete file recovered by a second instance commits when the record matches`() = runTest {
        val unit = ContractUnit("model")
        val before: DownloaderStorage = newStorage()
        before.beginTempFile(unit, ABC)
        writeTemp(before, unit, "abc")
        before.markTempFileComplete(unit)

        newStorage().commitResource(unit)

        assertTrue(newStorage().isResourceAvailable(unit))
        assertFalse(pathExists(recordPath(before, unit)))
    }

    // 8
    @Test
    fun `a partial without a record resumes and only the unit's hash is checked`() = runTest {
        val matching = ContractUnit("legacy-a", sha256 = ABC)
        writeTemp(storage, matching, "abc")
        assertEquals(TempFileState.Partial, storage.tempFileState(matching))
        assertEquals(3L, storage.getTempFileSize(matching))
        finish(storage, matching)
        assertTrue(storage.isResourceAvailable(matching))

        val mismatching = ContractUnit("legacy-b", sha256 = ABC)
        writeTemp(storage, mismatching, "abd")
        assertFailsWith<ResourceIntegrityException> { finish(storage, mismatching) }
        assertFalse(storage.isResourceAvailable(mismatching))

        val unconstrained = ContractUnit("legacy-c")
        writeTemp(storage, unconstrained, "anything")
        finish(storage, unconstrained)
        assertTrue(storage.isResourceAvailable(unconstrained))
    }

    // 9
    @Test
    fun `a record without a temp file reads as none and the next begin overwrites it`() = runTest {
        val unit = ContractUnit("model")
        writeFile(recordPath(storage, unit), "sha256:${OTHER.hex}\n".encodeToByteArray())

        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(0L, storage.getTempFileSize(unit))

        storage.beginTempFile(unit, null)
        assertEquals("none", readFile(recordPath(storage, unit))?.decodeToString()?.trim())
        writeTemp(storage, unit, "abc")
        finish(storage, unit)
        assertTrue(storage.isResourceAvailable(unit))
    }

    @Test
    fun `beginning always writes a record even without a hash`() {
        val unit = ContractUnit("model")

        storage.beginTempFile(unit, null)

        assertTrue(pathExists(recordPath(storage, unit)))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    // 10
    @Test
    fun `an unreadable record is an integrity failure and never no check`() = runTest {
        for ((index: Int, garbage: String) in listOf("not a record", "", "sha256:xyz", "sha256:" + "a".repeat(63)).withIndex()) {
            val unit = ContractUnit("model-$index")
            storage.beginTempFile(unit, ABC)
            writeTemp(storage, unit, "abc")
            storage.markTempFileComplete(unit)
            writeFile(recordPath(storage, unit), garbage.encodeToByteArray())

            assertFailsWith<ResourceIntegrityException>("record '$garbage'") { storage.commitResource(unit) }

            assertFalse(storage.isResourceAvailable(unit), "record '$garbage'")
            assertEquals(TempFileState.None, storage.tempFileState(unit), "record '$garbage'")
            assertFalse(pathExists(recordPath(storage, unit)), "record '$garbage'")
        }
    }

    // 11
    @Test
    fun `contradictory hashes make begin fail and leave the existing transfer untouched`() {
        val original = ContractUnit("model")
        storage.beginTempFile(original, OTHER)
        writeTemp(storage, original, "partial")
        val recordBefore: ByteArray? = readFile(recordPath(storage, original))

        val contradictory = ContractUnit("model", sha256 = ABC)
        assertFailsWith<IllegalArgumentException> { storage.beginTempFile(contradictory, OTHER) }

        assertEquals(TempFileState.Partial, storage.tempFileState(original))
        assertEquals(7L, storage.getTempFileSize(original))
        assertContentEquals(recordBefore, readFile(recordPath(storage, original)))
    }

    @Test
    fun `the same hash stated twice is not a contradiction`() {
        val unit = ContractUnit("model", sha256 = ABC)

        storage.beginTempFile(unit, ABC)

        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    // 12
    @Test
    fun `uppercase hex is accepted and equals its lowercase form`() = runTest {
        val upper: Sha256 = Sha256.parse(ABC.hex.uppercase())
        val unit = ContractUnit("model", sha256 = upper)
        storage.beginTempFile(unit, upper)
        writeTemp(storage, unit, "abc")

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
    }

    // 13
    @Test
    fun `two unit instances with the same id share one record`() = runTest {
        val first = ContractUnit("model")
        val second = ContractUnit("model")
        storage.beginTempFile(first, OTHER)
        writeTemp(storage, second, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, second) }

        assertFalse(storage.isResourceAvailable(first))
        assertFalse(pathExists(recordPath(storage, first)))
    }

    @Test
    fun `the record location does not depend on the temp extension`() {
        val tmp = ContractUnit("model", tempExtension = "tmp")
        val part = ContractUnit("model", tempExtension = "part")

        assertEquals(recordPath(storage, tmp), recordPath(storage, part))
    }

    @Test
    fun `two different ids never share a record`() = runTest {
        val a = ContractUnit("a")
        val b = ContractUnit("a.tmp")
        storage.beginTempFile(a, OTHER)
        storage.beginTempFile(b, null)
        writeTemp(storage, b, "abc")

        finish(storage, b)

        assertTrue(storage.isResourceAvailable(b))
        assertTrue(pathExists(recordPath(storage, a)), "committing b must not touch a's record")
    }

    // -- helpers -----------------------------------------------------------------------------

    private fun writeTemp(on: DownloaderStorage, unit: DownloadUnit, text: String) =
        writeTempBytes(on, unit, text.encodeToByteArray())

    private fun writeTempBytes(on: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        writeFile(on.getTempFilePath(unit), bytes)
    }

    private suspend fun finish(on: DownloaderStorage, unit: DownloadUnit) {
        on.markTempFileComplete(unit)
        on.commitResource(unit)
    }

    /** Where the contract says the record lives: `tmp/expect/<id>`, next to the temp files. */
    private fun recordPath(on: DownloaderStorage, unit: DownloadUnit): String =
        on.getTempFilePath(unit).substringBeforeLast('/') + "/expect/" + unit.id

    private class ContractUnit(
        override val id: String,
        override val sha256: Sha256? = null,
        override val format: ResourceFormat = ResourceFormat.Opaque,
        override val relativePath: String = "resources/$id.bin",
        override val tempExtension: String = "tmp",
    ) : DownloadUnit {
        override val apiPath: String = "/resources/$id"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@ContractUnit)
        }

        override fun toString(): String = "ContractUnit($id)"
    }

    private companion object {
        /** SHA-256 of the three ASCII bytes "abc", the FIPS 180-2 test vector. */
        val ABC: Sha256 = Sha256.parse("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

        /** A well-formed hash that none of the test bytes has. */
        val OTHER: Sha256 = Sha256.parse("0".repeat(64))

        /** A one-entry ZIP using STORED (no compression), valid for both platforms' extractors. */
        fun zipOf(name: String, content: ByteArray): ByteArray {
            val nameBytes: ByteArray = name.encodeToByteArray()
            val crc: Int = crc32(content)
            val local: ByteArray = le32(0x04034b50) + le16(20) + le16(0) + le16(0) + le16(0) + le16(0x21) +
                le32(crc) + le32(content.size) + le32(content.size) + le16(nameBytes.size) + le16(0) +
                nameBytes + content
            val central: ByteArray = le32(0x02014b50) + le16(20) + le16(20) + le16(0) + le16(0) + le16(0) +
                le16(0x21) + le32(crc) + le32(content.size) + le32(content.size) + le16(nameBytes.size) +
                le16(0) + le16(0) + le16(0) + le16(0) + le32(0) + le32(0) + nameBytes
            val end: ByteArray = le32(0x06054b50) + le16(0) + le16(0) + le16(1) + le16(1) +
                le32(central.size) + le32(local.size) + le16(0)
            return local + central + end
        }

        private fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte())

        private fun le32(value: Int): ByteArray = le16(value) + le16(value shr 16)

        private fun crc32(data: ByteArray): Int {
            var crc: Int = -1
            for (byte: Byte in data) {
                crc = crc xor (byte.toInt() and 0xFF)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
            }
            return crc.inv()
        }
    }
}
