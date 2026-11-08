package io.github.jamal_wia.kmptoolkit.downloader

/** The fixed part of an end of central directory record. */
private const val END_RECORD_FIXED_SIZE: Int = 22

/** The ZIP64 end of central directory locator sits this many bytes before the end record. */
private const val ZIP64_LOCATOR_SIZE: Int = 20

private const val ZIP64_MARKER_VALUE: Long = 0xFFFFFFFFL

/**
 * How many bytes from the end of a file are searched for the end record: the fixed part, the longest comment (65535)
 * and 4 KiB of junk after the record.
 */
internal const val ZIP_END_RECORD_WINDOW: Long = END_RECORD_FIXED_SIZE + 65535L + 4096L

/**
 * True when [tail] — the last bytes of a file, starting at absolute offset [tailOffset] — holds an end of central
 * directory record: a `PK\u0005\u0006` signature at position `p` with the 22 fixed bytes after it, whose fields are
 * self-consistent (`cdOffset + cdSize == p`, both unsigned 32-bit). When either is `0xFFFFFFFF` the record counts if
 * a ZIP64 locator signature (`PK\u0006\u0007`) sits 20 bytes before it. A comment cut short is tolerated (its length
 * is not checked against the end of the file) and so are bytes after the record. The caller passes
 * `min(fileLength, ZIP_END_RECORD_WINDOW)` bytes.
 *
 * Decided from the file alone, before any extraction, so both platforms give one archive one answer: a file cut anywhere
 * before its record is complete (its central directory included) is a half-written archive.
 */
internal fun hasZipEndRecord(tail: ByteArray, tailOffset: Long): Boolean {
    var position: Int = tail.size - END_RECORD_FIXED_SIZE
    while (position >= 0) {
        if (tail.startsWith(position, 0x50, 0x4B, 0x05, 0x06)) {
            val cdSize: Long = tail.unsigned32(position + 12)
            val cdOffset: Long = tail.unsigned32(position + 16)
            if (cdSize == ZIP64_MARKER_VALUE || cdOffset == ZIP64_MARKER_VALUE) {
                val locator: Int = position - ZIP64_LOCATOR_SIZE
                if (locator >= 0 && tail.startsWith(locator, 0x50, 0x4B, 0x06, 0x07)) return true
            } else if (cdOffset + cdSize == tailOffset + position) {
                return true
            }
        }
        position--
    }
    return false
}

private fun ByteArray.startsWith(position: Int, b0: Int, b1: Int, b2: Int, b3: Int): Boolean =
    (this[position].toInt() and 0xFF) == b0 && (this[position + 1].toInt() and 0xFF) == b1 &&
        (this[position + 2].toInt() and 0xFF) == b2 && (this[position + 3].toInt() and 0xFF) == b3

private fun ByteArray.unsigned32(position: Int): Long =
    (this[position].toLong() and 0xFF) or ((this[position + 1].toLong() and 0xFF) shl 8) or
        ((this[position + 2].toLong() and 0xFF) shl 16) or ((this[position + 3].toLong() and 0xFF) shl 24)
