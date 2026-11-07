package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem

/**
 * The most UTF-8 bytes the text columns of one row may hold together (payload, ids, keys, tag, last error).
 *
 * A row that does not fit Android's `CursorWindow` (about 2 MiB) can still be written but never read back, and
 * since the queue is read as a whole, one such row makes every later `getAllActive` fail: nothing runs and
 * nothing can be withdrawn. The same limit applies on every platform so the queue behaves alike everywhere.
 */
internal const val MAX_ROW_TEXT_BYTES: Int = 1_048_576

/**
 * The exact number of bytes [text] takes in UTF-8, counted without allocating: a character below `0x80` is
 * one byte, below `0x800` two, a valid surrogate pair four, anything else (including an unpaired surrogate)
 * three. `null` counts as nothing.
 */
internal fun utf8Length(text: String?): Int {
    if (text == null) return 0
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val c: Char = text[index]
        when {
            c.code < 0x80 -> bytes += 1
            c.code < 0x800 -> bytes += 2
            c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                bytes += 4
                index++
            }

            else -> bytes += 3
        }
        index++
    }
    return bytes
}

/** Fails with [IllegalArgumentException] when this item's text columns exceed [MAX_ROW_TEXT_BYTES] in UTF-8. */
internal fun UploaderItem.requireWithinRowLimit() {
    val total: Long = listOf(id, type, payload, uniqueKey, orderingKey, tag, lastError)
        .sumOf { utf8Length(it).toLong() }
    require(total <= MAX_ROW_TEXT_BYTES) {
        "an uploader item's text is $total bytes; the limit is $MAX_ROW_TEXT_BYTES. " +
            "Keep a large payload elsewhere (a file) and queue a reference to it."
    }
}
