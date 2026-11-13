package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round 8, Q4 (design-level mutants of the end-record rule): the KDoc of [hasZipEndRecord] says the fields are
 * self-consistent only when `cdOffset + cdSize == p`, where `p` is the absolute position of the signature. Two mutants
 * of that equation survived the earlier tests: `<=` and dropping the tail's own offset.
 */
class ZipEndRecordRound8Test {

    private fun counts(bytes: ByteArray): Boolean {
        val size: Int = minOf(bytes.size.toLong(), ZIP_END_RECORD_WINDOW).toInt()
        return hasZipEndRecord(bytes.copyOfRange(bytes.size - size, bytes.size), (bytes.size - size).toLong())
    }

    private fun record(cdOffset: Long, cdSize: Long): ByteArray {
        val sink = ZipBytesRound6.Sink()
        sink.u32(0x06054B50L)
        sink.u16(0); sink.u16(0); sink.u16(1); sink.u16(1)
        sink.u32(cdSize)
        sink.u32(cdOffset)
        sink.u16(0)
        return sink.toByteArray()
    }

    @Test
    fun `a record whose central directory ends before the record does not count`() {
        val head = ByteArray(100) { 7 }
        // The record sits at position 100: the directory would have to end exactly there. 0 + 99 and 0 + 3 are short of it.
        assertTrue(counts(head + record(cdOffset = 0, cdSize = 100)), "the consistent record must count")
        assertFalse(counts(head + record(cdOffset = 0, cdSize = 99)), "a directory that ends one byte early is not this record's")
        assertFalse(counts(head + record(cdOffset = 0, cdSize = 3)))
        assertFalse(counts(head + record(cdOffset = 0, cdSize = 101)))
    }

    @Test
    fun `a record in a file longer than the search window is judged by its absolute position`() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val built: BuiltZipRound6 = ZipBytesRound6.build(listOf(ZipEntryRound6("marker.txt".encodeToByteArray(), big)))
        assertTrue(built.bytes.size > ZIP_END_RECORD_WINDOW, "the archive must be longer than the window")
        assertTrue(counts(built.bytes), "a whole archive longer than the window has its record")
        // The same bytes with the record's directory offset moved by one byte: the absolute position no longer matches.
        val head: ByteArray = built.bytes.copyOf(built.endRecordStart)
        val moved: ByteArray = head + record(cdOffset = built.centralDirectoryStart.toLong() + 1, cdSize = (built.endRecordStart - built.centralDirectoryStart).toLong())
        assertFalse(counts(moved))
    }
}
