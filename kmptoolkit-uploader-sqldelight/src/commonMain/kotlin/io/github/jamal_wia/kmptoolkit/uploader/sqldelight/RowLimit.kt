package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem

/**
 * The most UTF-8 bytes the text columns of one row may hold together (payload, ids, keys, tag, last error).
 *
 * A row that does not fit Android's `CursorWindow` (about 2 MiB) can still be written but never read back, and
 * since the queue is read as a whole, one such row makes every later `getAllActive` fail: nothing runs and
 * nothing can be withdrawn. The same limit applies on every platform so the queue behaves alike everywhere.
 *
 * The limit counts the text as it is given. A text the drivers would change (a NUL, a lone surrogate, a leading
 * byte-order mark) is stored in a compact escaped form, at most twice as large, and the stored form is bounded as
 * well, by [MAX_ROW_STORED_BYTES]: a row is accepted only when both fit.
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

/** Fails with [IllegalArgumentException] when this item's text columns exceed [MAX_ROW_TEXT_BYTES] in UTF-8, as given or as stored. */
internal fun UploaderItem.requireWithinRowLimit() {
    val columns: List<String?> = listOf(id, type, payload, uniqueKey, orderingKey, tag, lastError)
    val total: Long = columns.sumOf { utf8Length(it).toLong() }
    require(total <= MAX_ROW_TEXT_BYTES) {
        "an uploader item's text is $total bytes; the limit is $MAX_ROW_TEXT_BYTES. " +
            "Keep a large payload elsewhere (a file) and queue a reference to it."
    }
    val stored: Long = columns.sumOf { storedUtf8Length(it).toLong() }
    require(stored <= MAX_ROW_STORED_BYTES) {
        "an uploader item's text takes $stored bytes once stored (NUL characters and lone surrogates are escaped); " +
            "the limit is $MAX_ROW_STORED_BYTES. Keep a large payload elsewhere (a file) and queue a reference to it."
    }
}

/**
 * [lastError] cut so that this row, carrying it as its last error, still fits [MAX_ROW_TEXT_BYTES] as given and
 * [MAX_ROW_STORED_BYTES] as stored.
 *
 * The limits are enforced on insert, but a failure text is written later; a row that was accepted at a limit
 * would otherwise grow past it and become unreadable on Android. The text is diagnostic, so it gives way: it is
 * cut on a character boundary (never inside a surrogate pair), or dropped when the rest of the row leaves no
 * room. `null` stays `null`.
 */
internal fun UploaderItem.fitLastError(lastError: String?): String? {
    if (lastError == null) return null
    val others: List<String?> = listOf(id, type, payload, uniqueKey, orderingKey, tag)
    val givenRoom: Long = MAX_ROW_TEXT_BYTES - others.sumOf { utf8Length(it).toLong() }
    val storedRoom: Long = MAX_ROW_STORED_BYTES - others.sumOf { storedUtf8Length(it).toLong() }
    if (utf8Length(lastError) <= givenRoom && storedUtf8Length(lastError) <= storedRoom) return lastError
    val escaped: Boolean = toStoredText(lastError) != lastError
    var given = 0L
    var stored: Long = if (escaped) STORED_MARKER_BYTES else 0L
    var index = 0
    while (index < lastError.length) {
        val c: Char = lastError[index]
        val isPair: Boolean = c.isHighSurrogate() && index + 1 < lastError.length && lastError[index + 1].isLowSurrogate()
        val width: Int = when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            isPair -> 4
            else -> 3
        }
        val storedWidth: Int = if (!escaped) width else when {
            c == '\u0000' -> 2
            c == '\u0080' || c == '\u0081' -> 3
            c.isSurrogate() && !isPair -> 5
            index == 0 && (c == '\uFEFF' || c == '\uFFFE') -> 5
            else -> width
        }
        if (given + width > givenRoom || stored + storedWidth > storedRoom) break
        given += width
        stored += storedWidth
        index += if (isPair) 2 else 1
    }
    return if (index == 0) null else lastError.substring(0, index)
}

/** The bytes the stored-form marker takes (U+FDD0, `kmp2`, U+FDD1). */
private const val STORED_MARKER_BYTES: Long = 10L
