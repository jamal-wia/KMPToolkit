package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [hasZipEndRecord]: the one rule both platforms use to decide, from the file alone, that an archive is complete. */
class ZipEndRecordTest {

    private fun marker(): ZipEntryRound6 = ZipEntryRound6("marker.txt", "marker\n")

    /** Runs the rule the way a platform does: over the last [ZIP_END_RECORD_WINDOW] bytes of [bytes]. */
    private fun counts(bytes: ByteArray): Boolean {
        val size: Int = minOf(bytes.size.toLong(), ZIP_END_RECORD_WINDOW).toInt()
        return hasZipEndRecord(bytes.copyOfRange(bytes.size - size, bytes.size), (bytes.size - size).toLong())
    }

    private fun record(cdOffset: Long, cdSize: Long, comment: ByteArray = ByteArray(0)): ByteArray {
        val sink = ZipBytesRound6.Sink()
        sink.u32(0x06054B50L)
        sink.u16(0); sink.u16(0); sink.u16(1); sink.u16(1)
        sink.u32(cdSize)
        sink.u32(cdOffset)
        sink.u16(comment.size)
        sink.bytes(comment)
        return sink.toByteArray()
    }

    @Test
    fun `an end record with a comment counts`() {
        val built: BuiltZipRound6 = ZipBytesRound6.build(listOf(marker()), comment = "a comment".encodeToByteArray())
        assertTrue(counts(built.bytes))
    }

    @Test
    fun `a comment cut short still counts but a record cut inside its fixed part does not`() {
        val built: BuiltZipRound6 = ZipBytesRound6.build(listOf(marker()), comment = "a comment".encodeToByteArray())
        assertTrue(counts(built.bytes.copyOf(built.endRecordStart + 22 + 3)))
        assertTrue(counts(built.bytes.copyOf(built.endRecordStart + 22)))
        assertFalse(counts(built.bytes.copyOf(built.endRecordStart + 21)))
        assertFalse(counts(built.bytes.copyOf(built.centralDirectoryStart)))
    }

    @Test
    fun `two thousand junk bytes after the record do not hide it`() {
        val built: BuiltZipRound6 = ZipBytesRound6.build(listOf(marker()), trailing = ByteArray(2000) { 0x7 })
        assertTrue(counts(built.bytes))
    }

    @Test
    fun `a signature inside data with inconsistent offsets is not a record`() {
        val fake: ByteArray = record(cdOffset = 5, cdSize = 5)
        val data: ByteArray = ByteArray(40) { 0x20 } + fake + ByteArray(10) { 0x21 }
        assertFalse(counts(data))
        // The same bytes consistent with their own position are a record: offset + size == 40.
        assertTrue(counts(ByteArray(40) { 0x20 } + record(cdOffset = 30, cdSize = 10)))
    }

    @Test
    fun `a record is found past a fake one inside a comment`() {
        val built: BuiltZipRound6 = ZipBytesRound6.build(listOf(marker()), comment = record(cdOffset = 1, cdSize = 1))
        assertTrue(counts(built.bytes))
    }

    @Test
    fun `a ZIP64 marker counts only with a locator in front of the record`() {
        val prefix = ByteArray(50) { 0x20 }
        val without: ByteArray = prefix + record(cdOffset = 0xFFFFFFFFL, cdSize = 3)
        assertFalse(counts(without))
        val locator = ZipBytesRound6.Sink().also { it.u32(0x07064B50L); it.bytes(ByteArray(16)) }.toByteArray()
        assertTrue(counts(prefix + locator + record(cdOffset = 0xFFFFFFFFL, cdSize = 3)))
        assertTrue(counts(prefix + locator + record(cdOffset = 7, cdSize = 0xFFFFFFFFL)))
    }

    @Test
    fun `a file shorter than the fixed record or without a signature has none`() {
        assertFalse(counts(ByteArray(0)))
        assertFalse(counts(ByteArray(21)))
        assertFalse(counts(ByteArray(22)))
        assertFalse(counts(ByteArray(100_000) { 0x41 }))
    }

    @Test
    fun `offsets are read as unsigned 32-bit`() {
        // cdOffset 0x80000000 + cdSize 0x10 placed at position 0x80000010 of a tail starting at that absolute offset.
        val tail: ByteArray = record(cdOffset = 0x80000000L, cdSize = 0x10L)
        assertTrue(hasZipEndRecord(tail, 0x80000010L))
        assertFalse(hasZipEndRecord(tail, 0x10L))
    }
}
